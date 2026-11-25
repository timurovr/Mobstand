package com.mobstands.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/**
 * Pigstep's Null Bubble, the thing you see: a golden bubble with a gold nugget spinning wildly inside it.
 * {@link com.mobstands.stand.NullBubble} moves it and pops it; this is just the body.
 */
public class NullBubbleEntity extends Entity {
    public NullBubbleEntity(EntityType<? extends NullBubbleEntity> type, Level level) {
        super(type, level);
        noPhysics = true;
        setNoGravity(true);
        setInvulnerable(true);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {}

    @Override
    public void tick() {
        super.tick();
        // Nobody's steering it (the bubble already popped, or the server restarted): gone.
        if (!level().isClientSide && !com.mobstands.stand.NullBubble.owns(this)) discard();
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false; // it doesn't exist: nothing can touch it
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double d) {
        return d < 96.0D * 96.0D;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {}

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {}
}
