package com.mobstands.stand;

import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Iron Golem: Iron Launch. Your Golem cups its hand under you and hurls you into the sky. Coming down, you're a mace:
 * hit someone on the way down (or land next to them) and it's a mace smash - the further you fell, the harder it hits,
 * and everything around the impact is thrown back. You take no fall damage from it.
 */
public final class GolemLaunch {
    private GolemLaunch() {}

    /** How long after the launch you still count as "the mace" (game time it ends), per player. */
    private static final Map<UUID, Long> ARMED = new HashMap<>();
    /** Must fall at least this far for the smash (like the real mace). */
    private static final float MIN_FALL = 1.5F;

    public static boolean launch(AbilityContext c) {
        ServerPlayer p = c.owner;
        // The Golem steps under you and heaves you up with its hand.
        c.stand.lunge(p.position().add(0.0D, 0.2D, 0.0D), 4);
        c.sound(SoundEvents.IRON_GOLEM_ATTACK, 1.2F, 0.8F);
        c.later(3, () -> {
            Vec3 v = p.getDeltaMovement();
            Vec3 look = c.flatLook();
            p.setDeltaMovement(v.x + look.x * 0.2D, 1.75D, v.z + look.z * 0.2D); // about 12 blocks up
            p.hurtMarked = true;
            p.fallDistance = 0.0F;
            ARMED.put(p.getUUID(), c.level.getGameTime() + 200L);
            c.sound(SoundEvents.PLAYER_ATTACK_KNOCKBACK, 1.2F, 0.6F);
            c.fx(ParticleTypes.CLOUD, p.position(), 20, 0.5D, 0.08D);
        });
        return true;
    }

    private static boolean armed(ServerPlayer p) {
        Long until = ARMED.get(p.getUUID());
        if (until == null) return false;
        if (p.level().getGameTime() > until || p.isInWater() || p.getAbilities().flying || p.isFallFlying()) {
            ARMED.remove(p.getUUID());
            return false;
        }
        return true;
    }

    /** Vanilla mace damage from a fall: +4 a block for the first 3, +2 for the next 5, +1 for every block after. */
    private static float maceBonus(float fall) {
        float a = Math.min(fall, 3.0F), b = Math.max(0.0F, Math.min(fall, 8.0F) - 3.0F), r = Math.max(0.0F, fall - 8.0F);
        return a * 4.0F + b * 2.0F + r;
    }

    /** You hit someone on the way down: mace smash on them. Returns true if it happened. */
    public static boolean onAttack(ServerPlayer p, LivingEntity target) {
        if (!armed(p) || p.fallDistance < MIN_FALL || p.onGround()) return false;
        smash(p, target, target.position());
        // Like the real mace: you bounce off them, and the fall is spent.
        p.setDeltaMovement(p.getDeltaMovement().x, 0.6D, p.getDeltaMovement().z);
        p.hurtMarked = true;
        p.fallDistance = 0.0F;
        ARMED.remove(p.getUUID());
        return true;
    }

    /** You hit the ground: no fall damage, and if you fell far enough, a ground smash around you. */
    public static boolean onLand(ServerPlayer p, float distance) {
        if (!armed(p)) return false;
        ARMED.remove(p.getUUID());
        if (distance >= MIN_FALL) {
            p.fallDistance = distance;
            smash(p, null, p.position());
        }
        return true;
    }

    private static void smash(ServerPlayer p, LivingEntity direct, Vec3 at) {
        ServerLevel sl = p.serverLevel();
        StandData d = StandManager.data(p);
        AbilityContext c = new AbilityContext(p, StandManager.getStand(p), sl, d.getType(), d);
        float bonus = maceBonus(p.fallDistance);
        // The one you hit takes the whole mace blow; everyone around the impact takes a share and is flung away.
        if (direct != null && StandCombat.valid(p, direct)) {
            direct.invulnerableTime = 0;
            c.hurt(direct, 6.0F + bonus);
        }
        for (LivingEntity e : c.enemies(at.add(0.0D, 0.5D, 0.0D), 3.5D)) {
            if (e == direct) continue;
            Vec3 away = e.position().subtract(at);
            double near = 1.0D - Math.min(1.0D, away.length() / 3.5D);
            if (direct == null) {
                e.invulnerableTime = 0;
                c.hurt(e, (float) ((4.0F + bonus * 0.6F) * (0.5D + 0.5D * near)));
            }
            c.push(e, away, 0.9D + near * 1.2D, 0.5D + near * 0.3D);
        }
        boolean heavy = p.fallDistance > 5.0F;
        sl.playSound(null, at.x, at.y, at.z, heavy ? SoundEvents.MACE_SMASH_GROUND_HEAVY : SoundEvents.MACE_SMASH_GROUND,
                SoundSource.PLAYERS, 1.2F, 1.0F);
        sl.playSound(null, at.x, at.y, at.z, SoundEvents.IRON_GOLEM_DAMAGE, SoundSource.PLAYERS, 0.8F, 0.7F);
        BlockState ground = sl.getBlockState(net.minecraft.core.BlockPos.containing(at.x, at.y - 0.5D, at.z));
        if (!ground.isAir()) {
            sl.sendParticles(new BlockParticleOption(ParticleTypes.DUST_PILLAR, ground), at.x, at.y, at.z, 60, 1.5D, 0.1D, 1.5D, 0.2D);
        }
        sl.sendParticles(ParticleTypes.CRIT, at.x, at.y + 0.5D, at.z, 30, 1.0D, 0.4D, 1.0D, 0.3D);
        sl.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y + 0.3D, at.z, heavy ? 3 : 1, 0.6D, 0.1D, 0.6D, 0.0D);
        p.fallDistance = 0.0F;
    }

    public static void clear() {
        ARMED.clear();
    }
}
