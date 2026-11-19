package com.mobstands.entity;

import com.mobstands.registry.ModEntities;
import com.mobstands.stand.StandCombat;
import com.mobstands.stand.TickScheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.UUID;

/**
 * Server-driven invisible projectile; all visuals are particles. Used for fireballs, arrows, webs and slime blobs.
 * Damage is attributed to the owning player and never affects the owner.
 */
public class StandProjectile extends Entity {

    public enum Kind {
        FIREBALL(ParticleTypes.FLAME, 1.25D, 0.0D, 70, 0),
        BONE_ARROW(ParticleTypes.CRIT, 2.4D, 0.012D, 60, 0),
        /** The Double Shot bonus arrow: leaves a glowing white trail and flashes on impact. */
        GLOW_ARROW(ParticleTypes.END_ROD, 2.4D, 0.012D, 60, 0),
        WEB(ParticleTypes.ITEM_COBWEB, 1.5D, 0.01D, 50, 0),
        SLIME(ParticleTypes.ITEM_SLIME, 1.1D, 0.03D, 80, 3),
        /** Aerosmith-style machine-gun bolts from the piloted Phantom: fast, straight, glowing blue. */
        AERO_BOLT(new net.minecraft.core.particles.DustParticleOptions(new org.joml.Vector3f(0.35F, 0.65F, 1.0F), 1.3F), 3.2D, 0.0D, 22, 0);

        public final ParticleOptions trail;
        public final double speed;
        public final double gravity;
        public final int maxLife;
        public final int bounces;

        Kind(ParticleOptions trail, double speed, double gravity, int maxLife, int bounces) {
            this.trail = trail;
            this.speed = speed;
            this.gravity = gravity;
            this.maxLife = maxLife;
            this.bounces = bounces;
        }
    }

    private Kind kind = Kind.FIREBALL;
    private UUID ownerId;
    private float damage;
    private Vec3 velocity = Vec3.ZERO;
    private int life;
    private int bouncesLeft;

    public StandProjectile(EntityType<? extends StandProjectile> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    public static void spawn(ServerPlayer owner, ServerLevel level, Kind kind, Vec3 origin, Vec3 dir, float damage) {
        StandProjectile p = new StandProjectile(ModEntities.STAND_PROJECTILE.get(), level);
        p.kind = kind;
        p.ownerId = owner.getUUID();
        p.damage = damage;
        p.velocity = dir.normalize().scale(kind.speed);
        p.bouncesLeft = kind.bounces;
        p.setPos(origin.x, origin.y, origin.z);
        level.addFreshEntity(p);
    }

    /** Places a temporary cobweb (removed after {@code ticks}). */
    public static void placeWeb(ServerLevel level, BlockPos pos, int ticks) {
        if (!level.getBlockState(pos).isAir()) return;
        level.setBlockAndUpdate(pos, Blocks.COBWEB.defaultBlockState());
        TickScheduler.later(ticks, () -> {
            if (level.getBlockState(pos).is(Blocks.COBWEB)) level.removeBlock(pos, false);
        });
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {}

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) return;
        ServerLevel sl = (ServerLevel) level();
        ServerPlayer owner = ownerId == null ? null : sl.getServer().getPlayerList().getPlayer(ownerId);
        if (owner == null || ++life > kind.maxLife) {
            discard();
            return;
        }

        Vec3 from = position();
        Vec3 to = from.add(velocity);
        BlockHitResult block = sl.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
        Vec3 end = block.getType() == HitResult.Type.MISS ? to : block.getLocation();

        LivingEntity hit = findHit(sl, from, end, owner);
        if (hit != null) {
            setPos(hit.position().x, hit.getY() + hit.getBbHeight() * 0.5D, hit.position().z);
            onHitEntity(sl, owner, hit);
            discard();
            return;
        }

        if (block.getType() != HitResult.Type.MISS) {
            if (kind == Kind.SLIME && bouncesLeft-- > 0) {
                Direction d = block.getDirection();
                Vec3 n = new Vec3(d.getStepX(), d.getStepY(), d.getStepZ());
                velocity = velocity.subtract(n.scale(2.0D * velocity.dot(n))).scale(0.85D);
                Vec3 loc = block.getLocation().add(n.scale(0.2D));
                setPos(loc.x, loc.y, loc.z);
                sl.playSound(null, loc.x, loc.y, loc.z, SoundEvents.SLIME_JUMP, SoundSource.PLAYERS, 0.8F, 1.3F);
                return;
            }
            onHitBlock(sl, end);
            discard();
            return;
        }

        setPos(to.x, to.y, to.z);
        velocity = velocity.add(0.0D, -kind.gravity, 0.0D);
        sl.sendParticles(kind.trail, to.x, to.y, to.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        if (kind == Kind.FIREBALL) sl.sendParticles(ParticleTypes.SMOKE, to.x, to.y, to.z, 1, 0.05D, 0.05D, 0.05D, 0.0D);
        if (kind == Kind.GLOW_ARROW) {
            // A couple more glowing specks along the path so the bonus arrow reads as a bright streak.
            Vec3 mid = from.add(to).scale(0.5D);
            sl.sendParticles(ParticleTypes.END_ROD, mid.x, mid.y, mid.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
            sl.sendParticles(ParticleTypes.ELECTRIC_SPARK, to.x, to.y, to.z, 1, 0.03D, 0.03D, 0.03D, 0.0D);
        }
    }

    private LivingEntity findHit(ServerLevel sl, Vec3 from, Vec3 to, ServerPlayer owner) {
        AABB sweep = new AABB(from, to).inflate(0.5D);
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (LivingEntity e : sl.getEntitiesOfClass(LivingEntity.class, sweep, x -> StandCombat.valid(owner, x))) {
            AABB bb = e.getBoundingBox().inflate(0.3D);
            double d;
            if (bb.contains(from)) {
                d = 0.0D;
            } else {
                Optional<Vec3> h = bb.clip(from, to);
                if (h.isEmpty()) continue;
                d = from.distanceToSqr(h.get());
            }
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    private void onHitEntity(ServerLevel sl, ServerPlayer owner, LivingEntity target) {
        Vec3 dir = velocity.lengthSqr() > 1.0E-6D ? velocity.normalize() : Vec3.ZERO;
        StandCombat.hurt(owner, target, damage);
        Vec3 at = target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D);
        switch (kind) {
            case FIREBALL -> {
                target.setRemainingFireTicks(Math.max(target.getRemainingFireTicks(), 80));
                sl.sendParticles(ParticleTypes.FLAME, at.x, at.y, at.z, 25, 0.3D, 0.3D, 0.3D, 0.08D);
                sl.playSound(null, at.x, at.y, at.z, SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 0.8F, 1.2F);
            }
            case BONE_ARROW, GLOW_ARROW -> {
                if (kind == Kind.GLOW_ARROW) sl.sendParticles(ParticleTypes.FLASH, at.x, at.y, at.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
                StandCombat.push(target, dir, 0.4D, 0.1D);
                sl.sendParticles(ParticleTypes.CRIT, at.x, at.y, at.z, 10, 0.2D, 0.2D, 0.2D, 0.2D);
                sl.playSound(null, at.x, at.y, at.z, SoundEvents.ARROW_HIT, SoundSource.PLAYERS, 0.9F, 1.0F);
            }
            case WEB -> {
                target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 100, 3));
                placeWeb(sl, target.blockPosition(), 100);
                sl.sendParticles(ParticleTypes.ITEM_COBWEB, at.x, at.y, at.z, 20, 0.4D, 0.4D, 0.4D, 0.05D);
            }
            case SLIME -> {
                StandCombat.push(target, dir, 1.3D, 0.45D);
                sl.sendParticles(ParticleTypes.ITEM_SLIME, at.x, at.y, at.z, 20, 0.3D, 0.3D, 0.3D, 0.1D);
                sl.playSound(null, at.x, at.y, at.z, SoundEvents.SLIME_SQUISH, SoundSource.PLAYERS, 1.0F, 1.0F);
            }
        }
    }

    private void onHitBlock(ServerLevel sl, Vec3 at) {
        switch (kind) {
            case FIREBALL -> sl.sendParticles(ParticleTypes.FLAME, at.x, at.y, at.z, 15, 0.2D, 0.2D, 0.2D, 0.05D);
            case BONE_ARROW, GLOW_ARROW -> {
                if (kind == Kind.GLOW_ARROW) sl.sendParticles(ParticleTypes.FLASH, at.x, at.y, at.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
                sl.sendParticles(ParticleTypes.CRIT, at.x, at.y, at.z, 6, 0.1D, 0.1D, 0.1D, 0.1D);
                sl.playSound(null, at.x, at.y, at.z, SoundEvents.ARROW_HIT, SoundSource.PLAYERS, 0.6F, 1.3F);
            }
            case WEB -> sl.sendParticles(ParticleTypes.ITEM_COBWEB, at.x, at.y, at.z, 12, 0.2D, 0.2D, 0.2D, 0.02D);
            case SLIME -> sl.sendParticles(ParticleTypes.ITEM_SLIME, at.x, at.y, at.z, 12, 0.2D, 0.2D, 0.2D, 0.05D);
        }
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {}

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {}
}
