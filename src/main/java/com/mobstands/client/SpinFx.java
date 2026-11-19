package com.mobstands.client;

import com.mobstands.network.ClientStandCache;
import com.mobstands.stand.StandManager;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

/**
 * The Spin's look: a violent spin that slowly winds down to a stop, wrapped in thin white whirl streaks
 * (rings that fade as it slows).
 */
public final class SpinFx {
    private SpinFx() {}

    /** Starting spin speed (degrees per tick). Not a multiple of 90 so a cube never looks frozen. */
    public static final float START_SPEED = 67.0F;

    /** Ticks since the spin started (with partial tick), or -1 if not spinning. */
    public static float elapsed(float partial) {
        return elapsed(partial, false);
    }

    public static float elapsed(float partial, boolean offHand) {
        Minecraft mc = Minecraft.getInstance();
        var d = ClientStandCache.get();
        if (mc.level == null || !(offHand ? d.offSpinning : d.spinning)) return -1.0F;
        return Math.max(0.0F, mc.level.getGameTime() - (offHand ? d.offSpinStart : d.spinStart) + partial);
    }

    /** The sword rolls forward for this long after a ground slash (ticks). */
    private static final float SLASH_ROLL_TICKS = 14.0F;

    public static boolean slashing(float partial) {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.level.getGameTime() - ClientStandCache.get().spinSlashAt + partial < SLASH_ROLL_TICKS;
    }

    /** Fast forward roll, easing out at the end. */
    public static float slashAngle(float partial) {
        Minecraft mc = Minecraft.getInstance();
        float t = Mth.clamp((mc.level.getGameTime() - ClientStandCache.get().spinSlashAt + partial) / SLASH_ROLL_TICKS, 0.0F, 1.0F);
        return 1080.0F * (1.0F - (1.0F - t) * (1.0F - t)); // three full turns
    }

    /** How strong the spin's effects (the shudder) are: 1 at the start, fading to 0 by the end. The spin itself never slows. */
    public static float speed(float el) {
        float k = 1.0F - Mth.clamp(el / StandManager.spinTicks(com.mobstands.network.ClientStandCache.get().spinLevel), 0.0F, 1.0F);
        return k * k;
    }

    /** Total angle spun so far: once charged it keeps spinning at full speed the whole time (only the effects fade). */
    public static float angle(float el) {
        return (START_SPEED * el) % 360.0F;
    }

    /** Rotates a dropped Spin item on the floor: fast at first, winding down over its floor time. */
    public static void floorSpin(net.minecraft.world.entity.item.ItemEntity entity, float partial, PoseStack ps) {
        net.minecraft.world.item.component.CustomData cd = entity.getItem().get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if (cd == null || !cd.contains(StandManager.SPIN_TAG)) return;
        net.minecraft.nbt.CompoundTag tag = cd.getUnsafe();
        float el = Math.max(0.0F, entity.level().getGameTime() - tag.getLong(StandManager.SPIN_TAG) + partial);
        float T = StandManager.FLOOR_SPIN_TICKS;
        float u = 1.0F - Mth.clamp(el / T, 0.0F, 1.0F);
        float power = tag.getFloat(StandManager.SPIN_POWER_TAG);
        float angle = START_SPEED * power * T / 3.0F * (1.0F - u * u * u);
        ps.mulPose(Axis.YP.rotationDegrees(angle));
    }

    /**
     * White whirl streaks around the spinning item: three horizontal rings and a tilted one, each a broken arc
     * spinning faster than the item. {@code r} = ring radius in the current pose's units.
     */
    public static void drawRings(PoseStack ps, MultiBufferSource buffers, float el, float r) {
        drawRings(ps, buffers, el, r, false);
    }

    /** {@code stay}: the streaks never fade (Steel Balls boosting a horse keep their wind on). */
    public static void drawRings(PoseStack ps, MultiBufferSource buffers, float el, float r, boolean stay) {
        // Only as it starts spinning: the streaks burst out for 1.5 seconds, then fade away over 1 second.
        float k = stay ? 0.5F : el < 30.0F ? 1.0F : 1.0F - (el - 30.0F) / 20.0F;
        k = Math.min(k, el / 3.0F); // and snap in quickly at the start
        if (k < 0.03F) return;
        VertexConsumer vc = buffers.getBuffer(RenderType.lightning());
        float a = angle(el) * 1.4F;
        ps.pushPose();
        ps.mulPose(Axis.YP.rotationDegrees(a));
        ring(vc, ps.last().pose(), r * 1.05F, -0.35F * r, 0.0F, 3.6F, 0.035F * r, 0.75F * k);
        ring(vc, ps.last().pose(), r * 1.25F, 0.0F, 2.0F, 3.2F, 0.03F * r, 0.6F * k);
        ring(vc, ps.last().pose(), r * 1.0F, 0.35F * r, 4.1F, 3.4F, 0.035F * r, 0.7F * k);
        ps.popPose();
        ps.pushPose();
        ps.mulPose(Axis.XP.rotationDegrees(70.0F));
        ps.mulPose(Axis.YP.rotationDegrees(-a * 0.8F));
        ring(vc, ps.last().pose(), r * 1.35F, 0.0F, 1.0F, 2.6F, 0.03F * r, 0.45F * k);
        ps.popPose();
    }

    /** A soft yellow glow around a spinning item on the floor: stacked golden rings whirling around it. */
    public static void drawGlow(PoseStack ps, MultiBufferSource buffers, float el, float r, float alpha) {
        VertexConsumer vc = buffers.getBuffer(RenderType.lightning());
        ps.pushPose();
        ps.mulPose(Axis.YP.rotationDegrees(el * 23.0F));
        Matrix4f m = ps.last().pose();
        for (int i = 0; i < 4; i++) {
            float y = -0.12F + i * 0.08F;
            ring(vc, m, r * (1.0F - Math.abs(i - 1.5F) * 0.15F), y, i * 1.6F, 5.4F, 0.06F, alpha, 255, 214, 74);
        }
        ps.popPose();
    }

    /** A thin pulsing golden circle on the ground marking the area the floor spin covers. */
    public static void drawAreaRing(PoseStack ps, MultiBufferSource buffers, float radius, float el, float alpha) {
        VertexConsumer vc = buffers.getBuffer(RenderType.lightning());
        ps.pushPose();
        ps.mulPose(Axis.YP.rotationDegrees(el * 4.0F));
        Matrix4f m = ps.last().pose();
        for (int i = 0; i < 4; i++) {
            ring(vc, m, radius, 0.0F, i * (Mth.PI / 2.0F), Mth.PI / 2.0F - 0.15F, 0.05F, alpha, 255, 200, 60);
        }
        ps.popPose();
    }

    /** A thin pulsing golden square on the ground marking the 8x8 area the floor spin covers. */
    public static void drawAreaSquare(PoseStack ps, MultiBufferSource buffers, float half, float alpha) {
        VertexConsumer vc = buffers.getBuffer(RenderType.lightning());
        Matrix4f m = ps.last().pose();
        int a = (int) (Mth.clamp(alpha, 0.0F, 1.0F) * 255.0F);
        float w = 0.06F;
        float[][] edges = {{-half, -half, half, -half}, {half, -half, half, half}, {half, half, -half, half}, {-half, half, -half, -half}};
        for (float[] ed : edges) {
            float dx = ed[2] - ed[0], dz = ed[3] - ed[1];
            float len = Mth.sqrt(dx * dx + dz * dz);
            float nx = -dz / len * w, nz = dx / len * w;
            quad(vc, m, ed[0] - nx, ed[1] - nz, ed[0] + nx, ed[1] + nz, ed[2] + nx, ed[3] + nz, ed[2] - nx, ed[3] - nz, a);
        }
    }

    private static void quad(VertexConsumer vc, Matrix4f m, float x0, float z0, float x1, float z1, float x2, float z2, float x3, float z3, int a) {
        vc.addVertex(m, x0, 0.0F, z0).setColor(255, 200, 60, a);
        vc.addVertex(m, x1, 0.0F, z1).setColor(255, 200, 60, a);
        vc.addVertex(m, x2, 0.0F, z2).setColor(255, 200, 60, a);
        vc.addVertex(m, x3, 0.0F, z3).setColor(255, 200, 60, a);
        vc.addVertex(m, x3, 0.0F, z3).setColor(255, 200, 60, a);
        vc.addVertex(m, x2, 0.0F, z2).setColor(255, 200, 60, a);
        vc.addVertex(m, x1, 0.0F, z1).setColor(255, 200, 60, a);
        vc.addVertex(m, x0, 0.0F, z0).setColor(255, 200, 60, a);
    }

    private static void ring(VertexConsumer vc, Matrix4f m, float radius, float y, float from, float span, float width, float alpha) {
        ring(vc, m, radius, y, from, span, width, alpha, 255, 255, 255);
    }

    /** One broken arc in the XZ plane at height y: a flat, tapered line that fades toward its tail. */
    private static void ring(VertexConsumer vc, Matrix4f m, float radius, float y, float from, float span, float width, float alpha,
                             int cr, int cg, int cb) {
        int al = (int) (Mth.clamp(alpha, 0.0F, 1.0F) * 255.0F);
        final int segs = 18;
        for (int s = 0; s < segs; s++) {
            float u0 = s / (float) segs, u1 = (s + 1) / (float) segs;
            float a0 = from + span * u0, a1 = from + span * u1;
            float w0 = width * Mth.sin(Mth.PI * u0), w1 = width * Mth.sin(Mth.PI * u1); // tapered ends
            float c0 = Mth.cos(a0), s0 = Mth.sin(a0), c1 = Mth.cos(a1), s1 = Mth.sin(a1);
            float ri0 = radius - w0, ro0 = radius + w0, ri1 = radius - w1, ro1 = radius + w1;
            int f0 = (int) (al * u0 * u0), f1 = (int) (al * u1 * u1); // faded: nothing at the tail, brightest at the front
            vc.addVertex(m, c0 * ri0, y, s0 * ri0).setColor(cr, cg, cb, f0);
            vc.addVertex(m, c0 * ro0, y, s0 * ro0).setColor(cr, cg, cb, f0);
            vc.addVertex(m, c1 * ro1, y, s1 * ro1).setColor(cr, cg, cb, f1);
            vc.addVertex(m, c1 * ri1, y, s1 * ri1).setColor(cr, cg, cb, f1);
            vc.addVertex(m, c1 * ri1, y, s1 * ri1).setColor(cr, cg, cb, f1);
            vc.addVertex(m, c1 * ro1, y, s1 * ro1).setColor(cr, cg, cb, f1);
            vc.addVertex(m, c0 * ro0, y, s0 * ro0).setColor(cr, cg, cb, f0);
            vc.addVertex(m, c0 * ri0, y, s0 * ri0).setColor(cr, cg, cb, f0);
        }
    }

    /** A little axis-aligned cube (half-size h), drawn with the additive glow. */
    public static void cube(VertexConsumer vc, Matrix4f m, float x, float y, float z, float h, int r, int g, int b, int a) {
        float x0 = x - h, x1 = x + h, y0 = y - h, y1 = y + h, z0 = z - h, z1 = z + h;
        face(vc, m, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, r, g, b, a);
        face(vc, m, x0, y0, z1, x0, y1, z1, x1, y1, z1, x1, y0, z1, r, g, b, a);
        face(vc, m, x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1, r, g, b, a);
        face(vc, m, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, r, g, b, a);
        face(vc, m, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, r, g, b, a);
        face(vc, m, x0, y0, z0, x0, y0, z1, x1, y0, z1, x1, y0, z0, r, g, b, a);
    }

    private static void face(VertexConsumer vc, Matrix4f m, float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz, int r, int g, int b, int a) {
        vc.addVertex(m, ax, ay, az).setColor(r, g, b, a);
        vc.addVertex(m, bx, by, bz).setColor(r, g, b, a);
        vc.addVertex(m, cx, cy, cz).setColor(r, g, b, a);
        vc.addVertex(m, dx, dy, dz).setColor(r, g, b, a);
        // and the back side too (the glow culls faces), so every cube face shows whichever way it's wound
        vc.addVertex(m, dx, dy, dz).setColor(r, g, b, a);
        vc.addVertex(m, cx, cy, cz).setColor(r, g, b, a);
        vc.addVertex(m, bx, by, bz).setColor(r, g, b, a);
        vc.addVertex(m, ax, ay, az).setColor(r, g, b, a);
    }
}
