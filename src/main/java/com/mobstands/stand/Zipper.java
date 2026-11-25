package com.mobstands.stand;

import com.mobstands.entity.ZipperEntity;
import com.mobstands.registry.ModEntities;
import com.mobstands.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Silver Fingers' zippers, Sticky Fingers style.
 * <ul>
 * <li>Zip Away: a zipper runs down the wall (or floor) you're looking at, it splits open onto a purple pocket
 * dimension, you slide in, and it zips shut behind you. Nothing can see, find or hurt you in there. Press again to
 * unzip it and step back out.</li>
 * <li>Zip Through: in one side of a wall (or floor, or ceiling) and out the other.</li>
 * <li>Zipper Punch: a punch that leaves a zipper on whatever it hits - it unzips, and the creature comes apart.</li>
 * </ul>
 */
public final class Zipper {
    private Zipper() {}

    /** How far away a wall can be zipped. */
    public static final double REACH = 4.5D;
    /** How thick a wall Zip Through can go through. */
    public static final int THROUGH_MAX = 12;

    private static final class Hide {
        ServerLevel level;
        ZipperEntity zip;        // the zipper you're going in (or coming out of)
        Vec3 hold;               // where your body is held while it happens
        Vec3 exit;               // where you step out
        Vec3 through;            // Zip Through: where you come out on the far side (null for Zip Away)
        BlockPos block;          // the block you're in
        boolean wasNoGravity;
    }

    private static final Map<UUID, Hide> HIDES = new HashMap<>();
    /** Zippers left on creatures by Zipper Punch, and who they're on. */
    private static final Map<ZipperEntity, LivingEntity> MOB_ZIPS = new HashMap<>();

    /** Is this zipper still being used? (Otherwise it removes itself.) */
    public static boolean owns(ZipperEntity z) {
        if (MOB_ZIPS.containsKey(z)) return true;
        for (Hide h : HIDES.values()) if (h.zip == z) return true;
        return false;
    }

    /** Is this player busy with a zipper (going in, inside, coming out)? */
    public static boolean busy(Entity e) {
        return e instanceof ServerPlayer && !HIDES.isEmpty() && HIDES.containsKey(e.getUUID());
    }

    /** Inside the pocket dimension right now (fully in: unseen, untouchable)? */
    public static boolean inside(Entity e) {
        if (!busy(e)) return false;
        int s = HIDES.get(e.getUUID()).zip.state();
        return s == ZipperEntity.INSIDE || s == ZipperEntity.OPEN_EXIT;
    }

    /** Read by TrackedEntityMixin: someone inside the pocket dimension is gone from everyone else's game. */
    public static boolean hiddenFrom(Entity e, ServerPlayer viewer) {
        if (HIDES.isEmpty() || e == viewer) return false;
        if (!(e instanceof ServerPlayer)) return false;
        Hide h = HIDES.get(e.getUUID());
        return h != null && h.zip.state() == ZipperEntity.INSIDE;
    }

    /** Nothing reaches you in there (except things nothing survives, like the void). */
    public static boolean blocks(LivingEntity e, DamageSource src) {
        return busy(e) && !src.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY);
    }

    // ------------------------------------------------------------------ Zip Away

    public static boolean hide(AbilityContext c) {
        if (busy(c.owner)) return false;
        Target t = target(c);
        if (t == null) {
            c.fail = "Look at a wall or the ground close by.";
            return false;
        }
        Hide h = new Hide();
        h.level = c.level;
        h.block = t.block;
        h.hold = c.owner.position();
        h.exit = standSpot(c.level, t, c.owner.position());
        h.zip = spawn(c.level, t, c.owner, ZipperEntity.OPEN_ENTER);
        begin(c.owner, h);
        return true;
    }

    // ------------------------------------------------------------------ Zip Through

    public static boolean through(AbilityContext c) {
        if (busy(c.owner)) return false;
        Target t = target(c);
        if (t == null) {
            c.fail = "Look at a wall, floor or ceiling close by.";
            return false;
        }
        // Straight through the block, the way you went in, until there's room for you on the other side.
        Direction dir = t.face.getOpposite();
        BlockPos last = t.block;
        Vec3 out = null;
        for (int i = 1; i <= THROUGH_MAX; i++) {
            BlockPos p = t.block.relative(dir, i);
            if (!c.level.isInWorldBounds(p)) break;
            if (solid(c.level, p)) {
                last = p;
                continue;
            }
            BlockPos feet = null;
            if (dir.getAxis().isHorizontal()) {
                // room for your body here (or a step down), then down onto the floor
                for (BlockPos cand : new BlockPos[]{p, p.below()}) {
                    if (!solid(c.level, cand) && !solid(c.level, cand.above())) { feet = cand; break; }
                }
                if (feet != null) for (int k = 0; k < 2 && !solid(c.level, feet.below()); k++) feet = feet.below();
            } else if (dir == Direction.UP) {
                if (!solid(c.level, p.above())) feet = p;
            } else { // DOWN: you drop out of a ceiling - room for your whole body
                if (!solid(c.level, p.below())) feet = p.below();
            }
            if (feet == null) continue;
            if (solid(c.level, p.relative(dir.getOpposite()))) last = p.relative(dir.getOpposite());
            out = Vec3.atBottomCenterOf(feet);
            break;
        }
        if (out == null) {
            c.fail = "Too thick - there's no other side within " + THROUGH_MAX + " blocks.";
            return false;
        }
        Hide h = new Hide();
        h.level = c.level;
        h.block = t.block;
        h.hold = c.owner.position();
        h.exit = out;
        // The zipper you come out of: on the far face of the last block, facing the way you're going.
        h.through = Vec3.atCenterOf(last).add(Vec3.atLowerCornerOf(dir.getNormal()).scale(0.5D));
        h.zip = spawn(c.level, t, c.owner, ZipperEntity.OPEN_ENTER);
        begin(c.owner, h);
        return true;
    }

    // ------------------------------------------------------------------ Zipper Punch

    public static void zipMob(AbilityContext c, LivingEntity target) {
        ZipperEntity z = ModEntities.ZIPPER.get().create(c.level);
        if (z == null) return;
        Vec3 toOwner = c.owner.position().subtract(target.position());
        Direction face = Direction.getNearest(toOwner.x, 0.0D, toOwner.z);
        z.setup(-1, face, Direction.UP, Math.max(0.5F, target.getBbHeight() * 0.7F), ZipperEntity.MOB_OPEN);
        placeOnMob(z, target);
        c.level.addFreshEntity(z);
        MOB_ZIPS.put(z, target);
        zipSound(c.level, z.position(), 1.1F);
    }

    private static void placeOnMob(ZipperEntity z, LivingEntity t) {
        Vec3 n = z.normalVec();
        double half = Math.max(t.getBbWidth() * 0.5D, 0.2D) + 0.03D;
        z.setPos(t.getX() + n.x * half, t.getY() + t.getBbHeight() * 0.55D, t.getZ() + n.z * half);
    }

    // ------------------------------------------------------------------ the flow

    private static void begin(ServerPlayer p, Hide h) {
        HIDES.put(p.getUUID(), h);
        h.wasNoGravity = p.isNoGravity();
        p.setNoGravity(true);
        p.setDeltaMovement(Vec3.ZERO);
        p.hurtMarked = true;
        zipSound(h.level, h.zip.position(), 1.0F);
    }

    /** Press Zip Away again while you're inside: out you come. */
    public static boolean leave(ServerPlayer p) {
        Hide h = HIDES.get(p.getUUID());
        if (h == null || h.through != null) return false;
        if (h.zip.state() != ZipperEntity.INSIDE) return true; // still going in: it'll let you out once you're in
        h.zip.setState(ZipperEntity.OPEN_EXIT);
        zipSound(h.level, h.zip.position(), 1.0F);
        refresh(h.level);
        return true;
    }

    public static void tick(MinecraftServer server) {
        if (!MOB_ZIPS.isEmpty()) tickMobZips();
        if (HIDES.isEmpty()) return;
        Iterator<Map.Entry<UUID, Hide>> it = HIDES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Hide> en = it.next();
            Hide h = en.getValue();
            ServerPlayer p = server.getPlayerList().getPlayer(en.getKey());
            StandData d = p != null ? StandManager.data(p) : null;
            if (p == null || !p.isAlive() || p.level() != h.level || h.zip.isRemoved()
                    || d.getType() != StandType.SILVERFISH || !d.isSummoned()) {
                it.remove();
                if (!h.zip.isRemoved()) h.zip.setState(ZipperEntity.CLOSE);
                if (p != null) release(p, h, p.level() == h.level && p.isAlive());
                if (h.level != null) refresh(h.level);
                continue;
            }
            int s = h.zip.state();
            float t = h.zip.since(0.0F);
            // Hold the body still the whole time.
            Vec3 at = s == ZipperEntity.INSIDE || s == ZipperEntity.OPEN_EXIT || s == ZipperEntity.SLIDE_OUT ? h.exit : h.hold;
            if (p.position().distanceToSqr(at) > 0.01D) p.teleportTo(at.x, at.y, at.z);
            p.setDeltaMovement(Vec3.ZERO);
            p.fallDistance = 0.0F;
            switch (s) {
                case ZipperEntity.OPEN_ENTER -> {
                    if (t >= ZipperEntity.OPEN_TICKS) h.zip.setState(ZipperEntity.SLIDE_IN);
                }
                case ZipperEntity.SLIDE_IN -> {
                    if (t >= ZipperEntity.SLIDE_TICKS) {
                        // In. Anything that was after you loses you.
                        forget(p);
                        h.level.sendParticles(ParticleTypes.PORTAL, h.zip.getX(), h.zip.getY(), h.zip.getZ(), 30, 0.2D, 0.4D, 0.2D, 0.4D);
                        if (h.through != null) {
                            // Through the wall: this zipper shuts behind you, one on the far side opens.
                            h.zip.setState(ZipperEntity.CLOSE);
                            zipSound(h.level, h.zip.position(), 1.15F);
                            p.teleportTo(h.exit.x, h.exit.y, h.exit.z);
                            Direction dir = h.zip.normal().getOpposite();
                            Target far = new Target(BlockPos.containing(h.through.subtract(Vec3.atLowerCornerOf(dir.getNormal()).scale(0.5D))), dir, h.through);
                            h.zip = spawn(h.level, far, p, ZipperEntity.OPEN_EXIT);
                            zipSound(h.level, h.zip.position(), 1.0F);
                        } else {
                            h.zip.setState(ZipperEntity.INSIDE);
                            p.teleportTo(h.exit.x, h.exit.y, h.exit.z);
                            zipSound(h.level, h.zip.position(), 1.15F);
                            refresh(h.level);
                        }
                    }
                }
                case ZipperEntity.INSIDE -> {
                    // The wall you're in was broken: you're thrown back out.
                    if (!solid(h.level, h.block)) leave(p);
                    else if (h.zip.tickCount % 40 == 0) forget(p);
                }
                case ZipperEntity.OPEN_EXIT -> {
                    if (t >= ZipperEntity.OPEN_TICKS) h.zip.setState(ZipperEntity.SLIDE_OUT);
                }
                case ZipperEntity.SLIDE_OUT -> {
                    if (t >= ZipperEntity.SLIDE_TICKS) {
                        h.zip.setState(ZipperEntity.CLOSE);
                        zipSound(h.level, h.zip.position(), 1.15F);
                        it.remove();
                        release(p, h, true);
                    }
                }
                default -> { }
            }
        }
    }

    private static void tickMobZips() {
        Iterator<Map.Entry<ZipperEntity, LivingEntity>> it = MOB_ZIPS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ZipperEntity, LivingEntity> en = it.next();
            ZipperEntity z = en.getKey();
            LivingEntity t = en.getValue();
            if (z.isRemoved() || !t.isAlive()) {
                it.remove();
                if (!z.isRemoved()) z.setState(ZipperEntity.MOB_CLOSE);
                continue;
            }
            placeOnMob(z, t);
            float since = z.since(0.0F);
            if (z.state() == ZipperEntity.MOB_OPEN && since >= 40.0F) {
                z.setState(ZipperEntity.MOB_CLOSE);
                zipSound((ServerLevel) z.level(), z.position(), 1.2F);
                it.remove();
            }
        }
    }

    private static void release(ServerPlayer p, Hide h, boolean place) {
        p.setNoGravity(h.wasNoGravity);
        p.fallDistance = 0.0F;
        if (place && p.position().distanceToSqr(h.exit) > 0.01D) p.teleportTo(h.exit.x, h.exit.y, h.exit.z);
    }

    /** Everything that was after you loses track of you. */
    private static void forget(ServerPlayer p) {
        for (Mob m : p.serverLevel().getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(64.0D), x -> x.getTarget() == p)) {
            m.setTarget(null);
            if (m instanceof net.minecraft.world.entity.monster.warden.Warden w) w.clearAnger(p);
        }
    }

    private static void refresh(ServerLevel sl) {
        for (ServerPlayer pl : sl.players()) sl.getChunkSource().move(pl);
    }

    public static void clear(ServerPlayer p) {
        Hide h = HIDES.remove(p.getUUID());
        if (h == null) return;
        if (!h.zip.isRemoved()) h.zip.setState(ZipperEntity.CLOSE);
        release(p, h, true);
        refresh(h.level);
    }

    public static void shutdown() {
        HIDES.clear();
        MOB_ZIPS.clear();
    }

    // ------------------------------------------------------------------ helpers

    private record Target(BlockPos block, Direction face, Vec3 center) {}

    private static Target target(AbilityContext c) {
        Vec3 eye = c.owner.getEyePosition();
        BlockHitResult hit = c.level.clip(new ClipContext(eye, eye.add(c.look().scale(REACH)), ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, c.owner));
        if (hit.getType() != HitResult.Type.BLOCK || !solid(c.level, hit.getBlockPos())) return null;
        BlockPos pos = hit.getBlockPos();
        Direction face = hit.getDirection();
        Vec3 center = Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.5D));
        return new Target(pos, face, center);
    }

    private static ZipperEntity spawn(ServerLevel level, Target t, ServerPlayer p, int state) {
        ZipperEntity z = ModEntities.ZIPPER.get().create(level);
        Direction along;
        float len;
        Vec3 center = t.center;
        if (t.face.getAxis().isHorizontal()) {
            along = Direction.UP;
            // A body-sized zipper if the wall goes on above or below, otherwise just this block.
            if (wallFace(level, t.block.above(), t.face)) { center = center.add(0.0D, 0.5D, 0.0D); len = 1.8F; }
            else if (wallFace(level, t.block.below(), t.face)) { center = center.add(0.0D, -0.5D, 0.0D); len = 1.8F; }
            else len = 0.95F;
        } else {
            along = p.getDirection();
            len = wallFace(level, t.block.relative(along), t.face) ? 1.8F : 0.95F;
            if (len > 1.0F) center = center.add(Vec3.atLowerCornerOf(along.getNormal()).scale(0.5D));
        }
        z.setup(p.getId(), t.face, along, len, state);
        z.setPos(center.x, center.y, center.z);
        level.addFreshEntity(z);
        return z;
    }

    private static boolean wallFace(ServerLevel level, BlockPos pos, Direction face) {
        return solid(level, pos) && !solid(level, pos.relative(face));
    }

    /** Where you'll be standing when you step back out: right in front of the zipper. */
    private static Vec3 standSpot(ServerLevel level, Target t, Vec3 fallback) {
        if (t.face == Direction.UP) return Vec3.atBottomCenterOf(t.block.above());
        if (t.face == Direction.DOWN) return fallback;
        BlockPos p = t.block.relative(t.face);
        for (int k = 0; k < 2 && !solid(level, p.below()); k++) p = p.below();
        if (solid(level, p) || solid(level, p.above())) return fallback;
        return Vec3.atBottomCenterOf(p);
    }

    private static boolean solid(ServerLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return !s.isAir() && !s.getCollisionShape(level, pos).isEmpty();
    }

    private static void zipSound(ServerLevel level, Vec3 at, float pitch) {
        level.playSound(null, at.x, at.y, at.z, ModSounds.ZIPPER.get(), SoundSource.PLAYERS, 1.0F,
                pitch * (0.95F + level.random.nextFloat() * 0.1F));
    }

    /** Zipper Punch: the creature comes apart along the zipper. */
    public static void apart(AbilityContext c, LivingEntity t) {
        c.effect(t, MobEffects.WEAKNESS, 80, 1);
        c.effect(t, MobEffects.MOVEMENT_SLOWDOWN, 80, 1);
    }
}
