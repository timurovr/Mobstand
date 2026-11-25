package com.mobstands.stand;

import com.mobstands.entity.StandEntity;
import com.mobstands.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Evoker;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * A secret. Sniff out a Woodland Mansion with Ancient Nose, go there, and one of the Evokers inside is... different.
 * Nothing tells you. It looks like any other Evoker - until you fight it, and time stops. In stopped time it walks
 * right up to you, leaves arrows hanging in the air all around you, hits you, and steps away. When time moves again,
 * everything lands at once.
 */
public final class TimeEvoker {
    private TimeEvoker() {}

    public static final String TAG = "mobstands:time_evoker";
    private static final String MARKS = "mobstands:mansion_marks";
    /** How long its stop lasts, and the gap between stops. */
    public static final int STOP_TICKS = 100, STOP_EVERY = 20 * 22;

    private static final Set<Mob> ACTIVE = new HashSet<>();
    private static final java.util.Map<Mob, Long> NEXT_STOP = new java.util.HashMap<>();
    private static final java.util.Map<Mob, Long> FIGHTING_SINCE = new java.util.HashMap<>();

    /** Server: can this move in stopped time? It, and its Stand. */
    public static boolean moves(Entity e) {
        if (e.getTags().contains(TAG)) return true;
        if (e instanceof StandEntity s && s.isMobStand()) {
            LivingEntity o = s.getMobOwner();
            return o != null && o.getTags().contains(TAG);
        }
        return false;
    }

    // ------------------------------------------------------------------ the lead: Sniff Out found a mansion

    /** Ancient Nose led this player to a Woodland Mansion. */
    public static void mark(ServerPlayer p, ServerLevel sl, BlockPos at) {
        CompoundTag pd = p.getPersistentData();
        ListTag l = pd.getList(MARKS, Tag.TAG_STRING);
        String k = sl.dimension().location() + "|" + at.getX() + "|" + at.getZ();
        for (int i = 0; i < l.size(); i++) if (l.getString(i).equals(k)) return;
        l.add(StringTag.valueOf(k));
        pd.put(MARKS, l);
    }

    /** Every second: walked into a mansion your Sniffer led you to? One of its Evokers has been waiting. */
    public static void playerTick(ServerPlayer p) {
        if (p.tickCount % 20 != 0) return;
        CompoundTag pd = p.getPersistentData();
        if (!pd.contains(MARKS)) return;
        ListTag l = pd.getList(MARKS, Tag.TAG_STRING);
        if (l.isEmpty()) return;
        ServerLevel sl = p.serverLevel();
        String dim = sl.dimension().location() + "|";
        for (int i = 0; i < l.size(); i++) {
            String k = l.getString(i);
            if (!k.startsWith(dim)) continue;
            String[] parts = k.split("\\|");
            int x = Integer.parseInt(parts[1]), z = Integer.parseInt(parts[2]);
            if (Math.abs(p.getX() - x) > 160.0D || Math.abs(p.getZ() - z) > 160.0D) continue;
            var reg = sl.registryAccess().registryOrThrow(Registries.STRUCTURE);
            Structure mansion = reg.get(BuiltinStructures.WOODLAND_MANSION);
            if (mansion == null) return;
            StructureStart start = sl.structureManager().getStructureWithPieceAt(p.blockPosition(), mansion);
            if (!start.isValid()) continue;
            l.remove(i);
            pd.put(MARKS, l);
            wake(sl, start, p);
            return;
        }
    }

    /** One of the mansion's own Evokers becomes it (or, if they're all dead, one more turns up somewhere inside). */
    private static void wake(ServerLevel sl, StructureStart start, ServerPlayer p) {
        BoundingBox bb = start.getBoundingBox();
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(bb.minX(), bb.minY(), bb.minZ(), bb.maxX() + 1, bb.maxY() + 1, bb.maxZ() + 1);
        List<Evoker> evokers = sl.getEntitiesOfClass(Evoker.class, box, Entity::isAlive);
        for (Evoker e : evokers) if (e.getTags().contains(TAG)) return; // already here
        Evoker ev;
        if (!evokers.isEmpty()) {
            // the one furthest from you: you'll find it as you explore
            evokers.sort((a, b) -> Double.compare(b.distanceToSqr(p), a.distanceToSqr(p)));
            ev = evokers.get(0);
        } else {
            BlockPos spot = spotInside(sl, start, p);
            if (spot == null) return;
            ev = EntityType.EVOKER.create(sl);
            if (ev == null) return;
            ev.moveTo(spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D, sl.random.nextFloat() * 360.0F, 0.0F);
            ev.finalizeSpawn(sl, sl.getCurrentDifficultyAt(spot), MobSpawnType.STRUCTURE, null);
            ev.getPersistentData().putBoolean(StandManager.PILLAGER_ROLLED, true);
            ev.getPersistentData().putString(StandManager.PILLAGER_STAND, StandType.ENDER_WORLD.name());
            ev.setPersistenceRequired();
            sl.addFreshEntity(ev);
        }
        become(ev);
    }

    /** /stand evoker timestop: one appears a few blocks in front of you. */
    public static void spawnTest(ServerPlayer p) {
        ServerLevel sl = p.serverLevel();
        Vec3 at = inFront(p);
        Evoker ev = EntityType.EVOKER.create(sl);
        if (ev == null) return;
        ev.moveTo(at.x, at.y, at.z, p.getYRot() + 180.0F, 0.0F);
        ev.finalizeSpawn(sl, sl.getCurrentDifficultyAt(ev.blockPosition()), MobSpawnType.COMMAND, null);
        prepare(ev);
        sl.addFreshEntity(ev); // (its Stand comes out of the usual Illager join: The Ender World)
    }

    /** A spot on the ground about 5 blocks in front of you. */
    static Vec3 inFront(ServerPlayer p) {
        Vec3 look = p.getLookAngle().multiply(1.0D, 0.0D, 1.0D);
        look = look.lengthSqr() < 1.0E-4D ? new Vec3(0.0D, 0.0D, 1.0D) : look.normalize();
        Vec3 at = p.position().add(look.scale(5.0D));
        ServerLevel sl = p.serverLevel();
        BlockPos b = BlockPos.containing(at);
        for (int i = 0; i < 6 && !sl.getBlockState(b).isAir(); i++) b = b.above();
        for (int i = 0; i < 8 && sl.getBlockState(b.below()).getCollisionShape(sl, b.below()).isEmpty(); i++) b = b.below();
        return new Vec3(at.x, b.getY(), at.z);
    }

    /** Turn this Evoker into the one that can stop time. Looks exactly the same. */
    private static void become(Evoker ev) {
        prepare(ev);
        // its old Stand goes; The Ender World comes out instead (hidden until it fights)
        ServerLevel sl = (ServerLevel) ev.level();
        for (StandEntity s : sl.getEntitiesOfClass(StandEntity.class, ev.getBoundingBox().inflate(16.0D), s -> s.isMobStand() && s.getMobOwner() == ev)) s.discard();
        StandManager.spawnMobStand(ev, StandType.ENDER_WORLD);
        ACTIVE.add(ev);
    }

    private static void prepare(Evoker ev) {
        ev.addTag(TAG);
        ev.setPersistenceRequired();
        CompoundTag pd = ev.getPersistentData();
        pd.putBoolean(StandManager.PILLAGER_ROLLED, true);
        pd.putString(StandManager.PILLAGER_STAND, StandType.ENDER_WORLD.name());
        pd.putBoolean("mobstands:encounter_boss", true); // its Stand hits as hard as a real Stand user's
        var hp = ev.getAttribute(Attributes.MAX_HEALTH);
        if (hp != null) hp.setBaseValue(100.0D);
        ev.setHealth(100.0F);
        var arm = ev.getAttribute(Attributes.ARMOR);
        if (arm != null) arm.setBaseValue(8.0D);
    }

    /** A loaded one comes back into the world. */
    public static void onJoin(Entity e) {
        if (e instanceof Mob m && e.getTags().contains(TAG)) ACTIVE.add(m);
    }

    private static BlockPos spotInside(ServerLevel sl, StructureStart start, ServerPlayer p) {
        List<StructurePiece> pieces = new ArrayList<>(start.getPieces());
        java.util.Collections.shuffle(pieces, new java.util.Random(sl.random.nextLong()));
        for (StructurePiece piece : pieces) {
            BoundingBox b = piece.getBoundingBox();
            BlockPos c = b.getCenter();
            if (c.distSqr(p.blockPosition()) < 20 * 20) continue;
            for (int y = b.minY(); y < b.maxY() - 1; y++) {
                BlockPos at = new BlockPos(c.getX(), y, c.getZ());
                if (sl.getBlockState(at).isAir() && sl.getBlockState(at.above()).isAir()
                        && !sl.getBlockState(at.below()).getCollisionShape(sl, at.below()).isEmpty()) return at;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ the fight

    public static void tick(MinecraftServer server) {
        if (ACTIVE.isEmpty()) return;
        Iterator<Mob> it = ACTIVE.iterator();
        while (it.hasNext()) {
            Mob m = it.next();
            if (m.isRemoved() || !m.isAlive()) {
                it.remove();
                NEXT_STOP.remove(m);
                FIGHTING_SINCE.remove(m);
                continue;
            }
            ServerLevel sl = (ServerLevel) m.level();
            long now = server.getTickCount();
            LivingEntity t = m.getTarget();
            int el = TimeStop.elapsed(m);
            if (el >= 0) {
                if (t instanceof ServerPlayer tp) stoppedTime(m, tp, sl, el);
                continue;
            }
            if (!(t instanceof ServerPlayer tp) || tp.isCreative() || tp.isSpectator() || !tp.isAlive()) {
                FIGHTING_SINCE.remove(m);
                continue;
            }
            long since = FIGHTING_SINCE.computeIfAbsent(m, k -> now);
            long next = NEXT_STOP.getOrDefault(m, 0L);
            boolean ready = now >= next && (now - since > 80 || m.getHealth() < m.getMaxHealth() * 0.8F);
            if (ready && m.distanceToSqr(tp) < 22.0D * 22.0D && m.hasLineOfSight(tp) && !TimeStop.serverStopped) {
                if (TimeStop.start(m, STOP_TICKS)) NEXT_STOP.put(m, now + STOP_TICKS + STOP_EVERY);
            }
        }
    }

    /** In its stopped time: walk up, surround you with arrows, hit you, step back. */
    private static void stoppedTime(Mob m, ServerPlayer p, ServerLevel sl, int el) {
        if (el == 10) {
            // right beside you
            Vec3 look = p.getLookAngle().multiply(1.0D, 0.0D, 1.0D).normalize();
            Vec3 side = new Vec3(-look.z, 0.0D, look.x);
            Vec3 to = p.position().add(side.scale(sl.random.nextBoolean() ? 2.2D : -2.2D)).add(look.scale(0.5D));
            BlockPos bp = BlockPos.containing(to);
            if (sl.getBlockState(bp).isAir() && sl.getBlockState(bp.above()).isAir()) m.teleportTo(to.x, to.y, to.z);
            m.getLookControl().setLookAt(p);
        }
        if (el >= 16 && el <= 44 && el % 4 == 0) {
            // an arrow left hanging in the air, pointed at you
            double a = sl.random.nextDouble() * Math.PI * 2.0D;
            double up = 0.6D + sl.random.nextDouble() * 1.4D;
            Vec3 from = p.position().add(Math.cos(a) * 3.0D, up, Math.sin(a) * 3.0D);
            Vec3 aim = p.position().add(0.0D, p.getBbHeight() * 0.55D, 0.0D).subtract(from).normalize();
            Arrow arrow = new Arrow(sl, m, new ItemStack(net.minecraft.world.item.Items.ARROW), null);
            arrow.setPos(from.x, from.y, from.z);
            arrow.setDeltaMovement(aim.scale(1.7D));
            arrow.setYRot((float) (Math.atan2(aim.x, aim.z) * 180.0D / Math.PI));
            arrow.setXRot((float) (Math.atan2(aim.y, aim.horizontalDistance()) * 180.0D / Math.PI));
            arrow.yRotO = arrow.getYRot();
            arrow.xRotO = arrow.getXRot();
            arrow.setBaseDamage(1.5D);
            arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
            sl.addFreshEntity(arrow);
            sl.playSound(null, from.x, from.y, from.z, net.minecraft.sounds.SoundEvents.ARROW_SHOOT, net.minecraft.sounds.SoundSource.HOSTILE, 0.6F, 1.4F);
        }
        if (el == 52) {
            // its Stand hits you (it all lands when time moves)
            for (StandEntity s : sl.getEntitiesOfClass(StandEntity.class, m.getBoundingBox().inflate(16.0D), s -> s.isMobStand() && s.getMobOwner() == m)) {
                s.lunge(p.position().add(0.0D, 1.0D, 0.0D), 6);
                s.playAnim(0);
            }
            p.hurt(sl.damageSources().mobAttack(m), 6.0F);
        }
        if (el == 72) {
            // and back away, as if it never moved
            Vec3 away = m.position().subtract(p.position()).multiply(1.0D, 0.0D, 1.0D);
            if (away.lengthSqr() < 1.0E-4D) away = new Vec3(1.0D, 0.0D, 0.0D);
            Vec3 to = p.position().add(away.normalize().scale(7.0D));
            BlockPos bp = BlockPos.containing(to);
            if (sl.getBlockState(bp).isAir() && sl.getBlockState(bp.above()).isAir()) m.teleportTo(to.x, to.y, to.z);
        }
    }

    /** It dies: three Stand Arrows, always - and half the time, a Spin scroll. */
    public static void onDeath(LivingEntity e, net.minecraft.world.damagesource.DamageSource src) {
        if (!e.getTags().contains(TAG) || !(src.getEntity() instanceof Player)) return;
        e.spawnAtLocation(new ItemStack(ModItems.ECHO_ARROW.get(), 3));
        if (e.getRandom().nextFloat() < 0.5F) e.spawnAtLocation(new ItemStack(ModItems.SPIN_SCROLL.get()));
    }

    /** On the way to a mansion your Sniffer found (in this dimension)? */
    public static boolean headingToMansion(ServerPlayer p) {
        CompoundTag pd = p.getPersistentData();
        if (!pd.contains(MARKS)) return false;
        ListTag l = pd.getList(MARKS, Tag.TAG_STRING);
        String dim = p.serverLevel().dimension().location() + "|";
        for (int i = 0; i < l.size(); i++) if (l.getString(i).startsWith(dim)) return true;
        return false;
    }

    public static void shutdown() {
        ACTIVE.clear();
        NEXT_STOP.clear();
        FIGHTING_SINCE.clear();
    }
}
