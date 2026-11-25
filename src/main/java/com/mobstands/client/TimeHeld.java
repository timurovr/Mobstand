package com.mobstands.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Caught in someone else's stopped time: you are completely frozen. You can't walk (not even a twitch), jump, sneak,
 * turn your head, hit, use or eat anything, switch hotbar slots or open your inventory. Chat and the pause menu still
 * work. It all lets go the moment time moves again.
 */
public final class TimeHeld {
    private TimeHeld() {}

    private static boolean held;
    private static float yaw, pitch;
    private static int slot;

    public static void set(boolean on) {
        Minecraft mc = Minecraft.getInstance();
        held = on;
        if (on && mc.player != null) {
            yaw = mc.player.getYRot();
            pitch = mc.player.getXRot();
            slot = mc.player.getInventory().selected;
            if (mc.player.isUsingItem()) mc.player.stopUsingItem();
        }
    }

    public static boolean held() {
        return held;
    }

    private static void pin(Minecraft mc) {
        var p = mc.player;
        if (p == null) {
            held = false;
            return;
        }
        p.setYRot(yaw);
        p.setXRot(pitch);
        p.yRotO = yaw;
        p.xRotO = pitch;
        p.setYHeadRot(yaw);
        p.yHeadRotO = yaw;
        p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        p.getInventory().selected = slot;
        if (p.isUsingItem()) p.stopUsingItem();
        if (p.isSprinting()) p.setSprinting(false);
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Pre event) {
        if (!held) return;
        Minecraft mc = Minecraft.getInstance();
        pin(mc);
        if (mc.screen instanceof AbstractContainerScreen<?>) mc.setScreen(null); // no inventory either
    }

    @SubscribeEvent
    public static void onFrame(RenderFrameEvent.Pre event) {
        if (held) pin(Minecraft.getInstance());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onCamera(ViewportEvent.ComputeCameraAngles event) {
        if (!held) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !mc.options.getCameraType().isFirstPerson()) return;
        event.setYaw(yaw);
        event.setPitch(pitch);
    }

    @SubscribeEvent
    public static void onInput(MovementInputUpdateEvent event) {
        if (!held) return;
        var in = event.getInput();
        in.forwardImpulse = 0.0F;
        in.leftImpulse = 0.0F;
        in.up = in.down = in.left = in.right = false;
        in.jumping = false;
        in.shiftKeyDown = false;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onClick(InputEvent.InteractionKeyMappingTriggered event) {
        if (!held) return;
        event.setCanceled(true);
        event.setSwingHand(false);
    }
}
