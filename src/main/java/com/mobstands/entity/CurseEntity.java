package com.mobstands.entity;

import com.mobstands.stand.Curses;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;

/**
 * A cursed Stand you never asked for: Cheap Trick (a Vex clinging to someone's back), Survivor (a tiny Drowned on
 * your shoulder), Boy II Man (a little Witch floating beside you) - or Notorious B.I.G. (a Magma Cube loose in the
 * world). {@link Curses} runs them; this is just the body everyone can see.
 */
public class CurseEntity extends Entity {
    public static final int CHEAP_TRICK = 0, SURVIVOR = 1, BIG = 2, BOY_II_MAN = 3;

    private static final EntityDataAccessor<Integer> KIND = SynchedEntityData.defineId(CurseEntity.class, EntityDataSerializers.INT);
    /** Entity it clings to (-1: free, like B.I.G.). */
    private static final EntityDataAccessor<Integer> HOST = SynchedEntityData.defineId(CurseEntity.class, EntityDataSerializers.INT);
    /** Player whose curse it is. */
    private static final EntityDataAccessor<Integer> OWNER = SynchedEntityData.defineId(CurseEntity.class, EntityDataSerializers.INT);
    /** B.I.G.'s size (blocks). */
    private static final EntityDataAccessor<Float> SIZE = SynchedEntityData.defineId(CurseEntity.class, EntityDataSerializers.FLOAT);
    /** Boy II Man: stolen ability slots (bits 0-5), wins (bits 8-11), losses (bits 12-15), all stolen (bit 16). */
    private static final EntityDataAccessor<Integer> DATA = SynchedEntityData.defineId(CurseEntity.class, EntityDataSerializers.INT);

    public CurseEntity(EntityType<? extends CurseEntity> type, Level level) {
        super(type, level);
        noPhysics = true;
        setNoGravity(true);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        b.define(KIND, CHEAP_TRICK);
        b.define(HOST, -1);
        b.define(OWNER, -1);
        b.define(SIZE, 0.6F);
        b.define(DATA, 0);
    }

    public int kind() { return entityData.get(KIND); }
    public int host() { return entityData.get(HOST); }
    public int owner() { return entityData.get(OWNER); }
    public float size() { return entityData.get(SIZE); }
    public int data() { return entityData.get(DATA); }

    public void setup(int kind, int host, int owner) {
        entityData.set(KIND, kind);
        entityData.set(HOST, host);
        entityData.set(OWNER, owner);
    }

    public void setHost(int host) { entityData.set(HOST, host); }
    public void setOwner(int owner) { entityData.set(OWNER, owner); }
    public void setData(int d) { entityData.set(DATA, d); }

    public void setSize(float s) {
        entityData.set(SIZE, s);
        refreshDimensions();
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (SIZE.equals(key) || KIND.equals(key)) refreshDimensions();
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        if (kind() == BIG) return EntityDimensions.scalable(size(), size() * 0.9F);
        return EntityDimensions.scalable(0.3F, 0.3F);
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide) {
            if (kind() == BIG) Curses.tickBig(this, (ServerLevel) level());
            else if (!Curses.owns(this)) discard();
        }
    }

    @Override
    public boolean isPickable() {
        return kind() == BIG && !isRemoved();
    }

    /** B.I.G. can't be hurt. Hitting it just feeds it - and now it's after you. */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (kind() != BIG || level().isClientSide) return false;
        Curses.bigHit(this, source);
        return false;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double d) {
        return d < 128.0D * 128.0D;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {}

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {}
}
