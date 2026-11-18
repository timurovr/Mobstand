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

    public HeartAttackEntity(EntityType<? extends HeartAttackEntity> type, Level level) {
        super(type, level);
        setInvulnerable(true);
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
        return false; // it can't be hurt
    }

    @Override
    public boolean ignoreExplosion(Explosion explosion) {
        return true; // its own blasts don't throw it around
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
