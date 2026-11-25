package com.mobstands.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import org.joml.Vector3f;

/**
 * GER's Iron Shot, the thing you see: an iron nugget held up in GER's fingers, then flicked so fast it leaves a
 * blazing white-gold trail all the way to its target. The entity sits where the shot starts; {@link #end()} is where it
 * hit. {@link com.mobstands.stand.GerShot} drives it.
 */
public class NuggetShotEntity extends Entity {
    private static final EntityDataAccessor<Vector3f> END = SynchedEntityData.defineId(NuggetShotEntity.class, EntityDataSerializers.VECTOR3);
    /** The entity's own tick when it was fired (-1 = still being held up). */
    private static final EntityDataAccessor<Integer> FIRED = SynchedEntityData.defineId(NuggetShotEntity.class, EntityDataSerializers.INT);

    public static final int TRAIL_TICKS = 40; // the trail hangs in the air for 2 seconds

    public NuggetShotEntity(EntityType<? extends NuggetShotEntity> type, Level level) {
        super(type, level);
        noPhysics = true;
        setNoGravity(true);
        setInvulnerable(true);
        noCulling = true; // the trail reaches far past its own little box
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        b.define(END, new Vector3f());
        b.define(FIRED, -1);
    }

    public void fire(net.minecraft.world.phys.Vec3 end) {
        entityData.set(END, end.toVector3f());
        entityData.set(FIRED, tickCount);
    }

    public net.minecraft.world.phys.Vec3 end() {
        Vector3f v = entityData.get(END);
        return new net.minecraft.world.phys.Vec3(v.x, v.y, v.z);
    }

    /** Ticks since it was flicked (negative = still held). */
    public float sinceFired(float partial) {
        int f = entityData.get(FIRED);
        return f < 0 ? -1.0F : tickCount + partial - f;
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) return;
        int f = entityData.get(FIRED);
        if (f >= 0 && tickCount - f > TRAIL_TICKS) discard();
        else if (f < 0 && tickCount > 60) discard(); // never fired (GER dismissed mid-throw)
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double d) {
        return d < 160.0D * 160.0D;
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
