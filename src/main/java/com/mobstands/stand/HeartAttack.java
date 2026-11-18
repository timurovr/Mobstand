package com.mobstands.stand;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Creeper Stand, level 10 (V): a little creeper riding a little minecart rolls out and hunts down whoever you're
 * looking at - Sheer Heart Attack style. It can't be hurt, it doesn't stop, and when it reaches its victim it blows
 * up... and keeps rolling, looking for the next one, until it runs out of time.
 */
public final class HeartAttack {
    private HeartAttack() {}

    public static final String TAG = "mobstands_heart_attack";
    /** How long it hunts, how fast it rolls, how big the blast is, and how long between blasts. */
    public static final int LIFE_TICKS = 400, BLAST_GAP = 30;
    private static final double SPEED = 0.34D, SEEK_RANGE = 24.0D;

    private static final class Hunter {
        com.mobstands.entity.HeartAttackEntity cart;
        UUID owner;
        LivingEntity target;
        long until, nextBlast;
        float power;
        int blasts;
    }

    private static final List<Hunter> HUNTERS = new ArrayList<>();

    public static boolean release(AbilityContext c) {
        ServerPlayer p = c.owner;
        ServerLevel sl = c.level;
        LivingEntity target = c.aim(SEEK_RANGE);
        if (target == null) target = nearestVictim(sl, p, p.position(), null);
        if (target == null) {
            p.displayClientMessage(Component.literal("There's no one to chase."), true);
            return false;
        }
        Vec3 look = c.flatLook();
        Vec3 at = p.position().add(look.scale(1.2D));
        com.mobstands.entity.HeartAttackEntity cart = com.mobstands.registry.ModEntities.HEART_ATTACK.get().create(sl);
        if (cart == null) return false;
        cart.moveTo(at.x, at.y, at.z, p.getYRot(), 0.0F);
        sl.addFreshEntity(cart);

        Hunter h = new Hunter();
        h.cart = cart;
        h.owner = p.getUUID();
        h.target = target;
        h.until = sl.getGameTime() + LIFE_TICKS;
        h.nextBlast = sl.getGameTime() + 10;
        h.power = (float) Math.min(4.0D, 1.6D + c.dmg(1.0D) * 0.5D);
        HUNTERS.add(h);
        sl.playSound(null, at.x, at.y, at.z, SoundEvents.MINECART_INSIDE, SoundSource.PLAYERS, 0.8F, 1.6F);
        sl.playSound(null, at.x, at.y, at.z, SoundEvents.CREEPER_PRIMED, SoundSource.PLAYERS, 0.8F, 1.6F);
        p.displayClientMessage(Component.literal("Look here!").withStyle(s -> s.withColor(0x4CFF4C).withBold(true)), true);
        return true;
    }

    /** The closest thing worth chasing near {@code from} (a monster first, otherwise any valid target). */
    private static LivingEntity nearestVictim(ServerLevel sl, ServerPlayer owner, Vec3 from, LivingEntity skip) {
        LivingEntity best = null;
        double bestD = SEEK_RANGE * SEEK_RANGE;
        for (LivingEntity e : sl.getEntitiesOfClass(LivingEntity.class, owner.getBoundingBox().move(from.subtract(owner.position())).inflate(SEEK_RANGE),
                x -> x != skip && x.isAlive() && !x.getTags().contains(TAG) && StandCombat.valid(owner, x)
                        && x instanceof net.minecraft.world.entity.monster.Enemy)) {
            double d = e.distanceToSqr(from);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    public static void tick(ServerLevel sl) {
        if (HUNTERS.isEmpty()) return;
        if (!sl.tickRateManager().runsNormally()) return; // stopped time
        long now = sl.getGameTime();
        Iterator<Hunter> it = HUNTERS.iterator();
        while (it.hasNext()) {
            Hunter h = it.next();
            if (h.cart.level() != sl) continue;
            ServerPlayer owner = sl.getServer().getPlayerList().getPlayer(h.owner);
            if (owner == null || h.cart.isRemoved() || now >= h.until) {
                it.remove();
                vanish(sl, h);
                continue;
            }
            if (h.target == null || !h.target.isAlive() || h.target.level() != sl) {
                h.target = nearestVictim(sl, owner, h.cart.position(), null);
                if (h.target == null) {
                    // Nothing left to hunt: it rolls back to you and fades.
                    if (h.cart.distanceToSqr(owner) < 4.0D) {
                        it.remove();
                        vanish(sl, h);
                        continue;
                    }
                }
            }
            Vec3 goal = h.target != null ? h.target.position() : owner.position();
            Vec3 to = goal.subtract(h.cart.position());
            Vec3 flat = new Vec3(to.x, 0.0D, to.z);
            double dist = flat.length();
            Vec3 dir = dist < 1.0E-4D ? Vec3.ZERO : flat.scale(1.0D / dist);
            // Roll toward it (hop up single blocks in the way), with real gravity.
            double vy = h.cart.onGround() ? 0.0D : Math.max(-0.6D, h.cart.getDeltaMovement().y - 0.06D);
            if (h.cart.horizontalCollision && h.cart.onGround()) vy = 0.48D;
            Vec3 step = new Vec3(dir.x * SPEED, vy, dir.z * SPEED);
            h.cart.move(MoverType.SELF, step);
            h.cart.setDeltaMovement(0.0D, vy, 0.0D);
            if (dist > 1.0E-4D) {
                float want = (float) Math.toDegrees(Math.atan2(dir.z, dir.x)) - 90.0F;
                h.cart.setYRot(net.minecraft.util.Mth.rotLerp(0.35F, h.cart.getYRot(), want)); // turns smoothly
            }
            if (now % 4L == 0L) sl.sendParticles(ParticleTypes.SMOKE, h.cart.getX(), h.cart.getY() + 0.2D, h.cart.getZ(), 1, 0.1D, 0.0D, 0.1D, 0.0D);
            if (now % 12L == 0L) sl.playSound(null, h.cart.getX(), h.cart.getY(), h.cart.getZ(), SoundEvents.MINECART_RIDING, SoundSource.PLAYERS, 0.25F, 1.8F);
            // It reaches its victim: BOOM - and keeps going.
            if (h.target != null && now >= h.nextBlast && h.cart.distanceToSqr(h.target) < 1.8D * 1.8D) {
                h.nextBlast = now + BLAST_GAP;
                h.blasts++;
                h.cart.markBlast();
                Vec3 c = h.cart.position();
                sl.playSound(null, c.x, c.y, c.z, SoundEvents.CREEPER_PRIMED, SoundSource.PLAYERS, 1.0F, 1.4F);
                // The blast is yours (so it never hurts you) and doesn't break blocks.
                sl.explode(owner, c.x, c.y + 0.4D, c.z, h.power, false, Level.ExplosionInteraction.NONE);
                h.target = h.target.isAlive() ? h.target : null;
            }
        }
    }

    private static void vanish(ServerLevel sl, Hunter h) {
        Vec3 c = h.cart.position();
        sl.sendParticles(ParticleTypes.POOF, c.x, c.y + 0.4D, c.z, 14, 0.3D, 0.3D, 0.3D, 0.02D);
        sl.playSound(null, c.x, c.y, c.z, SoundEvents.CREEPER_HURT, SoundSource.PLAYERS, 0.6F, 1.6F);
        h.cart.discard();
    }

    /** Leftovers saved with the world (a crash, a quit mid-hunt) disappear when they load back in. */
    public static boolean isLeftover(Entity e) {
        return e.getTags().contains(TAG);
    }

    /** Is this little cart still out hunting for someone? */
    public static boolean owns(Entity e) {
        for (Hunter h : HUNTERS) if (h.cart == e) return true;
        return false;
    }

    public static void clear() {
        HUNTERS.clear();
    }
}
