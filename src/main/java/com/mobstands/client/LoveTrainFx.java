package com.mobstands.client;

import com.mobstands.network.BarrierPayload;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * GER's Golden Barrier, drawn like Love Train in Steel Ball Run: a curtain of golden light raining straight down
 * from the sky over the whole area, streaks sliding down each ray, and a bright column of light at its heart.
 */
public final class LoveTrainFx {
    private LoveTrainFx() {}

    private static final class Ray {
        double x, z;
        float width, speed, phase, height, bright;
    }

    private static final class Barrier {
        Vec3 center;
        int ticks, age;
        final List<Ray> rays = new ArrayList<>();
    }

    private static final List<Barrier> BARRIERS = new ArrayList<>();

    public static void add(BarrierPayload p) {
        Barrier b = new Barrier();
        b.center = new Vec3(p.x(), p.y(), p.z());
        b.ticks = p.ticks();
        Random r = new Random(p.seed());
        // The curtain: rays scattered over the whole barrier, thicker towards its heart.
        for (int i = 0; i < 90; i++) {
            Ray ray = new Ray();
            double a = r.nextDouble() * Math.PI * 2.0D;
            double d = Math.pow(r.nextDouble(), 0.7D) * p.radius();
            ray.x = Math.cos(a) * d;
            ray.z = Math.sin(a) * d;
            ray.width = 0.03F + r.nextFloat() * 0.09F;
            ray.speed = 0.5F + r.nextFloat() * 0.9F;
            ray.phase = r.nextFloat() * 40.0F;
            ray.height = 14.0F + r.nextFloat() * 16.0F;
            ray.bright = 0.45F + r.nextFloat() * 0.55F;
            b.rays.add(ray);
        }
        // The column: a tight, bright bundle right where it was raised.
        for (int i = 0; i < 14; i++) {
            Ray ray = new Ray();
            double a = r.nextDouble() * Math.PI * 2.0D, d = r.nextDouble() * 0.9D;
            ray.x = Math.cos(a) * d;
            ray.z = Math.sin(a) * d;
            ray.width = 0.08F + r.nextFloat() * 0.12F;
            ray.speed = 0.9F + r.nextFloat() * 0.6F;
            ray.phase = r.nextFloat() * 40.0F;
            ray.height = 30.0F;
            ray.bright = 1.0F;
            b.rays.add(ray);
        }
        BARRIERS.add(b);
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            BARRIERS.clear();
            return;
        }
        Iterator<Barrier> it = BARRIERS.iterator();
        while (it.hasNext()) {
            Barrier b = it.next();
            if (++b.age > b.ticks) {
                it.remove();
                continue;
            }
            // Sparkles where the light meets the ground.
            if (b.age % 2 == 0 && !b.rays.isEmpty()) {
                Ray ray = b.rays.get(mc.level.random.nextInt(b.rays.size()));
                mc.level.addParticle(net.minecraft.core.particles.ParticleTypes.WAX_ON, b.center.x + ray.x, b.center.y + 0.1D, b.center.z + ray.z,
                        0.0D, 0.6D, 0.0D);
            }
        }
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || BARRIERS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        Vec3 cam = event.getCamera().getPosition();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        PoseStack ps = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buffers.getBuffer(RenderType.lightning());
        ps.pushPose();
        Matrix4f m = ps.last().pose();
        for (Barrier b : BARRIERS) {
            float age = b.age + partial;
            // Fades in fast, out slow.
            float fade = Mth.clamp(age / 8.0F, 0.0F, 1.0F) * Mth.clamp((b.ticks - age) / 30.0F, 0.0F, 1.0F);
            if (fade <= 0.01F) continue;
            for (Ray ray : b.rays) {
                double rx = b.center.x + ray.x - cam.x, rz = b.center.z + ray.z - cam.z;
                double ry = b.center.y - cam.y;
                // Turn the ray's flat strip to face the camera.
                double len = Math.sqrt(rx * rx + rz * rz);
                float sx = len < 1.0E-3D ? 1.0F : (float) (-rz / len), sz = len < 1.0E-3D ? 0.0F : (float) (rx / len);
                float flicker = 0.75F + 0.25F * Mth.sin(age * 0.7F + ray.phase * 3.0F);
                float a = fade * ray.bright * flicker;
                // A faint full-height ray...
                strip(vc, m, rx, ry, rz, sx, sz, ray.width * 2.6F, 0.0F, ray.height, a * 0.18F, a * 0.05F);
                // ...a thin bright core...
                strip(vc, m, rx, ry, rz, sx, sz, ray.width, 0.0F, ray.height, a * 0.55F, a * 0.1F);
                // ...and streaks of light sliding down it, like the speed lines in the panels.
                for (int k = 0; k < 3; k++) {
                    float cyc = ray.height + 6.0F;
                    float top = ray.height - ((age * ray.speed + ray.phase + k * cyc / 3.0F) % cyc);
                    float bottom = top - 3.5F;
                    float t0 = Math.max(0.0F, bottom), t1 = Math.min(ray.height, top);
                    if (t1 <= t0) continue;
                    strip(vc, m, rx, ry, rz, sx, sz, ray.width * 1.6F, t0, t1, a * 0.95F, a * 0.25F);
                }
            }
        }
        ps.popPose();
        buffers.endBatch(RenderType.lightning());
    }

    /** One vertical camera-facing strip from {@code y0} to {@code y1} above the ground: bright at the bottom, fading up. */
    private static void strip(VertexConsumer vc, Matrix4f m, double x, double y, double z, float sx, float sz, float w,
                              float y0, float y1, float aBottom, float aTop) {
        float hx = sx * w * 0.5F, hz = sz * w * 0.5F;
        float fx = (float) x, fy = (float) y, fz = (float) z;
        int r = 255, g = 214, bl = 90;
        int ab = (int) (Mth.clamp(aBottom, 0.0F, 1.0F) * 255.0F), at = (int) (Mth.clamp(aTop, 0.0F, 1.0F) * 255.0F);
        vc.addVertex(m, fx - hx, fy + y0, fz - hz).setColor(255, 240, 190, ab);
        vc.addVertex(m, fx + hx, fy + y0, fz + hz).setColor(255, 240, 190, ab);
        vc.addVertex(m, fx + hx, fy + y1, fz + hz).setColor(r, g, bl, at);
        vc.addVertex(m, fx - hx, fy + y1, fz - hz).setColor(r, g, bl, at);
        // and the back face, so it shows from both sides
        vc.addVertex(m, fx - hx, fy + y1, fz - hz).setColor(r, g, bl, at);
        vc.addVertex(m, fx + hx, fy + y1, fz + hz).setColor(r, g, bl, at);
        vc.addVertex(m, fx + hx, fy + y0, fz + hz).setColor(255, 240, 190, ab);
        vc.addVertex(m, fx - hx, fy + y0, fz - hz).setColor(255, 240, 190, ab);
    }
}
