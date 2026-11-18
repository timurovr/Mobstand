package com.mobstands.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/**
 * Horse Rush "focus": each well-timed Ctrl tap zooms the view in a little and darkens the screen; once the charge
 * completes there's an impact frame (a white flash snapping to black), the view bursts back out and the darkness
 * lifts as you take off.
 */
public final class RushFx {
    private RushFx() {}

    /** Charge time on the server (keep in sync with StandManager.RUSH_CHARGE_TICKS). */
    private static final int CHARGE_TICKS = 20;

    private static float focus, focusO, focusTarget;
    private static float burst, burstO;
    private static long chargeEnd = -1L;
    private static long flashStart = Long.MIN_VALUE / 2;
    private static long tapWindowEnd = -1L;

    /** A timing tap: n = 1, 2 or 3 taps so far. */
    public static void tap(int n, long now) {
        focusTarget = Math.min(1.0F, n / 3.0F);
        tapWindowEnd = now + 15;
        if (n >= 3) chargeEnd = now + CHARGE_TICKS;
    }

    public static void tick(long now) {
        focusO = focus;
        burstO = burst;
        if (chargeEnd >= 0 && now >= chargeEnd) {
            chargeEnd = -1L;
            flashStart = now;
            burst = 1.0F;
            focusTarget = 0.0F;
            // Took off from a standstill: you bolt forward on your own for 2 seconds before you take over.
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && mc.player.getDeltaMovement().horizontalDistanceSqr() < 0.004D) autoRun = 40;
        } else if (chargeEnd < 0 && now > tapWindowEnd) {
            focusTarget = 0.0F; // stopped tapping before the third
        }
        boolean rising = focusTarget > focus;
        focus += (focusTarget - focus) * (rising ? 0.55F : 0.12F);
        if (chargeEnd >= 0) focus = Math.min(1.0F, focus + 0.004F); // holding the focus while it charges
        burst *= 0.82F;
        if (burst < 0.002F) burst = 0.0F;
    }

    /** Ticks left of the forced forward run after a standing take-off. */
    public static int autoRun;

    /** Called at the start of every client tick: holds "forward" + sprint while the auto-run lasts. */
    public static void preTick() {
        Minecraft mc = Minecraft.getInstance();
        if (autoRun <= 0 || mc.player == null) return;
        autoRun--;
        if (autoRun > 0 && mc.screen == null) {
            mc.options.keyUp.setDown(true);
            mc.player.setSprinting(true);
        } else {
            // Hand control back: forward stays held only if you're actually holding it.
            mc.options.keyUp.setDown(com.mojang.blaze3d.platform.InputConstants.isKeyDown(mc.getWindow().getWindow(),
                    mc.options.keyUp.getKey().getValue()));
            autoRun = 0;
        }
    }

    public static boolean charging() {
        return chargeEnd >= 0;
    }

    public static boolean idle() {
        return focus < 0.002F && focusO < 0.002F && burst == 0.0F && burstO == 0.0F;
    }

    /** Field of view multiplier: zoomed in while focusing, bursting wide at the take-off. */
    public static double fovScale(float partial) {
        float f = Mth.lerp(partial, focusO, focus);
        float b = Mth.lerp(partial, burstO, burst);
        return (1.0D - 0.16D * f) * (1.0D + 0.22D * b);
    }

    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        float partial = delta.getGameTimeDeltaPartialTick(true);
        float f = Mth.lerp(partial, focusO, focus);
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        if (f > 0.005F) {
            // Darkness closing in from the edges, plus an overall dim.
            g.fill(0, 0, w, h, argb((int) (95 * f), 0x000000));
            int band = (int) (h * 0.32F);
            g.fillGradient(0, 0, w, band, argb((int) (200 * f), 0x000000), 0x00000000);
            g.fillGradient(0, h - band, w, h, 0x00000000, argb((int) (200 * f), 0x000000));
            int side = (int) (w * 0.18F);
            for (int i = 0; i < side; i += 2) { // horizontal fade from each side
                int a = (int) (170 * f * (1.0F - i / (float) side));
                g.fill(i, 0, i + 2, h, argb(a, 0x000000));
                g.fill(w - i - 2, 0, w - i, h, argb(a, 0x000000));
            }
        }
        // Impact frame: a hard white flash that snaps to black, then clears.
        float t = (mc.level.getGameTime() - flashStart) + partial;
        if (t >= 0.0F && t < 1.5F) {
            g.fill(0, 0, w, h, argb((int) (235 * (1.0F - t / 1.5F * 0.3F)), 0xFFFFFF));
        } else if (t >= 1.5F && t < 5.0F) {
            g.fill(0, 0, w, h, argb((int) (200 * (1.0F - (t - 1.5F) / 3.5F)), 0x000000));
        }
    }

    private static int argb(int alpha, int rgb) {
        return (Mth.clamp(alpha, 0, 255) << 24) | (rgb & 0xFFFFFF);
    }
}
