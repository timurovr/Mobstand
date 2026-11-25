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
import net.minecraft.world.phys.Vec3;

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
        /** The spinning balls put under the horse - they come back to you when the boost ends. */
        net.minecraft.world.item.ItemStack mainBall = net.minecraft.world.item.ItemStack.EMPTY, offBall = net.minecraft.world.item.ItemStack.EMPTY;
    }

    private static final List<Run> RUNS = new ArrayList<>();
    /** Horses launched by a sky-tossed ball, and until when they land softly. */
    private static final java.util.Map<AbstractHorse, Long> LAUNCHED = new java.util.HashMap<>();

    /** A sky-tossed Steel Ball dived under your horse: it's flung up into the air (higher for better spinners). */
    public static void launch(ServerLevel sl, AbstractHorse horse, ServerPlayer p) {
        float k = StandManager.spinSkill(StandManager.data(p).spinLevel);
        Vec3 f = p.getLookAngle().multiply(1.0D, 0.0D, 1.0D);
        f = f.lengthSqr() < 1.0E-4D ? Vec3.ZERO : f.normalize().scale(0.4D);
        double up = 1.15D + 0.55D * k;
        horse.setDeltaMovement(horse.getDeltaMovement().x + f.x, up, horse.getDeltaMovement().z + f.z);
        horse.hurtMarked = true;
        // The rider's game is the one moving the horse: launch it there too, so it works at a full gallop.
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p,
                new com.mobstands.network.HorseLaunchPayload(horse.getId(), (float) f.x, (float) f.z, (float) up));
        horse.resetFallDistance();
        LAUNCHED.put(horse, sl.getGameTime() + 100L);
        StandManager.addSpinXp(p, 6);
        sl.playSound(null, horse.getX(), horse.getY(), horse.getZ(), com.mobstands.registry.ModSounds.SPIN_HAMON.get(), SoundSource.PLAYERS, 1.0F, 1.1F);
        sl.playSound(null, horse.getX(), horse.getY(), horse.getZ(), SoundEvents.HORSE_JUMP, SoundSource.PLAYERS, 1.2F, 0.9F);
        sl.sendParticles(ParticleTypes.CLOUD, horse.getX(), horse.getY() + 0.2D, horse.getZ(), 20, 0.6D, 0.1D, 0.6D, 0.06D);
        StandManager.hamonBurst(sl, horse.position().add(0.0D, 0.3D, 0.0D), 18, 0.4D);
        p.displayClientMessage(Component.literal("Up!").withStyle(s -> s.withColor(0xE8C25A).withBold(true)), true);
    }

    /** Speed bonus (x base speed), jump bonus and how long it lasts, by Spin level 1..10. */
    private static double speedFor(int lvl) { return 0.15D + 0.035D * lvl; }
    private static double jumpFor(int lvl) { return 0.06D + 0.025D * lvl; }
    private static int ticksFor(int lvl) { return 200 + 20 * lvl; }

    /** Right-click with a spinning Steel Ball while riding and looking down: the Spin goes into the horse's legs. */
    public static boolean tryApply(ServerPlayer p, StandData d) {
        if (!(p.getVehicle() instanceof AbstractHorse horse)) return false;
        if (!p.getMainHandItem().is(com.mobstands.registry.ModItems.STEEL_BALL.get()) || !d.spinning) return false;
        // Only when you look down at your horse - otherwise it's a normal throw (or, looking up, the missile).
        if (p.getXRot() < 55.0F) return false;
        ServerLevel sl = p.serverLevel();
        long now = sl.getGameTime();
        // Two spinning balls (one in each hand) make a stronger, longer boost.
        boolean two = d.offSpinning && p.getOffhandItem().is(com.mobstands.registry.ModItems.STEEL_BALL.get());
        double mult = two ? 1.5D : 1.0D;
        Run r = null;
        for (Run x : RUNS) if (x.horse == horse) r = x;
        if (r == null) {
            r = new Run();
            r.horse = horse;
            r.lastYaw = horse.getYRot();
            RUNS.add(r);
        }
        r.rider = p;
        int ticks = (int) (ticksFor(d.spinLevel) * (two ? 1.3D : 1.0D));
        r.until = now + ticks;
        mod(horse.getAttribute(Attributes.MOVEMENT_SPEED), SPEED, speedFor(d.spinLevel) * mult, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        mod(horse.getAttribute(Attributes.JUMP_STRENGTH), JUMP, jumpFor(d.spinLevel) * (two ? 1.4D : 1.0D), AttributeModifier.Operation.ADD_VALUE);

        // The balls go down under the horse and keep spinning there as its boost - you can't use them till it's over.
        if (r.mainBall.isEmpty()) r.mainBall = p.getMainHandItem().split(1);
        if (two && r.offBall.isEmpty()) r.offBall = p.getOffhandItem().split(1);
        d.spinning = false;
        d.offSpinning = false;
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntityAndSelf(horse,
                new com.mobstands.network.HorseBallsPayload(horse.getId(), r.offBall.isEmpty() ? 1 : 2, ticks));
        StandManager.sync(p);
        StandManager.addSpinXp(p, 12);
        p.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
        sl.playSound(null, horse.getX(), horse.getY(), horse.getZ(), SoundEvents.HORSE_GALLOP, SoundSource.PLAYERS, 1.0F, 1.2F);
        sl.playSound(null, horse.getX(), horse.getY(), horse.getZ(), SoundEvents.TRIDENT_RIPTIDE_1.value(), SoundSource.PLAYERS, 0.7F, 1.6F);
        sl.sendParticles(ParticleTypes.ENCHANTED_HIT, horse.getX(), horse.getY() + 0.5D, horse.getZ(), 24, 0.5D, 0.3D, 0.5D, 0.3D);
        p.displayClientMessage(Component.literal(two ? "Both balls are spinning under it!" : "The Spin is in its legs!").withStyle(s -> s.withColor(0xE8C25A).withBold(true)), true);
        return true;
    }

    private static void mod(AttributeInstance a, ResourceLocation id, double v, AttributeModifier.Operation op) {
        if (a == null) return;
        a.removeModifier(id);
        if (v != 0.0D) a.addTransientModifier(new AttributeModifier(id, v, op));
    }

    public static void tick(ServerLevel sl) {
        if (!LAUNCHED.isEmpty()) {
            long t = sl.getGameTime();
            LAUNCHED.entrySet().removeIf(e -> e.getKey().level() == sl && (e.getKey().isRemoved() || t > e.getValue()
                    || (e.getKey().onGround() && t > e.getValue() - 90L)));
            for (AbstractHorse h : LAUNCHED.keySet()) if (h.level() == sl) {
                h.resetFallDistance(); // lands softly
                for (var pass : h.getPassengers()) pass.resetFallDistance();
            }
        }
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
        giveBalls(r, h);
        if (!h.isRemoved()) {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntityAndSelf(h, new com.mobstands.network.HorseBallsPayload(h.getId(), 0, 0));
            sl.playSound(null, h.getX(), h.getY(), h.getZ(), SoundEvents.HORSE_BREATHE, SoundSource.PLAYERS, 1.0F, 0.9F);
            if (!r.rider.isRemoved() && r.rider.getVehicle() == h)
                r.rider.displayClientMessage(Component.literal("The Spin wore off.").withStyle(s -> s.withColor(0x8A8A8A)), true);
        }
    }

    /** The balls come back to your hands (or your inventory, or drop where the horse is if you're gone). */
    private static void giveBalls(Run r, AbstractHorse h) {
        for (int i = 0; i < 2; i++) {
            net.minecraft.world.item.ItemStack ball = i == 0 ? r.mainBall : r.offBall;
            if (ball.isEmpty()) continue;
            // Out of the run first, so it can't be handed back a second time.
            if (i == 0) r.mainBall = net.minecraft.world.item.ItemStack.EMPTY;
            else r.offBall = net.minecraft.world.item.ItemStack.EMPTY;
            ServerPlayer p = r.rider;
            boolean given = false;
            if (!p.isRemoved()) {
                if (i == 0 && p.getMainHandItem().isEmpty()) {
                    p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ball);
                    given = true;
                } else if (i == 1 && p.getOffhandItem().isEmpty()) {
                    p.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, ball);
                    given = true;
                } else {
                    given = p.getInventory().add(ball);
                }
                if (given) p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.6F, 1.4F);
            }
            if (!given) {
                var drop = new net.minecraft.world.entity.item.ItemEntity(h.level(), h.getX(), h.getY() + 0.3D, h.getZ(), ball);
                h.level().addFreshEntity(drop);
            }
        }
        r.mainBall = net.minecraft.world.item.ItemStack.EMPTY;
        r.offBall = net.minecraft.world.item.ItemStack.EMPTY;
    }

    /** Looking down at your horse and right-clicking: the boost ends early and your balls come back. */
    public static void retrieve(ServerPlayer p) {
        if (!(p.getVehicle() instanceof AbstractHorse horse)) return;
        Iterator<Run> it = RUNS.iterator();
        while (it.hasNext()) {
            Run r = it.next();
            if (r.horse != horse || (r.mainBall.isEmpty() && r.offBall.isEmpty())) continue;
            it.remove();
            end(p.serverLevel(), r);
            return;
        }
    }

    /** The server's stopping: hand every ball back before anything is saved. */
    public static void shutdown() {
        for (Run r : RUNS) giveBalls(r, r.horse);
        RUNS.clear();
    }

    public static void clear() {
        RUNS.clear();
        LAUNCHED.clear();
    }
}
