package com.mobstands.stand;

import com.mobstands.entity.StandEntity;
import com.mobstands.network.ClashPayload;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Barrage Clash: two Stand users throw a Barrage at each other at the same time - the fists meet in the middle and it
 * becomes a mashing contest. Each press of the Barrage key pushes the clash point toward the other one. Push it all
 * the way, or be ahead when time runs out, and your Stand breaks through: the loser eats the finishing blow.
 */
public final class Clash {
    private Clash() {}

    /** How long a clash can last, how far ahead you must get to break through early, how close the two must be. */
    public static final int DURATION = 100, WIN_LEAD = 30;
    public static final double RANGE = 7.0D, BREAK_RANGE = 10.0D;
    /** How recently the other one must have started their Barrage for yours to clash with it. */
    private static final long CLASH_WINDOW = 24L;

    private static final class Fight {
        final ServerLevel level;
        final UUID a, b;
        int pressA, pressB;
        long lastA = -1L, lastB = -1L;
        int ticks;

        Fight(ServerLevel level, UUID a, UUID b) {
            this.level = level;
            this.a = a;
            this.b = b;
        }

        int lead() {
            return pressA - pressB;
        }
    }

    private static final Map<UUID, Long> BARRAGE_AT = new HashMap<>();
    private static final Map<UUID, Fight> FIGHTS = new HashMap<>();
    private static final List<Fight> ACTIVE = new ArrayList<>();

    public static boolean inClash(ServerPlayer p) {
        return FIGHTS.containsKey(p.getUUID());
    }

    /**
     * A Barrage is starting. If someone in front of you, facing you, threw theirs a moment ago, the fists meet: the
     * clash begins and true is returned (no normal barrage). Otherwise it's remembered, so theirs can meet yours.
     */
    public static boolean tryStart(AbilityContext c) {
        ServerPlayer p = c.owner;
        ServerLevel sl = c.level;
        long now = sl.getGameTime();
        if (inClash(p)) return true;
        ServerPlayer rival = null;
        double best = RANGE * RANGE;
        Vec3 look = flat(p.getLookAngle());
        for (ServerPlayer o : sl.players()) {
            if (o == p || !o.isAlive() || o.isSpectator() || inClash(o)) continue;
            Long at = BARRAGE_AT.get(o.getUUID());
            if (at == null || now - at > CLASH_WINDOW) continue;
            double d = o.distanceToSqr(p);
            if (d > best) continue;
            Vec3 to = flat(o.position().subtract(p.position()));
            // Face to face: you're looking at them and they're looking at you.
            if (look.dot(to) < 0.5D || flat(o.getLookAngle()).dot(to.scale(-1.0D)) < 0.5D) continue;
            StandData od = StandManager.data(o);
            if (od.getType() == null || !od.getType().barrage() || !od.isSummoned() || StandManager.getStand(o) == null) continue;
            best = d;
            rival = o;
        }
        BARRAGE_AT.put(p.getUUID(), now);
        if (BARRAGE_AT.size() > 256) BARRAGE_AT.clear();
        if (rival == null) return false;
        Fight f = new Fight(sl, rival.getUUID(), p.getUUID());
        FIGHTS.put(f.a, f);
        FIGHTS.put(f.b, f);
        ACTIVE.add(f);
        Vec3 mid = mid(rival, p);
        sl.sendParticles(ParticleTypes.FLASH, mid.x, mid.y, mid.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        sl.sendParticles(ParticleTypes.EXPLOSION, mid.x, mid.y, mid.z, 2, 0.2D, 0.2D, 0.2D, 0.0D);
        sl.playSound(null, mid.x, mid.y, mid.z, SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 1.0F, 0.6F);
        sl.playSound(null, mid.x, mid.y, mid.z, SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.PLAYERS, 1.5F, 0.5F);
        send(f);
        return true;
    }

    /** One press of the Barrage key during a clash (at most one counted per tick - no macros). */
    public static void press(ServerPlayer p) {
        Fight f = FIGHTS.get(p.getUUID());
        if (f == null) return;
        long now = f.level.getGameTime();
        if (p.getUUID().equals(f.a)) {
            if (f.lastA == now) return;
            f.lastA = now;
            f.pressA++;
        } else {
            if (f.lastB == now) return;
            f.lastB = now;
            f.pressB++;
        }
    }

    public static void tick(MinecraftServer server) {
        if (ACTIVE.isEmpty()) return;
        for (Fight f : new ArrayList<>(ACTIVE)) {
            ServerPlayer a = server.getPlayerList().getPlayer(f.a);
            ServerPlayer b = server.getPlayerList().getPlayer(f.b);
            StandEntity sa = a == null ? null : StandManager.getStand(a);
            StandEntity sb = b == null ? null : StandManager.getStand(b);
            if (a == null || b == null || !a.isAlive() || !b.isAlive() || a.level() != f.level || b.level() != f.level
                    || sa == null || sb == null || a.distanceToSqr(b) > BREAK_RANGE * BREAK_RANGE) {
                end(f, a, b, 0, false);
                continue;
            }
            f.ticks++;
            ServerLevel sl = f.level;
            // The clash point slides toward whoever is losing.
            Vec3 ab = flat(b.position().subtract(a.position()));
            double push = Math.max(-1.0D, Math.min(1.0D, f.lead() / (double) WIN_LEAD));
            Vec3 point = mid(a, b).add(ab.scale(push * a.distanceTo(b) * 0.3D));
            sa.lunge(point.subtract(ab.scale(0.7D)), 3);
            sb.lunge(point.add(ab.scale(0.7D)), 3);
            if (f.ticks % StandAbilities.BARRAGE_TICKS == 1) {
                sa.playAnim(1);
                sb.playAnim(1);
            }
            // Neither of you is going anywhere while it lasts.
            for (ServerPlayer p : new ServerPlayer[]{a, b}) {
                p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 6, 5, false, false));
            }
            // Fists colliding: sparks, crits, and a shockwave every so often.
            if (f.ticks % 2 == 0) {
                sl.sendParticles(ParticleTypes.CRIT, point.x, point.y, point.z, 6, 0.25D, 0.3D, 0.25D, 0.4D);
                sl.sendParticles(ParticleTypes.ELECTRIC_SPARK, point.x, point.y, point.z, 4, 0.2D, 0.25D, 0.2D, 0.2D);
                sl.playSound(null, point.x, point.y, point.z, SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.PLAYERS, 0.6F,
                        1.0F + sl.random.nextFloat() * 0.6F);
            }
            if (f.ticks % 10 == 0) {
                sl.sendParticles(ParticleTypes.SWEEP_ATTACK, point.x, point.y, point.z, 2, 0.3D, 0.3D, 0.3D, 0.0D);
                sl.sendParticles(ParticleTypes.EXPLOSION, point.x, point.y, point.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
                sl.playSound(null, point.x, point.y, point.z, SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.PLAYERS, 0.9F, 0.6F);
            }
            if (f.ticks % 2 == 0) send(f);
            if (Math.abs(f.lead()) >= WIN_LEAD || f.ticks >= DURATION) {
                end(f, a, b, Integer.signum(f.lead()), true);
            }
        }
    }

    /** {@code winner}: 1 = a, -1 = b, 0 = nobody (a draw, or it was broken off). */
    private static void end(Fight f, ServerPlayer a, ServerPlayer b, int winner, boolean decided) {
        ACTIVE.remove(f);
        FIGHTS.remove(f.a);
        FIGHTS.remove(f.b);
        sendEnd(a);
        sendEnd(b);
        if (!decided || a == null || b == null) return;
        ServerLevel sl = f.level;
        if (winner == 0) {
            // Dead even: both blown apart, nobody hurt.
            Vec3 ab = flat(b.position().subtract(a.position()));
            StandCombat.push(a, ab.scale(-1.0D), 1.2D, 0.3D);
            StandCombat.push(b, ab, 1.2D, 0.3D);
            Vec3 m = mid(a, b);
            sl.sendParticles(ParticleTypes.EXPLOSION_EMITTER, m.x, m.y, m.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
            sl.playSound(null, m.x, m.y, m.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 1.2F, 1.2F);
            return;
        }
        ServerPlayer w = winner > 0 ? a : b, l = winner > 0 ? b : a;
        StandEntity sw = StandManager.getStand(w);
        StandData wd = StandManager.data(w);
        // The winner's Stand breaks through and lands the finishing blow.
        if (sw != null) {
            sw.lunge(l.position().add(0.0D, 1.0D, 0.0D), 6);
            sw.playAnim(1);
        }
        float dmg = (float) (8.0D * StandClass.power(wd.getType()) * (1.0D + 0.06D * (wd.getLevel() - 1)));
        l.invulnerableTime = 0;
        StandCombat.hurt(w, l, dmg);
        StandCombat.push(l, flat(l.position().subtract(w.position())), 2.4D, 0.6D);
        Vec3 at = l.position().add(0.0D, 1.0D, 0.0D);
        sl.sendParticles(ParticleTypes.FLASH, at.x, at.y, at.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        sl.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y, at.z, 4, 0.4D, 0.4D, 0.4D, 0.0D);
        sl.playSound(null, at.x, at.y, at.z, SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1.5F, 0.6F);
        sl.playSound(null, at.x, at.y, at.z, SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.8F, 0.5F);
        sl.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 1.0F, 0.8F);
    }

    private static void send(Fight f) {
        ServerPlayer a = f.level.getServer().getPlayerList().getPlayer(f.a);
        ServerPlayer b = f.level.getServer().getPlayerList().getPlayer(f.b);
        float meter = Math.max(-1.0F, Math.min(1.0F, f.lead() / (float) WIN_LEAD));
        float left = 1.0F - f.ticks / (float) DURATION;
        if (a != null) PacketDistributor.sendToPlayer(a, new ClashPayload(true, meter, left, color(b)));
        if (b != null) PacketDistributor.sendToPlayer(b, new ClashPayload(true, -meter, left, color(a)));
    }

    private static void sendEnd(ServerPlayer p) {
        if (p != null) PacketDistributor.sendToPlayer(p, new ClashPayload(false, 0.0F, 0.0F, 0));
    }

    private static int color(ServerPlayer p) {
        StandType t = p == null ? null : StandManager.data(p).getType();
        return t == null ? 0xFF5555 : t.color();
    }

    private static Vec3 mid(ServerPlayer a, ServerPlayer b) {
        return a.position().add(b.position()).scale(0.5D).add(0.0D, 1.2D, 0.0D);
    }

    private static Vec3 flat(Vec3 v) {
        Vec3 f = new Vec3(v.x, 0.0D, v.z);
        return f.lengthSqr() < 1.0E-6D ? new Vec3(1.0D, 0.0D, 0.0D) : f.normalize();
    }

    public static void clear() {
        BARRAGE_AT.clear();
        FIGHTS.clear();
        ACTIVE.clear();
    }
}
