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
    private static final int COPIES = 5;

    public static void start(GerCutscenePayload p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        Loop l = new Loop();
        l.mobId = p.mobId();
        l.ownerId = p.ownerId();
        l.origin = new Vec3(p.ox(), p.oy(), p.oz());
        LOOPS.put(l.mobId, l);
        if (p.cinematic()) {
            endCine();
            cine = l;
            camera = EntityType.MARKER.create(mc.level);
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
        if (cine.t > Ger.T_DONE || !LOOPS.containsKey(cine.mobId)) {
            endCine();
            return;
        }
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

    /** Where the camera is this tick. */
    private static void placeCamera(Minecraft mc, float step) {
        Loop l = cine;
        Vec3 eye = mc.player.getEyePosition();
        Vec3 pos, look;
        if (l.t < Ger.T_REWIND) {
            // Shot 1: on you - slowly pushing in on your face, your Stand looming behind you.
            Vec3 fwd = Vec3.directionFromRotation(0.0F, mc.player.getYRot());
            Vec3 side = new Vec3(-fwd.z, 0.0D, fwd.x);
            double k = l.t / (double) Ger.T_REWIND;
            pos = eye.add(fwd.scale(3.4D - k * 0.9D)).add(side.scale(0.7D)).add(0.0D, 0.15D, 0.0D);
            look = eye.add(0.0D, -0.1D, 0.0D);
        } else {
            // Shot 2: on the mob, from the side, so the line of copies behind it is in view; slowly drifting round.
            Entity mob = mc.level.getEntity(l.mobId);
            double h = mob == null ? 1.8D : mob.getBbHeight();
            Vec3 center = (mob == null ? l.origin : mob.position()).add(0.0D, h * 0.6D, 0.0D);
            Vec3 line = l.origin.subtract(mc.player.position()).multiply(1.0D, 0.0D, 1.0D);
            line = line.lengthSqr() < 1.0E-4D ? new Vec3(1.0D, 0.0D, 0.0D) : line.normalize();
            orbit += 0.5F * step;
            double a = Math.toRadians(70.0D + orbit);
            Vec3 dir = line.scale(Math.cos(a)).add(new Vec3(-line.z, 0.0D, line.x).scale(Math.sin(a)));
            double dist = 5.0D + h * 1.6D;
            pos = center.add(dir.scale(dist)).add(line.scale(1.5D)).add(0.0D, 1.0D + h * 0.3D, 0.0D);
            look = center.add(line.scale(1.5D));
        }
        Vec3 d = look.subtract(pos);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        camera.setPos(pos.x, pos.y, pos.z);
        camera.setYRot(yaw);
        camera.setXRot(pitch);
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
        SAVED_HEAD.put(e, new float[]{e.yHeadRot, e.yHeadRotO});
        float turn = lookBack(tt, 0);
        e.yHeadRot = e.yBodyRot + turn;
        e.yHeadRotO = e.yBodyRotO + turn;
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
        float show = Mth.clamp((tt - Ger.T_REWIND) / 10.0F, 0.0F, 1.0F) * Mth.clamp((Ger.T_END - tt) / 6.0F, 0.0F, 1.0F);
        Minecraft mc = Minecraft.getInstance();
        Entity owner = mc.level == null ? null : mc.level.getEntity(l.ownerId);
        if (show > 0.01F && owner != null) {
            Vec3 back = l.origin.subtract(owner.position()).multiply(1.0D, 0.0D, 1.0D);
            back = back.lengthSqr() < 1.0E-4D ? new Vec3(1.0D, 0.0D, 0.0D) : back.normalize();
            double spacing = Math.max(0.9D, e.getBbWidth() * 1.3D);
            ResourceLocation tex = event.getRenderer().getTextureLocation(e);
            ghosting = true;
            try {
                for (int i = 1; i <= COPIES; i++) {
                    float turn = lookBack(tt, i);
                    e.yHeadRot = e.yBodyRot + turn;
                    e.yHeadRotO = e.yBodyRotO + turn;
                    float alpha = show * (0.5F - i * 0.07F);
                    ps.pushPose();
                    ps.translate(back.x * spacing * i, 0.0D, back.z * spacing * i);
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
