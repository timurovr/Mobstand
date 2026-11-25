package com.mobstands.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Iron Express: a car another player rides at the back of your train. It has no mind of its own - the server lays it
 * along the exact path your cart took - and it's gone the moment its rider hops off (Shift).
 */
public class TrainCarEntity extends Entity {
    private int lerpSteps;
    private double lx, ly, lz;
    private float lyRot;

    public TrainCarEntity(EntityType<? extends TrainCarEntity> type, Level level) {
        super(type, level);
        noPhysics = true;
        setNoGravity(true);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {}

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            // glide smoothly between the server's updates (a plain entity would jump)
            if (lerpSteps > 0) {
                double t = 1.0D / lerpSteps;
                setPos(getX() + (lx - getX()) * t, getY() + (ly - getY()) * t, getZ() + (lz - getZ()) * t);
                setYRot(getYRot() + net.minecraft.util.Mth.wrapDegrees(lyRot - getYRot()) * (float) t);
                lerpSteps--;
            }
            return;
        }
        if (getPassengers().isEmpty() && tickCount > 5) discard(); // its rider hopped off
    }

    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
        lx = x;
        ly = y;
        lz = z;
        lyRot = yRot;
        lerpSteps = Math.max(steps, 3);
    }

    @Override
    protected Vec3 getPassengerAttachmentPoint(Entity passenger, net.minecraft.world.entity.EntityDimensions dims, float scale) {
        return new Vec3(0.0D, 0.35D, 0.0D); // sitting down in it
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
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
