package com.mobstands.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Pigstep's Another World, on your screen. Crossing over: you're squeezed in between two worlds, an amethyst seam
 * splits the middle of the screen and tears open like doors - and through it, the other world (a little drained,
 * a little purple). The camera pinches in, then pops. While you're over there the tint stays. Crossing back: the
 * same tear in reverse, gold this time, with your own world on the other side.
 */
public final class AnotherWorldFx {
    private AnotherWorldFx() {}

    private static final ResourceLocation FX = ResourceLocation.fromNamespaceAndPath("mobstands", "shaders/post/another_world.json");
    private static final float PINCH = 4.0F, OPEN = 11.0F, LEN = PINCH + OPEN;

    private static boolean inside, entering, active;
    private static long ticks, start = Long.MIN_VALUE / 4;
    private static long tickNanos = System.nanoTime();

    public static void cross(boolean enter) {
        entering = enter;
        inside = enter;
        start = ticks;
        active = true;
        Minecraft mc = Minecraft.getInstance();
        var sm = mc.getSoundManager();
        // the snap of being pressed through, and a deep shimmer
        sm.play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_SWEEP, enter ? 0.5F : 0.7F, 0.8F));
        sm.play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.AMETHYST_CLUSTER_BREAK, enter ? 0.6F : 0.9F, 0.9F));
    }

    private static float t() {
        return ticks - start + Math.min(1.0F, (System.nanoTime() - tickNanos) / 50_000_000.0F);
    }

    public static void tick(Minecraft mc) {
        ticks++;
        tickNanos = System.nanoTime();
        if (mc.level == null || mc.player == null) {
            inside = false;
            active = false;
            return;
        }
        boolean animating = ticks - start <= LEN;
        boolean want = animating || inside;
        PostChain cur = mc.gameRenderer.currentEffect();
        boolean ours = cur != null && cur.getName().equals(FX.toString());
        if (want && !ours && !TimeStopFx.busy()) {
            mc.gameRenderer.loadEffect(FX);
            active = true;
        } else if (!want && ours) {
            mc.gameRenderer.shutdownEffect();
            if (mc.getCameraEntity() != null) mc.gameRenderer.checkEntityPostEffect(mc.getCameraEntity());
            active = false;
        } else if (!want) {
            active = false;
        }
    }

    /** Every frame, before the screen effect runs. */
    public static void frame(net.neoforged.neoforge.client.event.RenderLevelStageEvent event) {
        if (!active || event.getStage() != net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        Minecraft mc = Minecraft.getInstance();
        PostChain chain = mc.gameRenderer.currentEffect();
        if (chain == null || !chain.getName().equals(FX.toString())) return;
        float t = t();
        float half = 0.0F, in = 0.0F, out = inside ? 1.0F : 0.0F, pinch = 0.0F, flash = 0.0F;
        if (t <= LEN) {
            out = entering ? 0.0F : 1.0F;
            in = entering ? 1.0F : 0.0F;
            if (t < PINCH) {
                float k = t / PINCH;
                pinch = 0.35F * k * k;
            } else {
                float k = Mth.clamp((t - PINCH) / OPEN, 0.0F, 1.0F);
                float e = 1.0F - (1.0F - k) * (1.0F - k) * (1.0F - k);
                half = 0.001F + e * 0.8F;
                pinch = 0.35F * Math.max(0.0F, 1.0F - k * 3.0F);
                flash = 0.6F * Math.max(0.0F, 1.0F - (t - PINCH) / 3.0F);
            }
        }
        float aspect = mc.getWindow().getWidth() / (float) Math.max(1, mc.getWindow().getHeight());
        chain.setUniform("Aspect", aspect);
        chain.setUniform("Half", half);
        chain.setUniform("In", in);
        chain.setUniform("Out", out);
        chain.setUniform("Pinch", pinch);
        chain.setUniform("Flash", flash);
        chain.setUniform("Time", (float) ticks);
        chain.setUniform("Gold", entering ? 0.0F : 1.0F);
    }

    /** The camera pinches in as you're squeezed through, then pops back out. */
    public static float fovScale() {
        float t = t();
        if (t > LEN || t < 0.0F) return 1.0F;
        float s = Minecraft.getInstance().options.screenEffectScale().get().floatValue();
        if (t < PINCH) return 1.0F - 0.1F * s * (t / PINCH) * (t / PINCH);
        float k = (t - PINCH) / OPEN;
        return 1.0F + s * (-0.1F * (1.0F - k) * (1.0F - k) * (1.0F - k) + 0.06F * Mth.sin(k * Mth.PI));
    }
}
