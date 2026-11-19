package com.mobstands.client;

import com.mobstands.network.HorseBallsPayload;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Horse Spin: the Steel Balls spinning under a horse while they boost it - one under its belly, or two (one under
 * its front legs, one under its back legs) when both of your balls went in.
 */
public final class HorseBalls {
    private HorseBalls() {}

    private static final class State {
        int balls, ticks, age;
    }

    private static final Map<Integer, State> HORSES = new HashMap<>();
    private static ItemStack ball;

    public static void set(HorseBallsPayload p) {
        if (p.balls() <= 0) {
            HORSES.remove(p.horseId());
            return;
        }
        State s = new State();
        s.balls = p.balls();
        s.ticks = p.ticks();
        HORSES.put(p.horseId(), s);
    }

    /** Riding with balls under your horse, looking down: a right-click takes them back. */
    public static boolean hasBalls(int horseId) {
        return HORSES.containsKey(horseId);
    }

    /** Riding, looking down, empty main hand, balls under your horse: ask for them back. True if it asked. */
    public static boolean tryRetrieve() {
        var p = Minecraft.getInstance().player;
        if (p == null || !(p.getVehicle() instanceof net.minecraft.world.entity.animal.horse.AbstractHorse horse)) return false;
        if (p.getXRot() < 55.0F || !p.getMainHandItem().isEmpty() || !HORSES.containsKey(horse.getId())) return false;
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(new com.mobstands.network.StandActionPayload(31));
        return true;
    }

    /** On foot and looking at a horse: right-click should get you on it, even with your balls spinning. */
    public static boolean aimingAtHorse(Minecraft mc) {
        return mc.player != null && !mc.player.isPassenger()
                && mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult ehr
                && ehr.getEntity() instanceof net.minecraft.world.entity.animal.horse.AbstractHorse;
    }

    /** Wind trails streaming off the boosted horse: thin white streaks, longer the faster it runs. */
    private static void windTrails(PoseStack ps, net.minecraft.client.renderer.MultiBufferSource buf, net.minecraft.world.entity.LivingEntity horse,
                                   Vec3 fwd, Vec3 side, double h, float el) {
        Vec3 v = horse.getDeltaMovement();
        double speed = Math.sqrt(v.x * v.x + v.z * v.z);
        float len = (float) Mth.clamp(speed * 9.0D, 0.0D, 3.2D);
        if (len < 0.25F) return;
        com.mojang.blaze3d.vertex.VertexConsumer vc = buf.getBuffer(net.minecraft.client.renderer.RenderType.lightning());
        org.joml.Matrix4f m = ps.last().pose();
        // The same kind of wind as the Spin's rings: flat, tapered white lines, faded to nothing at the tail.
        // Six of them, each popping up at a random spot along the horse, living a moment, then somewhere else.
        int seedBase = horse.getId() * 31;
        for (int i = 0; i < 6; i++) {
            float life = 9.0F + (i % 3) * 3.0F;
            float t = el + i * 4.3F;
            int gen = (int) (t / life);
            float u = (t % life) / life;                       // 0..1 through this streak's life
            java.util.Random r = new java.util.Random(seedBase + i * 977L + gen * 7919L);
            if (r.nextFloat() < 0.25F) continue;                // sometimes a gap
            double across = (r.nextDouble() - 0.5D) * 1.2D;
            float y = (float) (h * (0.2D + r.nextDouble() * 0.8D));
            float l = len * (0.5F + r.nextFloat() * 0.7F);
            float fade = Mth.sin(Mth.PI * u);                  // in, then out
            float a = 0.22F * fade * Math.min(1.0F, len);
            double start = 0.3D + r.nextDouble() * 0.6D + u * 0.6D; // drifts back as it lives
            double sx = side.x * across, sz = side.z * across;
            float w = 0.03F + r.nextFloat() * 0.02F;
            final int segs = 8;
            for (int s = 0; s < segs; s++) {
                float u0 = s / (float) segs, u1 = (s + 1) / (float) segs;   // 0 = front, 1 = tail
                double b0 = start + u0 * l, b1 = start + u1 * l;
                float w0 = w * (1.0F - u0), w1 = w * (1.0F - u1);       // tapers to a point
                int a0 = (int) (255 * a * (1.0F - u0) * (1.0F - u0)), a1 = (int) (255 * a * (1.0F - u1) * (1.0F - u1));
                float x0 = (float) (sx - fwd.x * b0), z0 = (float) (sz - fwd.z * b0);
                float x1 = (float) (sx - fwd.x * b1), z1 = (float) (sz - fwd.z * b1);
                float ox0 = (float) side.x * w0, oz0 = (float) side.z * w0, ox1 = (float) side.x * w1, oz1 = (float) side.z * w1;
                vc.addVertex(m, x0 - ox0, y, z0 - oz0).setColor(255, 255, 255, a0);
                vc.addVertex(m, x0 + ox0, y, z0 + oz0).setColor(255, 255, 255, a0);
                vc.addVertex(m, x1 + ox1, y, z1 + oz1).setColor(255, 255, 255, a1);
                vc.addVertex(m, x1 - ox1, y, z1 - oz1).setColor(255, 255, 255, a1);
                vc.addVertex(m, x1 - ox1, y, z1 - oz1).setColor(255, 255, 255, a1);
                vc.addVertex(m, x1 + ox1, y, z1 + oz1).setColor(255, 255, 255, a1);
                vc.addVertex(m, x0 + ox0, y, z0 + oz0).setColor(255, 255, 255, a0);
                vc.addVertex(m, x0 - ox0, y, z0 - oz0).setColor(255, 255, 255, a0);
            }
        }
    }

    /** Horse launch, pending: applied again for a couple of ticks so the horse's own movement can't eat it. */
    private static int launchHorse = -1, launchTicks;
    private static float launchUp;

    public static void launch(com.mobstands.network.HorseLaunchPayload p) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || !(mc.player.getVehicle() instanceof net.minecraft.world.entity.animal.horse.AbstractHorse h)
                || h.getId() != p.horseId()) return;
        Vec3 v = h.getDeltaMovement();
        h.setDeltaMovement(v.x + p.pushX(), p.up(), v.z + p.pushZ());
        h.setOnGround(false);
        h.hasImpulse = true;
        launchHorse = h.getId();
        launchUp = p.up();
        launchTicks = 2;
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        if (Minecraft.getInstance().level == null) {
            HORSES.clear();
            return;
        }
        HORSES.values().removeIf(s -> ++s.age > s.ticks + 40);
        if (launchTicks > 0) {
            launchTicks--;
            var p = Minecraft.getInstance().player;
            if (p != null && p.getVehicle() instanceof net.minecraft.world.entity.animal.horse.AbstractHorse h && h.getId() == launchHorse
                    && h.getDeltaMovement().y < launchUp * 0.6F && h.onGround()) {
                // Still stuck on the ground (the gallop overrode it): kick it up again.
                h.setDeltaMovement(h.getDeltaMovement().x, launchUp, h.getDeltaMovement().z);
                h.setOnGround(false);
            }
        }
    }

    @SubscribeEvent
    public static void onRender(RenderLivingEvent.Post<?, ?> event) {
        if (HORSES.isEmpty()) return;
        State s = HORSES.get(event.getEntity().getId());
        if (s == null) return;
        if (ball == null) ball = new ItemStack(com.mobstands.registry.ModItems.STEEL_BALL.get());
        float partial = event.getPartialTick();
        float el = s.age + partial;
        float yaw = Mth.rotLerp(partial, event.getEntity().yBodyRotO, event.getEntity().yBodyRot) * Mth.DEG_TO_RAD;
        Vec3 fwd = new Vec3(-Mth.sin(yaw), 0.0D, Mth.cos(yaw));
        Vec3 side = new Vec3(-fwd.z, 0.0D, fwd.x);
        // At its rear, by its hips, like boosters: one in the middle, or one on each side.
        double[] across = s.balls >= 2 ? new double[]{0.3D, -0.3D} : new double[]{0.0D};
        double h = event.getEntity().getBbHeight();
        PoseStack ps = event.getPoseStack();
        windTrails(ps, event.getMultiBufferSource(), event.getEntity(), fwd, side, h, el);
        for (int i = 0; i < across.length; i++) {
            ps.pushPose();
            ps.translate(-fwd.x * 0.85D + side.x * across[i], h * 0.55D, -fwd.z * 0.85D + side.z * across[i]);
            ps.mulPose(Axis.YP.rotationDegrees(SpinFx.angle(el) * (i == 0 ? 1.0F : -1.0F))); // two balls spin opposite ways
            SpinFx.drawRings(ps, event.getMultiBufferSource(), el, 0.2F, true); // smaller, and it stays on
            SpinFx.drawGlow(ps, event.getMultiBufferSource(), el, 0.2F, 0.07F); // a faint, faded yellow shimmer
            ps.scale(0.5F, 0.5F, 0.5F);
            // Lit like the world around it (dark at night, in shade), not glowing.
            Minecraft.getInstance().getItemRenderer().renderStatic(ball, ItemDisplayContext.FIXED, event.getPackedLight(),
                    OverlayTexture.NO_OVERLAY, ps, event.getMultiBufferSource(), event.getEntity().level(), event.getEntity().getId() + i);
            ps.popPose();
        }
    }
}
