package com.mobstands.stand;

import com.mobstands.registry.ModSounds;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Evoker;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Test dummy (command only, never spawns on its own): an Evoker with Green Detonator: Bites the Dust. It remembers the
 * last 6 seconds around it - and every time it kills a player, you really die (death screen, message, drops), and then
 * the clock winds back: you're alive, standing where you were 6 seconds ago with the health and items you had. So is it.
 */
public final class RewindEvoker {
    private RewindEvoker() {}

    public static final String TAG = "mobstands:rewind_evoker";
    private static final int WINDOW = 120, STEP = 8;

    private record Snap(Vec3 pos, float yRot, float xRot, float health, int food, float sat, ListTag inv) {}

    private static final class Frame {
        long time;
        final Map<UUID, Snap> snaps = new HashMap<>();
    }

    private static final Set<Mob> ACTIVE = new HashSet<>();
    private static final Map<Mob, ArrayDeque<Frame>> FRAMES = new HashMap<>();
    /** Players mid-rewind (dying didn't happen; nothing touches them until they snap back). */
    private static final Set<UUID> WINDING = new HashSet<>();

    /** /stand evoker rewind: one appears a few blocks in front of you. */
    public static void spawnTest(ServerPlayer p) {
        ServerLevel sl = p.serverLevel();
        Vec3 at = TimeEvoker.inFront(p);
        Evoker ev = EntityType.EVOKER.create(sl);
        if (ev == null) return;
        ev.moveTo(at.x, at.y, at.z, p.getYRot() + 180.0F, 0.0F);
        ev.finalizeSpawn(sl, sl.getCurrentDifficultyAt(ev.blockPosition()), MobSpawnType.COMMAND, null);
        ev.addTag(TAG);
        ev.setPersistenceRequired();
        ev.getPersistentData().putBoolean(StandManager.PILLAGER_ROLLED, true);
        ev.getPersistentData().putString(StandManager.PILLAGER_STAND, StandType.CREEPER_REQUIEM.name());
        var hp = ev.getAttribute(Attributes.MAX_HEALTH);
        if (hp != null) hp.setBaseValue(60.0D);
        ev.setHealth(60.0F);
        sl.addFreshEntity(ev); // (its Stand comes out of the usual Illager roll: Bites the Dust)
    }

    public static void onJoin(Entity e) {
        if (e instanceof Mob m && e.getTags().contains(TAG)) ACTIVE.add(m);
    }

    /** Every tick: remember everyone near it (every 0.4 s, 6 seconds back). */
    public static void tick(MinecraftServer server) {
        if (ACTIVE.isEmpty()) return;
        Iterator<Mob> it = ACTIVE.iterator();
        while (it.hasNext()) {
            Mob m = it.next();
            if (m.isRemoved() || !m.isAlive()) {
                it.remove();
                FRAMES.remove(m);
                continue;
            }
            ServerLevel sl = (ServerLevel) m.level();
            if (!sl.tickRateManager().runsNormally()) continue; // (stopped time)
            long now = sl.getGameTime();
            if (now % STEP != 0) continue;
            Frame f = new Frame();
            f.time = now;
            f.snaps.put(m.getUUID(), new Snap(m.position(), m.getYRot(), m.getXRot(), m.getHealth(), 0, 0.0F, null));
            for (ServerPlayer p : sl.getEntitiesOfClass(ServerPlayer.class, m.getBoundingBox().inflate(40.0D), LivingEntity::isAlive)) {
                if (WINDING.contains(p.getUUID())) continue;
                f.snaps.put(p.getUUID(), new Snap(p.position(), p.getYRot(), p.getXRot(), p.getHealth(),
                        p.getFoodData().getFoodLevel(), p.getFoodData().getSaturationLevel(), p.getInventory().save(new ListTag())));
            }
            ArrayDeque<Frame> q = FRAMES.computeIfAbsent(m, k -> new ArrayDeque<>());
            q.addLast(f);
            while (q.size() > 1 && now - q.peekFirst().time > WINDOW) q.removeFirst();
        }
    }

    /** Which of these Evokers did this (directly, through its fangs, its Vexes, or its Stand)? */
    private static Mob killer(DamageSource src) {
        Entity e = src.getEntity();
        if (e instanceof Vex v && v.getOwner() != null) e = v.getOwner();
        return e instanceof Mob m && ACTIVE.contains(m) ? m : null;
    }

    /** Nothing reaches a player while they're being wound back. */
    public static boolean shielded(LivingEntity e) {
        return !WINDING.isEmpty() && WINDING.contains(e.getUUID());
    }

    /** It winds the clock back the instant you die (death screen, message and drops still happen first). */
    public static final int DEAD_FOR = 1;

    /**
     * It just killed a player - for real: the death happens, the screen, the message, the drops. A couple of seconds
     * and the clock winds back right away, and you were never dead: standing where you were 6 seconds before, with the health
     * and items you had. So is it. (Always false: the death isn't cancelled.)
     */
    public static boolean onPlayerDeath(ServerPlayer p, DamageSource src) {
        Mob m = killer(src);
        if (m == null) return false;
        ArrayDeque<Frame> q = FRAMES.get(m);
        if (q == null || q.isEmpty()) return false;
        Frame f = q.peekFirst();
        Snap mine = f.snaps.get(p.getUUID());
        if (mine == null) return false;
        ServerLevel sl = p.serverLevel();
        UUID id = p.getUUID();
        Vec3 diedAt = p.position();
        long diedTime = sl.getGameTime();
        q.clear(); // (it can't use the same 6 seconds twice)
        TickScheduler.later(DEAD_FOR, () -> {
            if (!m.isAlive()) return;
            // the clock winds back
            sl.playSound(null, m.getX(), m.getY(), m.getZ(), ModSounds.BITES_THE_DUST.get(), SoundSource.HOSTILE, 2.5F, 1.0F);
            ServerPlayer now = sl.getServer().getPlayerList().getPlayer(id);
            if (now != null) net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(now, new com.mobstands.network.RewoundPayload());
            TickScheduler.later(Mandom.WIND_TICKS, () -> revive(sl, id, mine, f, m, diedAt, diedTime));
        });
        return false;
    }

    private record Pending(ServerLevel level, Snap mine, Frame f, Mob m, Vec3 diedAt, long diedTime) {}
    /** Dead players waiting to be pulled back (applied the moment they respawn). */
    private static final Map<UUID, Pending> PENDING = new HashMap<>();

    private static void revive(ServerLevel sl, UUID id, Snap mine, Frame f, Mob m, Vec3 diedAt, long diedTime) {
        ServerPlayer p = sl.getServer().getPlayerList().getPlayer(id);
        if (p == null || p.hasDisconnected()) return;
        Pending pend = new Pending(sl, mine, f, m, diedAt, diedTime);
        if (p.isDeadOrDying()) {
            // Still on the death screen: their game presses Respawn, and the moment they're back, they're put back.
            PENDING.put(id, pend);
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new com.mobstands.network.RespawnNowPayload());
        } else {
            apply(p, pend); // already pressed Respawn: pulled back all the same
        }
    }

    /** Respawned: if time just wound back for them, they're back where they were (not at their bed). */
    public static void onRespawn(ServerPlayer p) {
        Pending pend = PENDING.remove(p.getUUID());
        if (pend != null) apply(p, pend);
    }

    private static void apply(ServerPlayer p, Pending pd) {
        ServerLevel sl = pd.level();
        Snap mine = pd.mine();
        if (p.level() != sl) p.teleportTo(sl, mine.pos().x, mine.pos().y, mine.pos().z, mine.yRot(), mine.xRot());
        else p.connection.teleport(mine.pos().x, mine.pos().y, mine.pos().z, mine.yRot(), mine.xRot());
        p.setHealth(Math.min(p.getMaxHealth(), Math.max(1.0F, mine.health())));
        p.removeAllEffects();
        p.getFoodData().setFoodLevel(mine.food());
        p.getFoodData().setSaturation(mine.sat());
        if (mine.inv() != null) {
            p.getInventory().load(mine.inv());
            p.containerMenu.broadcastChanges();
        }
        p.setDeltaMovement(Vec3.ZERO);
        p.resetFallDistance();
        p.clearFire();
        // the death never happened: its drops and xp were never there
        long age = sl.getGameTime() - pd.diedTime() + 5;
        Vec3 diedAt = pd.diedAt();
        for (Entity e : sl.getEntities((Entity) null, new net.minecraft.world.phys.AABB(diedAt, diedAt).inflate(12.0D),
                x -> (x instanceof net.minecraft.world.entity.item.ItemEntity || x instanceof net.minecraft.world.entity.ExperienceOrb) && x.tickCount <= age)) {
            e.discard();
        }
        // and it goes back too
        Mob m = pd.m();
        Snap its = pd.f().snaps.get(m.getUUID());
        if (its != null && m.isAlive()) {
            m.moveTo(its.pos().x, its.pos().y, its.pos().z, its.yRot(), its.xRot());
            m.setHealth(Math.min(m.getMaxHealth(), its.health()));
            m.getNavigation().stop();
        }
        sl.sendParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + 1.0D, p.getZ(), 40, 0.6D, 0.8D, 0.6D, 0.15D);
    }

    public static void shutdown() {
        ACTIVE.clear();
        FRAMES.clear();
        WINDING.clear();
        PENDING.clear();
    }
}
