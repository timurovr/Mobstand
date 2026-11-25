package com.mobstands.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;

/**
 * The Creeper Stand's Sheer Heart Attack: a tiny creeper riding a tiny minecart. All of its hunting is driven by
 * {@link com.mobstands.stand.HeartAttack}; this is just the little body (and its blast flash).
 */
public class HeartAttackEntity extends Entity {
    private static final EntityDataAccessor<Integer> BLAST = SynchedEntityData.defineId(HeartAttackEntity.class, EntityDataSerializers.INT);
    /** Client: when (in its own ticks) it last blew up, for the white swell. */
    private int localBlast = -100;
    /** It can be destroyed: this many hit points (a few solid hits, or one big one). */
    public static final float MAX_HEALTH = 12.0F;
    private float health = MAX_HEALTH;
    /** Knockback still to be carried out (the hunt pauses while it's flying). Builds up during stopped time. */
    private net.minecraft.world.phys.Vec3 knock = net.minecraft.world.phys.Vec3.ZERO;

    public HeartAttackEntity(EntityType<? extends HeartAttackEntity> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        b.define(BLAST, 0);
    }

    /** Server: it just went BOOM. */
    public void markBlast() {
        entityData.set(BLAST, entityData.get(BLAST) + 1);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (BLAST.equals(key)) localBlast = tickCount;
    }

    /** 1 right as it blows up, fading to 0 over half a second. */
    public float blastFlash(float partial) {
        float a = tickCount + partial - localBlast;
        return a < 0.0F || a > 10.0F ? 0.0F : 1.0F - a / 10.0F;
    }

    @Override
    public void tick() {
        super.tick();
        // Nobody's driving it any more (server restarted, the hunt ended oddly): gone.
        if (!level().isClientSide && !com.mobstands.stand.HeartAttack.owns(this)) discard();
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (level().isClientSide || isRemoved()) return false;
        if (source.is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION)) return false; // blasts don't touch it
        if (amount <= 0.0F) return false;
        // Knocked away from whoever hit it.
        Entity from = source.getDirectEntity() != null ? source.getDirectEntity() : source.getEntity();
        if (from != null) {
            net.minecraft.world.phys.Vec3 d = position().subtract(from.position());
            d = new net.minecraft.world.phys.Vec3(d.x, 0.0D, d.z);
            if (d.lengthSqr() > 1.0E-4D) d = d.normalize();
            knock = knock.add(d.scale(0.55D)).add(0.0D, 0.3D, 0.0D);
        }
        health -= amount;
        net.minecraft.server.level.ServerLevel sl = (net.minecraft.server.level.ServerLevel) level();
        sl.sendParticles(net.minecraft.core.particles.ParticleTypes.CRIT, getX(), getY() + 0.4D, getZ(), 6, 0.2D, 0.2D, 0.2D, 0.1D);
        if (health <= 0.0F) {
            sl.sendParticles(net.minecraft.core.particles.ParticleTypes.POOF, getX(), getY() + 0.4D, getZ(), 20, 0.3D, 0.3D, 0.3D, 0.03D);
            sl.sendParticles(net.minecraft.core.particles.ParticleTypes.LARGE_SMOKE, getX(), getY() + 0.4D, getZ(), 10, 0.3D, 0.2D, 0.3D, 0.02D);
            level().playSound(null, getX(), getY(), getZ(), net.minecraft.sounds.SoundEvents.CREEPER_DEATH, net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 1.6F);
            level().playSound(null, getX(), getY(), getZ(), net.minecraft.sounds.SoundEvents.ITEM_BREAK, net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 0.8F);
            discard(); // destroyed: the hunt is over
        } else {
            level().playSound(null, getX(), getY(), getZ(), net.minecraft.sounds.SoundEvents.CREEPER_HURT, net.minecraft.sounds.SoundSource.PLAYERS, 0.8F, 1.6F);
            level().playSound(null, getX(), getY(), getZ(), net.minecraft.sounds.SoundEvents.MINECART_RIDING, net.minecraft.sounds.SoundSource.PLAYERS, 0.5F, 2.0F);
        }
        return true;
    }

    /** Server: this tick's knockback movement (then it fades), or null when it isn't being knocked around. */
    public net.minecraft.world.phys.Vec3 knockStep() {
        if (knock.lengthSqr() < 0.0025D) {
            knock = net.minecraft.world.phys.Vec3.ZERO;
            return null;
        }
        net.minecraft.world.phys.Vec3 now = knock;
        knock = new net.minecraft.world.phys.Vec3(knock.x * 0.75D, knock.y - 0.08D, knock.z * 0.75D);
        if (onGround() && knock.y < 0.0D) knock = new net.minecraft.world.phys.Vec3(knock.x, 0.0D, knock.z);
        return now;
    }

    @Override
    public boolean ignoreExplosion(Explosion explosion) {
        return true; // its own blasts don't throw it around
    }

    @Override
    public boolean isPickable() {
        return !isRemoved(); // so it can be hit and shot
    }

    @Override
    public boolean isAttackable() {
        return true;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {}

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {}
}
