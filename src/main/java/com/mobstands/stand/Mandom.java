package com.mobstands.stand;

import com.mobstands.entity.StandEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Neoteny, the Axolotl's level 10 ability - Mandom-style. While your Axolotl is out, the world around you is quietly
 * remembered. Press it and the last 6 seconds are undone for everything within 40 blocks: every mob, every player
 * (you too) goes back to where it was with the health it had, mobs killed in those 6 seconds come back, things that
 * appeared vanish, blocks broken, placed or blown up go back the way they were. Only you remember it happened.
 */
public final class Mandom {
    private Mandom() {}

    /** How far back it goes (6 seconds), how often the world is remembered, and how far around you. */
    public static final int WINDOW = 120, STEP = 4;
    public static final double RADIUS = 40.0D;

    private record EState(Vec3 pos, float yRot, float xRot, float yHead, Vec3 vel, float health, int fire, int air, int food, float sat) {}

    private static final class Frame {
        long time;
        final Map<UUID, EState> states = new HashMap<>();
        final Set<UUID> present = new HashSet<>();
    }

    private record BlockChange(long time, BlockPos pos, BlockState old) {}

    private record Fallen(long time, CompoundTag nbt) {}

    private static final class Recorder {
        ResourceKey<Level> dim;
        Vec3 center = Vec3.ZERO;
        final ArrayDeque<Frame> frames = new ArrayDeque<>();
        final List<BlockChange> blocks = new ArrayList<>();
        final List<Fallen> fallen = new ArrayList<>();
    }

    private static final Map<UUID, Recorder> RECORDERS = new HashMap<>();

    private static boolean records(ServerPlayer p) {
        StandData d = StandManager.data(p);
        return d.getType() == StandType.AXOLOTL && d.isSummoned() && d.getLevel() >= StandType.AXOLOTL.ability(3).unlockLevel();
    }

    /** Every server tick, per level: remember the world around each Axolotl user. */
    public static void tick(ServerLevel sl) {
        if (!sl.tickRateManager().runsNormally()) return; // stopped time
        long now = sl.getGameTime();
        for (ServerPlayer p : sl.players()) {
            Recorder r = RECORDERS.get(p.getUUID());
            if (!records(p) || !p.isAlive()) {
                if (r != null) RECORDERS.remove(p.getUUID());
                continue;
            }
            if (r == null || r.dim != sl.dimension()) {
                r = new Recorder();
                r.dim = sl.dimension();
                RECORDERS.put(p.getUUID(), r);
            }
            r.center = p.position();
            if (now % STEP == 0L) {
                Frame f = new Frame();
                f.time = now;
                for (Entity e : sl.getEntities((Entity) null, box(r.center), x -> !(x instanceof StandEntity))) {
                    f.present.add(e.getUUID());
                    if (e instanceof LivingEntity le) {
                        int food = e instanceof Player pl ? pl.getFoodData().getFoodLevel() : 0;
                        float sat = e instanceof Player pl ? pl.getFoodData().getSaturationLevel() : 0.0F;
                        f.states.put(e.getUUID(), new EState(e.position(), e.getYRot(), e.getXRot(), e.getYHeadRot(), e.getDeltaMovement(),
                                le.getHealth(), e.getRemainingFireTicks(), e.getAirSupply(), food, sat));
                    }
                }
                r.frames.addLast(f);
                while (r.frames.size() > 1 && now - r.frames.peekFirst().time > WINDOW) r.frames.removeFirst();
            }
            r.blocks.removeIf(b -> now - b.time > WINDOW + STEP);
            r.fallen.removeIf(b -> now - b.time > WINDOW + STEP);
        }
        RECORDERS.keySet().removeIf(u -> sl.getServer().getPlayerList().getPlayer(u) == null);
    }

    private static AABB box(Vec3 c) {
        return new AABB(c.x - RADIUS, c.y - RADIUS, c.z - RADIUS, c.x + RADIUS, c.y + RADIUS, c.z + RADIUS);
    }

    private static boolean near(Recorder r, Level level, BlockPos pos) {
        return r.dim == level.dimension() && r.center.distanceToSqr(Vec3.atCenterOf(pos)) <= RADIUS * RADIUS;
    }

    /** A block is about to change (broken, placed over, blown up): remember what it was. */
    public static void onBlockChange(Level level, BlockPos pos, BlockState old) {
        if (level.isClientSide || RECORDERS.isEmpty()) return;
        long now = level.getGameTime();
        for (Recorder r : RECORDERS.values()) {
            if (near(r, level, pos)) r.blocks.add(new BlockChange(now, pos.immutable(), old));
        }
    }

    /** A mob died near an Axolotl user: keep everything about it, in case those seconds get undone. */
    public static void onDeath(LivingEntity e) {
        if (e.level().isClientSide || RECORDERS.isEmpty() || e instanceof Player) return;
        Recorder hit = null;
        for (Recorder r : RECORDERS.values()) if (near(r, e.level(), e.blockPosition())) hit = r;
        if (hit == null) return;
        CompoundTag tag = new CompoundTag();
        if (!e.save(tag)) return;
        tag.remove("DeathTime");
        tag.remove("HurtTime");
        // It's saved at the moment of death (0 health): it comes back alive - the rewind then sets the health it had.
        tag.putFloat("Health", e.getMaxHealth());
        tag.putShort("Fire", (short) -1);
        tag.putFloat("FallDistance", 0.0F);
        long now = e.level().getGameTime();
        for (Recorder r : RECORDERS.values()) if (near(r, e.level(), e.blockPosition())) r.fallen.add(new Fallen(now, tag));
    }

    /** Undo the last 6 seconds around you. */
    /** Ticks from pressing it to the moment everything snaps back (lines up with the "clunk" in the sound). */
    public static final int WIND_TICKS = 23;
    private static final Set<UUID> WINDING = new HashSet<>();

    /** Press: the watch winds back (sound, colours drain, a ring of light) and then the world snaps back. */
    public static boolean rewind(ServerPlayer p) {
        Recorder r = RECORDERS.get(p.getUUID());
        if (r == null || r.frames.isEmpty() || WINDING.contains(p.getUUID())) {
            if (!WINDING.contains(p.getUUID())) p.displayClientMessage(Component.literal("Nothing to undo yet..."), true);
            return false;
        }
        ServerLevel sl = p.serverLevel();
        WINDING.add(p.getUUID());
        StandData d = StandManager.data(p);
        d.mandomAt = sl.getGameTime();
        StandManager.sync(p);
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), com.mobstands.registry.ModSounds.MANDOM.get(), SoundSource.PLAYERS, 2.0F, 1.0F);
        TickScheduler.later(WIND_TICKS, () -> {
            WINDING.remove(p.getUUID());
            if (p.isAlive() && !p.hasDisconnected() && p.level() == sl) undo(p);
        });
        return true;
    }

    /** Everything within reach goes back 6 seconds. */
    private static void undo(ServerPlayer p) {
        Recorder r = RECORDERS.get(p.getUUID());
        if (r == null || r.frames.isEmpty()) return;
        ServerLevel sl = p.serverLevel();
        Frame f = r.frames.peekFirst();
        long since = f.time;

        // Blocks, newest change first, back to how they were.
        for (int i = r.blocks.size() - 1; i >= 0; i--) {
            BlockChange b = r.blocks.get(i);
            if (b.time >= since) sl.setBlock(b.pos, b.old, 3);
        }
        // Whatever appeared in those seconds was never there (drops, arrows, xp, new mobs).
        for (Entity e : sl.getEntities((Entity) null, box(r.center), x -> !(x instanceof StandEntity) && !(x instanceof Player))) {
            if (f.present.contains(e.getUUID())) continue;
            // Only things that actually came into being in those seconds (not a pet that just walked over).
            if (e.tickCount > sl.getGameTime() - since + STEP) continue;
            if (e instanceof Mob m && (m.hasCustomName() || m.isPersistenceRequired()
                    || (m instanceof net.minecraft.world.entity.OwnableEntity o && o.getOwnerUUID() != null))) continue;
            if (e instanceof ItemEntity || e instanceof ExperienceOrb || e instanceof Projectile || e instanceof Mob) {
                sl.sendParticles(ParticleTypes.CHERRY_LEAVES, e.getX(), e.getY() + 0.3D, e.getZ(), 4, 0.2D, 0.2D, 0.2D, 0.0D);
                e.discard();
            }
        }
        // Mobs that died come back.
        for (Fallen d : r.fallen) {
            if (d.time < since) continue;
            Entity back = EntityType.loadEntityRecursive(d.nbt, sl, x -> x);
            if (back == null) continue;
            Entity still = sl.getEntity(back.getUUID());
            if (still != null) {
                if (still instanceof LivingEntity le && le.isDeadOrDying()) still.discard(); // mid death animation
                else continue;
            }
            sl.addFreshEntity(back);
        }
        // Everyone goes back to where they were, as they were.
        for (Map.Entry<UUID, EState> en : f.states.entrySet()) {
            Entity e = sl.getEntity(en.getKey());
            if (!(e instanceof LivingEntity le) || !le.isAlive()) continue;
            EState s = en.getValue();
            if (e instanceof ServerPlayer sp) {
                sp.connection.teleport(s.pos.x, s.pos.y, s.pos.z, s.yRot, s.xRot);
                sp.getFoodData().setFoodLevel(s.food);
                sp.getFoodData().setSaturation(s.sat);
            } else {
                e.moveTo(s.pos.x, s.pos.y, s.pos.z, s.yRot, s.xRot);
                e.setYHeadRot(s.yHead);
                if (e instanceof Mob m) m.getNavigation().stop();
            }
            le.setHealth(Math.min(le.getMaxHealth(), Math.max(1.0F, s.health)));
            e.setDeltaMovement(s.vel);
            e.setRemainingFireTicks(s.fire);
            e.setAirSupply(s.air);
            e.resetFallDistance();
            e.hurtMarked = true;
        }
        r.blocks.clear();
        r.fallen.clear();
        r.frames.clear(); // can't undo the undo

        // Only you know.
        sl.sendParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + 1.0D, p.getZ(), 40, 0.6D, 0.8D, 0.6D, 0.15D);
        p.displayClientMessage(Component.literal("6 seconds... undone.").withStyle(st -> st.withColor(0xF7A1C4)), true);
    }

    public static void clear() {
        RECORDERS.clear();
        WINDING.clear();
    }
}
