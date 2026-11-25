package com.mobstands.stand;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Allay: Slam. While you're dragging a held enemy around (hold X on it), swing your view down hard and your Allays
 * let go and hurl it at the ground. It hits hard, and everyone around the impact - mobs and other players alike -
 * takes damage and gets thrown up.
 */
public final class AllaySlam {
    private AllaySlam() {}

    /** How fast you have to swing your view down (degrees in one tick) for it to count as a slam. */
    public static final float SWING = 11.0F;
    public static final double RADIUS = 3.5D;

    private static final class Slam {
        LivingEntity body;
        UUID owner;
        int allays;
        int age;
        double fromY;
    }

    private static final List<Slam> SLAMS = new ArrayList<>();

    /** Called each tick while dragging: true if this swing is a slam (and it's been thrown). */
    public static boolean check(ServerPlayer p, StandData d, LivingEntity held, int allays) {
        float pitch = p.getXRot();
        float swing = pitch - d.allayLastPitch;
        d.allayLastPitch = pitch;
        if (swing < SWING || pitch < 15.0F) return false;
        Slam s = new Slam();
        s.body = held;
        s.owner = p.getUUID();
        s.allays = Math.max(1, allays);
        s.fromY = held.getY();
        SLAMS.add(s);
        // Hurled straight down - and a little the way you're facing.
        Vec3 f = p.getLookAngle();
        held.setDeltaMovement(f.x * 0.4D, -2.6D, f.z * 0.4D);
        held.hurtMarked = true;
        ServerLevel sl = p.serverLevel();
        sl.playSound(null, held.getX(), held.getY(), held.getZ(), SoundEvents.ALLAY_THROW, SoundSource.PLAYERS, 1.2F, 0.7F);
        sl.playSound(null, held.getX(), held.getY(), held.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.0F, 0.6F);
        return true;
    }

    public static void tick(ServerLevel sl) {
        if (SLAMS.isEmpty()) return;
        Iterator<Slam> it = SLAMS.iterator();
        while (it.hasNext()) {
            Slam s = it.next();
            LivingEntity b = s.body;
            if (b.level() != sl) continue;
            if (!b.isAlive() || ++s.age > 60) {
                it.remove();
                continue;
            }
            // Keep it driving down until it hits.
            if (!b.onGround()) {
                Vec3 v = b.getDeltaMovement();
                if (v.y > -2.0D) {
                    b.setDeltaMovement(v.x, -2.0D, v.z);
                    b.hurtMarked = true;
                }
                if (s.age % 2 == 0) sl.sendParticles(ParticleTypes.CLOUD, b.getX(), b.getY() + b.getBbHeight(), b.getZ(), 2, 0.1D, 0.1D, 0.1D, 0.0D);
                continue;
            }
            it.remove();
            impact(sl, s, b);
        }
    }

    private static void impact(ServerLevel sl, Slam s, LivingEntity b) {
        ServerPlayer owner = sl.getServer().getPlayerList().getPlayer(s.owner);
        Vec3 at = b.position();
        double drop = Math.max(0.0D, s.fromY - at.y);
        b.resetFallDistance(); // the slam is the damage, not a fall on top of it
        if (owner != null) {
            StandData d = StandManager.data(owner);
            AbilityContext c = new AbilityContext(owner, StandManager.getStand(owner), sl, d.getType(), d);
            // The one slammed: harder the further it fell and the more Allays were holding it.
            float hit = c.dmg(5.0D + Math.min(10.0D, drop) * 0.6D + s.allays * 1.0D);
            b.invulnerableTime = 0;
            if (b != owner) c.hurt(b, hit);
            // Everyone around the impact: mobs and other players (never you, your pets, or anyone PvP protects).
            for (LivingEntity e : c.enemies(at.add(0.0D, 0.5D, 0.0D), RADIUS)) {
                if (e == b) continue;
                double near = 1.0D - Math.min(1.0D, e.distanceTo(b) / RADIUS);
                e.invulnerableTime = 0;
                c.hurt(e, (float) (hit * (0.4D + 0.4D * near)));
                c.push(e, e.position().subtract(at), 0.6D + 0.6D * near, 0.5D + 0.3D * near);
            }
        }
        BlockState ground = sl.getBlockState(BlockPos.containing(at.x, at.y - 0.2D, at.z));
        if (!ground.isAir()) {
            sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground), at.x, at.y + 0.1D, at.z, 40, 1.2D, 0.1D, 1.2D, 0.2D);
        }
        sl.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y + 0.3D, at.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        sl.sendParticles(ParticleTypes.CLOUD, at.x, at.y + 0.2D, at.z, 20, 1.0D, 0.1D, 1.0D, 0.05D);
        sl.playSound(null, at.x, at.y, at.z, SoundEvents.MACE_SMASH_GROUND, SoundSource.PLAYERS, 1.2F, 0.9F);
        sl.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_BIG_FALL, SoundSource.PLAYERS, 1.0F, 0.8F);
    }

    public static void clear() {
        SLAMS.clear();
    }
}
