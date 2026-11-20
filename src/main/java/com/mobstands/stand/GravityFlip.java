package com.mobstands.stand;

import com.mobstands.network.GravityPayload;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * C-Moon: gravity turned another way for one creature. Its "down" becomes a wall or the sky: it falls that way,
 * slams into whatever it hits, then lives on that surface - walking, chasing, shooting from it.
 * <ul>
 *   <li>Gravity Punch (passive): from the 10th punch on the same target, each punch may flip it left, right or up.
 *       Lasts 3 minutes, or until the C-Moon user dies.</li>
 *   <li>Gravity Shift: the C-Moon user's own gravity, pointed at the surface they look at (walls, ceilings).</li>
 * </ul>
 * Mobs are moved here on the server; players move themselves on their own game (GravityClient), the server only
 * keeps the slam damage and the bookkeeping.
 */
public final class GravityFlip {
    private GravityFlip() {}

    public static final int FLIP_TICKS = 20 * 60 * 3;
    public static final int PUNCHES_BEFORE = 10;
    public static final double SHIFT_RANGE = 24.0D;

    private static final class Flip {
        final UUID id;
        final ServerLevel level;
        Direction g;
        final long until;
        final UUID owner;
        final boolean self;
        final boolean hadNoGravity;
        boolean grantedFly;
        Vec3 last;
        double fallAlong;
        Vec3 wander = Vec3.ZERO;
        int wanderTime;

        Flip(UUID id, ServerLevel level, Direction g, long until, UUID owner, boolean self, boolean hadNoGravity) {
            this.id = id;
            this.level = level;
            this.g = g;
            this.until = until;
            this.owner = owner;
            this.self = self;
            this.hadNoGravity = hadNoGravity;
        }
    }

    private static final Map<UUID, Flip> FLIPS = new HashMap<>();
    /** Per C-Moon user: who they're punching and how many times in a row. */
    private static final Map<UUID, UUID> LAST_TARGET = new HashMap<>();
    private static final Map<UUID, Integer> HITS = new HashMap<>();

    // ------------------------------------------------------------------ shared maths (both sides)

    /** The rotation that turns the normal world (down = -Y) into one where down is {@code g}. */
    public static Quaternionf frame(Direction g) {
        Quaternionf q = new Quaternionf();
        if (g == null) return q;
        float h = (float) (Math.PI / 2.0D);
        return switch (g) {
            case DOWN -> q;
            case UP -> q.rotationX((float) Math.PI);
            case EAST -> q.rotationZ(h);
            case WEST -> q.rotationZ(-h);
            case SOUTH -> q.rotationX(-h);
            case NORTH -> q.rotationX(h);
        };
    }

    public static Vec3 vec(Direction d) {
        return new Vec3(d.getStepX(), d.getStepY(), d.getStepZ());
    }

    public static boolean anyFlipped() {
        return !FLIPS.isEmpty();
    }

    /** Which way "down" is for this creature on the server (null = normal). */
    public static Direction dir(Entity e) {
        Flip f = FLIPS.get(e.getUUID());
        return f == null ? null : f.g;
    }

    public static boolean isFlipped(Entity e) {
        return FLIPS.containsKey(e.getUUID());
    }

    // ------------------------------------------------------------------ Gravity Punch

    /** Gravity Punch: the arrow you picked, waiting for your next punch (and until when it waits). */
    private record Armed(int choice, long until) {}
    private static final Map<UUID, Armed> ARMED = new HashMap<>();
    /** How long a picked arrow waits for a punch. */
    public static final int ARMED_TICKS = 20 * 15;
    public static final int GRAVITY_PUNCH_SLOT = 2;

    /** The wheel closed on an arrow: the Shulker opens, charged, and the next punch carries it. */
    public static void arm(ServerPlayer p, int choice) {
        if (choice < 0 || choice > 4) return;
        StandData d = StandManager.data(p);
        if (d.getType() != StandType.SHULKER || !d.isSummoned()) return;
        long now = p.level().getGameTime();
        if (d.cooldownEnd(GRAVITY_PUNCH_SLOT) > now) return;
        ARMED.put(p.getUUID(), new Armed(choice, now + ARMED_TICKS));
        var stand = StandManager.getStand(p);
        Vec3 at = stand != null ? stand.position().add(0.0D, 0.5D, 0.0D) : p.position().add(0.0D, 1.6D, 0.0D);
        ServerLevel sl = p.serverLevel();
        sl.sendParticles(ParticleTypes.END_ROD, at.x, at.y, at.z, 10, 0.25D, 0.25D, 0.25D, 0.03D);
        sl.playSound(null, at.x, at.y, at.z, SoundEvents.SHULKER_OPEN, SoundSource.PLAYERS, 1.0F, 1.3F);
    }

    /**
     * Gravity Turn: the wheel on C, for your own gravity - instant, no aiming. 0 ceiling, 1 left wall, 2 right wall,
     * 3 normal floor, 4 the wall in front of you (left / right / front as you're facing).
     */
    private static final Map<UUID, Long> LAST_TURN = new HashMap<>();

    public static void turnSelf(ServerPlayer p, int choice) {
        if (choice < 0 || choice > 4) return;
        long t = p.level().getGameTime();
        Long last = LAST_TURN.get(p.getUUID());
        if (last != null && t - last < 5L) return; // a quarter second between turns
        LAST_TURN.put(p.getUUID(), t);
        StandData d = StandManager.data(p);
        if (d.getType() != StandType.SHULKER || !d.isSummoned()) return;
        Flip cur = FLIPS.get(p.getUUID());
        ServerLevel sl = p.serverLevel();
        if (choice == 3) {
            if (cur == null) return;
            unflip(cur);
            sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.SHULKER_CLOSE, SoundSource.PLAYERS, 1.0F, 0.8F);
            return;
        }
        Direction g = direction(p, choice == 4 ? 4 : choice);
        if (cur != null && cur.g == g) return;
        flip(p, g, p.getUUID(), true, Long.MAX_VALUE / 4);
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.SHULKER_OPEN, SoundSource.PLAYERS, 1.0F, 0.7F);
    }

    /** Which way "down" turns for the punched creature, as the puncher sees it. */
    private static Direction direction(ServerPlayer owner, int choice) {
        double yaw = Math.toRadians(owner.getYRot());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);   // where you face
        double rx = -Math.cos(yaw), rz = -Math.sin(yaw);  // your right
        return switch (choice) {
            case 0 -> Direction.UP;
            case 1 -> Direction.getNearest(-rx, 0.0D, -rz);
            case 2 -> Direction.getNearest(rx, 0.0D, rz);
            case 3 -> Direction.getNearest(-fx, 0.0D, -fz);
            default -> Direction.getNearest(fx, 0.0D, fz);
        };
    }

    /** A C-Moon punch landed on {@code target}: if an arrow is picked, its gravity turns that way. */
    public static void onPunch(ServerPlayer owner, LivingEntity target, int level) {
        if (!target.isAlive() || target == owner || isBoss(target)) return;
        Armed a = ARMED.get(owner.getUUID());
        long now = owner.level().getGameTime();
        if (a == null) return;
        ARMED.remove(owner.getUUID());
        if (now > a.until) return;
        flip(target, direction(owner, a.choice), owner.getUUID(), false, FLIP_TICKS);
        StandData d = StandManager.data(owner);
        Ability ab = StandType.SHULKER.ability(GRAVITY_PUNCH_SLOT);
        d.setCooldownEnd(GRAVITY_PUNCH_SLOT, now + ab.cooldownTicks(d.getLevel()));
        StandManager.sync(owner);
    }

    private static boolean isBoss(LivingEntity e) {
        return e instanceof net.minecraft.world.entity.boss.wither.WitherBoss
                || e instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon;
    }

    // ------------------------------------------------------------------ Gravity Shift

    /** Your own gravity toward the surface you're looking at; look at the floor (or nothing) to set it back. */
    public static boolean gravityShift(AbilityContext c) {
        ServerPlayer p = c.owner;
        Flip cur = FLIPS.get(p.getUUID());
        Vec3 eye = p.getEyePosition();
        Vec3 end = eye.add(p.getLookAngle().scale(SHIFT_RANGE));
        BlockHitResult hit = p.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        Direction g = hit.getType() == HitResult.Type.BLOCK ? hit.getDirection().getOpposite() : null;
        if (g == null || g == Direction.DOWN) {
            if (cur == null) return false; // nothing to stand on, and you're already normal
            unflip(cur);
            c.sound(SoundEvents.SHULKER_CLOSE, 1.0F, 0.8F);
            return true;
        }
        if (cur != null && cur.g == g) return false;
        flip(p, g, p.getUUID(), true, Long.MAX_VALUE / 4);
        c.sound(SoundEvents.SHULKER_OPEN, 1.0F, 0.7F);
        return true;
    }

    /** Your own game walked you onto another wall while Gravity Shift is on. */
    public static void requestSwitch(ServerPlayer p, int dir) {
        if (true) return; // no longer used: gravity only changes on the server's say-so (Z, the C wheel, punches)
        Flip f = FLIPS.get(p.getUUID());
        if (f == null || !f.self || dir < 0 || dir >= 6) return;
        Direction d = Direction.from3DDataValue(dir);
        if (d == f.g) return;
        if (d == Direction.DOWN) {
            unflip(f);
            return;
        }
        f.g = d;
        f.fallAlong = 0.0D;
        send(p, d);
    }

    // ------------------------------------------------------------------ flipping

    public static void flip(LivingEntity e, Direction g, UUID owner, boolean self, long ticks) {
        if (!(e.level() instanceof ServerLevel sl)) return;
        Flip old = FLIPS.remove(e.getUUID());
        boolean hadNoGravity = old != null ? old.hadNoGravity : e.isNoGravity();
        Flip f = new Flip(e.getUUID(), sl, g, sl.getGameTime() + ticks, owner, self, hadNoGravity);
        if (old != null) f.grantedFly = old.grantedFly;
        FLIPS.put(e.getUUID(), f);
        if (!(e instanceof ServerPlayer)) {
            e.setNoGravity(true);
            e.setDeltaMovement(e.getDeltaMovement().add(vec(g).scale(0.35D)));
            if (e instanceof Mob m) m.getNavigation().stop();
        }
        if (!self) {
            Vec3 at = e.position().add(0.0D, e.getBbHeight() * 0.5D, 0.0D);
            sl.sendParticles(ParticleTypes.END_ROD, at.x, at.y, at.z, 14, 0.3D, 0.4D, 0.3D, 0.06D);
            sl.playSound(null, at.x, at.y, at.z, SoundEvents.SHULKER_BULLET_HIT, SoundSource.PLAYERS, 1.0F, 0.6F);
            sl.playSound(null, at.x, at.y, at.z, SoundEvents.ENDER_EYE_DEATH, SoundSource.PLAYERS, 0.8F, 0.6F);
        }
        send(e, g);
    }

    private static void unflip(Flip f) {
        FLIPS.remove(f.id);
        Entity e = f.level.getEntity(f.id);
        if (e == null) return;
        if (e instanceof ServerPlayer sp) {
            if (f.grantedFly && !sp.isCreative() && !sp.isSpectator()) sp.getAbilities().mayfly = false;
        } else {
            e.setNoGravity(f.hadNoGravity);
        }
        send(e, null);
    }

    private static void send(Entity e, Direction g) {
        GravityBox.refresh(e); // its hitbox turns with it
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(e, new GravityPayload(e.getId(), g == null ? -1 : g.get3DDataValue()));
    }

    /** Someone starts seeing a flipped creature: tell them which way its world falls. */
    public static void onStartTracking(ServerPlayer viewer, Entity target) {
        Flip f = FLIPS.get(target.getUUID());
        if (f != null) PacketDistributor.sendToPlayer(viewer, new GravityPayload(target.getId(), f.g.get3DDataValue()));
    }

    public static void onLogin(ServerPlayer p) {
        Flip f = FLIPS.get(p.getUUID());
        if (f != null) {
            if (f.level != p.level()) unflip(f);
            else send(p, f.g);
        }
    }

    /** A creature died - and if it was a C-Moon user, everything they flipped falls normally again. */
    public static void onDeath(LivingEntity e) {
        Flip own = FLIPS.get(e.getUUID());
        if (own != null) unflip(own);
        if (!(e instanceof ServerPlayer)) return;
        List<Flip> theirs = new ArrayList<>();
        for (Flip f : FLIPS.values()) if (e.getUUID().equals(f.owner)) theirs.add(f);
        for (Flip f : theirs) unflip(f);
        LAST_TARGET.remove(e.getUUID());
        HITS.remove(e.getUUID());
        ARMED.remove(e.getUUID());
    }

    /** Gravity Shift goes away with the C-Moon. */
    public static void checkOwner(ServerPlayer p) {
        Flip f = FLIPS.get(p.getUUID());
        if (f != null && f.self && StandManager.data(p).getType() != StandType.SHULKER) unflip(f);
    }

    public static void shutdown() {
        for (Flip f : new ArrayList<>(FLIPS.values())) unflip(f);
        FLIPS.clear();
        LAST_TARGET.clear();
        HITS.clear();
        ARMED.clear();
        LAST_TURN.clear();
    }

    // ------------------------------------------------------------------ tick

    public static void tick(MinecraftServer server) {
        if (FLIPS.isEmpty()) return;
        Iterator<Flip> it = new ArrayList<>(FLIPS.values()).iterator();
        while (it.hasNext()) {
            Flip f = it.next();
            if (!f.level.tickRateManager().runsNormally()) continue; // stopped time: gravity waits too
            Entity raw = f.level.getEntity(f.id);
            long now = f.level.getGameTime();
            if (raw == null) {
                // Unloaded or logged out: keep it until its time runs out.
                if (now >= f.until && server.getPlayerList().getPlayer(f.id) == null) FLIPS.remove(f.id);
                continue;
            }
            if (!(raw instanceof LivingEntity e) || !e.isAlive() || now >= f.until || e.level() != f.level) {
                unflip(f);
                continue;
            }
            e.fallDistance = 0.0F;
            if (e instanceof ServerPlayer sp) {
                // Your own game moves you; the server just shouldn't call standing on a ceiling "flying".
                if (!sp.getAbilities().mayfly) {
                    sp.getAbilities().mayfly = true;
                    f.grantedFly = true;
                }
            } else {
                moveMob(f, e);
            }
            slam(f, e);
        }
    }

    private static boolean landed(LivingEntity e, Direction g) {
        return !e.level().noCollision(e, e.getBoundingBox().move(vec(g).scale(0.04D)));
    }

    /** A mob in its own gravity: falls its way, walks on whatever it landed on, chases its target along it. */
    private static void moveMob(Flip f, LivingEntity e) {
        e.setNoGravity(true);
        Vec3 g = vec(f.g);
        Vec3 v = e.getDeltaMovement();
        double vg = v.dot(g);
        Vec3 vp = v.subtract(g.scale(vg));
        boolean landed = landed(e, f.g);

        Vec3 wish = Vec3.ZERO;
        LivingEntity target = e instanceof Mob m ? m.getTarget() : null;
        if (target != null && target.isAlive()) {
            Vec3 to = target.position().subtract(e.position());
            to = to.subtract(g.scale(to.dot(g)));
            if (to.lengthSqr() > 1.4D) wish = to.normalize();
        } else {
            if (--f.wanderTime <= 0) {
                f.wanderTime = 60 + e.getRandom().nextInt(80);
                if (e.getRandom().nextBoolean()) {
                    f.wander = Vec3.ZERO;
                } else {
                    Vec3 r = new Vec3(e.getRandom().nextDouble() - 0.5D, e.getRandom().nextDouble() - 0.5D, e.getRandom().nextDouble() - 0.5D);
                    r = r.subtract(g.scale(r.dot(g)));
                    f.wander = r.lengthSqr() < 1.0E-4D ? Vec3.ZERO : r.normalize().scale(0.5D);
                }
            }
            wish = f.wander;
        }
        double speed = e.getAttributes().hasAttribute(Attributes.MOVEMENT_SPEED) ? e.getAttributeValue(Attributes.MOVEMENT_SPEED) : 0.25D;
        if (landed) {
            vp = vp.scale(0.6D).add(wish.scale(speed * speed * 0.8D));
            vg = Math.min(vg, 0.0D);
        } else {
            vp = vp.add(wish.scale(0.01D));
            vg += 0.08D;
        }
        // Thrown up into an open sky: it drifts above the world until its time runs out (then it all comes down).
        if (f.g == Direction.UP && e.getY() > f.level.getMaxBuildHeight() + 32) vg = Math.min(vg, 0.0D);
        e.setDeltaMovement(vp.add(g.scale(vg)));
        if (e instanceof Mob m) m.getNavigation().stop();
    }

    /** Falling "down" its own way and hitting the surface hurts, like a normal fall. */
    private static void slam(Flip f, LivingEntity e) {
        Vec3 pos = e.position();
        if (f.last != null) {
            double along = pos.subtract(f.last).dot(vec(f.g));
            if (along > 0.02D) {
                f.fallAlong += along;
            } else {
                if (f.fallAlong > 3.0D && landed(e, f.g)) {
                    float dmg = (float) Math.ceil(f.fallAlong - 3.0D);
                    ServerPlayer owner = f.self || f.owner == null ? null : f.level.getServer().getPlayerList().getPlayer(f.owner);
                    if (owner != null) StandCombat.credit(owner, e);
                    e.hurt(f.level.damageSources().fall(), dmg);
                    Vec3 at = pos.add(0.0D, e.getBbHeight() * 0.5D, 0.0D).add(vec(f.g).scale(e.getBbWidth() * 0.5D));
                    f.level.sendParticles(ParticleTypes.CLOUD, at.x, at.y, at.z, 6, 0.2D, 0.2D, 0.2D, 0.02D);
                    f.level.playSound(null, at.x, at.y, at.z, dmg > 4.0F ? SoundEvents.GENERIC_BIG_FALL : SoundEvents.GENERIC_SMALL_FALL,
                            SoundSource.HOSTILE, 1.0F, 1.0F);
                }
                f.fallAlong = 0.0D;
            }
        }
        f.last = pos;
    }
}
