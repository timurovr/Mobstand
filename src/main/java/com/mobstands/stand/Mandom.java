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
    public static final int WINDOW = 120, STEP = 8; // a snapshot every 0.4 s is plenty (each one saves everything nearby)
    public static final double RADIUS = 40.0D;

    private record EState(Vec3 pos, float yRot, float xRot, float yHead, Vec3 vel, float health, int fire, int air, int food, float sat,
                          net.minecraft.world.item.ItemStack[] equip, java.util.List<net.minecraft.world.item.ItemStack> carried,
                          net.minecraft.nbt.ListTag playerInv, net.minecraft.nbt.ListTag enderInv) {}

    private static final net.minecraft.world.entity.EquipmentSlot[] SLOTS = net.minecraft.world.entity.EquipmentSlot.values();

    private static final class Frame {
        long time;
        final Map<UUID, EState> states = new HashMap<>();
        final Set<UUID> present = new HashSet<>();
        /** Every dropped item lying around, exactly as it was (so things picked up since come back). */
        final Map<UUID, CompoundTag> items = new HashMap<>();
    }

    /** {@code be} is what was inside the block (a chest's items, a furnace's fuel, a sign's text...), or null. */
    private record BlockChange(long time, BlockPos pos, BlockState old, CompoundTag be) {}

    private record Fallen(long time, CompoundTag nbt) {}

    private static final class Recorder {
        ResourceKey<Level> dim;
        Vec3 center = Vec3.ZERO;
        final ArrayDeque<Frame> frames = new ArrayDeque<>();
        final List<BlockChange> blocks = new ArrayList<>();
        final List<Fallen> fallen = new ArrayList<>();
    }

    private static final Map<UUID, Recorder> RECORDERS = new HashMap<>();

    /** Players who died (and when, and where): if they're still on the death screen, a rewind can bring them back. */
    private record PlayerDeath(long time, ResourceKey<Level> dim) {}
    private static final Map<UUID, PlayerDeath> PLAYER_DEATHS = new HashMap<>();

    private static boolean records(ServerPlayer p) {
        StandData d = StandManager.data(p);
        return d.getType() == StandType.CREEPER_REQUIEM && d.isSummoned();
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
                    if (e instanceof ItemEntity ie) {
                        CompoundTag t = new CompoundTag();
                        if (ie.saveAsPassenger(t)) f.items.put(e.getUUID(), t);
                    }
                    if (e instanceof LivingEntity le) {
                        int food = e instanceof Player pl ? pl.getFoodData().getFoodLevel() : 0;
                        float sat = e instanceof Player pl ? pl.getFoodData().getSaturationLevel() : 0.0F;
                        // What they're holding and carrying, so items picked up since go back to where they were.
                        net.minecraft.world.item.ItemStack[] equip = null;
                        java.util.List<net.minecraft.world.item.ItemStack> carried = null;
                        net.minecraft.nbt.ListTag inv = null;
                        net.minecraft.nbt.ListTag ender = null;
                        if (e instanceof Player pl) {
                            inv = pl.getInventory().save(new net.minecraft.nbt.ListTag());
                            ender = pl.getEnderChestInventory().createTag(sl.registryAccess());
                        } else if (e instanceof net.minecraft.world.entity.Mob mob) {
                            equip = new net.minecraft.world.item.ItemStack[SLOTS.length];
                            for (int i = 0; i < SLOTS.length; i++) equip[i] = mob.getItemBySlot(SLOTS[i]).copy();
                            if (mob instanceof net.minecraft.world.entity.npc.InventoryCarrier ic) {
                                carried = new ArrayList<>();
                                for (int i = 0; i < ic.getInventory().getContainerSize(); i++) carried.add(ic.getInventory().getItem(i).copy());
                            }
                        }
                        f.states.put(e.getUUID(), new EState(e.position(), e.getYRot(), e.getXRot(), e.getYHeadRot(), e.getDeltaMovement(),
                                le.getHealth(), e.getRemainingFireTicks(), e.getAirSupply(), food, sat, equip, carried, inv, ender));
                    }
                }
                r.frames.addLast(f);
                while (r.frames.size() > 1 && now - r.frames.peekFirst().time > WINDOW) r.frames.removeFirst();
            }
            r.blocks.removeIf(b -> now - b.time > WINDOW + STEP);
            r.fallen.removeIf(b -> now - b.time > WINDOW + STEP);
        }
        RECORDERS.keySet().removeIf(u -> sl.getServer().getPlayerList().getPlayer(u) == null);
        if (sl.dimension() == Level.OVERWORLD) {
            long t = sl.getServer().overworld().getGameTime();
            PLAYER_DEATHS.values().removeIf(dd -> t - dd.time > WINDOW + 40);
        }
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
        CompoundTag be = null;
        boolean saved = false;
        for (Recorder r : RECORDERS.values()) {
            if (!near(r, level, pos)) continue;
            if (!saved) {
                // Remember the contents too, so a blown-up chest comes back full (its spilled loot is undone with the rest).
                net.minecraft.world.level.block.entity.BlockEntity e = level.getBlockEntity(pos);
                if (e != null) be = e.saveWithFullMetadata(level.registryAccess());
                saved = true;
            }
            r.blocks.add(new BlockChange(now, pos.immutable(), old, be));
        }
    }

    /** A mob died near an Axolotl user: keep everything about it, in case those seconds get undone. */
    public static void onDeath(LivingEntity e) {
        if (e.level().isClientSide) return;
        if (e instanceof Player) {
            PLAYER_DEATHS.put(e.getUUID(), new PlayerDeath(e.level().getGameTime(), e.level().dimension()));
            return;
        }
        if (RECORDERS.isEmpty()) return;
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

    /** True while a rewind is putting things back (what it removes itself isn't "lost"). */
    private static boolean undoing;

    /**
     * Something left the world for good near a Bites the Dust user - erased by Primer, blown apart, broken (a boat,
     * a minecart, an item frame, a painting), despawned: keep it whole so a rewind brings it back.
     */
    public static void onVanish(Entity e) {
        if (undoing || e.level().isClientSide || RECORDERS.isEmpty()) return;
        if (e instanceof Player || e instanceof StandEntity || e instanceof ItemEntity || e instanceof ExperienceOrb
                || e instanceof Projectile || e instanceof com.mobstands.entity.StandProjectile) return;
        boolean any = false;
        for (Recorder r : RECORDERS.values()) if (near(r, e.level(), e.blockPosition())) any = true;
        if (!any) return;
        var key = EntityType.getKey(e.getType());
        if (!e.getType().canSerialize()) return;
        CompoundTag tag = e.saveWithoutId(new CompoundTag());
        tag.putString("id", key.toString());
        tag.remove("DeathTime");
        tag.remove("HurtTime");
        if (e instanceof LivingEntity le) {
            if (le.getHealth() <= 0.0F) tag.putFloat("Health", le.getMaxHealth());
            tag.putShort("Fire", (short) -1);
            tag.putFloat("FallDistance", 0.0F);
        }
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
        undoing = true;
        try {
            undoInner(p);
        } finally {
            undoing = false;
        }
    }

    private static void undoInner(ServerPlayer p) {
        Recorder r = RECORDERS.get(p.getUUID());
        if (r == null || r.frames.isEmpty()) return;
        ServerLevel sl = p.serverLevel();
        Frame f = r.frames.peekFirst();
        long since = f.time;

        // Blocks, newest change first, back to how they were.
        for (int i = r.blocks.size() - 1; i >= 0; i--) {
            BlockChange b = r.blocks.get(i);
            if (b.time < since) continue;
            sl.setBlock(b.pos, b.old, 3);
            if (b.be != null) {
                net.minecraft.world.level.block.entity.BlockEntity e = sl.getBlockEntity(b.pos);
                if (e != null) {
                    e.loadWithComponents(b.be, sl.registryAccess());
                    e.setChanged();
                    sl.sendBlockUpdated(b.pos, b.old, b.old, 3);
                }
            }
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
        // Dropped items go back to how they were - including ones picked up since (by you, a player or a mob).
        for (Map.Entry<UUID, CompoundTag> it : f.items.entrySet()) {
            Entity now = sl.getEntity(it.getKey());
            if (now != null) now.discard();
            Entity back = EntityType.loadEntityRecursive(it.getValue().copy(), sl, x -> x);
            if (back != null) sl.addFreshEntity(back);
        }
        // Everything that died, was erased or broke comes back (each only once, whichever way it went).
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
        // "I'll say when we're done": players who died in those seconds and haven't pressed Respawn come back to life.
        reviveFallenPlayers(p, r, f, sl);
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
            // ...holding and carrying exactly what they had.
            if (s.playerInv != null && e instanceof Player pl) {
                pl.getInventory().load(s.playerInv);
                if (s.enderInv != null) pl.getEnderChestInventory().fromTag(s.enderInv, sl.registryAccess());
                pl.containerMenu.broadcastChanges();
            }
            if (s.equip != null && e instanceof net.minecraft.world.entity.Mob mob) {
                for (int i = 0; i < SLOTS.length; i++) mob.setItemSlot(SLOTS[i], s.equip[i].copy());
            }
            if (s.carried != null && e instanceof net.minecraft.world.entity.npc.InventoryCarrier ic) {
                for (int i = 0; i < Math.min(s.carried.size(), ic.getInventory().getContainerSize()); i++)
                    ic.getInventory().setItem(i, s.carried.get(i).copy());
            }
            e.setDeltaMovement(s.vel);
            e.setRemainingFireTicks(s.fire);
            e.setAirSupply(s.air);
            e.resetFallDistance();
            e.hurtMarked = true;
        }
        // Things the rewound players threw that ended up out of reach (too far to be rewound with the rest): they
        // already have them back in their inventory, so the thrown copies are gone too. One search, only now.
        Set<UUID> rewound = new HashSet<>();
        for (Map.Entry<UUID, EState> en : f.states.entrySet()) {
            if (en.getValue().playerInv != null) rewound.add(en.getKey());
        }
        if (!rewound.isEmpty()) {
            long age = sl.getGameTime() - since + STEP;
            for (ItemEntity thrown : sl.getEntities(EntityType.ITEM, i -> i.tickCount <= age && !f.items.containsKey(i.getUUID())
                    && i.getOwner() != null && rewound.contains(i.getOwner().getUUID()))) {
                thrown.discard();
            }
        }
        r.blocks.clear();
        r.fallen.clear();
        r.frames.clear(); // can't undo the undo

        // Only you know.
        sl.sendParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + 1.0D, p.getZ(), 40, 0.6D, 0.8D, 0.6D, 0.15D);
        p.displayClientMessage(Component.literal("6 seconds... undone.").withStyle(st -> st.withColor(0xF7A1C4)), true);
    }

    /**
     * Anyone who was within reach 6 seconds ago, died since, and is still sitting on the death screen gets pulled back:
     * respawned on the spot (keeping everything, since the death never happened) - the rewind right after then puts
     * them exactly where they were with the health and inventory they had. Their death drops are undone with the rest.
     */
    private static void reviveFallenPlayers(ServerPlayer user, Recorder r, Frame f, ServerLevel sl) {
        var list = sl.getServer().getPlayerList();
        for (Map.Entry<UUID, EState> en : f.states.entrySet()) {
            PlayerDeath death = PLAYER_DEATHS.get(en.getKey());
            if (death == null || death.time < f.time || death.dim != r.dim) continue;
            ServerPlayer dead = list.getPlayer(en.getKey());
            if (dead == null || dead == user || !dead.isDeadOrDying()) continue; // already respawned: too late
            if (dead.hasDisconnected()) continue;
            PLAYER_DEATHS.remove(en.getKey());
            EState s = en.getValue();
            ServerPlayer back = list.respawn(dead, true, Entity.RemovalReason.KILLED);
            if (back.level() != sl) back.teleportTo(sl, s.pos.x, s.pos.y, s.pos.z, s.yRot, s.xRot);
            else back.connection.teleport(s.pos.x, s.pos.y, s.pos.z, s.yRot, s.xRot);
            back.setHealth(Math.max(1.0F, s.health));
            back.removeAllEffects();
            sl.sendParticles(ParticleTypes.END_ROD, s.pos.x, s.pos.y + 1.0D, s.pos.z, 30, 0.4D, 0.8D, 0.4D, 0.1D);
        }
    }

    public static void clear() {
        PLAYER_DEATHS.clear();
        RECORDERS.clear();
        WINDING.clear();
    }
}
