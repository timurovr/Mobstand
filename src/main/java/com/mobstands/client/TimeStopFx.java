package com.mobstands.client;

import com.mobstands.network.ClientStandCache;
import com.mobstands.stand.StandData;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/**
 * Screen side of The Ender World's Time Stop: an inverted-colour shockwave as time stops, a drained, greyed-out
 * world while it's stopped, and a second inverted flash as time moves again.
 */
public final class TimeStopFx {
    private TimeStopFx() {}

    private static final ResourceLocation INVERT = ResourceLocation.fromNamespaceAndPath("mobstands", "shaders/post/time_stop_invert.json");
    private static final ResourceLocation STOPPED = ResourceLocation.fromNamespaceAndPath("mobstands", "shaders/post/time_stop.json");
    /** Time Stop's opening: inverted colour bursting out of the clock on the Stand's chest and swallowing the screen. */
    private static final ResourceLocation WAVE = ResourceLocation.fromNamespaceAndPath("mobstands", "shaders/post/time_wave.json");
    /** Ticks of inverted colours as time stops, and as it starts again. */
    private static final int WAVE_IN = 14, WAVE_OUT = 6;
    /** Ticks of inverted colours on a Time Skip. */
    private static final int SKIP_FLASH = 5;
    /** Neoteny's whole screen effect: flash in, drained while winding back, flash out. */
    private static final int MANDOM_TOTAL = com.mobstands.stand.Mandom.WIND_TICKS + 5;

    private enum Phase { NONE, INVERT_IN, WAVE, STOPPED, INVERT_OUT }

    private static Phase phase = Phase.NONE;
    private static boolean frozen;

    /** True while time is stopped on this client (the Stand's chest clock stops). */
    public static boolean frozen() {
        return frozen;
    }

    /** The game clock stops during Time Stop, so the screen effect keeps its own. */
    private static long clientTicks;
    private static long localStart = Long.MIN_VALUE / 4;
    private static long lastStopId = Long.MIN_VALUE;

    private static long sinceStop() {
        return clientTicks - localStart;
    }

    public static void tick(Minecraft mc) {
        clientTicks++;
        if (mc.level == null || mc.player == null) {
            com.mobstands.stand.TimeStop.clientStopped = false;
            set(mc, Phase.NONE);
            return;
        }
        StandData d = ClientStandCache.get();
        long now = mc.level.getGameTime();
        if (d.timeStopStart != lastStopId) {
            lastStopId = d.timeStopStart;
            // A stop that just started (not an old one remembered from before you logged in).
            if (d.timeStopStart >= 0 && Math.abs(now - d.timeStopStart) <= 10) {
                localStart = clientTicks;
                phase = Phase.NONE; // restart the effect even if one was showing
            }
        }
        long len = d.timeStopEnd - d.timeStopStart; // shrinks to the real length when time moves again
        long el = sinceStop();
        boolean stopping = el >= 0 && el <= len + WAVE_OUT;
        com.mobstands.stand.TimeStop.clientStopped = stopping;
        // Your own stopped time: keep this game's clock ticking too, so your Stand moves smoothly (see TimeStop.tick).
        if (stopping && !mc.level.tickRateManager().runsNormally()) mc.level.setGameTime(mc.level.getGameTime() + 1L);
        Phase want;
        long mandom = now - d.mandomAt;
        if (mandom >= 0 && mandom < MANDOM_TOTAL && !stopping) {
            // Neoteny, The World style: an inverted flash, the world drained of colour while it winds back,
            // then a second flash as everything snaps back into place.
            frozen = false;
            set(mc, mandom < 5 ? Phase.INVERT_IN : mandom < com.mobstands.stand.Mandom.WIND_TICKS ? Phase.STOPPED : Phase.INVERT_OUT);
            return;
        }
        long skip = now - d.timeSkipAt;
        if (skip >= 0 && skip < SKIP_FLASH && !stopping) {
            frozen = false;
            set(mc, Phase.INVERT_OUT); // Time Skip: a split-second inverted flash
            return;
        }
        if (!stopping) want = Phase.NONE;
        else if (el < WAVE_IN) want = Phase.WAVE;
        else if (el < len) want = Phase.STOPPED;
        else want = Phase.INVERT_OUT;
        frozen = want == Phase.STOPPED || want == Phase.WAVE;
        set(mc, want);
    }

    // ---- the wave: follow the clock on screen every frame ----

    /** Where the clock on your Stand's chest was drawn this frame (camera-relative world position). */
    private static final org.joml.Vector3f CLOCK = new org.joml.Vector3f();
    private static long clockFrame = -10L;
    private static long frameCount;

    /** Called by the Stand renderer when it draws your own Ender World's chest clock. */
    public static void clockSeen(org.joml.Vector3f camRelative) {
        CLOCK.set(camRelative);
        clockFrame = frameCount;
    }

    /** Every frame, after the world is drawn and before the screen effect runs: aim the wave at the clock. */
    public static void frame(net.neoforged.neoforge.client.event.RenderLevelStageEvent event) {
        if (event.getStage() != net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        frameCount++;
        if (phase != Phase.WAVE) return;
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.client.renderer.PostChain chain = mc.gameRenderer.currentEffect();
        if (chain == null || !chain.getName().equals(WAVE.toString()) || mc.level == null) return;
        float cx = 0.5F, cy = 0.5F;
        if (frameCount - clockFrame <= 2) {
            org.joml.Vector4f v = new org.joml.Vector4f(CLOCK.x, CLOCK.y, CLOCK.z, 1.0F);
            v.mul(event.getModelViewMatrix());
            v.mul(event.getProjectionMatrix());
            if (v.w > 0.01F) {
                cx = (v.x / v.w + 1.0F) * 0.5F;
                cy = (v.y / v.w + 1.0F) * 0.5F;
            }
        }
        float aspect = mc.getWindow().getWidth() / (float) Math.max(1, mc.getWindow().getHeight());
        // Far enough to reach the furthest corner of the screen from wherever it starts.
        float fx = Math.max(cx, 1.0F - cx) * aspect, fy = Math.max(cy, 1.0F - cy);
        float maxR = (float) Math.sqrt(fx * fx + fy * fy) + 0.05F;
        float t = (sinceStop() + mc.getTimer().getGameTimeDeltaPartialTick(true)) / WAVE_IN;
        t = Math.max(0.0F, Math.min(1.0F, t));
        float r = (float) Math.pow(t, 1.6D) * maxR;
        chain.setUniform("CenterX", cx);
        chain.setUniform("CenterY", cy);
        chain.setUniform("Radius", r);
        chain.setUniform("Aspect", aspect);
    }

    private static void set(Minecraft mc, Phase want) {
        if (want == phase) {
            // Something else (the game switching cameras, reloading shaders, a resize...) can drop the effect
            // while time is still stopped: put it straight back.
            if (want == Phase.NONE) return;
            net.minecraft.client.renderer.PostChain cur = mc.gameRenderer.currentEffect();
            ResourceLocation expect = want == Phase.STOPPED ? STOPPED : want == Phase.WAVE ? WAVE : INVERT;
            if (cur != null && cur.getName().equals(expect.toString())) return;
        }
        phase = want;
        switch (want) {
            case INVERT_IN, INVERT_OUT -> mc.gameRenderer.loadEffect(INVERT);
            case STOPPED -> mc.gameRenderer.loadEffect(STOPPED);
            case WAVE -> mc.gameRenderer.loadEffect(WAVE);
            case NONE -> {
                mc.gameRenderer.shutdownEffect();
                if (mc.getCameraEntity() != null) mc.gameRenderer.checkEntityPostEffect(mc.getCameraEntity()); // spectator views
                frozen = false;
            }
        }
    }
}
