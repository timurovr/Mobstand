package com.mobstands.stand;

import com.mobstands.entity.StandEntity;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Ender World's Time Stop - the real thing. It uses Minecraft's own game freeze (the same one as /tick freeze):
 * mobs, arrows, items, TNT, falling blocks, fire, water, crops... the whole world stops ticking, exactly where it is,
 * on the server and on everyone's screen. Only you, your Stand and anything you're riding keep moving. Other
 * players caught nearby are held in place. Damage dealt to frozen things piles up and lands all at once when time
 * starts again.
 */
public final class TimeStop {
    private TimeStop() {}

    public static final double RADIUS = 40.0D;
    /** Time is stopped right now (server side / on this client). Read by the game-freeze mixin. */
    public static volatile boolean serverStopped, clientStopped;

    private static final class Zone {
        final UUID owner;
        final ServerLevel level;
        final Vec3 center;
        final int length;
        int elapsed;
        final Map<ServerPlayer, Vec3> heldPlayers = new HashMap<>();
        final Map<LivingEntity, Float> pending = new HashMap<>();
        final Map<LivingEntity, DamageSource> lastSource = new HashMap<>();

        Zone(UUID owner, ServerLevel level, Vec3 center, int length) {
            this.owner = owner;
            this.level = level;
            this.center = center;
            this.length = length;
        }
    }

    private static final List<Zone> ZONES = new ArrayList<>();
    private static boolean releasing;
    /** Whether the game was already frozen (someone used /tick freeze) before time was stopped. */
    private static boolean wasFrozenBefore;

    /** 4 seconds at level 1, growing to 9 seconds. */
    public static int durationFor(int level) {
        return 80 + Math.min(100, Math.max(0, level - 1) * 11);
    }

    public static boolean active(ServerPlayer p) {
        for (Zone z : ZONES) if (z.owner.equals(p.getUUID())) return true;
        return false;
    }

    public static boolean start(ServerPlayer p, int ticks) {
        if (active(p)) return false;
        ServerLevel level = (ServerLevel) p.level();
        if (ZONES.isEmpty()) {
            wasFrozenBefore = level.getServer().tickRateManager().isFrozen();
            serverStopped = true;
            level.getServer().tickRateManager().setFrozen(true);
        }
        long now = level.getGameTime();
        ZONES.add(new Zone(p.getUUID(), level, p.position(), ticks));
        StandData d = StandManager.data(p);
        d.timeStopStart = now;           // identifies this stop (the game clock itself stops now)
        d.timeStopEnd = now + ticks;     // start + how long it lasts
        StandManager.sync(p);
        // "THE WORLD!": a deep bell, the world draining of colour, a shockwave of light rolling outward.
        // A horn call over the top of it ("Call" goat horn, a touch deeper).
        level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.GOAT_HORN_SOUND_VARIANTS.get(5).value(), SoundSource.PLAYERS, 1.6F, 0.85F);
        level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BELL_BLOCK, SoundSource.PLAYERS, 2.0F, 0.5F);
        level.playSound(null, p.getX(), p.getY(), p.getZ(), com.mobstands.registry.ModSounds.TIME_STOP.get(), SoundSource.PLAYERS, 2.0F, 1.0F);
        level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 1.5F, 0.5F);
        level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.PLAYERS, 0.8F, 1.6F);
        return true;
    }

    public static void ring(ServerLevel level, Vec3 c, double radius) {
        int n = (int) (radius * 10);
        for (int i = 0; i < n; i++) {
            double a = i * Math.PI * 2.0D / n;
            level.sendParticles(ParticleTypes.END_ROD, c.x + Math.cos(a) * radius, c.y, c.z + Math.sin(a) * radius, 1, 0.0D, 0.15D, 0.0D, 0.0D);
        }
    }

    /**
     * Called by the game-freeze mixin, on both sides: what keeps moving in stopped time. Players and anything a
     * player rides already do (vanilla); on top of that, players' Stands.
     */
    public static boolean keepsMoving(Entity e) {
        if (!(e.level().isClientSide ? clientStopped : serverStopped)) return false;
        if (e instanceof StandEntity s) return !s.isMobStand();
        return false;
    }

    /**
     * Something you shoot or throw in stopped time stops dead the moment it leaves your hand - a little way out in
     * front of you (so it's not stuck in your face), pointing where you aimed, and it flies on when time moves again.
     */
    public static void onShot(Entity e) {
        if (!serverStopped) return;
        boolean byPlayer = e instanceof Projectile pr && pr.getOwner() instanceof net.minecraft.world.entity.player.Player;
        if (!byPlayer && !(e instanceof com.mobstands.entity.SpinItemEntity)) return;
        Vec3 v = e.getDeltaMovement();
        if (v.lengthSqr() < 1.0E-4D) return;
        Vec3 out = e.position().add(v.normalize().scale(1.6D));
        e.setPos(out.x, out.y, out.z);
    }

    public static boolean isFrozen(Entity e) {
        for (Zone z : ZONES) {
            if (z.level != e.level()) continue;
            if (e instanceof ServerPlayer sp) {
                if (z.heldPlayers.containsKey(sp)) return true;
            } else if (z.level.tickRateManager().isEntityFrozen(e)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Frozen things can't hurt anyone: touching a frozen slime or magma cube, standing next to a frozen mob,
     * walking into a frozen arrow - none of it does damage while time is stopped. True = cancel the hit.
     */
    public static boolean fromFrozen(LivingEntity victim, DamageSource src) {
        if (!serverStopped || ZONES.isEmpty()) return false;
        Entity direct = src.getDirectEntity(), cause = src.getEntity();
        if (direct != null && direct != victim && isFrozen(direct)) return true;
        return cause != null && cause != victim && isFrozen(cause);
    }

    /** Called from the damage event: a hit on a frozen enemy is stored instead of landing. True = cancel it. */
    public static boolean absorb(LivingEntity e, DamageSource src, float amount) {
        if (releasing) return false;
        for (Zone z : ZONES) {
            if (z.level == e.level() && isFrozen(e)) {
                z.pending.merge(e, amount, Float::sum);
                z.lastSource.put(e, src);
                z.level.sendParticles(ParticleTypes.CRIT, e.getX(), e.getY() + e.getBbHeight() * 0.6D, e.getZ(), 4, 0.2D, 0.2D, 0.2D, 0.0D);
                return true;
            }
        }
        return false;
    }

    /** Server tick when the game clock was last nudged on (once per tick, whichever level ticks first). */
    private static int lastClockTick = -1;

    public static void tick(ServerLevel level) {
        if (ZONES.isEmpty()) return;
        // The freeze stops the game clock too - and everything that runs on it (your Stand's floating and punching,
        // ability cooldowns) would stall and stutter. Keep the clock going; the sun and moon stay put (the time of
        // day isn't touched), and everything frozen stays frozen.
        if (serverStopped && level.getServer().getTickCount() != lastClockTick && !level.tickRateManager().runsNormally()) {
            lastClockTick = level.getServer().getTickCount();
            var data = level.getServer().getWorldData().overworldData();
            data.setGameTime(data.getGameTime() + 1L);
        }
        Iterator<Zone> it = ZONES.iterator();
        while (it.hasNext()) {
            Zone z = it.next();
            if (z.level != level) continue;
            ServerPlayer owner = level.getServer().getPlayerList().getPlayer(z.owner);
            z.elapsed++;
            if (z.elapsed >= z.length || owner == null || !owner.isAlive() || owner.level() != level) {
                it.remove();
                release(z, owner);
                continue;
            }
            holdPlayers(z, owner);
        }
    }

    /** Other players are still "ticking" in a frozen game: hold anyone near you exactly where they were. */
    private static void holdPlayers(Zone z, ServerPlayer owner) {
        for (ServerPlayer sp : z.level.players()) {
            if (sp == owner || sp.isSpectator() || sp.isCreative() || ZONES.stream().anyMatch(o -> o.owner.equals(sp.getUUID()))) continue;
            if (!z.heldPlayers.containsKey(sp) && sp.position().distanceToSqr(z.center) <= RADIUS * RADIUS) z.heldPlayers.put(sp, sp.position());
        }
        for (Map.Entry<ServerPlayer, Vec3> en : z.heldPlayers.entrySet()) {
            ServerPlayer sp = en.getKey();
            Vec3 at = en.getValue();
            if (sp.isRemoved() || sp.level() != z.level) continue;
            sp.setDeltaMovement(Vec3.ZERO);
            if (sp.position().distanceToSqr(at) > 0.0025D) sp.connection.teleport(at.x, at.y, at.z, sp.getYRot(), sp.getXRot());
        }
        // Tick... tock... (faster in the last second)
        int t = z.elapsed;
        int left = z.length - t;
        if ((left <= 20 ? t % 5 == 0 : t % 10 == 0) && t > 6) {
            z.level.playSound(null, owner.getX(), owner.getY(), owner.getZ(), SoundEvents.NOTE_BLOCK_HAT.value(), SoundSource.PLAYERS,
                    0.9F, (t / (left <= 20 ? 5 : 10)) % 2 == 0 ? 1.9F : 1.5F);
        }
    }

    /** Time moves again: everything resumes exactly where it was, and the stored damage all lands at once. */
    private static void release(Zone z, ServerPlayer owner) {
        if (ZONES.isEmpty()) {
            serverStopped = false;
            if (!wasFrozenBefore) z.level.getServer().tickRateManager().setFrozen(false);
        }
        if (owner != null) {
            StandData d = StandManager.data(owner);
            d.timeStopEnd = d.timeStopStart + z.elapsed; // how long it actually lasted
            StandManager.sync(owner);
            z.level.playSound(null, owner.getX(), owner.getY(), owner.getZ(), SoundEvents.BELL_RESONATE, SoundSource.PLAYERS, 1.5F, 1.4F);
            z.level.playSound(null, owner.getX(), owner.getY(), owner.getZ(), SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 1.2F, 1.6F);
        }
        releasing = true;
        try {
            for (Map.Entry<LivingEntity, Float> en : z.pending.entrySet()) {
                LivingEntity e = en.getKey();
                if (e.isRemoved() || !e.isAlive()) continue;
                e.invulnerableTime = 0;
                DamageSource src = z.lastSource.getOrDefault(e, z.level.damageSources().generic());
                e.hurt(src, en.getValue());
                z.level.sendParticles(ParticleTypes.EXPLOSION, e.getX(), e.getY() + e.getBbHeight() * 0.5D, e.getZ(), 1, 0.0D, 0.0D, 0.0D, 0.0D);
                z.level.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1.0F, 0.8F);
            }
        } finally {
            releasing = false;
        }
    }

    public static void clear() {
        ZONES.clear();
        serverStopped = false;
    }
}
