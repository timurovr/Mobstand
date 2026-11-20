package com.mobstands.client;

import com.mobstands.network.GerCutscenePayload;
import com.mobstands.network.StandActionPayload;
import com.mobstands.stand.Ger;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * The client side of GER's Return to Zero: the camera cutscene (for whoever countered), and the loop itself for
 * everyone - the mob's copies stretching out behind it, each one looking back in turn, and the mob dying again
 * and again.
 */
public final class GerCutscene {
    private GerCutscene() {}

    private static final class Loop {
        int mobId, ownerId;
        Vec3 origin;
        int t;
    }

    private static final Map<Integer, Loop> LOOPS = new HashMap<>();
    private static Loop cine;
    private static Entity camera;
    private static boolean skipWasDown = true;
    private static float orbit;
    /** The cutscene plays on a stage high above the world (nothing can block the camera), in a rainbow cosmos. */
    private static Vec3 stage = Vec3.ZERO;
    /** Set while the mob is being drawn on the stage: its copies then stretch out in one long straight line. */
    private static boolean onStage;
    private static final int STAGE_COPIES = 18;
    private static final int COPIES = 7;

    public static void start(GerCutscenePayload p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        // The caster gets this twice (their own cinematic one, and the one everyone near the mob gets): the second,
        // plain copy must not replace the loop the cutscene is running on, or its clock freezes at zero.
        if (!p.cinematic() && LOOPS.containsKey(p.mobId())) return;
        Loop l = new Loop();
        l.mobId = p.mobId();
        l.ownerId = p.ownerId();
        l.origin = new Vec3(p.ox(), p.oy(), p.oz());
        LOOPS.put(l.mobId, l);
        if (p.cinematic()) {
            endCine();
            cine = l;
            camera = EntityType.MARKER.create(mc.level);
            stage = new Vec3(l.origin.x, mc.level.getMaxBuildHeight() + 90.0D, l.origin.z);

            orbit = 0.0F;
            skipWasDown = true; // the key you countered with doesn't skip it straight away
            if (camera != null) {
                placeCamera(mc, 0.0F);
                camera.xo = camera.getX();
                camera.yo = camera.getY();
                camera.zo = camera.getZ();
                camera.yRotO = camera.getYRot();
                camera.xRotO = camera.getXRot();
                mc.setCameraEntity(camera);
            }
        }
    }

    public static boolean active() {
        return cine != null;
    }

    private static void endCine() {
        Minecraft mc = Minecraft.getInstance();
        if (cine != null && mc.player != null && mc.getCameraEntity() == camera) mc.setCameraEntity(mc.player);
        cine = null;
        camera = null;
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            LOOPS.clear();
            cine = null;
            camera = null;
            return;
        }
        Iterator<Loop> it = LOOPS.values().iterator();
        while (it.hasNext()) {
            Loop l = it.next();
            l.t++;
            if (l.t > Ger.T_DONE || mc.level.getEntity(l.mobId) == null && l.t > Ger.T_END) it.remove();
        }
        if (cine == null) return;
        // Skip: X or Shift.
        boolean down = StandKeys.ABILITY2.isDown() || mc.options.keyShift.isDown();
        if (down && !skipWasDown && cine.t < Ger.T_END) {
            PacketDistributor.sendToServer(new StandActionPayload(30));
            LOOPS.remove(cine.mobId);
            endCine();
            return;
        }
        skipWasDown = down;
        magic(mc, cine);
        // The barrage sound lands on the deaths: it runs exactly as long as they do.
        if (cine.t == Ger.T_DEATHS) mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                com.mobstands.registry.ModSounds.GER_RETURN_TO_ZERO.get(), 1.0F, 1.0F));
        if (cine.t > Ger.T_DONE || !LOOPS.containsKey(cine.mobId)) {
            endCine();
            return;
        }
        // You stand dead still while it plays: no drifting forward, no walking legs.
        mc.player.setDeltaMovement(0.0D, Math.min(0.0D, mc.player.getDeltaMovement().y), 0.0D);
        mc.player.walkAnimation.setSpeed(0.0F);
        mc.player.setSprinting(false);
        if (camera != null) {
            camera.xo = camera.getX();
            camera.yo = camera.getY();
            camera.zo = camera.getZ();
            camera.yRotO = camera.getYRot();
            camera.xRotO = camera.getXRot();
            placeCamera(mc, 1.0F);
            if (mc.getCameraEntity() != camera) mc.setCameraEntity(camera);
        }
    }

    /** A smooth 0..1 ease. */
    private static double ease(double k) {
        k = Mth.clamp(k, 0.0D, 1.0D);
        return k * k * (3.0D - 2.0D * k);
    }

    /**
     * Where the camera is this tick. Three shots:
     * 1. the counter - a wide side-on shot of you and the attacker frozen mid-strike, slowly pushing in;
     * 2. the rewind - behind the attacker's shoulder, looking down the curving trail of its copies, drifting round;
     * 3. the deaths - a low close-up on the mob, jolting with each death.
     */
    /** Where something in the world appears on the stage. */
    private static Vec3 onStage(Vec3 world) {
        return stage.add(world.subtract(cine.origin));
    }

    private static void placeCamera(Minecraft mc, float step) {
        Loop l = cine;
        Entity mob = mc.level.getEntity(l.mobId);
        double h = mob == null ? 1.8D : mob.getBbHeight();
        Vec3 mobPos = onStage(mob == null ? l.origin : mob.position());
        Entity owner = mc.level.getEntity(l.ownerId);
        Vec3 line = l.origin.subtract(owner == null ? l.origin.add(-1.0D, 0.0D, 0.0D) : owner.position()).multiply(1.0D, 0.0D, 1.0D);
        line = line.lengthSqr() < 1.0E-4D ? new Vec3(1.0D, 0.0D, 0.0D) : line.normalize();
        Vec3 side = new Vec3(-line.z, 0.0D, line.x);
        Vec3 center = mobPos.add(0.0D, h * 0.6D, 0.0D);
        Vec3 pos, look;
        float tt = l.t;
        if (tt < Ger.T_DEATHS) {
            // From off to its side and a little in front: the mob in the foreground, and the line of its copies
            // stretching away behind it into the distance. The camera slowly glides along the line.
            orbit += 0.03F * step;
            double k = ease(tt / (double) Ger.T_DEATHS);
            pos = center.add(side.scale(2.6D + h * 1.2D)).add(line.scale(-2.4D - h * 0.5D + orbit)).add(0.0D, 0.5D + h * 0.2D, 0.0D);
            look = center.add(line.scale(2.0D + k * 3.5D));
        } else {
            // The deaths: low, from the other side, the copies still trailing off behind - jolting with each death.
            float d = (tt - Ger.T_DEATHS) % Ger.DEATH_LEN;
            double jolt = d < 4.0F ? (4.0F - d) * 0.04D : 0.0D;
            double wob = Math.sin(tt * 1.7D) * jolt;
            pos = center.add(side.scale(-(2.4D + h))).add(line.scale(-2.0D - h * 0.4D)).add(0.0D, -h * 0.2D, 0.0D).add(side.scale(wob));
            look = center.add(line.scale(1.5D)).add(0.0D, wob, 0.0D);
        }
        Vec3 dv = look.subtract(pos);
        float yaw = (float) Math.toDegrees(Math.atan2(-dv.x, dv.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(dv.y, Math.sqrt(dv.x * dv.x + dv.z * dv.z)));
        boolean cut = l.t == 0 || l.t == Ger.T_DEATHS;
        camera.setPos(pos.x, pos.y, pos.z);
        camera.setYRot(yaw);
        camera.setXRot(pitch);
        if (cut) {
            camera.xo = pos.x;
            camera.yo = pos.y;
            camera.zo = pos.z;
            camera.yRotO = yaw;
            camera.xRotO = pitch;
        }
    }

    /**
     * The other world: a rainbow cosmos all around the camera (it hides the real world entirely), and the mob - with
     * its endless line of copies - drawn on the stage up in it.
     */
    @SubscribeEvent
    public static void onRenderStage(net.neoforged.neoforge.client.event.RenderLevelStageEvent event) {
        if (cine == null || camera == null) return;
        if (event.getStage() != net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        Minecraft mc = Minecraft.getInstance();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        PoseStack ps = new PoseStack();
        drawCosmos(ps, buffers, cine.t + partial);
        buffers.endBatch(RenderType.endPortal());
        drawRainbow(ps, buffers, cine.t + partial);
        buffers.endBatch(ModRenderTypes.glow());
        Entity mob = mc.level.getEntity(cine.mobId);
        if (mob != null) {
            Vec3 at = onStage(new Vec3(Mth.lerp(partial, mob.xo, mob.getX()), Mth.lerp(partial, mob.yo, mob.getY()), Mth.lerp(partial, mob.zo, mob.getZ())));
            onStage = true;
            try {
                mc.getEntityRenderDispatcher().render(mob, at.x - cam.x, at.y - cam.y, at.z - cam.z,
                        Mth.rotLerp(partial, mob.yRotO, mob.getYRot()), partial, ps, buffers, net.minecraft.client.renderer.LightTexture.FULL_BRIGHT);
            } catch (RuntimeException ignored) {
                // never crash the cutscene over a model
            } finally {
                onStage = false;
            }
            buffers.endBatch();
        }
    }

    /** All around the camera: the End portal's shifting, colourful starfield - a world that isn't this one. */
    private static void drawCosmos(PoseStack ps, MultiBufferSource buffers, float t) {
        VertexConsumer vc = buffers.getBuffer(RenderType.endPortal());
        org.joml.Matrix4f m = ps.last().pose();
        final float r = 80.0F;
        final int lat = 12, lon = 24;
        for (int i = 0; i < lat; i++) {
            float p0 = Mth.PI * (i / (float) lat - 0.5F), p1 = Mth.PI * ((i + 1) / (float) lat - 0.5F);
            for (int j = 0; j < lon; j++) {
                float a0 = Mth.TWO_PI * j / lon, a1 = Mth.TWO_PI * (j + 1) / lon;
                float[][] q = {sph(r, p0, a0), sph(r, p0, a1), sph(r, p1, a1), sph(r, p1, a0)};
                // Both windings, so it shows from the inside whichever way it's culled.
                for (int k = 0; k < 4; k++) vc.addVertex(m, q[k][0], q[k][1], q[k][2]);
                for (int k = 3; k >= 0; k--) vc.addVertex(m, q[k][0], q[k][1], q[k][2]);
            }
        }
    }

    /**
     * Over the starfield: great soft bands of rainbow light washing slowly across the whole sky (added on as light,
     * so the stars still shine through).
     */
    private static void drawRainbow(PoseStack ps, MultiBufferSource buffers, float t) {
        VertexConsumer vc = buffers.getBuffer(ModRenderTypes.glow());
        org.joml.Matrix4f m = ps.last().pose();
        final float r = 76.0F;
        final int lat = 16, lon = 32;
        for (int i = 0; i < lat; i++) {
            float p0 = Mth.PI * (i / (float) lat - 0.5F), p1 = Mth.PI * ((i + 1) / (float) lat - 0.5F);
            for (int j = 0; j < lon; j++) {
                float a0 = Mth.TWO_PI * j / lon, a1 = Mth.TWO_PI * (j + 1) / lon;
                float[][] q = {sph(r, p0, a0), sph(r, p0, a1), sph(r, p1, a1), sph(r, p1, a0)};
                float[][] pa = {{p0, a0}, {p0, a1}, {p1, a1}, {p1, a0}};
                int[] cols = new int[4];
                for (int k = 0; k < 4; k++) cols[k] = rainbow(pa[k][0], pa[k][1], t);
                for (int k = 0; k < 4; k++) vc.addVertex(m, q[k][0], q[k][1], q[k][2]).setColor(cols[k]);
                for (int k = 3; k >= 0; k--) vc.addVertex(m, q[k][0], q[k][1], q[k][2]).setColor(cols[k]);
            }
        }
    }

    /** Smoothly wrapping rainbow (no seam): the hue swirls with direction and time. */
    private static int rainbow(float pitch, float yaw, float t) {
        float hue = 0.5F + 0.25F * Mth.sin(yaw * 2.0F + t * 0.025F) + 0.25F * Mth.sin(pitch * 3.0F - yaw + t * 0.017F);
        float band = 0.5F + 0.5F * Mth.sin(yaw * 3.0F + pitch * 4.0F + t * 0.04F);
        int rgb = java.awt.Color.HSBtoRGB(hue + t * 0.002F, 0.85F, 1.0F);
        int a = (int) (60 + 90 * band);
        return (a << 24) | (rgb & 0xFFFFFF);
    }

    /** Every tick of the cutscene: soft smoke clouds drifting past, rainbow motes and sparkles floating around the stage. */
    private static void magic(Minecraft mc, Loop l) {
        Entity mob = mc.level.getEntity(l.mobId);
        Vec3 c = onStage(mob == null ? l.origin : mob.position()).add(0.0D, 1.0D, 0.0D);
        var rnd = mc.level.random;
        for (int i = 0; i < 3; i++) {
            double x = c.x + (rnd.nextDouble() - 0.5D) * 26.0D, y = c.y + (rnd.nextDouble() - 0.6D) * 10.0D, z = c.z + (rnd.nextDouble() - 0.5D) * 26.0D;
            mc.level.addParticle(net.minecraft.core.particles.ParticleTypes.CAMPFIRE_COSY_SMOKE, true, x, y, z,
                    (rnd.nextDouble() - 0.5D) * 0.02D, 0.01D, (rnd.nextDouble() - 0.5D) * 0.02D);
        }
        for (int i = 0; i < 4; i++) {
            int rgb = java.awt.Color.HSBtoRGB(rnd.nextFloat(), 0.7F, 1.0F);
            var dust = new net.minecraft.core.particles.DustParticleOptions(new org.joml.Vector3f(((rgb >> 16) & 255) / 255.0F,
                    ((rgb >> 8) & 255) / 255.0F, (rgb & 255) / 255.0F), 2.5F);
            mc.level.addParticle(dust, true, c.x + (rnd.nextDouble() - 0.5D) * 14.0D, c.y + (rnd.nextDouble() - 0.5D) * 6.0D,
                    c.z + (rnd.nextDouble() - 0.5D) * 14.0D, 0.0D, 0.0D, 0.0D);
        }
        if (l.t % 2 == 0) {
            mc.level.addParticle(net.minecraft.core.particles.ParticleTypes.END_ROD, true, c.x + (rnd.nextDouble() - 0.5D) * 12.0D,
                    c.y + (rnd.nextDouble() - 0.5D) * 5.0D, c.z + (rnd.nextDouble() - 0.5D) * 12.0D, 0.0D, 0.01D, 0.0D);
        }
    }

    private static float[] sph(float r, float pitch, float yaw) {
        return new float[]{Mth.cos(pitch) * Mth.cos(yaw) * r, Mth.sin(pitch) * r, Mth.cos(pitch) * Mth.sin(yaw) * r};
    }

    /** No hands floating in the cutscene. */
    @SubscribeEvent
    public static void onHand(net.neoforged.neoforge.client.event.RenderHandEvent event) {
        if (cine != null) event.setCanceled(true);
    }

    /** Your controls are frozen while it plays. */
    @SubscribeEvent
    public static void onInput(MovementInputUpdateEvent event) {
        if (cine == null) return;
        event.getInput().forwardImpulse = 0.0F;
        event.getInput().leftImpulse = 0.0F;
        event.getInput().jumping = false;
        event.getInput().shiftKeyDown = false;
        event.getInput().up = event.getInput().down = event.getInput().left = event.getInput().right = false;
        Minecraft.getInstance().options.keyUp.setDown(false);
        Minecraft.getInstance().options.keySprint.setDown(false);
    }

    /** No HUD in the cutscene... */
    @SubscribeEvent
    public static void onGuiLayer(RenderGuiLayerEvent.Pre event) {
        if (cine != null) event.setCanceled(true);
    }

    /** ...just letterbox bars and a golden haze. */
    @SubscribeEvent
    public static void onGui(RenderGuiEvent.Post event) {
        if (cine == null) return;
        Minecraft mc = Minecraft.getInstance();
        GuiGraphics g = event.getGuiGraphics();
        int w = g.guiWidth(), h = g.guiHeight();
        float tt = cine.t + event.getPartialTick().getGameTimeDeltaPartialTick(true);
        float in = Mth.clamp(tt / 6.0F, 0.0F, 1.0F) * Mth.clamp((Ger.T_DONE - tt) / 6.0F, 0.0F, 1.0F);
        int bar = (int) (h * 0.11F * in);
        int gold = (int) (60 * in) << 24 | 0xF2C744;
        g.fill(0, 0, w, h, gold);
        g.fill(0, 0, w, bar, 0xFF000000);
        g.fill(0, h - bar, w, h, 0xFF000000);
        if (tt > 4) {
            String title = "RETURN TO ZERO";
            int a = (int) (255 * in) << 24;
            g.drawCenteredString(mc.font, Component.literal(title).withStyle(s -> s.withBold(true)), w / 2, h - bar + (bar - 8) / 2, a | 0xF2C744);
            g.drawString(mc.font, "[X] skip", w - mc.font.width("[X] skip") - 6, (bar - 8) / 2, a | 0x888888, false);
        }
    }

    // ------------------------------------------------------------------ the loop, for everyone

    private static boolean ghosting;
    private static final Map<LivingEntity, float[]> SAVED_HEAD = new HashMap<>();

    private static float tipAngle(Loop l, float tt) {
        if (tt < Ger.T_DEATHS || tt >= Ger.T_END) return 0.0F;
        float d = (tt - Ger.T_DEATHS) % Ger.DEATH_LEN;
        if (d < 6.0F || d >= 22.0F) return 0.0F;
        return Math.min(1.0F, (d - 6.0F) / 8.0F) * 90.0F;
    }

    /** How far the mob (copy 0) or copy i has turned to look behind it, in degrees. */
    private static float lookBack(float tt, int i) {
        float start = Ger.T_LOOK + i * 4.0F;
        if (tt < start) return 0.0F;
        float k = Math.min(1.0F, (tt - start) / 6.0F);
        // Look back... and stay looking back while the deaths repeat.
        return k * k * (3.0F - 2.0F * k) * 115.0F;
    }

    @SubscribeEvent
    public static void onRenderPre(RenderLivingEvent.Pre<?, ?> event) {
        if (ghosting) return;
        LivingEntity e = event.getEntity();
        Loop l = LOOPS.get(e.getId());
        if (l == null) return;
        float tt = l.t + event.getPartialTick();
        PoseStack ps = event.getPoseStack();
        ps.pushPose();
        float tip = tipAngle(l, tt);
        if (tip > 0.0F) ps.mulPose(Axis.ZP.rotationDegrees(tip));
        // It turns its whole body to look behind it (twisting just the head looked broken).
        SAVED_HEAD.put(e, new float[]{e.yHeadRot, e.yHeadRotO, e.yBodyRot, e.yBodyRotO});
        float turn = lookBack(tt, 0) * 0.55F;
        e.yBodyRot += turn;
        e.yBodyRotO += turn;
        e.yHeadRot = e.yBodyRot + lookBack(tt, 0) * 0.45F;
        e.yHeadRotO = e.yBodyRotO + lookBack(tt, 0) * 0.45F;
    }

    @SubscribeEvent
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void onRenderPost(RenderLivingEvent.Post event) {
        if (ghosting) return;
        LivingEntity e = event.getEntity();
        Loop l = LOOPS.get(e.getId());
        if (l == null) return;
        PoseStack ps = event.getPoseStack();
        ps.popPose();
        float tt = l.t + event.getPartialTick();
        // The copies: a line of see-through echoes stretching out behind it.
        float show = Mth.clamp((tt - (onStage ? 2.0F : Ger.T_REWIND)) / 10.0F, 0.0F, 1.0F) * Mth.clamp((Ger.T_END - tt) / 6.0F, 0.0F, 1.0F);
        Minecraft mc = Minecraft.getInstance();
        Entity owner = mc.level == null ? null : mc.level.getEntity(l.ownerId);
        if (show > 0.01F && owner != null) {
            Vec3 back = l.origin.subtract(owner.position()).multiply(1.0D, 0.0D, 1.0D);
            back = back.lengthSqr() < 1.0E-4D ? new Vec3(1.0D, 0.0D, 0.0D) : back.normalize();
            double spacing = Math.max(0.9D, e.getBbWidth() * 1.25D);
            Vec3 side = new Vec3(-back.z, 0.0D, back.x);
            float[] saved = SAVED_HEAD.get(e);
            ResourceLocation tex = event.getRenderer().getTextureLocation(e);
            ghosting = true;
            try {
                int count = onStage ? STAGE_COPIES : COPIES;
                for (int i = 1; i <= count; i++) {
                    // A curving trail: each copy further back swings a little more to the side and rises a touch,
                    // like the line bends back through time.
                    double along = spacing * i;
                    double bend = onStage ? 0.0D : 0.11D * i * i * spacing;
                    double rise = onStage ? 0.0D : 0.03D * i * i;
                    float curveYaw = onStage ? 0.0F : (float) Math.toDegrees(Math.atan2(0.22D * i, 1.0D)); // faces along the curve
                    float turn = lookBack(tt, i);
                    float baseBody = saved != null ? saved[2] : e.yBodyRot;
                    float baseBodyO = saved != null ? saved[3] : e.yBodyRotO;
                    e.yBodyRot = baseBody + curveYaw + turn * 0.55F;
                    e.yBodyRotO = baseBodyO + curveYaw + turn * 0.55F;
                    e.yHeadRot = e.yBodyRot + turn * 0.45F;
                    e.yHeadRotO = e.yBodyRotO + turn * 0.45F;
                    float alpha = onStage ? show * Math.max(0.15F, 0.95F - i * 0.045F) : show * (0.55F - i * 0.065F);
                    ps.pushPose();
                    ps.translate(back.x * along + side.x * bend, rise, back.z * along + side.z * bend);
                    event.getRenderer().render(e, Mth.rotLerp(event.getPartialTick(), e.yRotO, e.getYRot()), event.getPartialTick(), ps,
                            ghost(event.getMultiBufferSource(), tex, alpha), event.getPackedLight());
                    ps.popPose();
                }
            } catch (RuntimeException ignored) {
                // never crash rendering over an echo
            } finally {
                ghosting = false;
            }
        }
        float[] h = SAVED_HEAD.remove(e);
        if (h != null) {
            e.yHeadRot = h[0];
            e.yHeadRotO = h[1];
            e.yBodyRot = h[2];
            e.yBodyRotO = h[3];
        }
    }

    /** Everything drawn see-through, with a golden tint. */
    private static MultiBufferSource ghost(MultiBufferSource src, ResourceLocation tex, float alpha) {
        return type -> new Tinted(src.getBuffer(RenderType.entityTranslucent(tex)), alpha);
    }

    private record Tinted(VertexConsumer in, float alpha) implements VertexConsumer {
        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            in.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            in.setColor((int) (r * 0.9F + 25), (int) (g * 0.85F + 20), (int) (b * 0.6F), (int) (a * alpha));
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            in.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            in.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            in.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            in.setNormal(x, y, z);
            return this;
        }
    }
}
