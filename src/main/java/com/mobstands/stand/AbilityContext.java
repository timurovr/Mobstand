package com.mobstands.stand;

import com.mobstands.config.StandConfig;
import com.mobstands.entity.StandEntity;
import com.mobstands.entity.StandProjectile;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Everything an ability needs: who, where, how strong, plus helpers for targeting, damage, FX. */
public final class AbilityContext {
    public final ServerPlayer owner;
    public final StandEntity stand;
    public final ServerLevel level;
    public final StandType type;
    public final StandData data;
    public final int standLevel;

    /** Set by an ability that didn't go off, to say why (shown instead of "No valid target."). */
    public String fail;

    public AbilityContext(ServerPlayer owner, StandEntity stand, ServerLevel level, StandType type, StandData data) {
        this.owner = owner;
        this.stand = stand;
        this.level = level;
        this.type = type;
        this.data = data;
        this.standLevel = data.getLevel();
    }

    // ------------------------------------------------------------------ power

    public double power() {
        return type.rarity().power() * (1.0D + type.powerPerLevel() * (standLevel - 1) * 14.0D / 9.0D) * StandConfig.DAMAGE_MULTIPLIER.get();
    }

    public float dmg(double base) {
        return (float) (base * power());
    }

    // ------------------------------------------------------------------ geometry / targeting

    public Vec3 look() {
        return owner.getLookAngle();
    }

    public Vec3 flatLook() {
        Vec3 l = owner.getLookAngle();
        Vec3 f = new Vec3(l.x, 0.0D, l.z);
        return f.lengthSqr() < 1.0E-4D ? new Vec3(0.0D, 0.0D, 1.0D) : f.normalize();
    }

    /** Where Stand projectiles leave from. */
    public Vec3 shotOrigin() {
        return stand.position().add(0.0D, stand.getBbHeight() * 0.65D, 0.0D).add(flatLook().scale(0.5D));
    }

    public LivingEntity aim(double range) {
        Vec3 eye = owner.getEyePosition();
        Vec3 end = eye.add(look().scale(range));
        AABB box = new AABB(eye, end).inflate(1.5D);
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, x -> StandCombat.valid(owner, x))) {
            AABB bb = e.getBoundingBox().inflate(0.45D);
            double d;
            if (bb.contains(eye)) {
                d = 0.0D;
            } else {
                Optional<Vec3> hit = bb.clip(eye, end);
                if (hit.isEmpty()) continue;
                d = eye.distanceToSqr(hit.get());
            }
            if (d >= bestD) continue;
            BlockHitResult block = level.clip(new ClipContext(eye, e.getEyePosition(), ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, owner));
            if (block.getType() != HitResult.Type.MISS) continue;
            bestD = d;
            best = e;
        }
        return best;
    }

    /** Crosshair target first; otherwise the closest enemy right in front of the owner (aim assist). */
    public LivingEntity meleeTarget(double range) {
        LivingEntity t = aim(range);
        if (t != null) return t;
        Vec3 center = owner.position().add(flatLook().scale(2.0D));
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(center, center).inflate(3.0D),
                x -> StandCombat.valid(owner, x))) {
            Vec3 to = e.position().subtract(owner.position());
            if (to.lengthSqr() < 1.0E-4D || to.normalize().dot(look()) < 0.1D) continue;
            double d = e.distanceToSqr(center);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    /** Point the owner is aiming at: an entity, the first block hit, or the max-range point. */
    public Vec3 aimPoint(double range) {
        LivingEntity t = aim(range);
        if (t != null) return t.position().add(0.0D, t.getBbHeight() * 0.5D, 0.0D);
        Vec3 eye = owner.getEyePosition();
        Vec3 end = eye.add(look().scale(range));
        BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, owner));
        return hit.getType() == HitResult.Type.MISS ? end : hit.getLocation();
    }

    public List<LivingEntity> enemies(Vec3 center, double radius) {
        double r2 = radius * radius;
        return level.getEntitiesOfClass(LivingEntity.class, new AABB(center, center).inflate(radius),
                e -> StandCombat.valid(owner, e) && e.position().add(0.0D, e.getBbHeight() * 0.5D, 0.0D).distanceToSqr(center) <= r2);
    }

    public List<LivingEntity> cone(double range, double minDot) {
        Vec3 eye = owner.getEyePosition();
        Vec3 look = look();
        List<LivingEntity> out = new ArrayList<>();
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, owner.getBoundingBox().inflate(range),
                x -> StandCombat.valid(owner, x))) {
            Vec3 to = e.position().add(0.0D, e.getBbHeight() * 0.5D, 0.0D).subtract(eye);
            double d = to.length();
            if (d > range || d < 1.0E-4D) continue;
            if (to.scale(1.0D / d).dot(look) >= minDot) out.add(e);
        }
        return out;
    }

    public Vec3 beamEnd(Vec3 from, Vec3 dir, double range) {
        Vec3 end = from.add(dir.scale(range));
        BlockHitResult hit = level.clip(new ClipContext(from, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, owner));
        return hit.getType() == HitResult.Type.MISS ? end : hit.getLocation();
    }

    /** All valid enemies touched by a beam (piercing). */
    public List<LivingEntity> beam(Vec3 from, Vec3 dir, double range, double width) {
        Vec3 end = beamEnd(from, dir, range);
        List<LivingEntity> out = new ArrayList<>();
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(from, end).inflate(width + 1.0D),
                x -> StandCombat.valid(owner, x))) {
            AABB bb = e.getBoundingBox().inflate(width);
            if (bb.contains(from) || bb.clip(from, end).isPresent()) out.add(e);
        }
        return out;
    }

    /** Highest free point above {@code p} (up to {@code max} blocks), so rain attacks work under roofs. */
    public Vec3 skyPoint(Vec3 p, double max) {
        Vec3 top = p.add(0.0D, max, 0.0D);
        BlockHitResult hit = level.clip(new ClipContext(p, top, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, owner));
        return hit.getType() == HitResult.Type.MISS ? top : hit.getLocation().add(0.0D, -0.6D, 0.0D);
    }

    /** Safe teleport destination along the look vector, or null. */
    public Vec3 blinkDestination(double range) {
        Vec3 eye = owner.getEyePosition();
        Vec3 dir = look();
        Vec3 reach = beamEnd(eye, dir, range);
        for (int i = 0; i < 14; i++) {
            Vec3 eyeCandidate = reach.subtract(dir.scale(i * 0.35D));
            Vec3 feet = eyeCandidate.subtract(0.0D, owner.getEyeHeight(), 0.0D);
            AABB moved = owner.getBoundingBox().move(feet.subtract(owner.position()));
            if (level.noCollision(owner, moved) && feet.distanceTo(owner.position()) > 2.0D) return feet;
        }
        return null;
    }

    // ------------------------------------------------------------------ damage / effects

    public boolean hurt(LivingEntity target, float amount) {
        // Gold Experience Requiem: every blow overloads the senses - the enemy is left stunned for a moment.
        if (type == StandType.GER) Ger.stun(target);
        return StandCombat.hurt(owner, target, amount);
    }

    public boolean hurtSonic(LivingEntity target, float amount) {
        return StandCombat.hurtSonic(owner, target, amount);
    }

    public void push(LivingEntity target, Vec3 dir, double horizontal, double up) {
        StandCombat.push(target, dir, horizontal, up);
    }

    public void effect(LivingEntity target, Holder<MobEffect> effect, int ticks, int amplifier) {
        target.addEffect(new MobEffectInstance(effect, ticks, amplifier));
    }

    public void explode(Vec3 pos, double basePower) {
        float p = (float) (basePower * Math.min(1.6D, power()));
        Level.ExplosionInteraction mode = StandConfig.BLOCK_DAMAGE.get()
                ? Level.ExplosionInteraction.TNT : Level.ExplosionInteraction.NONE;
        // Owner is the explosion source (kills credit the owner); StandEvents makes the owner immune to it.
        level.explode(owner, pos.x, pos.y, pos.z, p, mode);
        // Without block damage vanilla only shows one small puff, so always add a full creeper-style blast.
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, pos.x, pos.y, pos.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        level.sendParticles(ParticleTypes.EXPLOSION, pos.x, pos.y, pos.z, (int) (6 + p * 4), p * 0.4D, p * 0.3D, p * 0.4D, 0.0D);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, pos.x, pos.y, pos.z, (int) (8 + p * 5), p * 0.5D, p * 0.4D, p * 0.5D, 0.02D);
        level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS,
                Math.min(4.0F, 1.5F + p * 0.5F), 0.85F + level.random.nextFloat() * 0.2F);
    }

    /**
     * A big, slow, cinematic blast (same damage as {@link #explode}): a white flash and a deep boom, a fireball that
     * swells and rolls upward, shockwave rings spreading along the ground, a mushroom column of smoke rising for
     * about two seconds, embers raining down, and a low rumble rolling away.
     */
    public void bigExplode(Vec3 pos, double basePower) {
        explode(pos, basePower);
        bigBlastFx(level, pos, basePower);
    }

    /** The cinematic part of a big blast on its own (no damage), for blasts made elsewhere (Heart Attack...). */
    public static void bigBlastFx(ServerLevel level, Vec3 pos, double basePower) {
        double r = Math.max(1.5D, basePower);
        level.sendParticles(ParticleTypes.FLASH, pos.x, pos.y, pos.z, 2, 0.0D, 0.0D, 0.0D, 0.0D);
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, pos.x, pos.y, pos.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 3.0F, 0.5F);
        level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.PLAYERS, 1.2F, 0.6F);
        for (int i = 1; i <= 40; i++) {
            final int k = i;
            TickScheduler.later(i, () -> {
                // Fireball: swells, then rolls up into the column.
                if (k <= 12) {
                    double s = r * (0.4D + k * 0.08D);
                    level.sendParticles(ParticleTypes.FLAME, pos.x, pos.y + k * 0.08D, pos.z, 18, s * 0.5D, s * 0.4D, s * 0.5D, 0.04D);
                    level.sendParticles(ParticleTypes.LAVA, pos.x, pos.y, pos.z, 2, s * 0.4D, s * 0.3D, s * 0.4D, 0.0D);
                }
                // Shockwave rings along the ground.
                if (k <= 10 && k % 2 == 0) {
                    double rad = r * 0.6D * k * 0.4D;
                    int pts = 24 + k * 3;
                    for (int j = 0; j < pts; j++) {
                        double a = (Math.PI * 2.0D) * j / pts;
                        level.sendParticles(ParticleTypes.CLOUD, pos.x + Math.cos(a) * rad, pos.y - 0.6D, pos.z + Math.sin(a) * rad, 1, 0.0D, 0.05D, 0.0D, 0.01D);
                    }
                }
                if (k == 3 || k == 7) {
                    level.sendParticles(ParticleTypes.EXPLOSION, pos.x, pos.y + k * 0.15D, pos.z, 6, r * 0.6D, r * 0.4D, r * 0.6D, 0.0D);
                    level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 1.5F, 0.4F + k * 0.03F);
                }
                // Mushroom column: smoke climbing, the cap spreading out at the top.
                double h = Math.min(k, 30) * 0.18D;
                level.sendParticles(ParticleTypes.LARGE_SMOKE, pos.x, pos.y + h, pos.z, 4, 0.3D + k * 0.01D, 0.2D, 0.3D + k * 0.01D, 0.01D);
                if (k >= 10) level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, pos.x, pos.y + h + 0.6D, pos.z, 2, r * 0.25D + k * 0.03D, 0.1D, r * 0.25D + k * 0.03D, 0.005D);
                // Embers drifting down.
                if (k >= 6 && k % 3 == 0) level.sendParticles(ParticleTypes.SMALL_FLAME, pos.x, pos.y + h, pos.z, 6, r * 0.8D, 0.4D, r * 0.8D, 0.0D);
                if (k == 14 || k == 26) level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.PLAYERS, 0.6F, 0.4F);
            });
        }
    }

    public void fire(StandProjectile.Kind kind, Vec3 origin, Vec3 dir, double baseDamage) {
        StandProjectile.spawn(owner, level, kind, origin, dir, dmg(baseDamage));
    }

    // ------------------------------------------------------------------ FX

    public void fx(ParticleOptions p, Vec3 pos, int count, double spread, double speed) {
        level.sendParticles(p, pos.x, pos.y, pos.z, count, spread, spread, spread, speed);
    }

    public void ring(ParticleOptions p, Vec3 center, double radius, int points) {
        for (int i = 0; i < points; i++) {
            double a = (Math.PI * 2.0D) * i / points;
            level.sendParticles(p, center.x + Math.cos(a) * radius, center.y, center.z + Math.sin(a) * radius,
                    1, 0.0D, 0.05D, 0.0D, 0.01D);
        }
    }

    public void trail(ParticleOptions p, Vec3 from, Vec3 to, double step) {
        Vec3 d = to.subtract(from);
        double len = d.length();
        if (len < 1.0E-4D) return;
        Vec3 dir = d.scale(1.0D / len);
        for (double t = 0.0D; t <= len; t += step) {
            Vec3 pt = from.add(dir.scale(t));
            level.sendParticles(p, pt.x, pt.y, pt.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }

    public void sound(SoundEvent e, float volume, float pitch) {
        Vec3 p = stand.position();
        level.playSound(null, p.x, p.y, p.z, e, SoundSource.PLAYERS, volume, pitch);
    }

    public void sound(Holder<SoundEvent> e, float volume, float pitch) {
        sound(e.value(), volume, pitch);
    }

    public void later(int delayTicks, Runnable r) {
        TickScheduler.later(delayTicks, () -> {
            if (owner.isRemoved() || !owner.isAlive()) return;
            r.run();
        });
    }
}
