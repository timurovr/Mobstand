package com.mobstands.stand;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Deep Dark Monarch: Stop Heart - like Jotaro against DIO. Your Stand reaches into your chest and stops your heart:
 * to everything around you, you're dead. Mobs hunting you lose interest and nothing new comes for you (Wardens
 * forget you too), and everyone sees you die in chat. The first 5 seconds are free - after that, a body with no
 * heartbeat fails fast: a heavy hit every second, a quarter of your health each, and it can really kill you. Press it again, strike
 * something, or run out of time and your heart restarts with a huge THUMP and a rush of adrenaline.
 */
public final class HeartStop {
    private HeartStop() {}

    /** Seconds of stopped heart before it starts to hurt, and the most it can last (ticks). */
    public static final int FREE_TICKS = 100, MAX_TICKS = 600;

    private static final Map<UUID, Long> STOPPED = new HashMap<>();

    public static boolean stopped(LivingEntity e) {
        return e instanceof ServerPlayer && STOPPED.containsKey(e.getUUID());
    }

    public static boolean use(AbilityContext c) {
        ServerPlayer p = c.owner;
        ServerLevel sl = c.level;
        long now = sl.getGameTime();
        STOPPED.put(p.getUUID(), now);
        c.data.heartStopAt = now;
        c.data.heartStopEnd = now + MAX_TICKS;
        StandManager.sync(p);
        // The Stand's hand closes round your heart... and it stops.
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.WARDEN_HEARTBEAT, SoundSource.PLAYERS, 1.6F, 0.6F);
        com.mobstands.stand.TickScheduler.later(10, () -> sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.WARDEN_HEARTBEAT, SoundSource.PLAYERS, 0.9F, 0.45F));
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.SCULK_CLICKING_STOP, SoundSource.PLAYERS, 1.0F, 0.5F);
        sl.sendParticles(ParticleTypes.SCULK_SOUL, p.getX(), p.getY() + 1.0D, p.getZ(), 12, 0.3D, 0.4D, 0.3D, 0.02D);
        // Everyone sees you die.
        Component msg = Component.translatable("death.attack.generic", p.getDisplayName());
        for (ServerPlayer other : sl.getServer().getPlayerList().getPlayers()) other.sendSystemMessage(msg);
        loseThem(p);
        return true;
    }

    /** Your heart starts again: a huge thump, a shockwave of sculk, and a rush of adrenaline. */
    public static void restart(ServerPlayer p) {
        Long at = STOPPED.remove(p.getUUID());
        if (at == null) return;
        ServerLevel sl = (ServerLevel) p.level();
        StandData d = StandManager.data(p);
        d.heartStopEnd = sl.getGameTime();
        StandManager.sync(p);
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.WARDEN_HEARTBEAT, SoundSource.PLAYERS, 2.5F, 0.8F);
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, 0.5F, 1.6F);
        com.mobstands.stand.TickScheduler.later(8, () -> sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.WARDEN_HEARTBEAT, SoundSource.PLAYERS, 1.8F, 1.0F));
        sl.sendParticles(ParticleTypes.SONIC_BOOM, p.getX(), p.getY() + 1.0D, p.getZ(), 1, 0.0D, 0.0D, 0.0D, 0.0D);
        sl.sendParticles(ParticleTypes.SCULK_CHARGE_POP, p.getX(), p.getY() + 0.3D, p.getZ(), 40, 1.5D, 0.1D, 1.5D, 0.0D);
        p.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, 100, 0));
        p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 100, 0));
    }

    public static void tick(MinecraftServer server) {
        if (STOPPED.isEmpty()) return;
        Iterator<Map.Entry<UUID, Long>> it = STOPPED.entrySet().iterator();
        java.util.List<ServerPlayer> restart = new java.util.ArrayList<>();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> en = it.next();
            ServerPlayer p = server.getPlayerList().getPlayer(en.getKey());
            if (p == null || !p.isAlive()) {
                it.remove();
                if (p != null) {
                    // Died with it stopped: clear it (so it isn't still showing after you respawn).
                    StandData d = StandManager.data(p);
                    d.heartStopAt = -1000L;
                    d.heartStopEnd = -1000L;
                    StandManager.sync(p);
                }
                continue;
            }
            long t = p.level().getGameTime() - en.getValue();
            if (t >= MAX_TICKS) {
                restart.add(p);
                continue;
            }
            if (t % 5L == 0L) loseThem(p);
            // A body with no heartbeat fails fast: once the free seconds are up, a heavy hit every second, each a
            // quarter of your health - about four and you're dead for real.
            if (t >= FREE_TICKS && (t - FREE_TICKS) % 20L == 0L) {
                p.hurt(p.damageSources().magic(), p.getMaxHealth() * 0.25F);
                p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.WARDEN_HEARTBEAT, SoundSource.PLAYERS, 1.2F, 0.4F);
                ((ServerLevel) p.level()).sendParticles(ParticleTypes.DAMAGE_INDICATOR, p.getX(), p.getY() + 1.2D, p.getZ(), 3, 0.2D, 0.2D, 0.2D, 0.0D);
            }
        }
        for (ServerPlayer p : restart) restart(p);
    }

    /** Everything hunting you loses interest (Wardens forget you too). */
    private static void loseThem(ServerPlayer p) {
        AABB box = p.getBoundingBox().inflate(48.0D);
        for (Mob m : p.level().getEntitiesOfClass(Mob.class, box, m -> m.getTarget() == p)) {
            m.setTarget(null);
            if (m instanceof net.minecraft.world.entity.monster.warden.Warden w) w.clearAnger(p);
        }
    }

    /** Leaving the game or dying: the heart's going again (for next time). */
    public static void clear(ServerPlayer p) {
        STOPPED.remove(p.getUUID());
    }
}
