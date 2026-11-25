package com.mobstands.stand;

import com.mobstands.entity.NuggetShotEntity;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * GER: Iron Shot. GER holds up an iron nugget between two fingers... and flicks it. It flies so fast it leaves a
 * blazing white-gold trail straight through whatever you're looking at - and everything behind it: it pierces every
 * creature in its path, out to its full range or the first wall. Each one takes the huge hit and is hurled back.
 */
public final class GerShot {
    private GerShot() {}

    /** How long the nugget charges beside you before it's flicked (ticks): 0.7 s. */
    public static final int HOLD = 14;
    public static final double RANGE = 64.0D;

    /** Where the nugget floats: a little ahead of you, off to the right and a bit low - on your screen, to the side. */
    private static Vec3 beside(AbilityContext c) {
        Vec3 look = c.owner.getLookAngle();
        Vec3 right = look.cross(new Vec3(0.0D, 1.0D, 0.0D));
        if (right.lengthSqr() < 1.0E-4D) right = new Vec3(1.0D, 0.0D, 0.0D);
        right = right.normalize();
        Vec3 up = right.cross(look).normalize();
        return c.owner.getEyePosition().add(look.scale(1.3D)).add(right.scale(0.55D)).add(up.scale(-0.3D)).subtract(0.0D, 0.06D, 0.0D);
    }

    public static boolean shoot(AbilityContext c) {
        // The nugget appears in the air beside you - off to the side of your view, where you can see it.
        Vec3 hand = beside(c);
        NuggetShotEntity shot = com.mobstands.registry.ModEntities.NUGGET_SHOT.get().create(c.level);
        if (shot == null) return false;
        shot.moveTo(hand.x, hand.y, hand.z, 0.0F, 0.0F);
        c.level.addFreshEntity(shot);
        c.sound(SoundEvents.CHAIN_PLACE, 0.8F, 1.6F);
        c.sound(SoundEvents.AMETHYST_BLOCK_CHIME, 1.0F, 1.4F);

        // The charge-up: a rising hum, and a sharp ring just before the flick.
        c.later(6, () -> c.sound(SoundEvents.BEACON_POWER_SELECT, 0.6F, 1.2F));
        c.later(HOLD - 4, () -> c.sound(SoundEvents.AMETHYST_BLOCK_RESONATE, 1.0F, 2.0F));
        // It hangs there beside you (keeping its place on your screen as you turn)...
        for (int t = 1; t < HOLD; t++) {
            c.later(t, () -> {
                if (shot.isRemoved()) return;
                Vec3 at = beside(c);
                shot.setPos(at.x, at.y, at.z);
            });
        }
        // ...then flies.
        c.later(HOLD, () -> {
            if (shot.isRemoved() || !c.owner.isAlive()) return;
            // Aim at the moment of the flick: the creature you're looking at, or whatever's in the way.
            Vec3 from = shot.position();
            // It's aimed at the creature you're looking at (or along your look) - and it doesn't stop there: it pierces
            // straight through everything in its path, out to its full range or the first wall.
            LivingEntity target = c.aim(RANGE);
            Vec3 aimAt = target != null ? target.position().add(0.0D, target.getBbHeight() * 0.6D, 0.0D)
                    : c.owner.getEyePosition().add(c.look().scale(RANGE));
            Vec3 dir = aimAt.subtract(from).normalize();
            Vec3 end = c.beamEnd(from, dir, RANGE);
            shot.fire(end);
            // Everything in the line of fire takes the full hit, and is hurled back along the shot.
            for (LivingEntity e : c.beam(from, dir, end.distanceTo(from), 0.5D)) {
                e.invulnerableTime = 0;
                c.hurt(e, c.dmg(22.0D));
                c.push(e, dir, 2.2D, 0.35D);
                // a burst of gold where it punches through each one
                Vec3 at = e.position().add(0.0D, e.getBbHeight() * 0.6D, 0.0D);
                c.level.sendParticles(ParticleTypes.CRIT, at.x, at.y, at.z, 20, 0.2D, 0.2D, 0.2D, 0.6D);
                c.level.sendParticles(new net.minecraft.core.particles.DustParticleOptions(new org.joml.Vector3f(1.0F, 0.83F, 0.27F), 1.4F),
                        at.x, at.y, at.z, 15, 0.25D, 0.25D, 0.25D, 0.0D);
                c.level.playSound(null, at.x, at.y, at.z, SoundEvents.ANVIL_LAND, net.minecraft.sounds.SoundSource.PLAYERS, 0.6F, 1.9F);
            }
            // The flick: a sonic crack that breaks the air, a thunderclap, and a shockwave ring off the fingers.
            c.sound(SoundEvents.TRIDENT_THUNDER.value(), 0.8F, 1.8F);
            c.sound(SoundEvents.WARDEN_SONIC_BOOM, 0.7F, 1.7F);
            c.sound(SoundEvents.FIREWORK_ROCKET_BLAST, 1.2F, 1.4F);
            c.sound(SoundEvents.ARROW_SHOOT, 1.0F, 2.0F);
            c.level.sendParticles(ParticleTypes.SONIC_BOOM, from.x, from.y, from.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
            // The impact: a blinding flash, a burst of gold and sparks, and a heavy, ringing slam.
            c.level.sendParticles(ParticleTypes.FLASH, end.x, end.y, end.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
            c.level.sendParticles(ParticleTypes.EXPLOSION, end.x, end.y, end.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
            c.level.sendParticles(ParticleTypes.END_ROD, end.x, end.y, end.z, 30, 0.2D, 0.2D, 0.2D, 0.3D);
            c.level.sendParticles(ParticleTypes.CRIT, end.x, end.y, end.z, 40, 0.3D, 0.3D, 0.3D, 0.7D);
            c.level.sendParticles(new net.minecraft.core.particles.DustParticleOptions(new org.joml.Vector3f(1.0F, 0.83F, 0.27F), 1.8F),
                    end.x, end.y, end.z, 40, 0.5D, 0.5D, 0.5D, 0.0D);
            c.level.playSound(null, end.x, end.y, end.z, SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 1.0F, 1.6F);
            c.level.playSound(null, end.x, end.y, end.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 0.8F, 1.5F);
            c.level.playSound(null, end.x, end.y, end.z, SoundEvents.BELL_BLOCK, SoundSource.PLAYERS, 1.2F, 0.6F);
        });
        return true;
    }
}
