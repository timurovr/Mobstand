package com.mobstands.client;

import com.mobstands.network.PossessInputPayload;
import com.mobstands.network.StandActionPayload;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Soul Control on your side: you see through the possessed mob's eyes, your keys walk it, your mouse turns it,
 * and your own body stands still.
 */
public final class PossessClient {
    private PossessClient() {}

    private static int mobId = -1;
    private static float yaw, pitch, frozenYaw, frozenPitch;
    private static float fwd, strafe;
    private static boolean jump, down;
    private static CameraType savedCamera;
    private static boolean started;

    public static boolean active() {
        return mobId >= 0;
    }

    public static void set(int id) {
        Minecraft mc = Minecraft.getInstance();
        if (id < 0) {
            mobId = -1;
            started = false;
            if (mc.player != null) mc.setCameraEntity(mc.player);
            if (savedCamera != null) mc.options.setCameraType(savedCamera);
            savedCamera = null;
            return;
        }
        mobId = id;
        started = false;
    }

    private static Entity mob(Minecraft mc) {
        return mc.level == null || mobId < 0 ? null : mc.level.getEntity(mobId);
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mobId < 0 || mc.player == null) return;
        Entity m = mob(mc);
        if (m == null) return; // not here yet
        if (!started) {
            started = true;
            frozenYaw = mc.player.getYRot();
            frozenPitch = mc.player.getXRot();
            yaw = m.getYRot();
            pitch = 0.0F;
            savedCamera = mc.options.getCameraType();
            mc.options.setCameraType(CameraType.FIRST_PERSON);
        }
        if (mc.getCameraEntity() != m) mc.setCameraEntity(m);
        // Seeing through a Warden's eyes: it's blind. The fog follows the camera, so the darkness goes on the Warden
        // itself here, on your screen only.
        if (m instanceof net.minecraft.world.entity.monster.warden.Warden w && w.tickCount % 10 == 0) {
            w.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.DARKNESS, 60, 0, false, false));
            w.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.BLINDNESS, 60, 0, false, false));
        }
        transferLook();
        PacketDistributor.sendToServer(new PossessInputPayload(fwd, strafe, jump, down, yaw, pitch));
    }

    /** Every frame: mouse movement turns the mob, not you; the mob's head follows straight away (no lag). */
    public static void onFrame(net.neoforged.neoforge.client.event.RenderFrameEvent.Pre event) {
        transferLook();
    }

    private static void transferLook() {
        Minecraft mc = Minecraft.getInstance();
        if (!started || mc.player == null) return;
        Entity m = mob(mc);
        if (m == null) return;
        var p = mc.player;
        yaw += p.getYRot() - frozenYaw;
        pitch = Mth.clamp(pitch + p.getXRot() - frozenPitch, -90.0F, 90.0F);
        p.setYRot(frozenYaw);
        p.yRotO = frozenYaw;
        p.setXRot(frozenPitch);
        p.xRotO = frozenPitch;
        p.setYHeadRot(frozenYaw);
        p.yHeadRotO = frozenYaw;
        m.setYRot(yaw);
        m.yRotO = yaw;
        m.setXRot(pitch);
        m.xRotO = pitch;
        m.setYHeadRot(yaw);
        if (m instanceof net.minecraft.world.entity.LivingEntity le) {
            le.yHeadRotO = yaw;
            le.yBodyRot = yaw;
            le.yBodyRotO = yaw;
        }
    }

    /** Your keys walk the mob; your body doesn't move. */
    public static void onInput(net.neoforged.neoforge.client.event.MovementInputUpdateEvent event) {
        if (!active()) return;
        var in = event.getInput();
        fwd = in.forwardImpulse;
        strafe = in.leftImpulse;
        jump = in.jumping;
        down = in.shiftKeyDown;
        in.forwardImpulse = 0.0F;
        in.leftImpulse = 0.0F;
        in.up = in.down = in.left = in.right = false;
        in.jumping = false;
        in.shiftKeyDown = false;
    }

    /** Left click attacks with the mob; right click does nothing. Returns true if it took the click. */
    public static boolean onClick(boolean attack, boolean use) {
        if (!active()) return false;
        if (attack || use) PacketDistributor.sendToServer(new StandActionPayload(attack ? 35 : 36)); // left: attack, right: its power
        return true;
    }
}
