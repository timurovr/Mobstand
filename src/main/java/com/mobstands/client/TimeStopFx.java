package com.mobstands.client;

import com.mobstands.network.ClientStandCache;
import com.mobstands.stand.StandData;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/**
 * Screen side of The Ender World's Time Stop, all on one shader pass (shaders/program/time_stop_fx.fsh):
 * <ol>
 *   <li><b>Charge</b> - a glowing orb swells in the clock on the Stand's chest, the view pulls in.</li>
 *   <li><b>Burst</b> - a sphere of inverted colour explodes out of the clock, its gold rim bending the world
 *       like heat haze with colour fringes; the view kicks out and the camera shakes.</li>
 *   <li><b>Collapse</b> - the sphere sucks back into the clock, leaving the world drained and grey.</li>
 *   <li><b>Stopped</b> - the grey world throbs with every tick of the clock; in the last second the edges go red.</li>
 *   <li><b>Resume</b> - colour floods back out of the clock in a ripple, with a flash and a jolt.</li>
 * </ol>
 * Time Skip gets a quick version: the world snaps inverted, the inversion slams shut into the clock, and a gold
 * shockwave rolls out. Neoteny keeps its inverted flashes.
 */
public final class TimeStopFx {
    private TimeStopFx() {}

    private static final ResourceLocation INVERT = ResourceLocation.fromNamespaceAndPath("mobstands", "shaders/post/time_stop_invert.json");
    private static final ResourceLocation STOPPED = ResourceLocation.fromNamespaceAndPath("mobstands", "shaders/post/time_stop.json");
    private static final ResourceLocation FX = ResourceLocation.fromNamespaceAndPath("mobstands", "shaders/post/time_stop_fx.json");
    private static final ResourceLocation BTD = ResourceLocation.fromNamespaceAndPath("mobstands", "shaders/post/bites_the_dust.json");
    /** Bites the Dust: the click, the rewind, the suck-in, the snap (at WIND_TICKS) and colour pouring back. */
    private static final float BTD_CLICK = 2.0F, BTD_SNAP = com.mobstands.stand.Mandom.WIND_TICKS, BTD_SUCK = BTD_SNAP - 2.5F, BTD_END = BTD_SNAP + 11.0F;

    // Time Stop timeline, in ticks from the moment time stops.
    private static final float CHARGE = 4, BURST = 12, HOLD = 3, COLLAPSE = 10;
    private static final float BURST_AT = CHARGE, HOLD_AT = BURST_AT + BURST, COLLAPSE_AT = HOLD_AT + HOLD, INTRO = COLLAPSE_AT + COLLAPSE;
    /** Ticks of colour flooding back when time moves again. */
    private static final int RESUME = 14;
    /** How long after the stop the game still counts this client as stopped (unchanged from before). */
    private static final int STOP_TAIL = 6;
    /** Time Skip timeline: the world snaps inverted, the inversion slams shut into the clock, a gold shockwave rolls out. */
    private static final float SKIP_SNAP = 1.5F, SKIP_SHUT = 6.0F, SKIP_WAVE = 6.0F, SKIP_TOTAL = SKIP_SNAP + SKIP_SHUT + SKIP_WAVE;
    /** Neoteny's whole screen effect: flash in, drained while winding back, flash out. */
    private static final int MANDOM_TOTAL = com.mobstands.stand.Mandom.WIND_TICKS + 5;

    private enum Phase { NONE, INVERT_IN, FX, STOPPED, INVERT_OUT, BTD }

    private static Phase phase = Phase.NONE;
    /** Whether the effect on screen is a Time Skip (otherwise a Time Stop). */
    private static boolean skipping;
    private static long skipStart = Long.MIN_VALUE / 4;
    private static long lastSkipId = Long.MIN_VALUE;
    private static long btdStart = Long.MIN_VALUE / 4;
    /** Caught in someone else's Bites the Dust: their rewind plays on your screen (whatever your own Stand is). */
    private static boolean btdCaught;

    /** From the server: you're being wound back by someone's Bites the Dust. */
    public static void rewound() {
        btdStart = clientTicks;
        btdCaught = true;
    }
    private static long lastMandomId = Long.MIN_VALUE;
    private static boolean frozen;

    /** True while any of these screen effects is showing (so other screen effects wait their turn). */
    public static boolean busy() {
        return phase != Phase.NONE;
    }

    /** True while time is stopped on this client (the Stand's chest clock stops). */
    public static boolean frozen() {
        return frozen;
    }

    /** The game clock stops during Time Stop, so the screen effect keeps its own. */
    private static long clientTicks;
    private static long tickNanos = System.nanoTime();
    private static long localStart = Long.MIN_VALUE / 4;
    private static long lastStopId = Long.MIN_VALUE;
    /** Length of the current stop in ticks (shrinks to the real length when time moves again). */
    private static long stopLen;
    /** When the effect on screen started (your own stop, or someone else's that caught you). */
    private static long fxStart = Long.MIN_VALUE / 4;
    /**
     * Caught in someone else's Time Stop: your own Stand never stopped time, but the game froze around you.
     * The effect runs from the moment you froze until time moves again.
     */
    private static boolean caught;
    private static long caughtStart, caughtEnd;

    private static long sinceStop() {
        return clientTicks - localStart;
    }

    /** Ticks since time stopped, smooth between ticks (real time, since the game's own partial tick freezes). */
    private static float t() {
        return clientTicks - fxStart + partial();
    }

    private static float partial() {
        return Math.min(1.0F, (System.nanoTime() - tickNanos) / 50_000_000.0F);
    }

    /** Ticks since Bites the Dust, smooth between ticks. */
    private static float btdT() {
        return clientTicks - btdStart + partial();
    }

    /** Ticks since the Time Skip, smooth between ticks. */
    private static float skipT() {
        return clientTicks - skipStart + partial();
    }

    public static void tick(Minecraft mc) {
        clientTicks++;
        tickNanos = System.nanoTime();
        if (mc.level == null || mc.player == null) {
            com.mobstands.stand.TimeStop.clientStopped = false;
            set(mc, Phase.NONE);
            return;
        }
        // The time-stopping Evoker (it carries The Ender World) and its Stand keep moving in stopped time here too.
        com.mobstands.stand.TimeStop.CLIENT_MOVERS.clear();
        if (mc.level.tickRateManager().isFrozen()) {
            for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
                if (e instanceof com.mobstands.entity.StandEntity s && s.isMobStand()
                        && s.getStandType() == com.mobstands.stand.StandType.ENDER_WORLD) {
                    com.mobstands.stand.TimeStop.CLIENT_MOVERS.add(s.getId());
                    net.minecraft.world.entity.LivingEntity o = s.getMobOwner();
                    if (o != null) com.mobstands.stand.TimeStop.CLIENT_MOVERS.add(o.getId());
                }
            }
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
        if (d.timeSkipAt != lastSkipId) {
            lastSkipId = d.timeSkipAt;
            if (d.timeSkipAt >= 0 && Math.abs(now - d.timeSkipAt) <= 10) skipStart = clientTicks;
        }
        long len = d.timeStopEnd - d.timeStopStart;
        long el = sinceStop();
        boolean stopping = el >= 0 && el <= len + STOP_TAIL;
        boolean showing = el >= 0 && el <= len + RESUME;
        if (showing) {
            caught = false;
            fxStart = localStart;
            stopLen = len;
        } else {
            // Someone else stopped time and you're frozen in it: same effect, timed by the freeze itself.
            boolean worldFrozen = mc.level.tickRateManager().isFrozen();
            if (worldFrozen && !caught) {
                caught = true;
                caughtStart = clientTicks;
                caughtEnd = Long.MAX_VALUE;
            } else if (!worldFrozen && caught && caughtEnd == Long.MAX_VALUE) {
                caughtEnd = clientTicks; // time moves again: play the colour flooding back
            }
            if (caught && caughtEnd != Long.MAX_VALUE && clientTicks - caughtEnd > RESUME) caught = false;
            if (caught) {
                showing = true;
                fxStart = caughtStart;
                stopLen = caughtEnd == Long.MAX_VALUE ? Integer.MAX_VALUE : caughtEnd - caughtStart;
                len = stopLen;
                el = clientTicks - caughtStart;
            }
        }
        com.mobstands.stand.TimeStop.clientStopped = stopping;
        // Your own stopped time: keep this game's clock ticking too, so your Stand moves smoothly (see TimeStop.tick).
        if (stopping && !mc.level.tickRateManager().runsNormally()) mc.level.setGameTime(mc.level.getGameTime() + 1L);
        if (d.mandomAt != lastMandomId) {
            lastMandomId = d.mandomAt;
            if (d.mandomAt >= 0 && Math.abs(now - d.mandomAt) <= 10) btdStart = clientTicks;
        }
        if (btdCaught && clientTicks - btdStart > BTD_END) btdCaught = false;
        if ((d.getType() == com.mobstands.stand.StandType.CREEPER_REQUIEM || btdCaught) && clientTicks - btdStart <= BTD_END && !showing) {
            frozen = false;
            set(mc, Phase.BTD); // Bites the Dust: its own rewind effect
            return;
        }
        long mandom = now - d.mandomAt;
        if (mandom >= 0 && mandom < MANDOM_TOTAL && !showing) {
            // Neoteny, The World style: an inverted flash, the world drained of colour while it winds back,
            // then a second flash as everything snaps back into place.
            frozen = false;
            set(mc, mandom < 5 ? Phase.INVERT_IN : mandom < com.mobstands.stand.Mandom.WIND_TICKS ? Phase.STOPPED : Phase.INVERT_OUT);
            return;
        }
        long skip = clientTicks - skipStart;
        if (skip >= 0 && skip <= SKIP_TOTAL && !showing) {
            frozen = false;
            skipping = true;
            set(mc, Phase.FX); // Time Skip: its own quick version of the Time Stop effect
            return;
        }
        skipping = false;
        frozen = showing && el < len;
        if (showing) {
            var sm = mc.getSoundManager();
            if (el == (long) COLLAPSE_AT) {
                // the sphere sucks back in
                sm.play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_SWEEP, 0.45F, 0.7F));
                sm.play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_RESONATE, 0.5F, 0.8F));
            }
            if (el == (long) INTRO) {
                // ...and time locks: a deep clock tock
                sm.play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BASEDRUM.value(), 0.5F, 1.0F));
                sm.play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.BELL_BLOCK, 0.5F, 0.6F));
            }
        }
        set(mc, showing ? Phase.FX : Phase.NONE);
    }

    // ---- every frame: follow the clock on screen and drive the shader ----

    /** Where the clock on your Stand's chest was drawn this frame (camera-relative world position). */
    private static final org.joml.Vector3f CLOCK = new org.joml.Vector3f();
    private static long clockFrame = -10L;
    private static long frameCount;
    /** Last known screen spot of the clock, so the effect doesn't jump if it goes out of view for a moment. */
    private static float lastCx = 0.5F, lastCy = 0.5F;

    /** Called by the Stand renderer when it draws your own Ender World's chest clock. */
    public static void clockSeen(org.joml.Vector3f camRelative) {
        CLOCK.set(camRelative);
        clockFrame = frameCount;
    }

    private static float clamp01(float v) {
        return Math.max(0.0F, Math.min(1.0F, v));
    }

    private static float easeOut(float u) {
        float k = 1.0F - u;
        return 1.0F - k * k * k;
    }

    /** Every frame, after the world is drawn and before the screen effect runs. */
    public static void frame(net.neoforged.neoforge.client.event.RenderLevelStageEvent event) {
        if (event.getStage() != net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        frameCount++;
        if (phase == Phase.BTD) {
            btdFrame();
            return;
        }
        if (phase != Phase.FX) return;
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.client.renderer.PostChain chain = mc.gameRenderer.currentEffect();
        if (chain == null || !chain.getName().equals(FX.toString()) || mc.level == null) return;

        float cx = lastCx, cy = lastCy;
        if (frameCount - clockFrame <= 2) {
            org.joml.Vector4f v = new org.joml.Vector4f(CLOCK.x, CLOCK.y, CLOCK.z, 1.0F);
            v.mul(event.getModelViewMatrix());
            v.mul(event.getProjectionMatrix());
            if (v.w > 0.01F) {
                cx = Math.max(-0.2F, Math.min(1.2F, (v.x / v.w + 1.0F) * 0.5F));
                cy = Math.max(-0.2F, Math.min(1.2F, (v.y / v.w + 1.0F) * 0.5F));
            }
        } else {
            // No clock in view (someone else stopped time, or you're looking away): drift to the middle.
            cx += (0.5F - cx) * 0.15F;
            cy += (0.5F - cy) * 0.15F;
        }
        lastCx = cx;
        lastCy = cy;
        float aspect = mc.getWindow().getWidth() / (float) Math.max(1, mc.getWindow().getHeight());
        float fx = Math.max(cx, 1.0F - cx) * aspect, fy = Math.max(cy, 1.0F - cy);
        float maxR = (float) Math.sqrt(fx * fx + fy * fy) + 0.08F;

        float t = t(), len = stopLen;
        float invR = 0, ringR = 0, ripple = 0, chroma = 0, grey = 0, resumeR = 0, vignette = 0, flash = 0, pulse = 0, danger = 0;
        float pal = 0, palAmt = 0;
        if (skipping) {
            float st = skipT();
            if (st < SKIP_SNAP) {
                // Snap: the whole world goes inverted in a white-hot flash.
                invR = maxR;
                chroma = 0.01F;
                vignette = 0.3F;
                flash = 0.7F * (1.0F - st / SKIP_SNAP);
            } else if (st < SKIP_SNAP + SKIP_SHUT) {
                // Shut: the inversion slams closed into the clock - the skipped second disappears.
                float u = (st - SKIP_SNAP) / SKIP_SHUT;
                invR = ringR = Math.max(0.001F, maxR * (1.0F - u) * (1.0F - u));
                ripple = 0.03F;
                chroma = 0.006F;
                vignette = 0.3F * (1.0F - u);
                flash = u > 0.8F ? 0.45F * (u - 0.8F) / 0.2F : 0.0F;
            } else {
                // Shockwave: a gold ring rolls out over the world, bending it as it passes.
                float u = clamp01((st - SKIP_SNAP - SKIP_SHUT) / SKIP_WAVE);
                ringR = 0.001F + easeOut(u) * maxR;
                ripple = 0.03F * (1.0F - u);
                chroma = 0.006F * (1.0F - u);
                flash = 0.45F * Math.max(0.0F, 1.0F - u * 4.0F);
            }
        } else if (t >= len) {
            // Time moves again: colour floods back out in a ripple.
            float u = clamp01((t - len) / RESUME);
            resumeR = 0.001F + easeOut(u) * maxR;
            grey = 1.0F;
            ripple = 0.03F * (1.0F - u);
            chroma = 0.006F * (1.0F - u);
            vignette = 0.3F * (1.0F - u);
            flash = 0.55F * Math.max(0.0F, 1.0F - u * 5.0F);
        } else if (t < BURST_AT) {
            // Charge: an orb of inverted colour swelling and flickering in the clock.
            float u = t / CHARGE;
            float flicker = 0.75F + 0.25F * (float) Math.sin(t * 5.0F);
            invR = ringR = (0.015F + 0.05F * u * u) * flicker;
            ripple = 0.012F * u;
            chroma = 0.005F * u;
            vignette = 0.35F * u;
        } else if (t < HOLD_AT) {
            // Burst: the sphere explodes out and swallows the screen.
            float u = (t - BURST_AT) / BURST;
            invR = ringR = 0.065F + easeOut(u) * (maxR - 0.065F);
            pal = (t - BURST_AT) * 0.16F;
            palAmt = 0.5F * Math.min(1.0F, u * 3.0F);
            ripple = 0.035F * (1.0F - 0.5F * u);
            chroma = 0.002F + 0.008F * (1.0F - u);
            vignette = 0.35F;
            flash = 0.6F * Math.max(0.0F, 1.0F - u * 6.0F);
        } else if (t < COLLAPSE_AT) {
            // Hold: the whole world inverted, for a heartbeat.
            float u = (t - HOLD_AT) / HOLD;
            invR = maxR;
            pal = (t - BURST_AT) * 0.16F;
            palAmt = 0.5F;
            grey = u; // (hidden under the inversion: ready for the collapse)
            chroma = 0.003F;
            vignette = 0.35F;
        } else if (t < INTRO) {
            // Collapse: the sphere sucks back into the clock, leaving a grey, stopped world behind.
            float u = (t - COLLAPSE_AT) / COLLAPSE;
            invR = ringR = Math.max(0.001F, maxR * (1.0F - u * u * (3.0F - 2.0F * u)));
            pal = (t - BURST_AT) * 0.16F;
            palAmt = 0.5F;
            grey = 1.0F;
            ripple = 0.022F;
            chroma = 0.004F;
            vignette = 0.35F;
            flash = u > 0.85F ? 0.4F * (u - 0.85F) / 0.15F : 0.0F;
        } else {
            // Stopped: the same calm, drained look as before (fading in from the collapse's flash).
            grey = 1.0F;
            flash = 0.4F * (float) Math.exp(-(t - INTRO) / 1.5F);
        }
        chain.setUniform("CenterX", cx);
        chain.setUniform("CenterY", cy);
        chain.setUniform("Aspect", aspect);
        chain.setUniform("InvR", invR);
        chain.setUniform("RingR", ringR);
        chain.setUniform("Ripple", ripple);
        chain.setUniform("Chroma", chroma);
        chain.setUniform("Grey", grey);
        chain.setUniform("ResumeR", resumeR);
        chain.setUniform("Vignette", vignette);
        chain.setUniform("Flash", flash);
        chain.setUniform("Pulse", pulse);
        chain.setUniform("Danger", danger);
        chain.setUniform("Pal", pal);
        chain.setUniform("PalAmt", palAmt);
    }

    private static void btdFrame() {
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.client.renderer.PostChain chain = mc.gameRenderer.currentEffect();
        boolean hasChain = chain != null && chain.getName().equals(BTD.toString());
        float t = btdT();
        float aspect = mc.getWindow().getWidth() / (float) Math.max(1, mc.getWindow().getHeight());
        float swirl = 0, scan = 0, phaseV = 0, clock = 0, hour = 0, min = 0, grey = 0, echo = 0, chroma = 0, vig = 0, flash = 0, inv = 0, ringR = 0, ripple = 0, pixel = 0;
        float colour = 0, palette = 0, bands = 0, split = 0;
        // The hands' angle: winding back, faster and faster (and they keep it through the snap).
        float wu = clamp01((t - BTD_CLICK) / (BTD_SNAP - BTD_CLICK));
        float spin = -(float) (Math.PI * 2.0D) * (1.2F * wu + 5.0F * wu * wu * wu);
        // The hands jump tick by tick, like a real clock (sixty steps a turn).
        float step = (float) (Math.PI * 2.0D / 60.0D);
        min = (float) Math.floor(spin / step) * step;
        hour = (float) Math.floor(spin / 8.0F / step) * step;
        if (t < BTD_CLICK) {
            // The switch clicks: the world flips to negative at once, flickering through palettes.
            float u = t / BTD_CLICK;
            flash = 0.5F * (1.0F - u);
            colour = 0.35F * u;
            palette = 0.0F;
            split = 1.0F;
            grey = 1.0F;
            vig = 0.4F * u;
            pixel = u;
        } else if (t < BTD_SNAP) {
            // The rewind: twisting backwards, tearing, ghosting, the clock racing back.
            float u = wu;
            grey = 1.0F;
            pixel = 1.0F;
            // The palette jumps every couple of ticks at first, then faster and faster as the rewind races.
            // Drifts gently from one palette to the next (a little quicker as the rewind races).
            colour = 0.35F + 0.15F * u;
            palette = (t - BTD_CLICK) * (0.12F + 0.18F * u);
            bands = 0.3F + 0.4F * u;
            split = u > 0.6F ? 1.0F : 0.0F;
            clock = clamp01((t - BTD_CLICK) / 2.0F) * (t < BTD_SUCK ? 1.0F : 1.0F - (t - BTD_SUCK) / (BTD_SNAP - BTD_SUCK));
            swirl = -(0.12F + 0.8F * u * u);
            scan = 0.3F + 0.7F * u;
            phaseV = t * 0.25F + u * u * u * 7.0F;
            echo = 0.35F + 0.5F * u;
            chroma = 0.004F + 0.01F * u;
            vig = 0.4F + 0.3F * u;
            if (t >= BTD_SUCK) {
                // Sucked in: the twist spikes and the light gathers.
                float k = (t - BTD_SUCK) / (BTD_SNAP - BTD_SUCK);
                swirl -= 1.4F * k * k;
                flash = 0.8F * k * k;
                vig += 0.2F * k;
            }
        } else {
            // SNAP: a white flash, the colours flip back from negative, and a shockwave rolls out.
            float a = t - BTD_SNAP;
            float u = clamp01(a / (BTD_END - BTD_SNAP));
            flash = Math.max(0.0F, 1.0F - a / 4.0F);
            float fx = 0.5F * aspect, fy = 0.5F;
            ringR = 0.001F + (1.0F - (1.0F - u) * (1.0F - u) * (1.0F - u)) * ((float) Math.sqrt(fx * fx + fy * fy) + 0.1F);
            ripple = 0.035F * (1.0F - u);
            grey = 0.0F;
            pixel = Math.max(0.0F, 1.0F - a / 7.0F);
            // A last burst of colour as it snaps back.
            colour = a < 4.0F ? 0.4F * (1.0F - a / 4.0F) : 0.0F;
            palette = 3.0F;
            split = 0.0F;
            vig = 0.5F * (1.0F - u);
        }
        // (kept for the overlay drawn on top of menus - the "You Died!" screen)
        ovClock = clock;
        ovHour = hour;
        ovMin = min;
        ovFlash = flash;
        ovRing = ringR;
        ovGrey = grey;
        ovPalette = palette;
        ovFrame = frameCount;
        if (!hasChain) return;
        chain.setUniform("Aspect", aspect);
        chain.setUniform("Swirl", swirl);
        chain.setUniform("Scan", scan);
        chain.setUniform("Phase", phaseV);
        chain.setUniform("ClockA", clock);
        chain.setUniform("HourA", hour);
        chain.setUniform("MinA", min);
        chain.setUniform("Grey", grey);
        chain.setUniform("Echo", echo);
        chain.setUniform("Vignette", vig);
        chain.setUniform("Flash", flash);
        chain.setUniform("Invert", inv);
        chain.setUniform("RingR", ringR);
        chain.setUniform("Ripple", ripple);
        chain.setUniform("Pixel", pixel);
        chain.setUniform("Colour", colour);
        chain.setUniform("Palette", palette);
        chain.setUniform("Bands", bands);
        chain.setUniform("Split", split);
    }

    // ---- the rewind, on top of menus: the shader runs before the "You Died!" screen is drawn, so its clock and colours
    // are drawn again over it (and its buttons) while you're being wound back ----

    private static float ovClock, ovHour, ovMin, ovFlash, ovRing, ovGrey, ovPalette;
    private static long ovFrame = -10L;

    public static void overMenu(net.neoforged.neoforge.client.event.ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof net.minecraft.client.gui.screens.DeathScreen)) return;
        if (phase != Phase.BTD || frameCount - ovFrame > 3) return;
        net.minecraft.client.gui.GuiGraphics g = event.getGuiGraphics();
        int w = g.guiWidth(), h = g.guiHeight();
        g.pose().pushPose();
        g.pose().translate(0.0F, 0.0F, 400.0F); // above the buttons
        // the world's colours flipping and drifting through palettes, washed over everything
        if (ovGrey > 0.0F) {
            int[] tints = {0xFF4DCC, 0x4DFFE6, 0xFFE64D};
            int tint = tints[Math.floorMod((int) Math.floor(ovPalette), 3)];
            g.fill(0, 0, w, h, ((int) (70 * ovGrey) << 24) | tint);
        }
        // the big pixel-art clock, winding back
        float cx = w * 0.5F, cy = h * 0.5F;
        float R = h * 0.34F;
        float u = Math.max(1.0F, h / 90.0F);
        if (ovClock > 0.5F) {
            int n = (int) Math.ceil(R / u) + 2;
            for (int iy = -n; iy <= n; iy++) {
                for (int ix = -n; ix <= n; ix++) {
                    float px = ix * u, py = -iy * u; // (y up, like the shader)
                    float cr = (float) Math.sqrt(px * px + py * py);
                    if (cr > R + u * 2.5F) continue;
                    float a = (float) Math.atan2(px, py);
                    float s12 = Math.abs(frac(a / 6.2831853F * 12.0F + 0.5F) - 0.5F) * 6.2831853F / 12.0F * cr;
                    boolean rim = Math.abs(cr - R) < u, rimEdge = Math.abs(cr - R) < u * 2.0F;
                    boolean mark = s12 < u * 0.9F && cr > R - u * 5.0F && cr < R - u * 2.0F;
                    boolean hourH = seg(px, py, (float) Math.sin(ovHour) * R * 0.5F, (float) Math.cos(ovHour) * R * 0.5F) < u * 1.2F;
                    boolean minH = seg(px, py, (float) Math.sin(ovMin) * R * 0.78F, (float) Math.cos(ovMin) * R * 0.78F) < u * 0.8F;
                    boolean hub = cr < u * 1.8F;
                    int col = -1;
                    if (rimEdge) col = 0x1A385C;
                    if (rim || mark) col = 0x9EDBFF;
                    if (hourH || minH) col = 0xFFFFFF;
                    if (hub) col = 0x5EBF4D;
                    if (col < 0) continue;
                    int x0 = (int) (cx + px - u * 0.5F), y0 = (int) (cy + iy * u - u * 0.5F);
                    g.fill(x0, y0, x0 + (int) Math.ceil(u), y0 + (int) Math.ceil(u), 0xFF000000 | col);
                }
            }
        }
        // the snap: a blocky ring blasting out, and a white flash
        if (ovRing > 0.0F) {
            float rr = ovRing * h;
            int n = (int) Math.ceil((rr + u * 3.0F) / u);
            for (int iy = -n; iy <= n; iy++) {
                for (int ix = -n; ix <= n; ix++) {
                    float d = Math.abs((float) Math.sqrt(ix * u * ix * u + iy * u * iy * u) - rr);
                    if (d >= u * 3.0F) continue;
                    int col = d < u * 1.5F ? 0xCFEEFF : 0x1A385C;
                    int x0 = (int) (cx + ix * u - u * 0.5F), y0 = (int) (cy + iy * u - u * 0.5F);
                    if (x0 < -u || y0 < -u || x0 > w || y0 > h) continue;
                    g.fill(x0, y0, x0 + (int) Math.ceil(u), y0 + (int) Math.ceil(u), 0xFF000000 | col);
                }
            }
        }
        if (ovFlash > 0.02F) {
            float f = Math.min(1.0F, Math.round(ovFlash * 4.0F) / 4.0F);
            g.fill(0, 0, w, h, ((int) (240 * f) << 24) | 0xF2FFFF);
        }
        g.pose().popPose();
    }

    private static float frac(float x) {
        return x - (float) Math.floor(x);
    }

    /** Distance from (px, py) to the segment from the centre to (bx, by). */
    private static float seg(float px, float py, float bx, float by) {
        float len2 = bx * bx + by * by;
        float t = len2 < 1.0E-6F ? 0.0F : Math.max(0.0F, Math.min(1.0F, (px * bx + py * by) / len2));
        float dx = px - bx * t, dy = py - by * t;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    // ---- frozen air: golden specks hanging still while time is stopped ----

    private static float[] specks;          // x, y, z, seed for each speck
    private static long specksFor = Long.MIN_VALUE;

    /** Every frame, after particles: draw the specks hanging in the air (and blowing away as time resumes). */
    public static void specks(net.neoforged.neoforge.client.event.RenderLevelStageEvent event) {
        if (event.getStage() != net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        if (phase != Phase.FX || skipping) return;
        Minecraft mc = Minecraft.getInstance();
        float t = t();
        if (t < COLLAPSE_AT) return;
        var cam = event.getCamera();
        if (specksFor != fxStart || specks == null) {
            // Scatter them through the air around you, once, when time stops.
            specksFor = fxStart;
            var rnd = new java.util.Random(fxStart * 31L);
            net.minecraft.world.phys.Vec3 c = cam.getPosition();
            specks = new float[70 * 4];
            for (int i = 0; i < 70; i++) {
                double a = rnd.nextDouble() * Math.PI * 2.0D, r = 1.5D + rnd.nextDouble() * 6.0D;
                specks[i * 4] = (float) (c.x + Math.cos(a) * r);
                specks[i * 4 + 1] = (float) (c.y - 1.5D + rnd.nextDouble() * 4.0D);
                specks[i * 4 + 2] = (float) (c.z + Math.sin(a) * r);
                specks[i * 4 + 3] = rnd.nextFloat() * 100.0F;
            }
        }
        float appear = clamp01((t - COLLAPSE_AT) / 6.0F);
        float leave = t >= stopLen ? clamp01((t - stopLen) / RESUME) : 0.0F;
        float alpha = appear * (1.0F - leave);
        if (alpha <= 0.01F) return;
        net.minecraft.world.phys.Vec3 cp = cam.getPosition();
        var buffers = mc.renderBuffers().bufferSource();
        com.mojang.blaze3d.vertex.PoseStack ps = new com.mojang.blaze3d.vertex.PoseStack();
        for (int i = 0; i < specks.length / 4; i++) {
            float x = specks[i * 4], y = specks[i * 4 + 1], z = specks[i * 4 + 2], seed = specks[i * 4 + 3];
            if (leave > 0.0F) {
                // blown away outward as the colour floods back
                float dx = x - (float) cp.x, dz = z - (float) cp.z;
                float len = Math.max(0.3F, (float) Math.sqrt(dx * dx + dz * dz));
                x += dx / len * leave * 4.0F;
                z += dz / len * leave * 4.0F;
                y += leave * 0.8F;
            }
            float tw = 0.55F + 0.45F * (float) Math.sin(seed + t * 0.12F);
            ps.pushPose();
            ps.translate(x - cp.x, y - cp.y, z - cp.z);
            ps.mulPose(cam.rotation());
            StandRenderer.disc(ps, buffers, 0.07F + 0.03F * tw, 0xFFE9A8, 0.75F * alpha * tw);
            ps.popPose();
        }
        buffers.endBatch(ModRenderTypes.glow());
    }

    // ---- camera: pull in on the charge, kick out on the burst, jolt when time moves again ----

    /** How strong camera effects are (the game's own "Distortion Effects" slider). */
    private static float camScale() {
        return Minecraft.getInstance().options.screenEffectScale().get().floatValue();
    }

    public static float fovScale() {
        if (phase == Phase.BTD) {
            // Pulled in as the rewind sucks inward, then a hard kick out on the snap.
            float t = btdT(), s = camScale();
            if (t < BTD_SNAP) return 1.0F - 0.12F * s * clamp01((t - BTD_CLICK) / (BTD_SNAP - BTD_CLICK)) * clamp01((t - BTD_CLICK) / (BTD_SNAP - BTD_CLICK));
            float u = clamp01((t - BTD_SNAP) / 8.0F);
            return 1.0F + s * (-0.12F * (1.0F - u) * (1.0F - u) * (1.0F - u) + 0.08F * (float) Math.sin(u * Math.PI));
        }
        if (phase != Phase.FX) return 1.0F;
        float t = t(), s = camScale();
        float k;
        if (skipping) {
            float st = skipT();
            if (st < SKIP_SNAP + SKIP_SHUT) k = -0.07F * clamp01(st / (SKIP_SNAP + SKIP_SHUT)); // pulled in as it shuts
            else k = -0.07F * (1.0F - clamp01((st - SKIP_SNAP - SKIP_SHUT) / 3.0F))
                    + 0.05F * (float) Math.sin(clamp01((st - SKIP_SNAP - SKIP_SHUT) / SKIP_WAVE) * Math.PI); // kick out
        } else if (t >= stopLen) {
            float u = clamp01((t - stopLen) / RESUME);
            k = 0.06F * (float) Math.sin(u * Math.PI);
        } else if (t < BURST_AT) {
            float u = t / CHARGE;
            k = -0.09F * u * u;
        } else if (t < HOLD_AT) {
            float u = (t - BURST_AT) / BURST;
            k = -0.09F * (1.0F - u) * (1.0F - u) * (1.0F - u) + 0.07F * (float) Math.sin(Math.min(1.0F, u * 1.6F) * Math.PI);
        } else {
            k = 0.0F;
        }
        return 1.0F + k * s;
    }

    public static void shake(net.neoforged.neoforge.client.event.ViewportEvent.ComputeCameraAngles event) {
        if (phase == Phase.BTD) {
            float t = btdT(), amp;
            if (t < 1.5F) amp = 0.8F * (1.0F - t / 1.5F);                             // the click
            else if (t < BTD_SNAP) amp = 0.25F * clamp01((t - BTD_SUCK + 4.0F) / 6.0F); // a rumble building
            else amp = 1.8F * Math.max(0.0F, 1.0F - (t - BTD_SNAP) / 7.0F);           // the snap
            amp *= camScale();
            if (amp <= 0.0F) return;
            event.setYaw(event.getYaw() + amp * (float) Math.sin(t * 9.1F));
            event.setPitch(event.getPitch() + amp * 0.7F * (float) Math.sin(t * 11.7F + 1.3F));
            event.setRoll(event.getRoll() + amp * (float) Math.sin(t * 7.3F + 2.1F));
            return;
        }
        if (phase != Phase.FX) return;
        float t = t(), amp;
        if (skipping) {
            float st = skipT() - SKIP_SNAP - SKIP_SHUT;
            if (st < 0.0F || st > 5.0F) return;
            t = skipT();
            amp = 0.9F * (1.0F - st / 5.0F);
        } else if (t >= stopLen) amp = 1.2F * Math.max(0.0F, 1.0F - (t - stopLen) / 6.0F);
        else if (t >= BURST_AT && t < BURST_AT + 8) amp = 1.6F * (1.0F - (t - BURST_AT) / 8.0F);
        else if (t >= INTRO - 1 && t < INTRO + 3) amp = 0.6F * (1.0F - (t - INTRO + 1) / 4.0F);
        else return;
        amp *= camScale();
        if (amp <= 0.0F) return;
        event.setYaw(event.getYaw() + amp * (float) Math.sin(t * 9.1F));
        event.setPitch(event.getPitch() + amp * 0.7F * (float) Math.sin(t * 11.7F + 1.3F));
        event.setRoll(event.getRoll() + amp * 0.9F * (float) Math.sin(t * 7.3F + 2.1F));
    }

    private static void set(Minecraft mc, Phase want) {
        if (want == phase) {
            // Something else (the game switching cameras, reloading shaders, a resize...) can drop the effect
            // while time is still stopped: put it straight back.
            if (want == Phase.NONE) return;
            net.minecraft.client.renderer.PostChain cur = mc.gameRenderer.currentEffect();
            ResourceLocation expect = want == Phase.STOPPED ? STOPPED : want == Phase.FX ? FX : want == Phase.BTD ? BTD : INVERT;
            if (cur != null && cur.getName().equals(expect.toString())) return;
        }
        phase = want;
        switch (want) {
            case INVERT_IN, INVERT_OUT -> mc.gameRenderer.loadEffect(INVERT);
            case STOPPED -> mc.gameRenderer.loadEffect(STOPPED);
            case FX -> mc.gameRenderer.loadEffect(FX);
            case BTD -> mc.gameRenderer.loadEffect(BTD);
            case NONE -> {
                mc.gameRenderer.shutdownEffect();
                if (mc.getCameraEntity() != null) mc.gameRenderer.checkEntityPostEffect(mc.getCameraEntity()); // spectator views
                frozen = false;
            }
        }
    }
}
