package com.mobstands.stand;

import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Crescent Shell's Gravity Zone and Orbit. */
public final class MoonPowers {
    private MoonPowers() {}

    // ------------------------------------------------------------------ Gravity Zone

    public static final double ZONE_RADIUS = 4.5D;
    public static final int ZONE_TICKS = 20 * 15;

    private static final class Zone {
        ServerLevel level;
        UUID owner;
        Vec3 center;
        Direction g;
        long until;
        final Set<UUID> inside = new HashSet<>();
    }

    private static final List<Zone> ZONES = new ArrayList<>();

    /**
     * Places a zone (a ball 4.5 blocks in radius) at your aim point. Gravity inside points the way you were looking:
     * look at a wall and things inside fall toward that wall, look up and they fall into the sky. Look down and it's
     * crushing gravity instead: pinned to the ground, unable to jump. Lasts 15 seconds; leave it and you fall normally.
     */
    public static boolean zone(AbilityContext c) {
        Vec3 look = c.look();
        Direction g = Direction.getNearest(look.x, look.y, look.z);
        Zone z = new Zone();
        z.level = c.level;
        z.owner = c.owner.getUUID();
        z.center = c.aimPoint(16.0D);
        z.g = g;
        z.until = c.level.getGameTime() + ZONE_TICKS;
        ZONES.add(z);
        c.sound(SoundEvents.SHULKER_OPEN, 1.2F, 0.5F);
        c.sound(SoundEvents.BEACON_ACTIVATE, 0.8F, 1.6F);
        c.ring(ParticleTypes.END_ROD, z.center, ZONE_RADIUS, 48);
        c.owner.displayClientMessage(Component.literal(g == Direction.DOWN ? "Gravity Zone: crushing gravity"
                : "Gravity Zone: falling " + name(g)).withStyle(s -> s.withColor(0xB38BC9)), true);
        return true;
    }

    private static String name(Direction g) {
        return switch (g) {
            case UP -> "up into the sky";
            default -> g.getName();
        };
    }

    private static void tickZones(ServerLevel sl, long now) {
        Iterator<Zone> it = ZONES.iterator();
        while (it.hasNext()) {
            Zone z = it.next();
            if (z.level != sl) continue;
            ServerPlayer owner = sl.getServer().getPlayerList().getPlayer(z.owner);
            boolean over = owner == null || now >= z.until;
            Set<UUID> now_in = new HashSet<>();
            if (!over) {
                double r2 = ZONE_RADIUS * ZONE_RADIUS;
                for (LivingEntity e : sl.getEntitiesOfClass(LivingEntity.class, new AABB(z.center, z.center).inflate(ZONE_RADIUS),
                        e -> e.isAlive() && !e.isSpectator()
                                && e.position().add(0.0D, e.getBbHeight() * 0.5D, 0.0D).distanceToSqr(z.center) <= r2)) {
                    if (e instanceof net.minecraft.world.entity.player.Player pl && pl.isCreative()) continue;
                    now_in.add(e.getUUID());
                    if (z.g == Direction.DOWN) {
                        // Crushing gravity: slammed down and held there.
                        e.setDeltaMovement(e.getDeltaMovement().x * 0.5D, Math.min(e.getDeltaMovement().y, -0.6D), e.getDeltaMovement().z * 0.5D);
                        e.hurtMarked = true;
                        e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 10, 3, false, false, true));
                        e.addEffect(new MobEffectInstance(MobEffects.JUMP, 10, 128, false, false, false)); // can't jump
                    } else if (!z.inside.contains(e.getUUID()) || GravityFlip.dir(e) != z.g) {
                        GravityFlip.flip(e, z.g, z.owner, e == owner, ZONE_TICKS);
                    }
                }
            }
            // Out of the zone (or it's over): gravity goes back to normal.
            for (UUID id : z.inside) {
                if (!over && now_in.contains(id)) continue;
                Entity e = sl.getEntity(id);
                if (e instanceof LivingEntity le && z.g != Direction.DOWN && GravityFlip.dir(le) == z.g) GravityFlip.release(le);
            }
            z.inside.clear();
            z.inside.addAll(now_in);
            if (over) {
                it.remove();
                continue;
            }
            // The zone shimmers, its motes drifting the way gravity pulls.
            if (now % 4L == 0L) {
                Vec3 v = GravityFlip.vec(z.g).scale(0.15D);
                for (int i = 0; i < 6; i++) {
                    Vec3 p = z.center.add(new Vec3(sl.random.nextDouble() - 0.5D, sl.random.nextDouble() - 0.5D, sl.random.nextDouble() - 0.5D)
                            .normalize().scale(ZONE_RADIUS * Math.cbrt(sl.random.nextDouble())));
                    sl.sendParticles(ParticleTypes.END_ROD, p.x, p.y, p.z, 0, v.x, v.y, v.z, 1.0D);
                }
            }
            if (now % 20L == 0L) {
                int pts = 40;
                for (int i = 0; i < pts; i++) {
                    double a = (Math.PI * 2.0D) * i / pts;
                    sl.sendParticles(ParticleTypes.REVERSE_PORTAL, z.center.x + Math.cos(a) * ZONE_RADIUS, z.center.y, z.center.z + Math.sin(a) * ZONE_RADIUS, 1, 0.0D, 0.0D, 0.0D, 0.0D);
                }
            }
        }
    }

    // ------------------------------------------------------------------ Orbit

    public static final int ORBIT_SLOT = 4;
    public static final int ORBIT_TICKS = 20 * 8;
    private static final double ORBIT_RANGE = 8.0D;
    private static final int ORBIT_MAX = 12;

    private static final class Orbit {
        ServerLevel level;
        UUID owner;
        long start, until;
        final List<Entity> bodies = new ArrayList<>();
    }

    private static final Map<UUID, Orbit> ORBITS = new HashMap<>();

    /**
     * Your Shulker becomes a tiny moon: nearby enemies and dropped items are pulled into orbit around you. Press again
     * to fling them all where you're looking. After 8 seconds they're flung outward on their own.
     */
    public static boolean orbit(AbilityContext c) {
        ServerPlayer p = c.owner;
        Orbit o = ORBITS.get(p.getUUID());
        if (o != null) {
            fling(p, o, true);
            return endOrbit(c, p);
        }
        long now = c.level.getGameTime();
        if (c.data.cooldownEnd(ORBIT_SLOT) > now) return false;
        o = new Orbit();
        o.level = c.level;
        o.owner = p.getUUID();
        o.start = now;
        o.until = now + ORBIT_TICKS;
        Vec3 at = p.position().add(0.0D, 1.0D, 0.0D);
        for (LivingEntity e : c.enemies(at, ORBIT_RANGE)) {
            if (o.bodies.size() >= ORBIT_MAX) break;
            if (e instanceof net.minecraft.world.entity.boss.wither.WitherBoss || e instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon) continue;
            o.bodies.add(e);
        }
        for (ItemEntity it : c.level.getEntitiesOfClass(ItemEntity.class, new AABB(at, at).inflate(ORBIT_RANGE))) {
            if (o.bodies.size() >= ORBIT_MAX) break;
            o.bodies.add(it);
        }
        if (o.bodies.isEmpty()) {
            c.fail = "Nothing nearby to pull into orbit.";
            return false;
        }
        ORBITS.put(p.getUUID(), o);
        c.sound(SoundEvents.SHULKER_OPEN, 1.2F, 0.4F);
        c.sound(SoundEvents.BEACON_POWER_SELECT, 0.8F, 1.8F);
        p.displayClientMessage(Component.literal("Orbit: press again to fling them.").withStyle(s -> s.withColor(0xB38BC9)), true);
        // The cooldown starts when they're flung, so the second press works.
        c.fail = "";
        return false;
    }

    private static boolean endOrbit(AbilityContext c, ServerPlayer p) {
        ORBITS.remove(p.getUUID());
        Ability ab = StandType.SHULKER.ability(ORBIT_SLOT);
        c.data.setCooldownEnd(ORBIT_SLOT, c.level.getGameTime() + ab.cooldownTicks(c.data.getLevel()));
        StandManager.sync(p);
        c.fail = "";
        return false;
    }

    /** Throws everything in orbit: where you look ({@code aimed}), or straight outward when time runs out. */
    private static void fling(ServerPlayer p, Orbit o, boolean aimed) {
        StandData d = StandManager.data(p);
        AbilityContext c = new AbilityContext(p, StandManager.getStand(p), o.level, StandType.SHULKER, d);
        Vec3 look = p.getLookAngle();
        for (Entity e : o.bodies) {
            if (!e.isAlive()) continue;
            Vec3 out = e.position().subtract(p.position());
            Vec3 dir = aimed ? look : new Vec3(out.x, 0.0D, out.z).normalize();
            e.setDeltaMovement(dir.scale(aimed ? 2.0D : 1.2D).add(0.0D, 0.25D, 0.0D));
            e.hurtMarked = true;
            if (e instanceof LivingEntity le) {
                le.invulnerableTime = 0;
                c.hurt(le, c.dmg(4.0D));
            }
            if (e instanceof ItemEntity it) it.setPickUpDelay(20);
        }
        o.level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.SHULKER_SHOOT, SoundSource.PLAYERS, 1.2F, 0.6F);
        o.level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.0F, 0.6F);
        o.level.sendParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + 1.0D, p.getZ(), 30, 0.6D, 0.4D, 0.6D, 0.2D);
    }

    private static void tickOrbits(ServerLevel sl, long now) {
        Iterator<Map.Entry<UUID, Orbit>> it = ORBITS.entrySet().iterator();
        while (it.hasNext()) {
            Orbit o = it.next().getValue();
            if (o.level != sl) continue;
            ServerPlayer p = sl.getServer().getPlayerList().getPlayer(o.owner);
            StandData d = p != null ? StandManager.data(p) : null;
            if (p == null || d.getType() != StandType.SHULKER || !d.isSummoned()) {
                it.remove();
                continue;
            }
            o.bodies.removeIf(e -> !e.isAlive() || e.level() != sl);
            if (now >= o.until || o.bodies.isEmpty()) {
                fling(p, o, false);
                it.remove();
                Ability ab = StandType.SHULKER.ability(ORBIT_SLOT);
                d.setCooldownEnd(ORBIT_SLOT, now + ab.cooldownTicks(d.getLevel()));
                StandManager.sync(p);
                continue;
            }
            // Everything circles you like little moons, at different heights and speeds.
            int n = o.bodies.size();
            double t = (now - o.start);
            for (int i = 0; i < n; i++) {
                Entity e = o.bodies.get(i);
                double r = 3.0D + (i % 3) * 0.7D;
                double a = t * (0.16D - (i % 3) * 0.03D) + (Math.PI * 2.0D) * i / n;
                Vec3 target = p.position().add(Math.cos(a) * r, 1.2D + Math.sin(t * 0.1D + i) * 0.5D, Math.sin(a) * r);
                Vec3 pull = target.subtract(e.position());
                e.setDeltaMovement(pull.scale(0.45D));
                e.hurtMarked = true;
                e.fallDistance = 0.0F;
                if (e instanceof net.minecraft.world.entity.Mob mob) mob.getNavigation().stop();
                if (e instanceof ItemEntity item) item.setPickUpDelay(10); // you don't just catch them
                if (now % 3L == 0L) sl.sendParticles(ParticleTypes.END_ROD, e.getX(), e.getY() + e.getBbHeight() * 0.5D, e.getZ(), 1, 0.05D, 0.05D, 0.05D, 0.0D);
            }
        }
    }

    // ------------------------------------------------------------------

    public static void tick(ServerLevel sl) {
        if (ZONES.isEmpty() && ORBITS.isEmpty()) return;
        if (!sl.tickRateManager().runsNormally()) return;
        long now = sl.getGameTime();
        tickZones(sl, now);
        tickOrbits(sl, now);
    }

    public static void clear() {
        ZONES.clear();
        ORBITS.clear();
    }
}
