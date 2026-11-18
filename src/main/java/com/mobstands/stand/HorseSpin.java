package com.mobstands.stand;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.horse.AbstractHorse;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Gyro's horse Spin (Steel Ball Run, stage 1): riding a horse with a spinning Steel Ball, right-click and the Spin goes
 * into its legs - it runs faster, jumps higher and never takes fall damage while it lasts. Stronger and longer the
 * higher your Spin level.
 *
 * <p>And the sailing rule: a ship can't sail straight into the wind, but it can zig-zag against it and keep gaining.
 * While the Spin is in its legs, cutting hard left, then hard right, then left again ("tacking") builds extra speed,
 * up to 5 times. Ride dead straight for a while and the extra speed drains away.
 */
public final class HorseSpin {
    private HorseSpin() {}

    private static final ResourceLocation SPEED = ResourceLocation.fromNamespaceAndPath("mobstands", "horse_spin_speed");
    private static final ResourceLocation JUMP = ResourceLocation.fromNamespaceAndPath("mobstands", "horse_spin_jump");
    private static final ResourceLocation TACK = ResourceLocation.fromNamespaceAndPath("mobstands", "horse_spin_tack");
    public static final int MAX_TACKS = 5;
    private static final double TACK_BONUS = 0.07D;

    private static final class Run {
        AbstractHorse horse;
        ServerPlayer rider;
        long until;
        float lastYaw, turnAcc;
        int lastTurnDir, tacks;
        long lastTurnAt;
    }

    private static final List<Run> RUNS = new ArrayList<>();

    /** Speed bonus (x base speed), jump bonus and how long it lasts, by Spin level 1..10. */
    private static double speedFor(int lvl) { return 0.15D + 0.035D * lvl; }
    private static double jumpFor(int lvl) { return 0.06D + 0.025D * lvl; }
    private static int ticksFor(int lvl) { return 200 + 20 * lvl; }

    /** Right-click with a spinning Steel Ball while riding: the Spin goes into the horse's legs. */
    public static boolean tryApply(ServerPlayer p, StandData d) {
        if (!(p.getVehicle() instanceof AbstractHorse horse)) return false;
        if (!p.getMainHandItem().is(com.mobstands.registry.ModItems.STEEL_BALL.get()) || !d.spinning) return false;
        ServerLevel sl = p.serverLevel();
        long now = sl.getGameTime();
        Run r = null;
        for (Run x : RUNS) if (x.horse == horse) r = x;
        if (r == null) {
            r = new Run();
            r.horse = horse;
            r.lastYaw = horse.getYRot();
            RUNS.add(r);
        }
        r.rider = p;
        r.until = now + ticksFor(d.spinLevel);
        mod(horse.getAttribute(Attributes.MOVEMENT_SPEED), SPEED, speedFor(d.spinLevel), AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        mod(horse.getAttribute(Attributes.JUMP_STRENGTH), JUMP, jumpFor(d.spinLevel), AttributeModifier.Operation.ADD_VALUE);

        // The ball spins down into its legs (it stays in your hand, like Gyro's).
        d.spinning = false;
        StandManager.sync(p);
        StandManager.addSpinXp(p, 12);
        p.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
        sl.playSound(null, horse.getX(), horse.getY(), horse.getZ(), SoundEvents.HORSE_GALLOP, SoundSource.PLAYERS, 1.0F, 1.2F);
        sl.playSound(null, horse.getX(), horse.getY(), horse.getZ(), SoundEvents.TRIDENT_RIPTIDE_1.value(), SoundSource.PLAYERS, 0.7F, 1.6F);
        sl.sendParticles(ParticleTypes.ENCHANTED_HIT, horse.getX(), horse.getY() + 0.5D, horse.getZ(), 24, 0.5D, 0.3D, 0.5D, 0.3D);
        p.displayClientMessage(Component.literal("The Spin is in its legs!").withStyle(s -> s.withColor(0xE8C25A).withBold(true)), true);
        return true;
    }

    private static void mod(AttributeInstance a, ResourceLocation id, double v, AttributeModifier.Operation op) {
        if (a == null) return;
        a.removeModifier(id);
        if (v != 0.0D) a.addTransientModifier(new AttributeModifier(id, v, op));
    }

    public static void tick(ServerLevel sl) {
        if (RUNS.isEmpty()) return;
        if (!sl.tickRateManager().runsNormally()) return; // stopped time
        long now = sl.getGameTime();
        Iterator<Run> it = RUNS.iterator();
        while (it.hasNext()) {
            Run r = it.next();
            AbstractHorse h = r.horse;
            if (h.level() != sl) continue;
            if (h.isRemoved() || !h.isAlive() || now >= r.until || r.rider.isRemoved() || r.rider.getVehicle() != h) {
                it.remove();
                end(sl, r);
                continue;
            }
            h.resetFallDistance(); // the Spin carries it down softly
            double speed = h.getDeltaMovement().horizontalDistance();
            // Golden spin around its hooves while it runs.
            if (now % 2L == 0L && speed > 0.05D) {
                double a = now * 0.9D;
                for (int i = 0; i < 2; i++) {
                    double ang = a + i * Math.PI;
                    sl.sendParticles(ParticleTypes.ENCHANTED_HIT, h.getX() + Math.cos(ang) * 0.6D, h.getY() + 0.25D, h.getZ() + Math.sin(ang) * 0.6D, 1, 0.0D, 0.0D, 0.0D, 0.0D);
                }
            }
            // Tacking: hard cut one way, then the other, while moving.
            float yaw = h.getYRot();
            r.turnAcc = r.turnAcc * 0.85F + Mth.wrapDegrees(yaw - r.lastYaw);
            r.lastYaw = yaw;
            if (Math.abs(r.turnAcc) > 28.0F && speed > 0.12D) {
                int dir = r.turnAcc > 0 ? 1 : -1;
                if (dir != r.lastTurnDir && r.lastTurnDir != 0 && now - r.lastTurnAt < 60L && r.tacks < MAX_TACKS) {
                    r.tacks++;
                    mod(h.getAttribute(Attributes.MOVEMENT_SPEED), TACK, TACK_BONUS * r.tacks, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
                    sl.sendParticles(ParticleTypes.CLOUD, h.getX(), h.getY() + 0.6D, h.getZ(), 10, 0.5D, 0.3D, 0.5D, 0.04D);
                    sl.playSound(null, h.getX(), h.getY(), h.getZ(), SoundEvents.PHANTOM_FLAP, SoundSource.PLAYERS, 0.6F, 1.5F + r.tacks * 0.08F);
                    int n = r.tacks;
                    r.rider.displayClientMessage(Component.literal("Tacking against the wind x" + n).withStyle(s -> s.withColor(0x9ED8FF)), true);
                }
                r.lastTurnDir = dir;
                r.lastTurnAt = now;
                r.turnAcc = 0.0F;
            }
            // Riding straight for 4 seconds: the wind's gain drains away, one tack at a time.
            if (r.tacks > 0 && now - r.lastTurnAt > 80L && (now - r.lastTurnAt) % 20L == 0L) {
                r.tacks--;
                mod(h.getAttribute(Attributes.MOVEMENT_SPEED), TACK, TACK_BONUS * r.tacks, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
            }
        }
    }

    private static void end(ServerLevel sl, Run r) {
        AbstractHorse h = r.horse;
        mod(h.getAttribute(Attributes.MOVEMENT_SPEED), SPEED, 0.0D, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        mod(h.getAttribute(Attributes.MOVEMENT_SPEED), TACK, 0.0D, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        mod(h.getAttribute(Attributes.JUMP_STRENGTH), JUMP, 0.0D, AttributeModifier.Operation.ADD_VALUE);
        if (!h.isRemoved()) {
            sl.playSound(null, h.getX(), h.getY(), h.getZ(), SoundEvents.HORSE_BREATHE, SoundSource.PLAYERS, 1.0F, 0.9F);
            if (!r.rider.isRemoved() && r.rider.getVehicle() == h)
                r.rider.displayClientMessage(Component.literal("The Spin wore off.").withStyle(s -> s.withColor(0x8A8A8A)), true);
        }
    }

    public static void clear() {
        RUNS.clear();
    }
}
