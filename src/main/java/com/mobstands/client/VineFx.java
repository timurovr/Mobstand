package com.mobstands.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Hermit Purple's vines, as real 3D vines: two thick purple strands twisted around each other, studded with pale
 * thorns, that whip out from the user, wriggle, and pull back in. Ground vines creep along the terrain. The trail a
 * Spirit Photograph leaves is a vine crawling across the ground ahead of you, pointing the way.
 */
public final class VineFx {
    private VineFx() {}

    private record Vine(Vec3 a, Vec3 b, long start, int life, float thick, boolean ground, int seed) {}

    private static final List<Vine> VINES = new ArrayList<>();
    private static Vec3 lead;
    private static long clientTicks;

    /** From the server: a vine from a to b, lasting {@code life} ticks. */
    public static void add(Vec3 a, Vec3 b, int life, float thick, boolean ground) {
        VINES.add(new Vine(a, b, clientTicks, life, thick, ground, (int) (Math.random() * 100000)));
    }

    /** From the server: where your photo's trail leads (null: none). */
    public static void setLead(Vec3 to) {
        lead = to;
    }

    public static void tick() {
        clientTicks++;
        VINES.removeIf(v -> clientTicks - v.start() > v.life());
        if (lead != null) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) lead = null;
        }
    }

    // colours
    private static final int DARK = 0x4A1F72, MID = 0x7438AA, LIGHT = 0x9A5BD0, THORN = 0xE6CCFF;

    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        if (VINES.isEmpty() && lead == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buffers.getBuffer(ModRenderTypes.cosmos());
        Matrix4f m = new PoseStack().last().pose();
        float now = clientTicks + partial;

        for (Vine v : VINES) {
            float age = now - v.start();
            // whips out fast, holds, pulls back in over the last few ticks
            float grow = Mth.clamp(age / 3.0F, 0.0F, 1.0F);
            float back = Mth.clamp((v.life() - age) / 4.0F, 0.0F, 1.0F);
            float reach = Math.min(grow, back);
            if (reach <= 0.01F) continue;
            List<Vec3> path = path(mc.level, v.a(), v.b(), v.ground(), v.seed(), now, reach);
            draw(vc, m, cam, mc.level, path, v.thick(), v.seed(), now);
        }
        if (lead != null) {
            // a vine creeping along the ground ahead of you, toward what the photo showed
            Vec3 p = mc.player.getPosition(partial);
            Vec3 to = new Vec3(lead.x - p.x, 0.0D, lead.z - p.z);
            if (to.lengthSqr() > 1.0E-3D) {
                Vec3 dir = to.normalize();
                double len = Math.min(10.0D, Math.max(2.0D, to.length() - 1.0D));
                Vec3 a = p.add(dir.scale(1.0D)).add(0.0D, 0.05D, 0.0D);
                Vec3 b = p.add(dir.scale(1.0D + len)).add(0.0D, 0.05D, 0.0D);
                List<Vec3> path = path(mc.level, a, b, true, 7, now, 1.0F);
                draw(vc, m, cam, mc.level, path, 0.7F, 7, now);
            }
        }
        buffers.endBatch(ModRenderTypes.cosmos());
    }

    /** The vine's centre line: a lazy curve with a wriggle, cut short at {@code reach}; ground vines hug the terrain. */
    private static List<Vec3> path(Level level, Vec3 a, Vec3 b, boolean ground, int seed, float t, float reach) {
        Vec3 d = b.subtract(a);
        double len = d.length();
        int n = Math.max(6, (int) (len * 5.0D));
        Vec3 dir = len > 1.0E-4D ? d.scale(1.0D / len) : new Vec3(0, 1, 0);
        Vec3 side = dir.cross(new Vec3(0.0D, 1.0D, 0.0D));
        if (side.lengthSqr() < 1.0E-4D) side = new Vec3(1.0D, 0.0D, 0.0D);
        side = side.normalize();
        Vec3 up = side.cross(dir).normalize();
        List<Vec3> out = new ArrayList<>();
        int cut = Math.max(2, (int) Math.ceil(n * reach));
        for (int i = 0; i <= cut && i <= n; i++) {
            double u = (double) i / n;
            double bow = Math.sin(u * Math.PI) * Math.min(0.6D, len * 0.06D);
            double wig = Math.sin(u * 9.0D + t * 0.35D + seed) * 0.08D * Math.sin(u * Math.PI);
            Vec3 pt = a.add(d.scale(u)).add(side.scale(wig + (ground ? 0.0D : Math.sin(seed) * bow * 0.5D)));
            if (ground) {
                pt = new Vec3(pt.x, groundY(level, pt.x, pt.y + 1.0D, pt.z) + 0.07D, pt.z);
            } else {
                pt = pt.add(up.scale(bow + Math.cos(u * 7.0D + t * 0.3D + seed) * 0.05D * Math.sin(u * Math.PI)));
            }
            out.add(pt);
        }
        return out;
    }

    private static void draw(VertexConsumer vc, Matrix4f m, Vec3 cam, Level level, List<Vec3> path, float thick, int seed, float t) {
        if (path.size() < 2) return;
        Vec3 mid = path.get(path.size() / 2);
        int packed = LevelRenderer.getLightColor(level, BlockPos.containing(mid));
        float light = Math.max(LightTexture.block(packed), LightTexture.sky(packed)) / 15.0F * 0.75F + 0.25F;
        int n = path.size();
        float strandR = 0.045F * thick, twistR = 0.05F * thick;
        // two strands braided around the centre line
        for (int s = 0; s < 2; s++) {
            Vec3[] prevRing = null;
            for (int i = 0; i < n; i++) {
                float u = (float) i / (n - 1);
                Vec3 c = path.get(i);
                Vec3 tan = (i < n - 1 ? path.get(i + 1).subtract(c) : c.subtract(path.get(i - 1))).normalize();
                Vec3 side = tan.cross(new Vec3(0.0D, 1.0D, 0.0D));
                if (side.lengthSqr() < 1.0E-4D) side = new Vec3(1.0D, 0.0D, 0.0D);
                side = side.normalize();
                Vec3 up = side.cross(tan).normalize();
                float taper = 1.0F - 0.65F * u; // thinner toward the tip
                double ang = i * 0.55D + s * Math.PI;
                Vec3 sc = c.add(side.scale(Math.cos(ang) * twistR * taper)).add(up.scale(Math.sin(ang) * twistR * taper));
                Vec3[] ring = new Vec3[5];
                for (int k = 0; k < 5; k++) {
                    double a = k * Math.PI * 2.0D / 5.0D;
                    ring[k] = sc.add(side.scale(Math.cos(a) * strandR * taper)).add(up.scale(Math.sin(a) * strandR * taper));
                }
                if (prevRing != null) {
                    for (int k = 0; k < 5; k++) {
                        int k2 = (k + 1) % 5;
                        // faces facing up are lit, those underneath darker; the strands differ a touch
                        double faceUp = (Math.sin(k * Math.PI * 2.0D / 5.0D + Math.PI / 5.0D) + 1.0D) * 0.5D;
                        int base = s == 0 ? MID : DARK;
                        int col = mix(base, LIGHT, (float) faceUp * 0.6F);
                        quad(vc, m, cam, prevRing[k], prevRing[k2], ring[k2], ring[k], col, light);
                    }
                }
                prevRing = ring;
                // thorns, sticking out every so often
                if (s == 0 && i % 3 == 1 && i < n - 1) {
                    double ta = i * 2.39D + seed;
                    Vec3 out = side.scale(Math.cos(ta)).add(up.scale(Math.sin(ta))).normalize();
                    Vec3 base = c.add(out.scale(twistR * taper));
                    Vec3 tip = base.add(out.scale(0.09D * thick * taper)).add(tan.scale(0.04D));
                    Vec3 w1 = base.add(tan.scale(0.025D * thick)), w2 = base.subtract(tan.scale(0.025D * thick));
                    Vec3 w3 = base.add(out.cross(tan).normalize().scale(0.022D * thick));
                    quad(vc, m, cam, w1, w2, tip, tip, THORN, light);
                    quad(vc, m, cam, w2, w3, tip, tip, mix(THORN, MID, 0.3F), light);
                    quad(vc, m, cam, w3, w1, tip, tip, mix(THORN, DARK, 0.2F), light);
                }
            }
        }
    }

    private static void quad(VertexConsumer vc, Matrix4f m, Vec3 cam, Vec3 a, Vec3 b, Vec3 c, Vec3 d, int rgb, float light) {
        int r = (int) (((rgb >> 16) & 255) * light), g = (int) (((rgb >> 8) & 255) * light), bl = (int) ((rgb & 255) * light);
        vc.addVertex(m, (float) (a.x - cam.x), (float) (a.y - cam.y), (float) (a.z - cam.z)).setColor(r, g, bl, 255);
        vc.addVertex(m, (float) (b.x - cam.x), (float) (b.y - cam.y), (float) (b.z - cam.z)).setColor(r, g, bl, 255);
        vc.addVertex(m, (float) (c.x - cam.x), (float) (c.y - cam.y), (float) (c.z - cam.z)).setColor(r, g, bl, 255);
        vc.addVertex(m, (float) (d.x - cam.x), (float) (d.y - cam.y), (float) (d.z - cam.z)).setColor(r, g, bl, 255);
    }

    private static int mix(int a, int b, float t) {
        int r = (int) Mth.lerp(t, (a >> 16) & 255, (b >> 16) & 255);
        int g = (int) Mth.lerp(t, (a >> 8) & 255, (b >> 8) & 255);
        int bl = (int) Mth.lerp(t, a & 255, b & 255);
        return (r << 16) | (g << 8) | bl;
    }

    private static double groundY(Level level, double x, double y, double z) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int bx = Mth.floor(x), bz = Mth.floor(z), by = Mth.floor(y);
        for (int dy = 2; dy >= -6; dy--) {
            p.set(bx, by + dy, bz);
            var s = level.getBlockState(p);
            var shape = s.getCollisionShape(level, p);
            if (!shape.isEmpty()) {
                p.set(bx, by + dy + 1, bz);
                if (level.getBlockState(p).getCollisionShape(level, p).isEmpty()) {
                    p.set(bx, by + dy, bz);
                    return by + dy + shape.max(net.minecraft.core.Direction.Axis.Y);
                }
            }
        }
        return y - 1.0D;
    }
}
