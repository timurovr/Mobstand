package com.mobstands.entity;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Silver Fingers' zipper: a golden zipper on a wall, a floor, or a creature, that unzips onto a purple pocket
 * dimension. {@link com.mobstands.stand.Zipper} drives it; every client animates it from its synced state.
 */
public class ZipperEntity extends Entity {
    /** Ticks for the slider to run the zipper's full length (opening or closing). */
    public static final int OPEN_TICKS = 8;
    /** Ticks for its user to slide in through it (or back out). */
    public static final int SLIDE_TICKS = 7;

    /** Zipper states. */
    public static final int OPEN_ENTER = 0, SLIDE_IN = 1, INSIDE = 2, OPEN_EXIT = 3, SLIDE_OUT = 4, CLOSE = 5,
            MOB_OPEN = 6, MOB_CLOSE = 7;

    private static final EntityDataAccessor<Integer> OCCUPANT = SynchedEntityData.defineId(ZipperEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> STATE = SynchedEntityData.defineId(ZipperEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> STATE_AT = SynchedEntityData.defineId(ZipperEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> NORMAL = SynchedEntityData.defineId(ZipperEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> ALONG = SynchedEntityData.defineId(ZipperEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> LEN = SynchedEntityData.defineId(ZipperEntity.class, EntityDataSerializers.FLOAT);

    public ZipperEntity(EntityType<? extends ZipperEntity> type, Level level) {
        super(type, level);
        noPhysics = true;
        setNoGravity(true);
        setInvulnerable(true);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        b.define(OCCUPANT, -1);
        b.define(STATE, OPEN_ENTER);
        b.define(STATE_AT, 0);
        b.define(NORMAL, Direction.NORTH.get3DDataValue());
        b.define(ALONG, Direction.UP.get3DDataValue());
        b.define(LEN, 1.8F);
    }

    public void setup(int occupant, Direction normal, Direction along, float len, int state) {
        entityData.set(OCCUPANT, occupant);
        entityData.set(NORMAL, normal.get3DDataValue());
        entityData.set(ALONG, along.get3DDataValue());
        entityData.set(LEN, len);
        setState(state);
    }

    public void setState(int state) {
        entityData.set(STATE, state);
        entityData.set(STATE_AT, (int) level().getGameTime());
    }

    public int occupant() { return entityData.get(OCCUPANT); }
    public int state() { return entityData.get(STATE); }
    public int stateAt() { return entityData.get(STATE_AT); }
    public Direction normal() { return Direction.from3DDataValue(entityData.get(NORMAL)); }
    public Direction along() { return Direction.from3DDataValue(entityData.get(ALONG)); }
    public float len() { return entityData.get(LEN); }
    public void setNormal(Direction d) { entityData.set(NORMAL, d.get3DDataValue()); }

    /** Ticks since the current state began. */
    public float since(float partial) {
        return (float) (level().getGameTime() - stateAt()) + partial;
    }

    /** How far open the zipper is (0 shut, 1 wide open). */
    public float openAmount(float partial) {
        float t = since(partial) / OPEN_TICKS;
        float o = switch (state()) {
            case OPEN_ENTER, OPEN_EXIT, MOB_OPEN -> t;
            case SLIDE_IN, SLIDE_OUT -> 1.0F;
            case INSIDE, CLOSE, MOB_CLOSE -> 1.0F - t;
            default -> 0.0F;
        };
        return Math.max(0.0F, Math.min(1.0F, o));
    }

    /** How far its user has gone in (0 standing outside, 1 all the way into the pocket dimension). */
    public float inAmount(float partial) {
        float t = Math.max(0.0F, Math.min(1.0F, since(partial) / SLIDE_TICKS));
        return switch (state()) {
            case SLIDE_IN -> t;
            case INSIDE, OPEN_EXIT -> 1.0F;
            case SLIDE_OUT -> 1.0F - t;
            default -> 0.0F;
        };
    }

    public Vec3 normalVec() {
        return Vec3.atLowerCornerOf(normal().getNormal());
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) return;
        int s = state();
        float t = since(0.0F);
        if ((s == CLOSE || s == MOB_CLOSE) && t > OPEN_TICKS + 6) discard();
        else if (s != CLOSE && s != MOB_CLOSE && !com.mobstands.stand.Zipper.owns(this)) discard();
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
    public boolean shouldRenderAtSqrDistance(double d) {
        return d < 96.0D * 96.0D;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {}

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {}
}
