package com.mobstands.stand;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Weaver of Webs: the Möbius Strip - Stone Free's infinite string. Not a key you press: the string learns it when
 * you're nearly killed. The first time a hit would take you below a third of your health, your body's string twists
 * into a loop with only one side. From then on it happens on its own whenever you're badly hurt.
 *
 * For 20 seconds: a strip with one side has no inside and no outside, so nothing can turn you around - gravity
 * flips (Crescent Shell) slide off and nothing knocks you back. And a hit that lands on you has nowhere to stop:
 * most of it runs round the loop and comes out again into whoever threw it.
 */
public final class Mobius {
    private Mobius() {}

    public static final int TICKS = 20 * 20;
    public static final int COOLDOWN = 20 * 120;
    /** Below this share of your health, the loop closes. */
    private static final float THRESHOLD = 0.35F;
    /** How much of a hit runs round the strip and back into the attacker (you take the rest). */
    private static final float RETURNED = 0.6F;

    private static final Map<UUID, Long> ACTIVE = new HashMap<>();
    private static final Map<UUID, Long> READY_AT = new HashMap<>();
    private static final DustParticleOptions STRING = new DustParticleOptions(new org.joml.Vector3f(0.95F, 0.95F, 1.0F), 0.6F);
    private static final DustParticleOptions STRING_RED = new DustParticleOptions(new org.joml.Vector3f(0.9F, 0.3F, 0.3F), 0.7F);

    public static boolean active(Entity e) {
        return e != null && !ACTIVE.isEmpty() && ACTIVE.containsKey(e.getUUID());
    }

    /** A hit is about to land on a Weaver of Webs user: maybe the loop closes, and if it's closed, the hit goes round it. */
    public static float onHurt(ServerPlayer p, DamageSource src, float amount) {
        if (amount <= 0.0F) return amount;
        StandData d = StandManager.data(p);
        if (d.getType() != StandType.SPIDER) return amount;
        if (src.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)) return amount; // /kill, the void
        long now = p.level().getGameTime();
        boolean awakening = false;
        if (!active(p)) {
            if (p.getHealth() - amount > p.getMaxHealth() * THRESHOLD) return amount;
            if (now < READY_AT.getOrDefault(p.getUUID(), 0L)) return amount;
            awaken(p, d, now);
            awakening = true;
        }
        float kept;
        Entity att = src.getEntity();
        if (att instanceof LivingEntity le && le != p && le.isAlive()) {
            float back = amount * RETURNED;
            kept = amount - back;
            ServerLevel sl = p.serverLevel();
            Vec3 a = p.position().add(0.0D, p.getBbHeight() * 0.5D, 0.0D);
            Vec3 b = le.position().add(0.0D, le.getBbHeight() * 0.5D, 0.0D);
            int n = (int) Math.min(48, a.distanceTo(b) / 0.4D);
            for (int i = 0; i <= n; i++) {
                Vec3 at = a.add(b.subtract(a).scale(i / (double) Math.max(1, n)));
                sl.sendParticles(STRING_RED, at.x, at.y, at.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
            }
            // next tick, so it never loops straight back into another loop
            TickScheduler.later(1, () -> {
                if (!le.isAlive()) return;
                StandCombat.credit(p, le);
                le.hurt(p.damageSources().thorns(p), back);
            });
            sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.SPIDER_HURT, SoundSource.PLAYERS, 0.6F, 1.6F);
        } else {
            kept = amount * 0.5F;
        }
        // the hit that closed the loop never finishes you
        if (awakening) kept = Math.min(kept, Math.max(0.0F, p.getHealth() - 1.0F));
        return kept;
    }

    private static void awaken(ServerPlayer p, StandData d, long now) {
        ACTIVE.put(p.getUUID(), now + TICKS);
        READY_AT.put(p.getUUID(), now + TICKS + COOLDOWN);
        if (GravityFlip.isFlipped(p)) GravityFlip.release(p); // nothing can stay turned around
        ServerLevel sl = p.serverLevel();
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 1.5F, 0.6F);
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.SPIDER_AMBIENT, SoundSource.PLAYERS, 1.0F, 0.5F);
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 1.0F, 1.4F);
        if (!d.mobiusLearned) {
            d.mobiusLearned = true;
            p.sendSystemMessage(Component.literal("Your string twists into a loop with only one side. It has no inside, and no outside... ")
                    .withStyle(s -> s.withColor(0xC0392B).withItalic(true))
                    .append(Component.literal("Möbius Strip.").withStyle(s -> s.withColor(0xFFFFFF).withItalic(false).withBold(true))));
        } else {
            p.displayClientMessage(Component.literal("Möbius Strip").withStyle(s -> s.withColor(0xFFFFFF).withItalic(true)), true);
        }
    }

    public static void tick(MinecraftServer server) {
        if (ACTIVE.isEmpty()) return;
        Iterator<Map.Entry<UUID, Long>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> en = it.next();
            ServerPlayer p = server.getPlayerList().getPlayer(en.getKey());
            if (p == null || !p.isAlive()) {
                it.remove();
                continue;
            }
            ServerLevel sl = p.serverLevel();
            long now = sl.getGameTime();
            if (now > en.getValue() || StandManager.data(p).getType() != StandType.SPIDER) {
                it.remove();
                sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.WOOL_BREAK, SoundSource.PLAYERS, 1.0F, 0.7F);
                continue;
            }
            if (now % 2L != 0L) continue;
            // a figure-eight of string turning slowly around your waist
            double spin = now * 0.06D;
            double cy = p.getY() + p.getBbHeight() * 0.55D;
            for (int i = 0; i < 28; i++) {
                double t = (Math.PI * 2.0D) * i / 28.0D;
                double s = Math.sin(t), c = Math.cos(t), k = 1.0D + s * s;
                double lx = 1.1D * c / k, lz = 1.1D * s * c / k;
                double x = lx * Math.cos(spin) - lz * Math.sin(spin);
                double z = lx * Math.sin(spin) + lz * Math.cos(spin);
                double y = 0.25D * Math.sin(t * 2.0D + spin * 3.0D); // the half-twist
                sl.sendParticles(STRING, p.getX() + x, cy + y, p.getZ() + z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
            }
        }
    }

    public static void logout(ServerPlayer p) {
        ACTIVE.remove(p.getUUID());
    }

    public static void shutdown() {
        ACTIVE.clear();
        READY_AT.clear();
    }
}
