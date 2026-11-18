package com.mobstands.client;

import com.mobstands.network.ClientStandCache;
import com.mobstands.network.StandActionPayload;
import com.mobstands.stand.StandType;
import net.minecraft.Util;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Spin controls (Spin users holding an item):
 *  - tap Q: drops the item like normal;
 *  - hold Shift too (or have an empty main hand) and all of this works on your off hand instead;
 *  - hold Q: winds up, then a cue pops up: "SPAM Q!" - hit Q fast enough times before the cue runs out and
 *    the item starts spinning;
 *  - while it spins: tap Q to throw it (it hits the ground and blasts mobs around), hold Q to stop.
 */
public final class SpinInput {
    private SpinInput() {}

    /** How long Q must be held before the cue appears (ms). */
    private static final long HOLD_MS = 600L;
    /** A press shorter than this is a tap (drops the item); held longer, the wind-up bar shows and letting go cancels. */
    private static final long TAP_MS = 200L;
    /** While spinning: hold Q this long before the "stop" bar even shows up (a tap just drops it)... */
    private static final long STOP_DELAY_MS = 1000L;
    /** ...then the bar fills over this long and the spin stops. */
    private static final long STOP_FILL_MS = 600L;
    /** How long the cue lasts, and how many presses it wants. */
    private static final long CUE_MS = 1500L;
    public static final int SPAM_NEEDED = 4;
    /** Spinning both hands' items at once takes more. */
    public static final int SPAM_NEEDED_BOTH = 6;

    /** A little more time for the 6-press double spin. Timing mode gets time for every beat. */
    private static long cueMs() {
        if (timingMode()) return 1400L + spamNeeded() * 550L;
        return bothTarget ? 2000L : CUE_MS;
    }

    // ---- Below Spin mastery the cue is a timing test, not a spam: hit Q while the marker is in the gold zone.
    // The lower your Spin level, the smaller the zone, the faster the marker and the fewer slips you're allowed.

    private static int spinLevel() {
        return ClientStandCache.get().spinLevel;
    }

    /** Masters (Lv 10) just spam like before; everyone else has to hit the timing. */
    private static boolean timingMode() {
        return spinLevel() < com.mobstands.stand.StandData.SPIN_MAX_LEVEL;
    }

    private static float skill() {
        return com.mobstands.stand.StandManager.spinSkill(spinLevel());
    }

    /** Width of the gold zone (fraction of the bar): tiny for a rookie, generous near mastery. */
    private static float zoneWidth() {
        return 0.15F + 0.03F * (spinLevel() - 1); // +3% of the bar every Spin level
    }

    /** Milliseconds for the marker to cross the bar once: fast for a rookie. */
    private static float sweepMs() {
        return (460.0F + 70.0F * (spinLevel() - 1)) * (float) Math.pow(0.94D, spam); // slower every Spin level; speeds up a little every beat
    }

    /** Misses you can make and still pull it off: none at Lv 1, up to 2 near mastery. */
    private static int allowedMisses() {
        return (int) (skill() * 2.99F);
    }

    private static int misses;
    private static float zoneStart;
    /** Marker phase origin (ms), restarted each beat so a new zone always gets a fair sweep. */
    private static long beatStart;
    private static final java.util.Random RNG = new java.util.Random();

    /** Marker position 0..1, bouncing back and forth. */
    private static float marker(long now) {
        float t = (now - beatStart) / sweepMs();
        float m = t % 2.0F;
        return m <= 1.0F ? m : 2.0F - m;
    }

    private static void newZone(long now) {
        float w = zoneWidth();
        float cur = marker(now);
        // Put the zone somewhere the marker isn't sitting right now.
        for (int i = 0; i < 8; i++) {
            zoneStart = RNG.nextFloat() * (1.0F - w);
            if (Math.abs(zoneStart + w / 2.0F - cur) > 0.3F) break;
        }
    }

    private static void fail(Minecraft mc, long now, boolean slipped) {
        state = State.IDLE;
        resultAt = now;
        resultOk = false;
        resultSlip = slipped;
        mc.player.playSound(SoundEvents.NOTE_BLOCK_BASS.value(), 0.7F, 0.5F);
        if (slipped) send(offTarget ? 22 : 21); // it slips out of your hand
    }

    private static boolean resultSlip;

    /** Shift while winding up a Steel Ball with a second one anywhere in your inventory: spin both together. */
    private static boolean dualSteel(Minecraft mc) {
        return !bothTarget && !offTarget && Screen.hasShiftDown() && holdsSteel(mc)
                && mc.player.getInventory().countItem(com.mobstands.registry.ModItems.STEEL_BALL.get()) >= 2;
    }

    /** A Steel Ball in either hand. */
    private static boolean holdsSteel(Minecraft mc) {
        return mc.player.getMainHandItem().is(com.mobstands.registry.ModItems.STEEL_BALL.get())
                || mc.player.getOffhandItem().is(com.mobstands.registry.ModItems.STEEL_BALL.get());
    }

    private static int spamNeeded() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return SPAM_NEEDED;
        boolean mainSteel = mc.player.getMainHandItem().is(com.mobstands.registry.ModItems.STEEL_BALL.get());
        boolean offSteel = mc.player.getOffhandItem().is(com.mobstands.registry.ModItems.STEEL_BALL.get());
        // Steel Balls answer the master's hand: one press fewer each.
        if (bothTarget) return SPAM_NEEDED_BOTH - (mainSteel ? 1 : 0) - (offSteel ? 1 : 0);
        if (dualSteel(mc)) return SPAM_NEEDED_BOTH - 2; // Shift + two Steel Balls: both at once
        return SPAM_NEEDED - ((offTarget ? offSteel : mainSteel) ? 1 : 0);
    }

    private enum State { IDLE, HOLDING, CUE, SPIN_HOLD }

    private static State state = State.IDLE;

    /** True while you're winding up a spin (the HUD shows your Spin panel). */
    public static boolean busy() {
        return state != State.IDLE;
    }
    private static long holdStart;
    private static long cueStart;
    private static int spam;
    /** For the "Spin!" / "Too slow" flash. */
    private static long resultAt = -10000L;
    private static boolean resultOk;
    /** After a successful spin, extra Q presses are ignored for a moment (so over-spamming doesn't stop or drop it). */
    private static final long GRACE_MS = 800L;
    private static long graceUntil;

    private static boolean active(Minecraft mc) {
        return mc.player != null && mc.screen == null && ClientStandCache.get().spinLearned
                && (!mc.player.getMainHandItem().isEmpty() || !mc.player.getOffhandItem().isEmpty()) && !mc.player.isSpectator();
    }

    /**
     * Which hand this Q press works on: the off hand when you hold Shift (or when your main hand is empty),
     * otherwise the main hand.
     */
    private static boolean offTarget;
    /** Both hands hold (non-spinning) items and no Shift: one wind-up spins both. */
    private static boolean bothTarget;

    private static boolean pickOffHand(Minecraft mc) {
        boolean offHas = !mc.player.getOffhandItem().isEmpty();
        // Shift with a Steel Ball in hand and another one somewhere: that's the double Steel Ball spin, not the off hand.
        if (Screen.hasShiftDown() && holdsSteel(mc)
                && mc.player.getInventory().countItem(com.mobstands.registry.ModItems.STEEL_BALL.get()) >= 2) return false;
        return offHas && (Screen.hasShiftDown() || mc.player.getMainHandItem().isEmpty());
    }

    private static boolean targetSpinning() {
        return offTarget ? ClientStandCache.get().offSpinning : ClientStandCache.get().spinning;
    }

    /** Q pressed or released (from ClientEvents.onKey). Returns true if Spin handled it. */
    public static boolean onDropKey(boolean pressed) {
        Minecraft mc = Minecraft.getInstance();
        if (!active(mc)) {
            state = State.IDLE;
            return false;
        }
        long now = Util.getMillis();
        if (now < graceUntil) {
            state = State.IDLE;
            return true; // still spamming after the spin started: ignore
        }
        if (pressed) {
            if (state == State.IDLE) {
                // Choose the hand(s) at the start of a press.
                offTarget = pickOffHand(mc);
                bothTarget = !offTarget && !Screen.hasShiftDown() && !mc.player.getMainHandItem().isEmpty()
                        && !mc.player.getOffhandItem().isEmpty() && !ClientStandCache.get().spinning && !ClientStandCache.get().offSpinning;
            }
            if (targetSpinning()) {
                // Spinning: tap Q to throw it, hold Q to stop.
                state = State.SPIN_HOLD;
                holdStart = now;
                return true;
            }
            switch (state) {
                case IDLE -> {
                    state = State.HOLDING;
                    holdStart = now;
                }
                case CUE -> {
                    if (timingMode()) {
                        float m = marker(now);
                        if (m < zoneStart || m > zoneStart + zoneWidth()) {
                            misses++;
                            if (misses > allowedMisses()) {
                                fail(mc, now, true);
                                return true;
                            }
                            mc.player.playSound(SoundEvents.NOTE_BLOCK_DIDGERIDOO.value(), 0.5F, 0.7F);
                            return true;
                        }
                        beatStart = now;
                    }
                    spam++;
                    mc.player.playSound(SoundEvents.NOTE_BLOCK_HAT.value(), 0.5F, 1.0F + spam * 0.15F);
                    if (timingMode() && spam < spamNeeded()) newZone(now);
                    if (spam >= spamNeeded()) {
                        send(bothTarget ? 17 : offTarget ? 15 : dualSteel(mc) ? 18 : 10); // spin!
                        state = State.IDLE;
                        graceUntil = now + GRACE_MS;
                        resultAt = now;
                        resultOk = true;
                    }
                }
                default -> { }
            }
        } else if (state == State.SPIN_HOLD) {
            // Let go before the stop bar appeared: drop the spinning item. Let go while the bar fills: never mind.
            if (now - holdStart < STOP_DELAY_MS) send(offTarget ? 13 : 12);
            state = State.IDLE;
        } else if (state == State.HOLDING) {
            // A quick tap: drop the item like normal. Let go part-way through the wind-up: never mind, nothing happens.
            if (now - holdStart < TAP_MS) send(offTarget ? 16 : Screen.hasControlDown() ? 11 : 9);
            state = State.IDLE;
        }
        return true;
    }

    /** Every client tick: turns a long enough hold into the cue, and times the cue out. */
    /** Right-click throw: when the wind-up started (ms), or -1. Full power after THROW_FULL_MS. */
    private static long throwStart = -1L;
    private static final long THROW_FULL_MS = 450L;

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        long now = Util.getMillis();
        // Hold right click while it spins to wind up a throw; let go to throw it (further the longer you held).
        boolean mainSpin = mc.player != null && ClientStandCache.get().spinning && !mc.player.getMainHandItem().isEmpty()
                && !(mc.player.getMainHandItem().getItem() instanceof net.minecraft.world.item.ProjectileWeaponItem); // bows shoot instead
        boolean offSpin = mc.player != null && ClientStandCache.get().offSpinning && !mc.player.getOffhandItem().isEmpty();
        boolean spinning = mc.player != null && mc.screen == null && (mainSpin || offSpin); // both spinning: both get thrown
        if (spinning && mc.options.keyUse.isDown()) {
            if (throwStart < 0) throwStart = now;
        } else if (throwStart >= 0) {
            if (spinning) {
                int charge = (int) Mth.clamp((now - throwStart) * 40L / THROW_FULL_MS, 0L, 40L);
                send(100 + charge);
            }
            throwStart = -1L;
        }
        if (state != State.IDLE && !active(mc)) {
            state = State.IDLE;
            return;
        }
        if (state == State.SPIN_HOLD && now - holdStart >= STOP_DELAY_MS + STOP_FILL_MS) {
            send(offTarget ? 14 : 7); // held: stop spinning
            state = State.IDLE;
        } else if (state == State.HOLDING && now - holdStart >= TAP_MS + HOLD_MS) {
            state = State.CUE;
            cueStart = now;
            spam = 0;
            misses = 0;
            beatStart = now;
            newZone(now);
            mc.player.playSound(SoundEvents.NOTE_BLOCK_BELL.value(), 0.8F, 1.6F);
        } else if (state == State.CUE && now - cueStart > cueMs()) {
            fail(mc, now, false); // too slow: nothing happens, you just don't get the spin
        }
    }

    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        long now = Util.getMillis();
        int cx = mc.getWindow().getGuiScaledWidth() / 2;
        int cy = mc.getWindow().getGuiScaledHeight() / 2;
        String q = KeyLabels.of(mc.options.keyDrop);
        if (throwStart >= 0) {
            // Throw power bar.
            float k = Mth.clamp((now - throwStart) / (float) THROW_FULL_MS, 0.0F, 1.0F);
            int w = 50;
            g.fill(cx - w / 2, cy + 12, cx + w / 2, cy + 15, 0x90000000);
            g.fill(cx - w / 2, cy + 12, cx - w / 2 + (int) (w * k), cy + 15, k >= 1.0F ? 0xFFFFF4A0 : 0xFFE8C25A);
            return;
        }
        boolean stopBar = state == State.SPIN_HOLD && now - holdStart >= STOP_DELAY_MS;
        boolean windBar = state == State.HOLDING && now - holdStart >= TAP_MS;
        if (windBar || stopBar) {
            // Wind-up bar under the crosshair (only once it's clearly a hold, not a tap).
            float k = stopBar ? Mth.clamp((now - holdStart - STOP_DELAY_MS) / (float) STOP_FILL_MS, 0.0F, 1.0F)
                    : Mth.clamp((now - holdStart - TAP_MS) / (float) HOLD_MS, 0.0F, 1.0F);
            int w = 40;
            g.fill(cx - w / 2, cy + 12, cx + w / 2, cy + 15, 0x90000000);
            g.fill(cx - w / 2, cy + 12, cx - w / 2 + (int) (w * k), cy + 15, 0xFFE8C25A);
        } else if (state == State.CUE && timingMode()) {
            // Timing bar: hit Q while the white marker is inside the gold zone.
            float left = 1.0F - Mth.clamp((now - cueStart) / (float) cueMs(), 0.0F, 1.0F);
            String text = "TIME IT!";
            g.pose().pushPose();
            g.pose().translate(cx, cy - 30, 0);
            g.pose().scale(2.0F, 2.0F, 1.0F);
            g.drawString(mc.font, text, -mc.font.width(text) / 2, -4, 0xFFE8C25A, true);
            g.pose().popPose();
            int w = 80 + 8 * (spinLevel() - 1), by = cy + 14; // the bar itself grows as you level up
            g.fill(cx - w / 2 - 1, by - 1, cx + w / 2 + 1, by + 7, 0xC0000000);
            int zx = cx - w / 2 + (int) (w * zoneStart);
            g.fill(zx, by, zx + Math.max(2, (int) (w * zoneWidth())), by + 6, 0xFFE8C25A);
            int mx = cx - w / 2 + (int) (w * marker(now));
            g.fill(mx - 1, by - 3, mx + 1, by + 9, 0xFFFFFFFF);
            StringBuilder dots = new StringBuilder();
            for (int i = 0; i < spamNeeded(); i++) dots.append(i < spam ? "◆ " : "◇ ");
            String ds = dots.toString().trim();
            g.drawString(mc.font, ds, cx - mc.font.width(ds) / 2, by + 11, 0xFFFFFFFF, true);
            int allowed = allowedMisses();
            if (allowed > 0) {
                String ms = "slips left: " + Math.max(0, allowed - misses);
                g.drawString(mc.font, ms, cx - mc.font.width(ms) / 2, by + 21, 0xFFAAAAAA, true);
            }
            g.fill(cx - w / 2, by + 31, cx + w / 2, by + 33, 0x90000000);
            g.fill(cx - w / 2, by + 31, cx - w / 2 + (int) (w * left), by + 33, 0xFFE8C25A);
        } else if (state == State.CUE) {
            float left = 1.0F - Mth.clamp((now - cueStart) / (float) cueMs(), 0.0F, 1.0F);
            float pulse = 1.0F + 0.15F * Mth.sin(now / 60.0F);
            String text = "SPAM " + q + "!";
            g.pose().pushPose();
            g.pose().translate(cx, cy - 30, 0);
            g.pose().scale(2.0F * pulse, 2.0F * pulse, 1.0F);
            g.drawString(mc.font, text, -mc.font.width(text) / 2, -4, 0xFFE8C25A, true);
            g.pose().popPose();
            // Presses so far, and the time left.
            StringBuilder dots = new StringBuilder();
            for (int i = 0; i < spamNeeded(); i++) dots.append(i < spam ? "◆ " : "◇ ");
            String ds = dots.toString().trim();
            g.drawString(mc.font, ds, cx - mc.font.width(ds) / 2, cy + 14, 0xFFFFFFFF, true);
            int w = 60;
            g.fill(cx - w / 2, cy + 26, cx + w / 2, cy + 28, 0x90000000);
            g.fill(cx - w / 2, cy + 26, cx - w / 2 + (int) (w * left), cy + 28, 0xFFE8C25A);
        } else if (now - resultAt < 800L) {
            float a = 1.0F - (now - resultAt) / 800.0F;
            String text = resultOk ? "Spin!" : resultSlip ? "It slipped!" : "Too slow...";
            int col = ((int) (a * 255.0F) << 24) | (resultOk ? 0xE8C25A : 0xAAAAAA);
            if (a > 0.02F) g.drawString(mc.font, text, cx - mc.font.width(text) / 2, cy - 30, col, true);
        }
    }

    private static void send(int action) {
        PacketDistributor.sendToServer(new StandActionPayload(action));
    }
}
