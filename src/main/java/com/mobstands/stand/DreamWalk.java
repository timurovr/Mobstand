package com.mobstands.stand;

import com.mobstands.MobStands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.gossip.GossipType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Phantom Stand: Dream Walk. At night, look at a sleeping villager and use any Phantom ability: you slip into its
 * nightmare - a dark, fog-drowned flat world where you're in creative. Somewhere in the fog stand a few houses;
 * in one of them waits the villager's nightmare self. Kill it and the villager wakes up, so grateful it gives you
 * huge discounts (just like curing a zombie villager). Leave, run out of time or get lost and nothing happens.
 *
 * Nothing from the dream comes back: your real inventory and ender chest are put aside while you dream and given
 * back exactly as they were when you wake.
 */
public final class DreamWalk {
    private DreamWalk() {}

    public static final ResourceKey<Level> DREAM = ResourceKey.create(Registries.DIMENSION, MobStands.id("dream"));
    /**
     * The good dream: one shared, sunny, peaceful superflat world in creative. Every villager you've freed from its
     * nightmare dreams of this same place, so whatever you build there is still there next time - through any of them.
     */
    public static final ResourceKey<Level> SWEET = ResourceKey.create(Registries.DIMENSION, MobStands.id("daydream"));
    /** Top of the sweet dream's ground (high enough that the sky looks like the real Overworld's). */
    private static final int SWEET_GROUND = 64;
    /** Put on a real villager once you've ended its nightmare. */
    public static final String CLEANSED_TAG = "mobstands_cleansed";
    private static final String SWEET_POS = "mobstands_sweet_pos";
    /** The Phantom level that unlocks Dream Walk. */
    public static final int UNLOCK_LEVEL = 8;
    /** How long a dream lasts before you wake up empty-handed (5 minutes). */
    public static final int DREAM_TICKS = 6000;
    private static final String KEY = "mobstands_dream";
    private static final String DREAM_TAG = "mobstands_dream_villager";
    /** Top of the dream's flat ground (bedrock + 2 dirt + grass). */
    private static final int GROUND = 4;
    /** The dream is small: walk past this far from where you arrived and it loops you back in from the other side. */
    private static final double EDGE = 85.0D;
    /** And you can't fly up out of it either. */
    private static final double CEILING = GROUND + 30.0D;

    private static CompoundTag state(Player p) {
        CompoundTag persisted = p.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        return persisted.contains(KEY) ? persisted.getCompound(KEY) : null;
    }

    private static void setState(Player p, CompoundTag tag) {
        CompoundTag root = p.getPersistentData();
        CompoundTag persisted = root.getCompound(Player.PERSISTED_NBT_TAG);
        if (tag == null) persisted.remove(KEY);
        else persisted.put(KEY, tag);
        root.put(Player.PERSISTED_NBT_TAG, persisted);
    }

    public static boolean dreaming(Player p) {
        return state(p) != null;
    }

    /** The sleeping villager you're looking at (within 7 blocks), or null. */
    private static Villager sleeperInSight(ServerPlayer p) {
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getLookAngle();
        Villager best = null;
        double bestDot = 0.9D;
        for (Villager v : p.serverLevel().getEntitiesOfClass(Villager.class, p.getBoundingBox().inflate(7.0D), Villager::isSleeping)) {
            Vec3 to = v.position().add(0.0D, 0.3D, 0.0D).subtract(eye);
            double dist = to.length();
            if (dist > 7.0D) continue;
            double dot = to.normalize().dot(look);
            if (dot > bestDot) {
                bestDot = dot;
                best = v;
            }
        }
        return best;
    }

    /**
     * Dream Walk (the Phantom's 5th ability, V): in a dream, wakes you up; otherwise enters the dream of the
     * sleeping villager you're looking at. Returns true if something happened (puts the ability on cooldown).
     */
    public static boolean use(ServerPlayer p) {
        CompoundTag st = state(p);
        if (st != null) {
            wake(p, false, st.getBoolean("Sweet") ? "You wake up feeling rested." : "You force yourself awake.");
            return true;
        }
        if (!p.level().dimension().equals(Level.OVERWORLD)) {
            p.displayClientMessage(Component.literal("There are no dreams to walk into here."), true);
            return false;
        }
        if (!p.level().isNight()) {
            p.displayClientMessage(Component.literal("Dreams only open at night..."), true);
            return false;
        }
        Villager v = sleeperInSight(p);
        if (v == null) {
            p.displayClientMessage(Component.literal("Look at a sleeping villager."), true);
            return false;
        }
        enter(p, v);
        return true;
    }

    /** Your waking self: where you were, your game mode, and your belongings (put aside). */
    private static CompoundTag saveWaking(ServerPlayer p, Villager real) {
        ServerLevel from = p.serverLevel();
        CompoundTag st = new CompoundTag();
        st.putUUID("Villager", real.getUUID());
        st.putString("Dim", from.dimension().location().toString());
        st.putDouble("X", p.getX());
        st.putDouble("Y", p.getY());
        st.putDouble("Z", p.getZ());
        st.putFloat("Yaw", p.getYRot());
        st.putFloat("Pitch", p.getXRot());
        st.putInt("Mode", p.gameMode.getGameModeForPlayer().getId());
        st.put("Inv", p.getInventory().save(new ListTag()));
        st.put("Ender", p.getEnderChestInventory().createTag(p.registryAccess()));
        p.getInventory().clearContent();
        p.getEnderChestInventory().clearContent();
        return st;
    }

    /** A villager you've already saved: its dreams are good now - the shared creative world. */
    private static void enterSweet(ServerPlayer p, Villager real) {
        ServerLevel sweet = p.server.getLevel(SWEET);
        if (sweet == null) {
            p.displayClientMessage(Component.literal("The dream world is missing (datapack not loaded)."), true);
            return;
        }
        ServerLevel from = p.serverLevel();
        CompoundTag st = saveWaking(p, real);
        st.putBoolean("Sweet", true);
        setState(p, st);
        from.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.2F, 1.2F);
        from.sendParticles(ParticleTypes.HAPPY_VILLAGER, real.getX(), real.getY() + 0.5D, real.getZ(), 20, 0.4D, 0.3D, 0.4D, 0.0D);
        // Back where you were last time you dreamed here (or the middle of the world).
        CompoundTag persisted = p.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        double x = 0.5D, y = SWEET_GROUND, z = 0.5D;
        if (persisted.contains(SWEET_POS)) {
            CompoundTag pos = persisted.getCompound(SWEET_POS);
            x = pos.getDouble("X");
            y = pos.getDouble("Y");
            z = pos.getDouble("Z");
        }
        sweet.getChunk(BlockPos.containing(x, y, z));
        // Never wake into a wall (or under a cherry tree): stand on top if that spot's taken.
        BlockPos at = BlockPos.containing(x, y, z);
        if (!sweet.getBlockState(at).isAir() || !sweet.getBlockState(at.above()).isAir()) {
            y = sweet.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, at.getX(), at.getZ());
        }
        p.teleportTo(sweet, x, y, z, p.getYRot(), 0.0F);
        p.setGameMode(GameType.CREATIVE);
        p.connection.send(new ClientboundSetTitlesAnimationPacket(10, 40, 20));
        p.connection.send(new ClientboundSetTitleTextPacket(Component.literal("A Sweet Dream").withStyle(s -> s.withColor(0xFFD6F0))));
        p.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("Build anything. Press Dream Walk again to wake up.").withStyle(s -> s.withColor(0xCCCCCC))));
        sweet.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.AMBIENT, 1.0F, 1.0F);
    }

    private static void enter(ServerPlayer p, Villager real) {
        if (real.getTags().contains(CLEANSED_TAG)) {
            enterSweet(p, real);
            return;
        }
        ServerLevel dream = p.server.getLevel(DREAM);
        if (dream == null) {
            p.displayClientMessage(Component.literal("The dream world is missing (datapack not loaded)."), true);
            return;
        }
        ServerLevel from = p.serverLevel();
        // Remember everything about the waking world, and put your belongings aside.
        CompoundTag st = saveWaking(p, real);
        st.putLong("End", dream.getGameTime() + DREAM_TICKS);

        from.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PHANTOM_AMBIENT, SoundSource.PLAYERS, 1.2F, 0.6F);
        from.sendParticles(ParticleTypes.SOUL, real.getX(), real.getY() + 0.5D, real.getZ(), 30, 0.4D, 0.3D, 0.4D, 0.02D);

        // Every dream is somewhere new and empty.
        RandomSource r = p.getRandom();
        int ox = (r.nextInt(4000) - 2000) * 64, oz = (r.nextInt(4000) - 2000) * 64;
        BlockPos spawn = new BlockPos(ox, GROUND, oz);
        dream.getChunk(spawn); // generate it now
        UUID nightmare = buildDream(dream, spawn, real, r);
        st.putUUID("Nightmare", nightmare);
        st.putInt("OX", ox);
        st.putInt("OZ", oz);
        setState(p, st);

        p.teleportTo(dream, ox + 0.5D, GROUND, oz + 0.5D, p.getYRot(), 0.0F);
        p.setGameMode(GameType.CREATIVE);
        p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 80, 0, false, false));
        p.connection.send(new ClientboundSetTitlesAnimationPacket(15, 50, 25));
        p.connection.send(new ClientboundSetTitleTextPacket(Component.literal("A Bad Dream").withStyle(s -> s.withColor(0x8C7BFF))));
        p.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("Find them in the fog. End the nightmare.").withStyle(s -> s.withColor(0xAAAAAA))));
        dream.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.AMBIENT_CAVE.value(), SoundSource.AMBIENT, 1.0F, 0.6F);
    }

    // ------------------------------------------------------------------ the dream world

    /** Three houses scattered in the fog; the nightmare villager waits in one of them. Returns its UUID. */
    private static UUID buildDream(ServerLevel dream, BlockPos spawn, Villager real, RandomSource r) {
        int houses = 3;
        int with = r.nextInt(houses);
        UUID id = null;
        double startAngle = r.nextDouble() * Math.PI * 2.0D;
        for (int i = 0; i < houses; i++) {
            double a = startAngle + i * (Math.PI * 2.0D / houses) + (r.nextDouble() - 0.5D) * 0.9D;
            int dist = 28 + r.nextInt(40);
            BlockPos at = spawn.offset((int) (Math.cos(a) * dist), 0, (int) (Math.sin(a) * dist));
            UUID made = buildHouse(dream, at, r, i == with ? real : null);
            if (made != null) id = made;
        }
        return id;
    }

    private static final Block[] WALLS = {Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.DARK_OAK_PLANKS, Blocks.COBBLESTONE,
            Blocks.BIRCH_PLANKS, Blocks.MOSSY_COBBLESTONE, Blocks.STRIPPED_SPRUCE_LOG};
    private static final Block[] LOGS = {Blocks.OAK_LOG, Blocks.SPRUCE_LOG, Blocks.DARK_OAK_LOG, Blocks.STRIPPED_OAK_LOG};
    private static final Block[] ROOFS = {Blocks.DARK_OAK_SLAB, Blocks.SPRUCE_SLAB, Blocks.COBBLESTONE_SLAB, Blocks.OAK_SLAB};

    /** A small, slightly wrong village house. Puts the nightmare villager inside when {@code real} isn't null. */
    private static UUID buildHouse(ServerLevel l, BlockPos corner, RandomSource r, Villager real) {
        int w = 7 + r.nextInt(4), d = 7 + r.nextInt(4), h = 4 + r.nextInt(2);
        Block wall = WALLS[r.nextInt(WALLS.length)];
        Block log = LOGS[r.nextInt(LOGS.length)];
        Block roof = ROOFS[r.nextInt(ROOFS.length)];
        int x0 = corner.getX(), z0 = corner.getZ(), y0 = GROUND;
        for (int cx = (x0 >> 4) - 1; cx <= ((x0 + w) >> 4) + 1; cx++)
            for (int cz = (z0 >> 4) - 1; cz <= ((z0 + d) >> 4) + 1; cz++) l.getChunk(cx, cz);
        // Clear, floor, walls, roof.
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                set(l, x0 + x, y0 - 1, z0 + z, Blocks.COBBLESTONE.defaultBlockState());
                boolean edgeX = x == 0 || x == w - 1, edgeZ = z == 0 || z == d - 1;
                for (int y = 0; y < h; y++) {
                    BlockState s;
                    if (edgeX && edgeZ) s = log.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y);
                    else if (edgeX || edgeZ) s = (y == 1 || y == 2) && ((edgeX ? z : x) % 3 == 1) ? Blocks.GLASS_PANE.defaultBlockState() : wall.defaultBlockState();
                    else s = Blocks.AIR.defaultBlockState();
                    set(l, x0 + x, y0 + y, z0 + z, s);
                }
                set(l, x0 + x, y0 + h, z0 + z, roof.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
            }
        }
        // Glass panes need to connect to their neighbours.
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) for (int y = 1; y <= 2; y++) {
            BlockPos p = new BlockPos(x0 + x, y0 + y, z0 + z);
            BlockState s = l.getBlockState(p);
            if (s.is(Blocks.GLASS_PANE)) l.setBlock(p, Block.updateFromNeighbourShapes(s, l, p), 2);
        }
        // A door in the middle of a random wall.
        Direction side = Direction.from2DDataValue(r.nextInt(4));
        BlockPos door = switch (side) {
            case NORTH -> new BlockPos(x0 + w / 2, y0, z0);
            case SOUTH -> new BlockPos(x0 + w / 2, y0, z0 + d - 1);
            case WEST -> new BlockPos(x0, y0, z0 + d / 2);
            default -> new BlockPos(x0 + w - 1, y0, z0 + d / 2);
        };
        BlockState doorState = Blocks.SPRUCE_DOOR.defaultBlockState().setValue(DoorBlock.FACING, side.getOpposite());
        l.setBlock(door, doorState.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), 2);
        l.setBlock(door.above(), doorState.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 2);
        // Inside: a bed in a corner, a dim lantern, a few odd things.
        BlockPos bedFoot = new BlockPos(x0 + 1, y0, z0 + 2);
        BlockState bed = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH);
        l.setBlock(bedFoot, bed.setValue(BedBlock.PART, BedPart.FOOT), 2);
        l.setBlock(bedFoot.north(), bed.setValue(BedBlock.PART, BedPart.HEAD), 2);
        set(l, x0 + w - 2, y0, z0 + d - 2, Blocks.LANTERN.defaultBlockState());
        set(l, x0 + w - 2, y0 + h - 1, z0 + 1, Blocks.SOUL_LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        if (r.nextBoolean()) set(l, x0 + w - 2, y0, z0 + 1, Blocks.BARREL.defaultBlockState());
        if (r.nextBoolean()) set(l, x0 + 1, y0, z0 + d - 2, Blocks.COBWEB.defaultBlockState());
        // Outside: a lantern by the door so there's something to walk toward in the fog.
        BlockPos outside = door.relative(side, 1).relative(side.getClockWise(), 1);
        set(l, outside.getX(), y0, outside.getZ(), Blocks.OAK_FENCE.defaultBlockState());
        set(l, outside.getX(), y0 + 1, outside.getZ(), Blocks.LANTERN.defaultBlockState());

        if (real == null) return null;
        Villager v = EntityType.VILLAGER.create(l);
        if (v == null) return null;
        v.setVillagerData(real.getVillagerData());
        v.moveTo(x0 + w / 2.0D + 0.5D, y0, z0 + d / 2.0D + 0.5D, side.toYRot(), 0.0F);
        v.setYHeadRot(side.toYRot());
        v.setNoAi(true);           // it just stands there, staring at the door
        v.setPersistenceRequired();
        v.setSilent(true);
        v.addTag(DREAM_TAG);
        l.addFreshEntity(v);
        return v.getUUID();
    }

    private static void set(ServerLevel l, int x, int y, int z, BlockState s) {
        l.setBlock(new BlockPos(x, y, z), s, 2);
    }

    // ------------------------------------------------------------------ during the dream

    public static void tick(ServerPlayer p) {
        CompoundTag st = state(p);
        if (st == null) {
            // Somehow in the dream with nothing to wake up to: send them home.
            if ((p.level().dimension().equals(DREAM) || p.level().dimension().equals(SWEET)) && p.tickCount > 40) {
                ServerLevel ow = p.server.overworld();
                BlockPos sp = ow.getSharedSpawnPos();
                p.teleportTo(ow, sp.getX() + 0.5D, sp.getY(), sp.getZ() + 0.5D, p.getYRot(), p.getXRot());
                if (p.gameMode.getGameModeForPlayer() == GameType.CREATIVE && !p.hasPermissions(2)) p.setGameMode(GameType.SURVIVAL);
            }
            return;
        }
        boolean sweet = st.getBoolean("Sweet");
        if (!p.level().dimension().equals(sweet ? SWEET : DREAM)) {
            // Left the dream some other way (relog, death, command): wake up properly.
            wake(p, false, null);
            return;
        }
        if (sweet) return; // the good dream lasts as long as you like
        ServerLevel dream = p.serverLevel();
        long left = st.getLong("End") - dream.getGameTime();
        if (left <= 0) {
            wake(p, false, "The dream fades before you find them...");
            return;
        }
        // The dream has edges: wander too far and the fog turns you around - you step back in on the far side.
        double ox = st.getInt("OX") + 0.5D, oz = st.getInt("OZ") + 0.5D;
        double dx = p.getX() - ox, dz = p.getZ() - oz;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist > EDGE) {
            double k = (EDGE - 6.0D) / dist;
            double nx = ox - dx * k, nz = oz - dz * k;
            p.teleportTo(dream, nx, Math.min(p.getY(), CEILING), nz, p.getYRot(), p.getXRot());
            p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 40, 0, false, false));
            dream.playSound(null, nx, p.getY(), nz, SoundEvents.PHANTOM_SWOOP, SoundSource.AMBIENT, 1.0F, 0.5F);
            p.displayClientMessage(Component.literal("The fog turns you around...").withStyle(s -> s.withColor(0x8C7BFF)), true);
        } else if (p.getY() > CEILING) {
            p.teleportTo(dream, p.getX(), CEILING, p.getZ(), p.getYRot(), p.getXRot());
            p.setDeltaMovement(p.getDeltaMovement().multiply(1.0D, 0.0D, 1.0D));
            p.hurtMarked = true;
        }
        if (left <= 600 && left % 20 == 0) {
            p.displayClientMessage(Component.literal("The dream is fading... " + left / 20 + "s").withStyle(s -> s.withColor(0x8C7BFF)), true);
        }
        // Now and then, something in the fog.
        if (p.getRandom().nextInt(420) == 0) {
            Vec3 o = p.position().add((p.getRandom().nextDouble() - 0.5D) * 24.0D, 1.0D, (p.getRandom().nextDouble() - 0.5D) * 24.0D);
            dream.playSound(null, o.x, o.y, o.z, p.getRandom().nextBoolean() ? SoundEvents.PHANTOM_AMBIENT : SoundEvents.VILLAGER_AMBIENT,
                    SoundSource.AMBIENT, 0.8F, 0.5F);
        }
    }

    /** The nightmare villager died: if a dreamer killed it, they've saved the real one. */
    public static void onDeath(Entity dead, Entity killer) {
        if (!dead.getTags().contains(DREAM_TAG)) return;
        if (!(killer instanceof ServerPlayer p)) return;
        CompoundTag st = state(p);
        if (st == null || !st.hasUUID("Nightmare") || !st.getUUID("Nightmare").equals(dead.getUUID())) return;
        ServerLevel dream = p.serverLevel();
        dream.sendParticles(ParticleTypes.SOUL, dead.getX(), dead.getY() + 1.0D, dead.getZ(), 40, 0.4D, 0.6D, 0.4D, 0.05D);
        dream.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PHANTOM_DEATH, SoundSource.PLAYERS, 1.0F, 0.6F);
        wake(p, true, null);
    }

    /** Back to the waking world: same place, same game mode, your own things back. */
    public static void wake(ServerPlayer p, boolean saved, String msg) {
        CompoundTag st = state(p);
        if (st == null) return;
        setState(p, null);
        if (st.getBoolean("Sweet") && p.level().dimension().equals(SWEET)) {
            CompoundTag pos = new CompoundTag();
            pos.putDouble("X", p.getX());
            pos.putDouble("Y", p.getY());
            pos.putDouble("Z", p.getZ());
            CompoundTag root = p.getPersistentData();
            CompoundTag persisted = root.getCompound(Player.PERSISTED_NBT_TAG);
            persisted.put(SWEET_POS, pos);
            root.put(Player.PERSISTED_NBT_TAG, persisted);
        }
        ServerLevel dream = p.server.getLevel(DREAM);
        if (dream != null && st.hasUUID("Nightmare")) {
            Entity n = dream.getEntity(st.getUUID("Nightmare"));
            if (n != null) n.discard();
        }
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(st.getString("Dim")));
        ServerLevel back = p.server.getLevel(dim);
        if (back == null) back = p.server.overworld();
        // Nothing from the dream comes with you.
        p.getInventory().clearContent();
        p.getInventory().load(st.getList("Inv", 10));
        p.getEnderChestInventory().clearContent();
        p.getEnderChestInventory().fromTag(st.getList("Ender", 10), p.registryAccess());
        p.setGameMode(GameType.byId(st.getInt("Mode")));
        if (p.isAlive()) {
            p.teleportTo(back, st.getDouble("X"), st.getDouble("Y"), st.getDouble("Z"), st.getFloat("Yaw"), st.getFloat("Pitch"));
        }
        p.inventoryMenu.broadcastChanges();
        p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 30, 0, false, false));
        if (msg != null) p.displayClientMessage(Component.literal(msg).withStyle(s -> s.withColor(0x8C7BFF)), true);
        if (!saved) return;

        UUID villagerId = st.getUUID("Villager");
        final ServerLevel home = back;
        TickScheduler.later(25, () -> thank(p, home, villagerId));
    }

    private static void thank(ServerPlayer p, ServerLevel level, UUID villagerId) {
        Entity e = level.getEntity(villagerId);
        if (!(e instanceof Villager v) || !v.isAlive()) {
            p.displayClientMessage(Component.literal("You ended the nightmare... but the villager is gone."), true);
            return;
        }
        if (v.isSleeping()) v.stopSleeping();
        v.addTag(CLEANSED_TAG); // its dreams are good from now on
        // The same gratitude as curing a zombie villager: big, lasting discounts for you.
        v.getGossips().add(p.getUUID(), GossipType.MAJOR_POSITIVE, 20);
        v.getGossips().add(p.getUUID(), GossipType.MINOR_POSITIVE, 25);
        v.getLookControl().setLookAt(p, 30.0F, 30.0F);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, v.getX(), v.getY() + 1.6D, v.getZ(), 20, 0.4D, 0.4D, 0.4D, 0.0D);
        level.sendParticles(ParticleTypes.HEART, v.getX(), v.getY() + 2.0D, v.getZ(), 4, 0.3D, 0.2D, 0.3D, 0.0D);
        level.playSound(null, v.getX(), v.getY(), v.getZ(), SoundEvents.VILLAGER_CELEBRATE, SoundSource.NEUTRAL, 1.0F, 1.0F);
        level.playSound(null, v.getX(), v.getY(), v.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.NEUTRAL, 0.6F, 1.6F);
        String[] lines = {
                "...You were there. In the fog. Thank you for waking me from that nightmare!",
                "Hrmm! That dream... it was so dark. You saved me. Please, take my best prices!",
                "I couldn't find the door... and then you came. Thank you, friend. My goods are cheap for you now.",
        };
        p.sendSystemMessage(Component.literal("<Villager> ").withStyle(s -> s.withColor(0x55FF55))
                .append(Component.literal(lines[p.getRandom().nextInt(lines.length)]).withStyle(s -> s.withColor(0xFFFFFF))));
        p.displayClientMessage(Component.literal("This villager now gives you big discounts.").withStyle(s -> s.withColor(0xE8C25A)), true);
    }

    /** Never let the nightmare villager be killed by anything but the dreamer (or wander off and despawn). */
    public static boolean isNightmare(Entity e) {
        return e.getTags().contains(DREAM_TAG);
    }
}
