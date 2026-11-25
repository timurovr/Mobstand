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
        int[] deaths = {0, 0, 0};
        /** Where the mob was when it struck (it's dragged back from here to {@code origin}). */
        Vec3 hitPos;
        int t;
    }

    private static final Map<Integer, Loop> LOOPS = new HashMap<>();
    private static Loop cine;
    private static Entity camera;
    private static boolean skipWasDown = true;
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
        l.deaths = new int[]{p.d0(), p.d1(), p.d2()};
        Entity m = mc.level.getEntity(p.mobId());
        l.hitPos = m != null ? m.position() : l.origin;
        LOOPS.put(l.mobId, l);
        if (p.cinematic()) {
            endCine();
            cine = l;
            camera = EntityType.MARKER.create(mc.level);
            stage = new Vec3(l.origin.x, mc.level.getMaxBuildHeight() + 90.0D, l.origin.z);

            skipWasDown = true; // the key you countered with doesn't skip it straight away
            if (camera != null) {
                placeCamera(mc, 0.0F);
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
        beats(mc, cine);
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
        if (camera != null && mc.getCameraEntity() != camera) mc.setCameraEntity(camera);
    }

    /**
     * The deaths happen down in the real world, but the cutscene films the stage high above it - so they're
     * played again here, on the stage, where the camera can see them (same timing as Ger.deathTick).
     */
    private static void stageDeath(Minecraft mc, Loop l, int kind, int d, double h) {
        var lv = mc.level;
        var rnd = lv.random;
        Vec3 o = onStage(l.origin);
        int hit = hitTick(kind);
        switch (kind) {
            case 0 -> { // burns
                if (d < 22) for (int k = 0; k < 6; k++) lv.addParticle(net.minecraft.core.particles.ParticleTypes.FLAME, true,
                        o.x + (rnd.nextDouble() - 0.5D) * 0.8D, o.y + rnd.nextDouble() * h, o.z + (rnd.nextDouble() - 0.5D) * 0.8D, 0.0D, 0.04D, 0.0D);
                if (d < 22 && d % 2 == 0) lv.addParticle(net.minecraft.core.particles.ParticleTypes.LARGE_SMOKE, true, o.x, o.y + h, o.z, 0.0D, 0.06D, 0.0D);
            }
            case 1 -> { // lightning
                if (d == 0) {
                    var bolt = EntityType.LIGHTNING_BOLT.create(lv);
                    if (bolt != null) {
                        bolt.setId(-7000 - l.t); // a client-only bolt: an id no real entity uses
                        bolt.moveTo(o.x, o.y, o.z);
                        bolt.setVisualOnly(true);
                        lv.addEntity(bolt);
                    }
                }
                if (d >= hit && d < hit + 6) for (int k = 0; k < 5; k++) lv.addParticle(net.minecraft.core.particles.ParticleTypes.ELECTRIC_SPARK, true,
                        o.x + (rnd.nextDouble() - 0.5D), o.y + rnd.nextDouble() * h, o.z + (rnd.nextDouble() - 0.5D), (rnd.nextDouble() - 0.5D) * 0.3D, 0.1D, (rnd.nextDouble() - 0.5D) * 0.3D);
            }
            case 2 -> { // drowns
                if (d < 22) for (int k = 0; k < 5; k++) lv.addParticle(net.minecraft.core.particles.ParticleTypes.BUBBLE_COLUMN_UP, true,
                        o.x + (rnd.nextDouble() - 0.5D) * 0.6D, o.y + rnd.nextDouble() * h, o.z + (rnd.nextDouble() - 0.5D) * 0.6D, 0.0D, 0.1D, 0.0D);
                if (d == 0 || d == hit) for (int k = 0; k < 20; k++) lv.addParticle(net.minecraft.core.particles.ParticleTypes.SPLASH, true,
                        o.x + (rnd.nextDouble() - 0.5D) * 0.8D, o.y + h, o.z + (rnd.nextDouble() - 0.5D) * 0.8D, 0.0D, 0.2D, 0.0D);
            }
            case 3 -> { // falls from the sky
                if (d == 0) for (int k = 0; k < 10; k++) lv.addParticle(net.minecraft.core.particles.ParticleTypes.CLOUD, true,
                        o.x + (rnd.nextDouble() - 0.5D) * 0.6D, o.y + 9.0D, o.z + (rnd.nextDouble() - 0.5D) * 0.6D, 0.0D, 0.0D, 0.0D);
                if (d == 10) for (int k = 0; k < 24; k++) {
                    double a = k / 24.0D * Math.PI * 2.0D;
                    lv.addParticle(net.minecraft.core.particles.ParticleTypes.CLOUD, true, o.x, o.y + 0.1D, o.z, Math.cos(a) * 0.25D, 0.02D, Math.sin(a) * 0.25D);
                }
            }
            case 4 -> { // an anvil out of nowhere
                var anvil = new net.minecraft.core.particles.BlockParticleOption(net.minecraft.core.particles.ParticleTypes.BLOCK,
                        net.minecraft.world.level.block.Blocks.ANVIL.defaultBlockState());
                if (d < hit) lv.addParticle(net.minecraft.core.particles.ParticleTypes.CRIT, true, o.x, o.y + h + 4.0D - d * 0.5D, o.z, 0.0D, -0.5D, 0.0D);
                if (d == hit) for (int k = 0; k < 30; k++) lv.addParticle(anvil, true, o.x + (rnd.nextDouble() - 0.5D) * 0.8D, o.y + h, o.z + (rnd.nextDouble() - 0.5D) * 0.8D,
                        (rnd.nextDouble() - 0.5D) * 0.4D, 0.3D, (rnd.nextDouble() - 0.5D) * 0.4D);
            }
            case 5 -> { // stabbed from nowhere
                if (d == hit) {
                    for (int k = 0; k < 3; k++) lv.addParticle(net.minecraft.core.particles.ParticleTypes.SWEEP_ATTACK, true,
                            o.x + (rnd.nextDouble() - 0.5D) * 0.5D, o.y + h * (0.4D + 0.3D * rnd.nextDouble()), o.z + (rnd.nextDouble() - 0.5D) * 0.5D, 0.0D, 0.0D, 0.0D);
                    for (int k = 0; k < 20; k++) lv.addParticle(net.minecraft.core.particles.ParticleTypes.CRIT, true, o.x, o.y + h * 0.6D, o.z,
                            (rnd.nextDouble() - 0.5D) * 0.8D, (rnd.nextDouble() - 0.2D) * 0.6D, (rnd.nextDouble() - 0.5D) * 0.8D);
                }
            }
            default -> { }
        }
        if (d == hit) for (int k = 0; k < 12; k++) lv.addParticle(net.minecraft.core.particles.ParticleTypes.DAMAGE_INDICATOR, true,
                o.x, o.y + h * 0.6D, o.z, (rnd.nextDouble() - 0.5D) * 0.4D, 0.2D, (rnd.nextDouble() - 0.5D) * 0.4D);
        if (d == 22) {
            // It dies... and is back where it started, again.
            for (int k = 0; k < 18; k++) lv.addParticle(net.minecraft.core.particles.ParticleTypes.POOF, true,
                    o.x + (rnd.nextDouble() - 0.5D) * 0.6D, o.y + rnd.nextDouble() * h, o.z + (rnd.nextDouble() - 0.5D) * 0.6D, 0.0D, 0.03D, 0.0D);
            var gold = new net.minecraft.core.particles.DustParticleOptions(new org.joml.Vector3f(1.0F, 0.83F, 0.25F), 1.4F);
            for (int k = 0; k < 16; k++) {
                double a = k / 16.0D * Math.PI * 2.0D;
                lv.addParticle(gold, true, o.x + Math.cos(a) * 0.9D, o.y + 0.1D, o.z + Math.sin(a) * 0.9D, 0.0D, 0.05D, 0.0D);
            }
        }
    }

    /** A smooth 0..1 ease. */
    private static double ease(double k) {
        k = Mth.clamp(k, 0.0D, 1.0D);
        return k * k * (3.0D - 2.0D * k);
    }

    /** Where something in the world appears on the stage. */
    private static Vec3 onStage(Vec3 world) {
        return stage.add(world.subtract(cine.origin));
    }

    /**
     * Where the mob is at time tt, worked out from the same timeline the server follows (held where it struck,
     * dragged back to its origin, falling from the sky on a fall death) - instead of following the position the
     * server sends, which arrives in uneven steps and made the camera and the stage jitter.
     */
    private static Vec3 anchor(Loop l, float tt) {
        Vec3 p;
        if (tt < Ger.T_REWIND) p = l.hitPos;
        else if (tt < Ger.T_LOOK) p = l.hitPos.lerp(l.origin, ease((tt - Ger.T_REWIND) / (double) (Ger.T_LOOK - Ger.T_REWIND)));
        else p = l.origin;
        if (tt >= Ger.T_DEATHS && tt < Ger.T_END) {
            int i = Math.min(2, (int) ((tt - Ger.T_DEATHS) / Ger.DEATH_LEN));
            float d = (tt - Ger.T_DEATHS) - i * Ger.DEATH_LEN;
            if (l.deaths[i] == 3 && d < 10.0F) p = p.add(0.0D, 9.0D - d * 0.9D, 0.0D);
        }
        return p;
    }

    /** Which shot the cutscene is on: 0 counter, 1 rewind, 2 look back, 3-5 the three deaths, 6 the end. */
    private static int shot(float tt) {
        if (tt < Ger.T_REWIND) return 0;
        if (tt < Ger.T_LOOK) return 1;
        if (tt < Ger.T_DEATHS) return 2;
        if (tt < Ger.T_END) return 3 + Math.min(2, (int) ((tt - Ger.T_DEATHS) / Ger.DEATH_LEN));
        return 6;
    }

    /** The tick a death's blow lands, counted from the start of that death (matches Ger.deathTick). */
    private static int hitTick(int kind) {
        return kind == 4 ? 8 : 6;
    }

    /** 1 right as a death's blow lands, fading fast; 0 otherwise. */
    private static float hitPulse(Loop l, float tt) {
        if (tt < Ger.T_DEATHS || tt >= Ger.T_END) return 0.0F;
        int i = Math.min(2, (int) ((tt - Ger.T_DEATHS) / Ger.DEATH_LEN));
        float d = (tt - Ger.T_DEATHS) - i * Ger.DEATH_LEN - hitTick(l.deaths[i]);
        return d < 0.0F ? 0.0F : (float) Math.exp(-d / 2.5F);
    }

    /** The camera's zoom: a slow push on the counter, a vertigo dolly zoom on the rewind, a punch on every death. */
    private static float fovMult(Loop l, float tt) {
        int sh = shot(tt);
        float m;
        if (sh == 0) m = 1.08F - 0.1F * (float) ease(tt / Ger.T_REWIND);
        else if (sh == 1) m = 1.0F - 0.42F * (float) ease((tt - Ger.T_REWIND) / (double) (Ger.T_LOOK - Ger.T_REWIND));
        else if (sh == 2) m = 0.92F;
        else if (sh <= 5) m = 0.95F - 0.13F * hitPulse(l, tt);
        else m = 0.95F + 0.2F * (float) ease((tt - Ger.T_END) / (double) (Ger.T_DONE - Ger.T_END));
        return m;
    }

    /** Places the camera for time tt (called every frame, so every move and shake is perfectly smooth). */
    private static void placeCamera(Minecraft mc, float tt) {
        Loop l = cine;
        Entity mob = mc.level.getEntity(l.mobId);
        double h = mob == null ? 1.8D : mob.getBbHeight();
        // The camera follows where it was dragged back to (not where it falls from, on a fall death).
        Vec3 mobPos = onStage(tt < Ger.T_DEATHS ? anchor(l, tt) : l.origin);
        Entity owner = mc.level.getEntity(l.ownerId);
        Vec3 line = l.origin.subtract(owner == null ? l.origin.add(-1.0D, 0.0D, 0.0D) : owner.position()).multiply(1.0D, 0.0D, 1.0D);
        line = line.lengthSqr() < 1.0E-4D ? new Vec3(1.0D, 0.0D, 0.0D) : line.normalize();
        Vec3 side = new Vec3(-line.z, 0.0D, line.x);
        Vec3 up = new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 center = mobPos.add(0.0D, h * 0.6D, 0.0D);
        Vec3 front = line.scale(-1.0D); // the mob faces you; its copies stretch away behind it
        double size = 1.0D + h * 0.6D;
        Vec3 pos, look;
        int sh = shot(tt);
        switch (sh) {
            case 0 -> {
                // The counter: a low hero shot from just off its face, pushing slowly in.
                double k = ease(tt / (double) Ger.T_REWIND);
                pos = center.add(front.scale(size * (3.6D - 1.2D * k))).add(side.scale(size * 0.9D)).add(up.scale(-h * 0.35D));
                look = center.add(up.scale(h * 0.1D));
            }
            case 1 -> {
                // The rewind: a vertigo dolly zoom - the camera pulls back as the lens narrows, so the mob stays
                // the same size while the line of copies behind it stretches out of nowhere.
                double k = ease((tt - Ger.T_REWIND) / (double) (Ger.T_LOOK - Ger.T_REWIND));
                double dist = size * 3.2D / fovMult(l, tt);
                Vec3 dir = front.scale(0.75D).add(side.scale(0.65D)).normalize();
                pos = center.add(dir.scale(dist)).add(up.scale(h * 0.25D + k * 0.6D));
                look = center.add(line.scale(0.8D + k * 1.5D));
            }
            case 2 -> {
                // Looking back: a slow orbit round to its side as each copy turns to look behind it.
                double k = ease((tt - Ger.T_LOOK) / (double) (Ger.T_DEATHS - Ger.T_LOOK));
                double ang = Math.toRadians(35.0D + 75.0D * k);
                Vec3 dir = front.scale(Math.cos(ang)).add(side.scale(Math.sin(ang)));
                pos = center.add(dir.scale(size * 3.4D)).add(up.scale(h * 0.3D));
                look = center.add(line.scale(0.5D + 3.0D * k));
            }
            case 3, 4, 5 -> {
                // The deaths: a different angle for each, slowly pushing in.
                int i = sh - 3;
                double k = ease((tt - Ger.T_DEATHS - i * Ger.DEATH_LEN) / (double) Ger.DEATH_LEN);
                if (i == 0) {
                    // Low, from its other side, the copies trailing off behind.
                    pos = center.add(side.scale(-size * (2.8D - 0.6D * k))).add(line.scale(-size * 1.6D)).add(up.scale(-h * 0.25D));
                    look = center.add(line.scale(1.2D));
                } else if (i == 1) {
                    // From high above, looking down the whole endless line.
                    pos = center.add(up.scale(size * (4.2D - 0.8D * k))).add(front.scale(size * 2.2D)).add(side.scale(size * 0.6D));
                    look = center.add(line.scale(2.5D));
                } else {
                    // Face to face.
                    pos = center.add(front.scale(size * (2.6D - 0.7D * k))).add(side.scale(size * 0.35D)).add(up.scale(h * 0.05D));
                    look = center.add(up.scale(h * 0.05D));
                }
            }
            default -> {
                // The end: the camera cranes up and away from the endless line.
                double k = ease((tt - Ger.T_END) / (double) (Ger.T_DONE - Ger.T_END));
                pos = center.add(front.scale(size * (3.0D + 2.0D * k))).add(side.scale(size * 1.2D)).add(up.scale(size * (0.5D + 3.5D * k)));
                look = center.add(line.scale(1.5D + 2.0D * k));
            }
        }
        // Shake: the counter's impact, and every death's blow.
        float shake = (sh == 0 ? 0.16F * (float) Math.exp(-tt / 3.0F) : 0.0F) + 0.14F * hitPulse(l, tt);
        if (shake > 0.002F) pos = pos.add(side.scale(Math.sin(tt * 13.1D) * shake)).add(up.scale(Math.sin(tt * 17.3D + 1.0D) * shake * 0.7D));
        Vec3 dv = look.subtract(pos);
        float yaw = (float) Math.toDegrees(Math.atan2(-dv.x, dv.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(dv.y, Math.sqrt(dv.x * dv.x + dv.z * dv.z)));
        // Exactly here this frame: no blending with last tick (that blending is what made it step and judder).
        camera.setPos(pos.x, pos.y, pos.z);
        camera.xo = camera.xOld = pos.x;
        camera.yo = camera.yOld = pos.y;
        camera.zo = camera.zOld = pos.z;
        camera.setYRot(yaw);
        camera.setXRot(pitch);
        camera.yRotO = yaw;
        camera.xRotO = pitch;
    }

    /** Every frame, before the world is drawn: put the camera exactly where it belongs at this instant. */
    @SubscribeEvent
    public static void onFrame(net.neoforged.neoforge.client.event.RenderFrameEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (cine == null || camera == null || mc.level == null) return;
        placeCamera(mc, cine.t + event.getPartialTick().getGameTimeDeltaPartialTick(true));
    }

    @SubscribeEvent
    public static void onFov(net.neoforged.neoforge.client.event.ViewportEvent.ComputeFov event) {
        if (cine == null || camera == null) return;
        float tt = cine.t + (float) event.getPartialTick();
        event.setFOV(event.getFOV() * fovMult(cine, tt));
    }

    /** A gentle dutch tilt on the counter, a slow sway while it looks back, and a jolt with every death. */
    @SubscribeEvent
    public static void onAngles(net.neoforged.neoforge.client.event.ViewportEvent.ComputeCameraAngles event) {
        if (cine == null || camera == null) return;
        float tt = cine.t + (float) event.getPartialTick();
        int sh = shot(tt);
        float roll = 0.0F;
        if (sh == 0) roll = -6.0F * (1.0F - (float) ease(tt / Ger.T_REWIND)) + 2.0F;
        else if (sh == 2) roll = 3.0F * Mth.sin((tt - Ger.T_LOOK) * 0.12F);
        else if (sh >= 3 && sh <= 5) roll = (sh == 4 ? 0.0F : sh == 3 ? -3.0F : 2.0F) + 5.0F * hitPulse(cine, tt) * Mth.sin(tt * 9.0F);
        event.setRoll(event.getRoll() + roll);
    }

    /** When copy i (1..) appears on the stage: one after another down the line, as the mob is dragged back. */
    private static float appear(int i) {
        return Ger.T_REWIND + (i - 1) * 0.9F;
    }

    /** The beats: the title's bell, a rising chime as each copy appears (with a golden burst), a deep bell on every death. */
    private static void beats(Minecraft mc, Loop l) {
        int t = l.t;
        var sm = mc.getSoundManager();
        if (t == 0) {
            sm.play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.BELL_BLOCK, 1.5F, 0.9F));
            sm.play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_RESONATE, 0.7F, 1.0F));
        }
        if (t == TITLE_AT) sm.play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.BELL_BLOCK, 0.7F, 0.8F));
        // Copies: every other one rings a note, climbing a pentatonic scale.
        final int[] scale = {0, 2, 4, 7, 9, 12, 14, 16, 19, 21, 24};
        Entity mob = mc.level.getEntity(l.mobId);
        Entity owner = mc.level.getEntity(l.ownerId);
        for (int i = 1; i <= STAGE_COPIES; i++) {
            if (t != (int) Math.ceil(appear(i))) continue;
            if (i % 2 == 1) {
                int note = scale[Math.min(scale.length - 1, (i - 1) / 2)];
                float pitch = 0.5F * (float) Math.pow(2.0D, note / 12.0D);
                sm.play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_CHIME, pitch, 0.8F));
            }
            if (mob != null && owner != null) {
                Vec3 back = l.origin.subtract(owner.position()).multiply(1.0D, 0.0D, 1.0D);
                back = back.lengthSqr() < 1.0E-4D ? new Vec3(1.0D, 0.0D, 0.0D) : back.normalize();
                double spacing = Math.max(0.9D, mob.getBbWidth() * 1.25D);
                Vec3 at = onStage(anchor(l, t)).add(back.scale(spacing * i)).add(0.0D, mob.getBbHeight() * 0.5D, 0.0D);
                var gold = new net.minecraft.core.particles.DustParticleOptions(new org.joml.Vector3f(1.0F, 0.83F, 0.25F), 1.6F);
                for (int k = 0; k < 10; k++) {
                    double a = k / 10.0D * Math.PI * 2.0D;
                    mc.level.addParticle(gold, true, at.x + Math.cos(a) * 0.5D, at.y + Math.sin(a) * 0.5D * mob.getBbHeight(), at.z + Math.sin(a) * 0.5D, 0.0D, 0.0D, 0.0D);
                }
                mc.level.addParticle(net.minecraft.core.particles.ParticleTypes.END_ROD, true, at.x, at.y, at.z, 0.0D, 0.04D, 0.0D);
            }
        }
        // Each death's blow: a deep bell under the barrage - and the death itself, on the stage.
        if (t >= Ger.T_DEATHS && t < Ger.T_END) {
            int i = (t - Ger.T_DEATHS) / Ger.DEATH_LEN, d = (t - Ger.T_DEATHS) % Ger.DEATH_LEN;
            if (d == hitTick(l.deaths[i])) sm.play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.BELL_BLOCK, 0.5F, 0.7F));
            stageDeath(mc, l, l.deaths[i], d, mob == null ? 1.8D : mob.getBbHeight());
        }
        if (t == Ger.T_END) {
            sm.play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_CHIME, 1.0F, 1.0F));
            sm.play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP, 0.6F, 0.6F));
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
            Vec3 at = onStage(anchor(cine, cine.t + partial));
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
        Vec3 c = onStage(anchor(l, l.t)).add(0.0D, 1.0D, 0.0D);
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

    /** When the big title starts spelling itself out. */
    private static final int TITLE_AT = 3;

    /** ...just letterbox bars, a vignette, flashes on every cut and blow, and the title. */
    @SubscribeEvent
    public static void onGui(RenderGuiEvent.Post event) {
        if (cine == null) return;
        Minecraft mc = Minecraft.getInstance();
        GuiGraphics g = event.getGuiGraphics();
        int w = g.guiWidth(), h = g.guiHeight();
        float tt = cine.t + event.getPartialTick().getGameTimeDeltaPartialTick(true);
        float in = (float) ease(tt / 8.0F) * (float) ease((Ger.T_DONE - tt) / 8.0F);

        // Vignette: soft dark edges top and bottom, and a faint golden glow over everything.
        int vig = (int) (150 * in);
        g.fillGradient(0, 0, w, h / 3, vig << 24, 0);
        g.fillGradient(0, h - h / 3, w, h, 0, vig << 24);
        g.fill(0, 0, w, h, ((int) (28 * in) << 24) | 0xF2C744);

        // Flashes: golden on every cut, white on every death's blow, and a golden wash as it ends.
        float flash = 0.0F;
        int flashRgb = 0xFFE9A8;
        for (int cutAt : new int[]{0, Ger.T_REWIND, Ger.T_LOOK, Ger.T_DEATHS, Ger.T_DEATHS + Ger.DEATH_LEN, Ger.T_DEATHS + 2 * Ger.DEATH_LEN, Ger.T_END}) {
            if (tt >= cutAt) flash = Math.max(flash, 0.55F * (float) Math.exp(-(tt - cutAt) / 1.6F));
        }
        float blow = hitPulse(cine, tt);
        if (blow * 0.6F > flash) {
            flash = blow * 0.6F;
            flashRgb = 0xFFFFFF;
        }
        flash = Math.max(flash, 0.7F * (float) ease((tt - (Ger.T_DONE - 6)) / 6.0F));
        if (flash > 0.01F) g.fill(0, 0, w, h, ((int) (255 * Mth.clamp(flash, 0.0F, 1.0F)) << 24) | flashRgb);

        // Letterbox bars.
        int bar = (int) (h * 0.12F * in);
        g.fill(0, 0, w, bar, 0xFF000000);
        g.fill(0, h - bar, w, h, 0xFF000000);

        // The title: RETURN TO ZERO spelled out letter by letter in the middle of the screen, each letter
        // slamming in, then it fades and settles into the bottom bar.
        String title = "RETURN TO ZERO";
        float big = Ger.T_REWIND - 4.0F;
        if (tt >= TITLE_AT && tt < big + 4.0F) {
            float fade = 1.0F - Mth.clamp((tt - big) / 4.0F, 0.0F, 1.0F);
            float scale = 2.6F;
            int tw = mc.font.width(title);
            float x0 = w / 2.0F - tw * scale / 2.0F, y0 = h / 2.0F - 4.0F * scale + h * 0.18F;
            float x = 0.0F;
            for (int j = 0; j < title.length(); j++) {
                String ch = String.valueOf(title.charAt(j));
                int cw = mc.font.width(ch);
                float lt = tt - TITLE_AT - j * 0.55F;
                if (lt > 0.0F && ch.charAt(0) != ' ') {
                    float pop = 1.0F + 0.9F * (float) Math.exp(-lt / 0.9F);
                    int a = (int) (255 * Mth.clamp(lt / 1.2F, 0.0F, 1.0F) * fade);
                    if (a > 4) {
                        g.pose().pushPose();
                        g.pose().translate(x0 + (x + cw / 2.0F) * scale, y0 + 4.0F * scale, 0.0F);
                        g.pose().scale(scale * pop, scale * pop, 1.0F);
                        g.drawString(mc.font, Component.literal(ch).withStyle(s -> s.withBold(true)), -cw / 2, -4, (a << 24) | 0xF2C744, true);
                        g.pose().popPose();
                    }
                }
                x += cw;
            }
        }
        if (tt > big) {
            int a = (int) (255 * in * Mth.clamp((tt - big) / 4.0F, 0.0F, 1.0F)) << 24;
            g.drawCenteredString(mc.font, Component.literal(title).withStyle(s -> s.withBold(true)), w / 2, h - bar + (bar - 8) / 2, a | 0xF2C744);
        }
        if (tt > 4) {
            int a = (int) (255 * in) << 24;
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
        float show = onStage ? Mth.clamp((Ger.T_DONE - tt) / 6.0F, 0.0F, 1.0F)
                : Mth.clamp((tt - Ger.T_REWIND) / 10.0F, 0.0F, 1.0F) * Mth.clamp((Ger.T_END - tt) / 6.0F, 0.0F, 1.0F);
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
                    if (onStage) {
                        // Pops in one after another down the line...
                        alpha *= Mth.clamp((tt - appear(i)) / 3.0F, 0.0F, 1.0F);
                        // ...and on every death a wave of light runs down it.
                        float wave = 0.0F;
                        for (int dI = 0; dI < 3; dI++) {
                            float hitAt = Ger.T_DEATHS + dI * Ger.DEATH_LEN + hitTick(l.deaths[dI]) + i * 0.7F;
                            float x = tt - hitAt;
                            wave = Math.max(wave, (float) Math.exp(-x * x / 1.8F));
                        }
                        alpha = Math.min(1.0F, alpha + 0.45F * wave * show);
                    }
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
        return type -> type == RenderType.entityCutoutNoCull(tex) || type == RenderType.entityCutout(tex) || type == RenderType.entitySolid(tex)
                || type == RenderType.entityTranslucent(tex) || type == RenderType.entityTranslucentCull(tex) || type == RenderType.entityCutoutNoCullZOffset(tex)
                ? new Tinted(src.getBuffer(RenderType.entityTranslucent(tex)), alpha)
                : SKIP;
    }

    /** Swallows everything drawn into it. */
    private static final VertexConsumer SKIP = new VertexConsumer() {
        @Override public VertexConsumer addVertex(float x, float y, float z) { return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    };

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
