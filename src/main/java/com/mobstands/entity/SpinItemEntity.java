package com.mobstands.entity;

import com.mobstands.stand.StandCombat;
import com.mobstands.stand.StandManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A Spin item dropped on the floor. It falls like a normal dropped item but keeps spinning hard, can't be picked
 * up, glows yellow, and launches or scatters anyone in the 8x8 area around it. When it winds down it turns back
 * into an ordinary item you can pick up.
 */
public class SpinItemEntity extends Entity {
    private static final EntityDataAccessor<ItemStack> ITEM = SynchedEntityData.defineId(SpinItemEntity.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<Integer> START = SynchedEntityData.defineId(SpinItemEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> POWER = SynchedEntityData.defineId(SpinItemEntity.class, EntityDataSerializers.FLOAT);
    /** Game time it stops spinning (extended while it keeps catching people). */
    private static final EntityDataAccessor<Integer> END = SynchedEntityData.defineId(SpinItemEntity.class, EntityDataSerializers.INT);
    /** Thrown with right click and in the air (going out or coming back): clients draw the golden trail. */
    private static final EntityDataAccessor<Boolean> FLYING = SynchedEntityData.defineId(SpinItemEntity.class, EntityDataSerializers.BOOLEAN);

    /** Spins this long on the floor if it catches nobody (15 s)... */
    public static final int IDLE_TICKS = 300;
    /** ...each catch keeps it going at least this much longer, up to MAX_TICKS in total. */
    public static final int CATCH_EXTEND = 200;
    public static final int MAX_TICKS = 600;

    /** Top spin speed on the floor (degrees per tick). */
    public static final float SPIN_SPEED = 67.0F;

    /** Client: accumulated spin angle (so the speed can change smoothly). */
    private float angle, angleO;

    private UUID owner;
    /** Thrown with right click: flying, hits the first enemy in its path. */
    private boolean thrown;
    /** Thrown from (and returns to) the off hand. */
    private boolean offHand;

    public void setOffHand(boolean offHand) {
        this.offHand = offHand;
    }
    /** Already ricocheted off a wall once this throw. */
    private boolean bounced;
    /** Which way it curves on the way back (it's spinning). */
    private double curveSign = 1.0D;
    /** Flying back to its owner after a right-click throw. */
    private boolean returning;
    /** Mobs already hit on the current trip back (each one only once). */
    private final java.util.Set<java.util.UUID> returnHits = new java.util.HashSet<>();
    private int flightTicks;
    /** Client: recent positions for the golden trail. */
    public final java.util.ArrayDeque<Vec3> trail = new java.util.ArrayDeque<>();

    /** Sky toss (Steel Balls only): tossed upward, it then follows where you look, so you can bring it down or swing it sideways. */
    private boolean steer;

    // ---- Wall Sense: stuck to a wall, spinning, counting the vibrations of whatever moves nearby ----
    private boolean wallSense;
    /** Stuck to the wall (game time it stuck), or -1. */
    private long stuckAt = -1L;
    private Vec3 stuckPos;
    /** Vibrations counted per mob. */
    private final Map<UUID, Integer> vibes = new HashMap<>();
    private final Map<UUID, Vec3> lastSeen = new HashMap<>();
    /** Locked on: flying straight for it (through anything - it already knows where it is). */
    private LivingEntity sensed;
    /** Its way to the target, block by block, around walls (never through them). */
    private java.util.List<Vec3> sensePath = new java.util.ArrayList<>();
    private int pathIndex;

    /** Shortest way through open blocks from here to the target (breadth-first, kept small). Empty if none. */
    private java.util.List<Vec3> findPath(ServerLevel sl, LivingEntity target) {
        net.minecraft.core.BlockPos start = net.minecraft.core.BlockPos.containing(position().add(0.0D, getBbHeight() * 0.5D, 0.0D));
        net.minecraft.core.BlockPos goal = net.minecraft.core.BlockPos.containing(target.position().add(0.0D, Math.min(1.0D, target.getBbHeight() * 0.5D), 0.0D));
        java.util.Map<net.minecraft.core.BlockPos, net.minecraft.core.BlockPos> from = new java.util.HashMap<>();
        java.util.ArrayDeque<net.minecraft.core.BlockPos> q = new java.util.ArrayDeque<>();
        from.put(start, start);
        q.add(start);
        net.minecraft.core.BlockPos found = null;
        int budget = 6000;
        while (!q.isEmpty() && budget-- > 0) {
            net.minecraft.core.BlockPos c = q.poll();
            if (c.distManhattan(goal) <= 1) {
                found = c;
                break;
            }
            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
                net.minecraft.core.BlockPos nb = c.relative(d);
                if (from.containsKey(nb) || nb.distManhattan(start) > 40) continue;
                if (!sl.getBlockState(nb).getCollisionShape(sl, nb).isEmpty()) continue;
                from.put(nb, c);
                q.add(nb);
            }
        }
        java.util.LinkedList<Vec3> path = new java.util.LinkedList<>();
        if (found == null) return path;
        for (net.minecraft.core.BlockPos c = found; !c.equals(start); c = from.get(c)) path.addFirst(Vec3.atCenterOf(c));
        return path;
    }
    private static final int SENSE_RANGE = 9, VIBES_NEEDED = 3, SENSE_GIVE_UP = 200, PULSE = 12;

    /** A plain dropped item (a Steel Ball on the ground) pulled back from afar: it becomes a ball flying home. */
    public static void recallItem(ServerPlayer p, ItemEntity ie) {
        SpinItemEntity si = new SpinItemEntity(com.mobstands.registry.ModEntities.SPIN_ITEM.get(), ie.level());
        si.setup(p, ie.getItem().copy(), 1.0F);
        si.setPos(ie.getX(), ie.getY(), ie.getZ());
        si.returns = true;
        si.restingReturn = true;
        si.setDeltaMovement(0.0D, 0.35D, 0.0D);
        ie.discard();
        ie.level().addFreshEntity(si);
        si.startReturn();
    }

    /** Whose throw this is. */
    public boolean ownedBy(ServerPlayer p) {
        return p.getUUID().equals(owner);
    }

    /** Called back from far away (right-click on it): it drops whatever it's doing and comes home. */
    public boolean recall() {
        if (!thrown && !returning && stuckAt < 0L && sensed == null) {
            // Lying on the floor (dropped, or wound down): it hops up and comes home.
            returns = true;
            restingReturn = true;
            setDeltaMovement(0.0D, 0.35D, 0.0D);
            startReturn();
            return true;
        }
        if (returning && sensed == null && stuckAt < 0L) return false;
        stuckAt = -1L;
        sensed = null;
        wallSense = false;
        steer = false;
        if (!returning) startReturn();
        return true;
    }

    public void setWallSense() {
        wallSense = true;
    }

    /** True if Wall Sense handled this tick. */
    private boolean wallSenseTick(ServerLevel sl, ServerPlayer tp) {
        if (sensed != null) {
            // Off the wall and straight for it.
            if (!sensed.isAlive() || sensed.level() != sl) {
                sensed = null;
                startReturn();
                return true;
            }
            Vec3 aim = sensed.position().add(0.0D, sensed.getBbHeight() * 0.5D, 0.0D);
            Vec3 to = aim.subtract(position());
            // Around the corner, not through it: re-plan now and then as the target moves.
            if (flightTicks % 10 == 0) {
                sensePath = findPath(sl, sensed);
                pathIndex = 0;
                if (sensePath.isEmpty() && flightTicks == 0) {
                    // No open way to it at all: give up and come home.
                    sensed = null;
                    startReturn();
                    return true;
                }
            }
            if (to.length() < 1.1D) {
                StandManager.spinThrowHit(tp, sensed, power() * 1.3F, true);
                sl.playSound(null, sensed.getX(), sensed.getY(), sensed.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.2F, 1.6F);
                StandManager.hamonBurst(sl, aim, 14, 0.4D);
                sensed = null;
                startReturn();
                return true;
            }
            // Next waypoint (skip the ones it's already reached); straight at it once it's in the open.
            Vec3 centre = position().add(0.0D, getBbHeight() * 0.5D, 0.0D);
            while (pathIndex < sensePath.size() && sensePath.get(pathIndex).distanceTo(centre) < 0.6D) pathIndex++;
            boolean clear = sl.clip(new net.minecraft.world.level.ClipContext(centre, aim, net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, this)).getType() == net.minecraft.world.phys.HitResult.Type.MISS;
            Vec3 goal = clear || pathIndex >= sensePath.size() ? aim : sensePath.get(pathIndex);
            Vec3 dir = goal.subtract(centre);
            double step = Math.min(0.9D, dir.length());
            Vec3 move = dir.lengthSqr() < 1.0E-6D ? Vec3.ZERO : dir.normalize().scale(step);
            // Never into a block: if the step would hit one, stop there and let it re-plan.
            net.minecraft.world.phys.BlockHitResult bh = sl.clip(new net.minecraft.world.level.ClipContext(centre, centre.add(move),
                    net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, this));
            if (bh.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                move = bh.getLocation().subtract(centre).scale(0.8D);
                flightTicks = (flightTicks / 10) * 10 + 9; // re-plan next tick
            }
            setDeltaMovement(move);
            Vec3 next = position().add(move);
            setPos(next.x, next.y, next.z);
            if (++flightTicks % 2 == 0) StandManager.hamonBurst(sl, position(), 1, 0.05D);
            if (flightTicks > 160) {
                sensed = null;
                startReturn();
            }
            return true;
        }
        if (stuckAt < 0L) return false;
        long now = sl.getGameTime();
        setPos(stuckPos.x, stuckPos.y, stuckPos.z);
        setDeltaMovement(Vec3.ZERO);
        if (now - stuckAt > SENSE_GIVE_UP || tp.distanceTo(this) > 48.0D) {
            stuckAt = -1L;
            tp.displayClientMessage(net.minecraft.network.chat.Component.literal("No vibrations...").withStyle(s -> s.withColor(0x7FB8C9)), true);
            startReturn();
            return true;
        }
        if ((now - stuckAt) % PULSE != 0) return true;
        // A pulse: every enemy nearby gives off a vibration it can feel - moving ones twice as much.
        sl.playSound(null, getX(), getY(), getZ(), SoundEvents.SCULK_CLICKING, SoundSource.PLAYERS, 0.7F, 1.4F);
        LivingEntity best = null;
        int bestCount = 0;
        for (LivingEntity e : sl.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(SENSE_RANGE),
                x -> x != tp && x.isAlive() && StandCombat.valid(tp, x)
                        && x instanceof net.minecraft.world.entity.monster.Enemy)) { // only evil mobs
            Vec3 was = lastSeen.put(e.getUUID(), e.position());
            boolean moving = was != null && was.distanceToSqr(e.position()) > 0.01D;
            int c = vibes.merge(e.getUUID(), moving ? 2 : 1, Integer::sum);
            // The vibration travelling from it to the ball, like a sculk sensor's.
            sl.sendParticles(new net.minecraft.core.particles.VibrationParticleOption(
                            new net.minecraft.world.level.gameevent.EntityPositionSource(this, 0.0F), 10),
                    e.getX(), e.getY() + e.getBbHeight() * 0.5D, e.getZ(), 1, 0.0D, 0.0D, 0.0D, 0.0D);
            if (c > bestCount) {
                bestCount = c;
                best = e;
            }
        }
        if (best != null) {
            tp.displayClientMessage(net.minecraft.network.chat.Component.literal("Vibrations: " + Math.min(bestCount, VIBES_NEEDED) + "/" + VIBES_NEEDED)
                    .withStyle(s -> s.withColor(0x3FD0C9)), true);
        }
        if (best != null && bestCount >= VIBES_NEEDED) {
            // Counted enough: it knows exactly where you are.
            sensed = best;
            stuckAt = -1L;
            flightTicks = 0;
            sl.playSound(null, getX(), getY(), getZ(), SoundEvents.SCULK_SHRIEKER_SHRIEK, SoundSource.PLAYERS, 0.5F, 2.0F);
            sl.playSound(null, getX(), getY(), getZ(), com.mobstands.registry.ModSounds.SPIN_CRACKLE.get(), SoundSource.PLAYERS, 1.0F, 1.2F);
            tp.displayClientMessage(net.minecraft.network.chat.Component.literal("Found it!").withStyle(s -> s.withColor(0xE8C25A).withBold(true)), true);
        }
        return true;
    }
    /** This throw already dove under your horse and launched it. */
    private boolean dived;

    /**
     * Sky toss on horseback, looking straight down: the ball dives under your horse and launches it skyward.
     * Works any time during a steerable throw - on the way out, after a hit or a bounce, or on the way back.
     * True while it's diving (it moved itself this tick).
     */
    private boolean diveUnderHorse(ServerLevel sl, ServerPlayer tp) {
        if (!steer || dived || flightTicks <= 5 && !returning) return false;
        if (!(tp.getVehicle() instanceof net.minecraft.world.entity.animal.horse.AbstractHorse horse) || tp.getXRot() <= 60.0F) return false;
        // Aim where the horse is going, not where it was: a galloping horse moves a lot each tick.
        Vec3 moved = new Vec3(horse.getX() - horse.xo, 0.0D, horse.getZ() - horse.zo);
        Vec3 under = horse.position().add(moved).add(0.0D, 0.3D, 0.0D);
        Vec3 to = under.subtract(position());
        if (to.length() < 2.0D || horse.position().add(0.0D, 0.3D, 0.0D).distanceTo(position()) < 2.0D) {
            dived = true;
            com.mobstands.stand.HorseSpin.launch(sl, horse, tp);
            setPos(under.x, under.y, under.z);
            if (!returning) startReturn();
            return true;
        }
        double sp = Math.max(1.6D, Math.min(2.8D, getDeltaMovement().length() + 0.3D));
        setDeltaMovement(to.normalize().scale(sp));
        Vec3 next = position().add(getDeltaMovement());
        setPos(next.x, next.y, next.z);
        if (flightTicks % 2 == 0) StandManager.hamonBurst(sl, position(), 1, 0.05D);
        return true;
    }
    private static final int STEER_TICKS = 80;

    public void setSteerable() {
        steer = true;
    }

    public void markThrown() {
        thrown = true;
        bounces = 0;
        bounced = false;
        curveSign = random.nextBoolean() ? 1.0D : -1.0D;
        flightTicks = 0;
        entityData.set(FLYING, true);
    }

    public boolean isFlying() {
        return entityData.get(FLYING);
    }

    /**
     * After a wall bounce: look for the nearest enemy roughly along the rebound (within ~30 degrees and 14 blocks).
     * Found one: fly straight at it (still a throw, so it hits). Otherwise: come back.
     */
    private LivingEntity ricochetTarget(ServerLevel sl, ServerPlayer tp, Vec3 rebound) {
        Vec3 dir = rebound.multiply(1.0D, 0.0D, 1.0D);
        if (tp == null || dir.lengthSqr() < 1.0E-4D) return null;
        dir = dir.normalize();
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (LivingEntity t : sl.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(14.0D),
                x -> x != tp && x.isAlive() && StandCombat.valid(tp, x))) {
            Vec3 to = t.position().add(0.0D, t.getBbHeight() * 0.5D, 0.0D).subtract(position());
            double d = to.length();
            if (d > 14.0D || d < 1.0E-3D) continue;
            Vec3 flat = to.multiply(1.0D, 0.0D, 1.0D);
            if (flat.lengthSqr() < 1.0E-4D || flat.normalize().dot(dir) < 0.866D) continue; // outside the rebound's path
            if (d < bestD) {
                bestD = d;
                best = t;
            }
        }
        return best;
    }

    /** Bounces this throw has made, and how many it may make (Steel Balls keep bouncing). */
    private int bounces;

    private int maxBounces() {
        return isSteel() ? 3 : 1;
    }

    /**
     * Thrown flight with exact collisions: every tick it traces its path against the blocks (so it can't skip
     * through a wall at speed) and, when it hits one, bounces off that face right away - mirrored off the surface,
     * losing a little speed. Off a wall it ricochets into an enemy standing along the rebound if there is one.
     */
    private void flyThrown(ServerLevel sl, ServerPlayer tp) {
        Vec3 vel = getDeltaMovement().add(0.0D, -0.03D, 0.0D);
        if (diveUnderHorse(sl, tp)) return;
        if (steer && !bounced && chainHit.isEmpty() && flightTicks > 5) {
            // Sky toss: once it's up, it curves toward wherever you're looking. Look down at a mob and it dives;
            // look to the side and it swings over. Better spinners turn it sharper.
            Vec3 eye = tp.getEyePosition();
            double reach = Math.max(5.0D, eye.distanceTo(position()));
            Vec3 aim = eye.add(tp.getLookAngle().scale(reach + 3.0D));
            Vec3 want = aim.subtract(position());
            if (want.lengthSqr() > 1.0E-4D) {
                double speed = Math.max(0.9D, Math.min(1.8D, vel.length()));
                float k = StandManager.spinSkill(StandManager.data(tp).spinLevel);
                vel = vel.lerp(want.normalize().scale(speed), 0.10D + 0.12D * k);
            }
            if (flightTicks == 6) sl.playSound(null, getX(), getY(), getZ(), com.mobstands.registry.ModSounds.SPIN_HAMON.get(),
                    net.minecraft.sounds.SoundSource.PLAYERS, 0.5F, 1.6F);
            if (flightTicks % 2 == 0) StandManager.hamonBurst(sl, position(), 1, 0.05D);
        }
        double half = getBbHeight() * 0.5D;
        Vec3 from = position().add(0.0D, half, 0.0D);
        Vec3 to = from.add(vel);
        net.minecraft.world.phys.BlockHitResult bh = sl.clip(new net.minecraft.world.level.ClipContext(from, to,
                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, this));
        if (bh.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
            setPos(to.x, to.y - half, to.z);
            setDeltaMovement(vel);
            return;
        }
        // Hit a block face: sit just off the surface and mirror the motion off it.
        Vec3 n = Vec3.atLowerCornerOf(bh.getDirection().getNormal());
        Vec3 at = bh.getLocation().add(n.scale(half + 0.02D));
        setPos(at.x, at.y - half, at.z);
        if (wallSense && stuckAt < 0L && sensed == null && bounces == 0) {
            // Wall Sense: it sticks right there, spinning in place, and starts listening.
            stuckAt = sl.getGameTime();
            stuckPos = position();
            setDeltaMovement(Vec3.ZERO);
            vibes.clear();
            lastSeen.clear();
            net.minecraft.world.level.block.state.BlockState ws = sl.getBlockState(bh.getBlockPos());
            sl.sendParticles(new net.minecraft.core.particles.BlockParticleOption(net.minecraft.core.particles.ParticleTypes.BLOCK, ws),
                    at.x, at.y, at.z, 10, 0.1D, 0.1D, 0.1D, 0.1D);
            sl.playSound(null, at.x, at.y, at.z, SoundEvents.SCULK_SENSOR_PLACE, SoundSource.PLAYERS, 1.0F, 1.2F);
            sl.playSound(null, at.x, at.y, at.z, SoundEvents.SHIELD_BLOCK, SoundSource.PLAYERS, 0.5F, 0.8F);
            tp.displayClientMessage(net.minecraft.network.chat.Component.literal("Listening for vibrations...").withStyle(s -> s.withColor(0x3FD0C9)), true);
            return;
        }
        // A soft bounce: the push off the surface is damped a lot, the slide along it a little.
        Vec3 normalPart = n.scale(vel.dot(n));
        Vec3 slide = vel.subtract(normalPart);
        Vec3 refl = slide.scale(0.7D).subtract(normalPart.scale(0.45D));
        bounces++;
        sl.playSound(null, at.x, at.y, at.z, SoundEvents.SHIELD_BLOCK, SoundSource.PLAYERS, 0.45F, 1.3F + 0.1F * bounces);
        StandManager.hamonBurst(sl, at, 5, 0.1D);
        net.minecraft.world.level.block.state.BlockState bs = sl.getBlockState(bh.getBlockPos());
        sl.sendParticles(new net.minecraft.core.particles.BlockParticleOption(net.minecraft.core.particles.ParticleTypes.BLOCK, bs),
                at.x, at.y, at.z, 6, 0.1D, 0.1D, 0.1D, 0.1D);
        leaveSpinField(null);
        if (bounces > maxBounces() || refl.length() < 0.25D) {
            setDeltaMovement(refl.scale(0.3D));
            startReturn(); // out of bounce: comes home (or drops and keeps spinning, if it doesn't return)
            return;
        }
        // Off a wall: straight into an enemy along the rebound, if there is one.
        LivingEntity target = n.y == 0.0D ? ricochetTarget(sl, tp, refl) : null;
        if (target != null) {
            Vec3 aim = target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D).subtract(position());
            refl = aim.normalize().scale(Math.max(1.1D, refl.length()));
        }
        setDeltaMovement(refl);
        flightTicks = 0;
    }

    /** Comes back after a throw (Steel Balls always; weapons too for a Spin master). Otherwise it stays down, spinning. */
    private boolean returns;
    /** Coming back after winding down on the floor (so it arrives still, not spinning). */
    private boolean restingReturn;

    public void setReturns(boolean returns) {
        this.returns = returns;
    }

    /** A Steel Ball (stronger, chains more). */
    public boolean isSteel() {
        return getItem().is(com.mobstands.registry.ModItems.STEEL_BALL.get());
    }

    /**
     * Steel Ball: where it bounces off something it leaves a patch of spin behind for a moment. Everyone in it is
     * caught once (and only once), including anyone who wanders in while it lasts.
     */
    private void leaveSpinField(LivingEntity alreadyHit) {
        if (!isSteel() || !(level() instanceof ServerLevel sl) || owner == null) return;
        if (!(sl.getPlayerByUUID(owner) instanceof ServerPlayer tp)) return;
        StandManager.addSpinField(tp, position(), power(), alreadyHit);
    }

    private void startReturn() {
        if (!returns) {
            // An ordinary item: no coming back. It drops and keeps spinning on the floor.
            thrown = false;
            returning = false;
            entityData.set(FLYING, false);
            entityData.set(END, (int) level().getGameTime() + IDLE_TICKS);
            return;
        }
        thrown = false;
        returning = true;
        returnHits.clear();
        flightTicks = 0;
        entityData.set(FLYING, true);
        level().playSound(null, getX(), getY(), getZ(), SoundEvents.TRIDENT_RETURN, SoundSource.PLAYERS, 0.8F, 1.3F);
        // The Spin crackle as it turns around and comes flying home - played right at you, so you always hear it.
        if (owner != null && level().getPlayerByUUID(owner) instanceof ServerPlayer op) {
            level().playSound(null, op.getX(), op.getY(), op.getZ(), com.mobstands.registry.ModSounds.SPIN_CRACKLE.get(), SoundSource.PLAYERS, 1.0F, 1.0F);
        }
    }

    /** Back in your hand (or inventory). */
    private void giveBack(ServerPlayer p) {
        ItemStack stack = getItem().copy();
        net.minecraft.world.InteractionHand ih = offHand ? net.minecraft.world.InteractionHand.OFF_HAND : net.minecraft.world.InteractionHand.MAIN_HAND;
        ItemStack hand = p.getItemInHand(ih);
        boolean inHand = false;
        if (hand.isEmpty()) {
            p.setItemInHand(ih, stack);
            inHand = true;
        } else if (ItemStack.isSameItemSameComponents(hand, stack) && hand.getCount() < hand.getMaxStackSize()) {
            hand.grow(1);
            inHand = true;
        } else if (!p.getInventory().add(stack)) {
            p.drop(stack, false);
        }
        // Caught it in your hand: still spinning.
        if (inHand && !restingReturn) StandManager.resumeSpin(p, offHand); // (a ball coming home after it stopped isn't spinning)
        level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.5F, 1.4F);
        level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.TRIDENT_RIPTIDE_1.value(), SoundSource.PLAYERS, 0.4F, 1.8F);
        discard();
    }
    private final Map<UUID, Long> lastHit = new HashMap<>();
    /** How many times each mob has been caught by this floor spin (at most FLOOR_HITS_MAX). */
    private final Map<UUID, Integer> floorHits = new HashMap<>();
    public static final int FLOOR_HITS_MAX = 3;
    /** Mobs hit by this throw's chain, in order. */
    private final java.util.LinkedHashSet<UUID> chainHit = new java.util.LinkedHashSet<>();
    /** Chance each hit chains on to another mob, and the longest chain. */
    public static final float CHAIN_CHANCE = 0.25F;
    public static final int MAX_CHAIN = 3;
    /** Steel Balls chain far more often, and further. */
    public static final float CHAIN_CHANCE_STEEL = 0.5F;
    public static final int MAX_CHAIN_STEEL = 4;

    /** The nearest other enemy (not yet hit this chain) within 8 blocks of the one just hit. */
    private LivingEntity nextInChain(ServerLevel sl, ServerPlayer tp, LivingEntity from) {
        LivingEntity best = null;
        double bestD = 64.0D;
        for (LivingEntity t : sl.getEntitiesOfClass(LivingEntity.class, from.getBoundingBox().inflate(8.0D),
                x -> x != tp && x != from && x.isAlive() && !chainHit.contains(x.getUUID()) && StandCombat.valid(tp, x))) {
            double d = t.distanceToSqr(from);
            if (d < bestD) {
                bestD = d;
                best = t;
            }
        }
        return best;
    }
    private final java.util.Set<UUID> killed = new java.util.HashSet<>();
    /** The floor spin stops after finishing off this many different mobs. */
    public static final int KILLS_TO_STOP = 3;

    public SpinItemEntity(EntityType<? extends SpinItemEntity> type, Level level) {
        super(type, level);
    }

    public void setup(ServerPlayer owner, ItemStack stack, float power) {
        this.owner = owner.getUUID();
        entityData.set(ITEM, stack);
        entityData.set(START, (int) level().getGameTime());
        entityData.set(POWER, power);
        entityData.set(END, (int) level().getGameTime() + IDLE_TICKS);
    }

    /** 1 at full spin, easing to 0 as it runs out of time. */
    public float speed(float partial) {
        float left = entityData.get(END) - (level().getGameTime() + partial);
        float k = Mth.clamp(left / IDLE_TICKS, 0.0F, 1.0F);
        return k * k * (3.0F - 2.0F * k);
    }

    public float angle(float partial) {
        return Mth.lerp(partial, angleO, angle);
    }

    public ItemStack getItem() {
        return entityData.get(ITEM);
    }

    public float power() {
        return entityData.get(POWER);
    }

    /** Ticks it has been spinning (with partial tick). */
    public float spinAge(float partial) {
        return Math.max(0.0F, (float) (level().getGameTime() - entityData.get(START)) + partial);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(ITEM, ItemStack.EMPTY);
        builder.define(START, 0);
        builder.define(POWER, 1.0F);
        builder.define(END, 0);
        builder.define(FLYING, false);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            // Golden trail: remember where it's been while it flies (and let the tail shrink away after).
            if (isFlying()) {
                trail.addFirst(position());
                while (trail.size() > 14) trail.removeLast();
            } else if (!trail.isEmpty()) {
                trail.removeLast();
            }
        }
        if (!level().isClientSide && level() instanceof ServerLevel tsl && (thrown || returning)) {
            ServerPlayer tp = owner == null ? null : (ServerPlayer) tsl.getPlayerByUUID(owner);
            if (tp == null || !tp.isAlive() || tp.level() != level()) {
                // Nobody to come back to: it just drops.
                thrown = false;
                returning = false;
                entityData.set(FLYING, false);
            } else if (returning && diveUnderHorse(tsl, tp)) {
                flightTicks++; // still coming home - but first it dives under your horse
            } else if (returning) {
                // Flying back to you (through anything in the way), faster and faster.
                Vec3 target = tp.position().add(0.0D, tp.getBbHeight() * 0.6D, 0.0D);
                Vec3 to = target.subtract(position());
                double d = to.length();
                if (d < 1.3D) {
                    giveBack(tp);
                    return;
                }
                flightTicks++;
                // ...and it keeps crackling the whole way back.
                if (flightTicks % 8 == 0) tsl.playSound(null, getX(), getY(), getZ(), com.mobstands.registry.ModSounds.SPIN_CRACKLE.get(), SoundSource.PLAYERS, 1.0F, 1.0F + flightTicks * 0.004F);
                double sp = Math.min(1.6D, 0.45D + flightTicks * 0.06D);
                // It's spinning, so it swings back in a curve: a sideways pull that fades as it closes in.
                Vec3 dir = to.scale(1.0D / d);
                Vec3 side = new Vec3(-dir.z, 0.0D, dir.x).normalize().scale(curveSign * sp * 0.9D * Math.min(1.0D, d / 7.0D));
                setDeltaMovement(getDeltaMovement().lerp(dir.scale(sp).add(side), 0.3D));
                // On the way home it still hits whatever is in its path - each mob once per trip back, a bit softer,
                // and it keeps flying on through them.
                for (LivingEntity t : tsl.getEntitiesOfClass(LivingEntity.class, getBoundingBox().expandTowards(getDeltaMovement()).inflate(0.15D),
                        x -> x != tp && x != tp.getVehicle() && x.isAlive() && StandCombat.valid(tp, x) && !returnHits.contains(x.getUUID()))) {
                    returnHits.add(t.getUUID());
                    StandManager.spinThrowHit(tp, t, power() * 0.7F, isSteel());
                    tsl.playSound(null, t.getX(), t.getY(), t.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.9F, 1.4F);
                }
                Vec3 next = position().add(getDeltaMovement());
                setPos(next.x, next.y, next.z);
                if (flightTicks > 200) giveBack(tp); // safety net
                return;
            } else if (wallSense && wallSenseTick(tsl, tp)) {
                return;
            } else {
                // Thrown: check for an enemy in its path before moving.
                Vec3 vel = getDeltaMovement();
                LivingEntity hit = null;
                double best = Double.MAX_VALUE;
                for (LivingEntity t : tsl.getEntitiesOfClass(LivingEntity.class, getBoundingBox().expandTowards(vel).inflate(0.05D), // a tight hitbox: you have to aim
                        x -> x != tp && x != tp.getVehicle() && x.isAlive() && StandCombat.valid(tp, x))) {
                    double dd = t.distanceToSqr(this);
                    if (dd < best) {
                        best = dd;
                        hit = t;
                    }
                }
                if (hit != null) {
                    lastHit.put(hit.getUUID(), tsl.getGameTime());
                    chainHit.add(hit.getUUID());
                    // Each link of a chain hits harder: x1, x1.5, x2.
                    float mult = 1.0F + 0.5F * (chainHit.size() - 1);
                    StandManager.spinThrowHit(tp, hit, power() * mult, isSteel());
                    if (chainHit.size() > 1) {
                        tsl.playSound(null, hit.getX(), hit.getY(), hit.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.2F,
                                0.9F + chainHit.size() * 0.25F);
                        StandManager.hamonBurst(tsl, hit.position().add(0.0D, hit.getBbHeight() * 0.5D, 0.0D), 10 * chainHit.size(), 0.5D);
                    }
                    // A slight chance it keeps going: chains to the next nearest mob (up to 3 in a chain).
                    boolean steel = isSteel();
                    LivingEntity next = chainHit.size() < (steel ? MAX_CHAIN_STEEL : MAX_CHAIN)
                            && random.nextFloat() < (steel ? CHAIN_CHANCE_STEEL : CHAIN_CHANCE) * (0.3F + 0.7F * com.mobstands.stand.StandManager.spinSkill(com.mobstands.stand.StandManager.data(tp).spinLevel)) ? nextInChain(tsl, tp, hit) : null;
                    if (next != null) {
                        Vec3 to = next.position().add(0.0D, next.getBbHeight() * 0.5D, 0.0D).subtract(position());
                        setDeltaMovement(to.normalize().scale(Math.max(1.2D, vel.length())));
                        flightTicks = 0;
                        return;
                    }
                    leaveSpinField(hit);
                    setDeltaMovement(vel.scale(-0.2D).add(0.0D, 0.25D, 0.0D)); // bounces off...
                    startReturn();                                               // ...and heads home
                    return;
                }
                if (++flightTicks > (steer ? STEER_TICKS : 30)) {
                    startReturn();
                    return;
                }
                flyThrown(tsl, tp);
                return;
            }
        }
        if (level().isClientSide && isFlying()) {
            // Flying: the server steers it; just spin.
            angleO = angle;
            angle += SPIN_SPEED * power() * speed(0.0F);
            return;
        }
        // Exactly like a dropped item: gravity, ground friction, a little bounce.
        if (!isNoGravity()) setDeltaMovement(getDeltaMovement().add(0.0D, thrown ? -0.03D : -0.04D, 0.0D));
        move(MoverType.SELF, getDeltaMovement());
        float f = 0.98F;
        if (onGround()) f = level().getBlockState(getBlockPosBelowThatAffectsMyMovement()).getFriction(level(), getBlockPosBelowThatAffectsMyMovement(), this) * 0.98F;
        setDeltaMovement(getDeltaMovement().multiply(f, 0.98D, f));
        if (onGround() && getDeltaMovement().y < 0.0D) setDeltaMovement(getDeltaMovement().multiply(1.0D, -0.5D, 1.0D));
        if (thrown && !level().isClientSide && (onGround() || horizontalCollision || verticalCollision)) {
            leaveSpinField(null);
            startReturn(); // hit the ground or a wall: comes back
            return;
        }

        if (level().isClientSide) {
            // Spin: up to ~67 degrees a tick, slowing as it runs out of time.
            angleO = angle;
            angle += SPIN_SPEED * power() * speed(0.0F);
            return;
        }
        if (!(level() instanceof ServerLevel sl)) return;
        if (getItem().isEmpty()) {
            discard();
            return;
        }
        if (level().getGameTime() >= entityData.get(END) || getY() < level().getMinBuildHeight() - 32) {
            // A Steel Ball that wound down on the floor rolls itself back to its owner.
            if (isSteel() && owner != null && sl.getPlayerByUUID(owner) instanceof ServerPlayer sp && sp.isAlive() && sp.level() == sl
                    && sp.distanceToSqr(this) < 64.0D * 64.0D) {
                returns = true;
                restingReturn = true;
                setDeltaMovement(0.0D, 0.35D, 0.0D);
                startReturn();
                return;
            }
            // Wound down: an ordinary item again.
            ItemEntity ie = new ItemEntity(sl, getX(), getY(), getZ(), getItem().copy());
            ie.setDeltaMovement(Vec3.ZERO);
            ie.setPickUpDelay(10);
            sl.addFreshEntity(ie);
            sl.playSound(null, getX(), getY(), getZ(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.4F, 0.5F);
            discard();
            return;
        }
        if (!onGround()) return;
        float k = speed(0.0F);
        long now = sl.getGameTime();
        if (now % 8L == 0L) {
            sl.playSound(null, getX(), getY(), getZ(), SoundEvents.TRIDENT_RIPTIDE_1.value(), SoundSource.PLAYERS, 0.05F + 0.15F * k, 1.2F + k);
        }
        if (now % 5L == 0L) StandManager.hamonBurst(sl, position().add(0.0D, 0.25D, 0.0D), 1, 0.25D);
        ServerPlayer p = owner == null ? null : (ServerPlayer) sl.getPlayerByUUID(owner);
        if (p == null) return;
        float power = power() * (0.4F + 0.6F * k);
        Vec3 c = position();
        int caught = 0;
        for (LivingEntity t : sl.getEntitiesOfClass(LivingEntity.class, area(c),
                x -> x != p && x.isAlive() && StandCombat.valid(p, x))) {
            // Each one can get caught up to 3 times while it stays in range (2 seconds apart).
            Long last = lastHit.get(t.getUUID());
            int times = floorHits.getOrDefault(t.getUUID(), 0);
            if (times >= FLOOR_HITS_MAX || (last != null && now - last < 40L)) continue;
            floorHits.put(t.getUUID(), times + 1);
            lastHit.put(t.getUUID(), now);
            StandManager.spinAffect(p, sl, c, t, power, now, true);
            caught++;
        }
        // Count the mobs it has finished off (right away or after being flung into a wall).
        for (java.util.Map.Entry<UUID, Long> en : lastHit.entrySet()) {
            if (killed.contains(en.getKey())) continue;
            Entity was = sl.getEntity(en.getKey());
            if (was == null || (was instanceof LivingEntity le && le.isDeadOrDying())) {
                if (now - en.getValue() < 60L) killed.add(en.getKey()); // died within 3 s of being caught
            }
        }
        if (killed.size() >= KILLS_TO_STOP) {
            // Three kills: the spin's energy is spent - it stops right there.
            entityData.set(END, (int) now);
        } else if (caught >= 1) {
            // Still catching people: it keeps spinning a while longer.
            int start = (int) (now - spinAge(0.0F));
            entityData.set(END, Math.min(start + MAX_TICKS, Math.max(entityData.get(END), (int) now + CATCH_EXTEND)));
        }
    }

    /** The 8x8x8 block cube it affects (from just below the floor upward). */
    public static AABB area(Vec3 c) {
        double r = StandManager.SPIN_RADIUS;
        return new AABB(c.x - r, c.y - 1.0D, c.z - r, c.x + r, c.y + 7.0D, c.z + r);
    }

    /** You can aim at it (to right-click it); hitting it does nothing. */
    @Override
    public boolean isPickable() {
        return isAlive();
    }

    /** Right-click the spinning item to grab it back right away (as a normal item). */
    @Override
    public net.minecraft.world.InteractionResult interact(net.minecraft.world.entity.player.Player player, net.minecraft.world.InteractionHand hand) {
        if (!isAlive() || getItem().isEmpty()) return net.minecraft.world.InteractionResult.PASS;
        if (!level().isClientSide) {
            ItemStack stack = getItem().copy();
            if (!player.getInventory().add(stack)) player.drop(stack, false);
            level().playSound(null, getX(), getY(), getZ(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.4F,
                    ((random.nextFloat() - random.nextFloat()) * 0.7F + 1.0F) * 2.0F);
            discard();
        }
        return net.minecraft.world.InteractionResult.sidedSuccess(level().isClientSide);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains("Item")) entityData.set(ITEM, ItemStack.parse(registryAccess(), tag.getCompound("Item")).orElse(ItemStack.EMPTY));
        if (tag.hasUUID("Owner")) owner = tag.getUUID("Owner");
        entityData.set(POWER, tag.getFloat("Power"));
        entityData.set(START, (int) level().getGameTime() - tag.getInt("Age"));
        entityData.set(END, (int) level().getGameTime() + tag.getInt("Left"));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        if (!getItem().isEmpty()) tag.put("Item", getItem().save(registryAccess()));
        if (owner != null) tag.putUUID("Owner", owner);
        tag.putFloat("Power", power());
        tag.putInt("Age", (int) spinAge(0.0F));
        tag.putInt("Left", Math.max(0, entityData.get(END) - (int) level().getGameTime()));
    }
}
