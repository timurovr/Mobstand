package com.mobstands.entity;

import com.mobstands.config.StandConfig;
import com.mobstands.stand.AbilityContext;
import com.mobstands.stand.StandAbilities;
import com.mobstands.stand.StandData;
import com.mobstands.stand.StandManager;
import com.mobstands.stand.StandType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.UUID;

/**
 * The Stand itself. Deliberately NOT a Mob: no pathfinding, no spawning, no AI goals, no damage.
 * Its position is driven every tick from its owner, like a spectral extension of the player.
 */
public class StandEntity extends Entity {
    /** Ticks it takes the Stand to drift out of its owner and fade in. */
    public static final int FADE_TICKS = 11;

    /** How long a Stand takes to appear: the Axolotl pops in suddenly. */
    public static int fadeTicks(StandType type) {
        return type == StandType.AXOLOTL ? 3 : FADE_TICKS;
    }

    public int allayCount() {
        return entityData.get(ALLAYS);
    }

    /** Red hurt flash, like a mob that just got hit. */
    public boolean hurtFlash(float partial) {
        return level().getGameTime() - entityData.get(HURT_AT) + partial < 8.0F;
    }

    /**
     * Where Allay number {@code idx} (0 = yours, then the duplicates) sits when they're not roaming:
     * 4 - all circling around you; 3 - one on each shoulder and one on your head; 2 - one each side of you.
     */
    public static Vec3 allayFormation(Vec3 o, float bodyYaw, float height, int count, int idx, float t) {
        double yaw = Math.toRadians(bodyYaw);
        Vec3 fwd = new Vec3(-Math.sin(yaw), 0.0D, Math.cos(yaw));
        Vec3 right = new Vec3(-fwd.z, 0.0D, fwd.x).scale(-1.0D);
        double bob = Math.sin(t * 0.1D + idx * 1.7D) * 0.04D;
        if (count >= 4) {
            double ang = t * 0.045D + idx * (Math.PI * 2.0D / count);
            double rad = 1.8D + 0.15D * Math.sin(t * 0.05D + idx);
            return o.add(Math.cos(ang) * rad, 1.4D + 0.25D * Math.sin(t * 0.08D + idx * 1.9D), Math.sin(ang) * rad);
        }
        if (count == 3) {
            if (idx == 2) return o.add(0.0D, height + 0.05D + bob, 0.0D);                       // on your head
            Vec3 sh = right.scale(idx == 0 ? 0.42D : -0.42D);                                    // on a shoulder
            return o.add(sh).add(0.0D, height * 0.78D + bob, 0.0D);
        }
        // Two: one either side of you.
        Vec3 s = right.scale(idx == 0 ? 0.95D : -0.95D);
        return o.add(s).add(0.0D, 1.0D + bob * 2.0D, 0.0D);
    }

    /** Which way an Allay in formation faces. */
    public static float allayFormationYaw(float bodyYaw, int count, int idx, float t) {
        if (count >= 4) {
            double ang = t * 0.045D + idx * (Math.PI * 2.0D / count);
            return (float) (Mth.atan2(Math.cos(ang), -Math.sin(ang)) * (180.0D / Math.PI)) - 90.0F; // along the circle
        }
        return bodyYaw; // facing the way you face
    }

    public boolean isRoaming() {
        return entityData.get(ROAM);
    }

    /** Server: push the Allay state (count, roam, holds) to clients. */
    public void syncAllays(StandData d) {
        entityData.set(ALLAYS, Math.max(1, d.allayCount));
        entityData.set(ROAM, d.allayRoam);
        CompoundTag tag = new CompoundTag();
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        for (StandData.AllayHold h : d.allayHolds) {
            CompoundTag e = new CompoundTag();
            e.putInt("id", h.entityId);
            e.putInt("n", h.allays);
            e.putBoolean("main", h.usesMain);
            list.add(e);
        }
        tag.put("h", list);
        entityData.set(HOLDS, tag);
    }

    /** Held enemies as {entityId, allays, main ? 1 : 0}. */
    public java.util.List<int[]> allayHolds() {
        java.util.List<int[]> out = new java.util.ArrayList<>();
        net.minecraft.nbt.ListTag list = entityData.get(HOLDS).getList("h", 10);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            out.add(new int[]{e.getInt("id"), e.getInt("n"), e.getBoolean("main") ? 1 : 0});
        }
        return out;
    }

    /** Allays not busy holding enemies (at least 1). */
    public int freeAllays() {
        int held = 0;
        for (int[] h : allayHolds()) held += h[1];
        return Math.max(1, allayCount() - held);
    }

    /** The enemy your own Allay is holding, or null. */
    private Entity mainHoldTarget() {
        for (int[] h : allayHolds()) if (h[2] == 1) return level().getEntity(h[0]);
        return null;
    }

    public void startLift(int start, int end) {
        entityData.set(LIFT_START, start);
        entityData.set(LIFT_END, end);
        allayGoal = null;
    }

    public void stopLift() {
        entityData.set(LIFT_START, -1);
        entityData.set(LIFT_END, -1);
    }

    /** The Allay is carrying its owner through the air. */
    public boolean isLifting() {
        int start = entityData.get(LIFT_START);
        return start >= 0 && level().getGameTime() < entityData.get(LIFT_END);
    }

    /**
     * Client, own player only: the actual carrying. A quick lift up, then a glide: slowly sinking, drifting where
     * you look while you hold forward, climbing or sinking with where you look. More Allays: faster and more agile.
     */
    private void liftOwner(Player owner) {
        int n = Math.max(1, allayCount());
        float el = (float) (level().getGameTime() - entityData.get(LIFT_START));
        if (el > com.mobstands.stand.StandAbilities.LIFT_RISE_TICKS + 4 && (owner.onGround() || owner.isInWater())) return;
        Vec3 v = owner.getDeltaMovement();
        double vy;
        int rise = com.mobstands.stand.StandAbilities.LIFT_RISE_TICKS;
        if (el < rise) {
            // Pulled up about 2.5 blocks, more with more Allays pulling (up to ~4 with 4).
            vy = (0.06D + 0.34D * (1.0D - el / rise)) * (1.0D + 0.18D * (n - 1));
        } else {
            double pitch = -owner.getXRot() / 90.0D; // +1 looking straight up
            vy = -0.045D + pitch * (0.02D + 0.02D * (n - 1));
            vy = Mth.clamp(vy, -0.12D, 0.03D + 0.02D * (n - 1));
        }
        double yaw = Math.toRadians(owner.getYRot());
        Vec3 fwd = new Vec3(-Math.sin(yaw), 0.0D, Math.cos(yaw));
        Vec3 right = new Vec3(-Math.cos(yaw), 0.0D, -Math.sin(yaw));
        double speed = 0.2D + 0.05D * n;
        double steer = 0.05D + 0.035D * n;
        Vec3 want = Vec3.ZERO;
        if (owner.zza > 0.0F) want = want.add(fwd.scale(speed));
        else if (owner.zza < 0.0F) want = want.add(fwd.scale(-speed * 0.4D));
        if (owner.xxa != 0.0F) want = want.add(right.scale(-Math.signum(owner.xxa) * speed * 0.5D));
        if (want.lengthSqr() < 1.0E-6D) want = new Vec3(v.x * 0.92D, 0.0D, v.z * 0.92D); // let go: drift to a stop
        double hx = Mth.lerp(steer, v.x, want.x);
        double hz = Mth.lerp(steer, v.z, want.z);
        owner.setDeltaMovement(hx, vy, hz);
        owner.resetFallDistance();
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (TYPE.equals(key)) refreshDimensions();
    }

    /** The Allay is small and can be hit (that's how it duplicates); every other Stand keeps the default box. */
    @Override
    public net.minecraft.world.entity.EntityDimensions getDimensions(net.minecraft.world.entity.Pose pose) {
        if (getStandType() == StandType.ALLAY) return net.minecraft.world.entity.EntityDimensions.scalable(0.5F, 0.75F);
        return super.getDimensions(pose);
    }
    /** Ticks the Stand takes to drift away and vanish when dismissed. */
    public static final int FADE_OUT_TICKS = 10;

    private Vec3 dismissOffset = Vec3.ZERO;
    private Vec3 dismissDir = Vec3.ZERO;

    private static final EntityDataAccessor<Optional<UUID>> OWNER =
            SynchedEntityData.defineId(StandEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final EntityDataAccessor<Integer> TYPE =
            SynchedEntityData.defineId(StandEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> ANIM =
            SynchedEntityData.defineId(StandEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> ANIM_START =
            SynchedEntityData.defineId(StandEntity.class, EntityDataSerializers.INT);
    /** True while an attack is moving the Stand; otherwise the client glues it to its owner itself. */
    private static final EntityDataAccessor<Boolean> LUNGING =
            SynchedEntityData.defineId(StandEntity.class, EntityDataSerializers.BOOLEAN);
    /** Summon-rush combo: tick (after the start) punch 2 lands, or -1 if the player only punched once. */
    private static final EntityDataAccessor<Integer> COMBO_HIT2 =
            SynchedEntityData.defineId(StandEntity.class, EntityDataSerializers.INT);
    /** The owner has struck their Stand's signature pose (toggled with P); synced so everyone sees it. */
    private static final EntityDataAccessor<Boolean> POSING =
            SynchedEntityData.defineId(StandEntity.class, EntityDataSerializers.BOOLEAN);
    /** Which shoulder the Stand stands behind: 1 = your right, -1 = your left (picked at random each summon). */
    private static final EntityDataAccessor<Integer> SIDE =
            SynchedEntityData.defineId(StandEntity.class, EntityDataSerializers.INT);
    /** Slime Stand: game time its current hop started (drives the squish animation on every client). */
    /** Axolotl Stand is playing dead (lying where it took the hit for you). */
    private static final EntityDataAccessor<Boolean> FAKE_DEAD =
            SynchedEntityData.defineId(StandEntity.class, EntityDataSerializers.BOOLEAN);
    /** Allay Lift: game time it started and ends (-1 = not lifting). */
    private static final EntityDataAccessor<Integer> LIFT_START =
            SynchedEntityData.defineId(StandEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> LIFT_END =
            SynchedEntityData.defineId(StandEntity.class, EntityDataSerializers.INT);
    /** Game time the Stand was last hit (it flashes red like a hurt mob). */
    private static final EntityDataAccessor<Integer> HURT_AT =
            SynchedEntityData.defineId(StandEntity.class, EntityDataSerializers.INT);
    /** Allay Stand: roaming instead of standing by its owner. */
    private static final EntityDataAccessor<Boolean> ROAM =
            SynchedEntityData.defineId(StandEntity.class, EntityDataSerializers.BOOLEAN);
    /** Allay Stand: enemies held up by Allays: list of {id, n, main}. */
    private static final EntityDataAccessor<CompoundTag> HOLDS =
            SynchedEntityData.defineId(StandEntity.class, EntityDataSerializers.COMPOUND_TAG);
    /** Allay Stand: how many Allays the owner has (1..4). */
    private static final EntityDataAccessor<Integer> ALLAYS =
            SynchedEntityData.defineId(StandEntity.class, EntityDataSerializers.INT);
    /** Squid Stand rain intensity: 0 none, 1 rain, 2 downpour, 3 crushing deluge. */
    /** Entity id of the mob (a Pillager) this Stand belongs to, or -1 for a player's Stand. */
    private static final EntityDataAccessor<Integer> MOB_OWNER =
            SynchedEntityData.defineId(StandEntity.class, EntityDataSerializers.INT);
    /** Game time a mob's Stand last came out (its fade-in starts from here). */
    private static final EntityDataAccessor<Integer> MOB_APPEAR =
            SynchedEntityData.defineId(StandEntity.class, EntityDataSerializers.INT);
    /** Minecart Stand: its user is riding it (it's drawn under them instead of floating). */
    private static final EntityDataAccessor<Boolean> CART_RIDE =
            SynchedEntityData.defineId(StandEntity.class, EntityDataSerializers.BOOLEAN);
    /** Minecart Stand boost: kind (1 Powered Rails, 2 Nitro) and the game time it ends. */
    private static final EntityDataAccessor<Integer> CART_BOOST =
            SynchedEntityData.defineId(StandEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> CART_BOOST_END =
            SynchedEntityData.defineId(StandEntity.class, EntityDataSerializers.INT);
    /** Axolotl's Fake Funeral: its owner is lying "dead" on the ground. */
    private static final EntityDataAccessor<Boolean> OWNER_DEAD =
            SynchedEntityData.defineId(StandEntity.class, EntityDataSerializers.BOOLEAN);
    /** Requiem aura shown (players can switch it off with /stand aura). */
    private static final EntityDataAccessor<Boolean> AURA =
            SynchedEntityData.defineId(StandEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> RAIN =
            SynchedEntityData.defineId(StandEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> HOP_START =
            SynchedEntityData.defineId(StandEntity.class, EntityDataSerializers.INT);
    /** Regular punch: 0 = right fist (punch 1), 1 = left fist (punch 2 / follow-up). */
    private static final EntityDataAccessor<Integer> PUNCH_SIDE =
            SynchedEntityData.defineId(StandEntity.class, EntityDataSerializers.INT);
    /** Game time the dismiss fade started (0 = not dismissing). */
    private static final EntityDataAccessor<Integer> DISMISS =
            SynchedEntityData.defineId(StandEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> HIDDEN =
            SynchedEntityData.defineId(StandEntity.class, EntityDataSerializers.BOOLEAN);

    // Client-side walk animation state
    public float limbPos;
    public float limbSpeed;
    public float limbSpeedO;
    /** Client: 0..1, the Squid swelling up while it rains. */
    public float rainGrow;
    public float rainGrowO;
    /** Client: tick the Axolotl started playing dead (-1 when alive). */
    private int fakeDeadStart = -1;

    /** Client: ticks since the fake death began, or -1. */
    public float fakeDeadTime(float partial) {
        return fakeDeadStart < 0 ? -1.0F : tickCount - fakeDeadStart + partial;
    }

    // Server-side behaviour state
    private Vec3 lungeTarget = Vec3.ZERO;
    private int lungeTicks;
    private int reformTicks;
    private int autoCooldown;

    public StandEntity(EntityType<? extends StandEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(OWNER, Optional.empty());
        builder.define(TYPE, -1);
        builder.define(ANIM, 0);
        builder.define(ANIM_START, 0);
        builder.define(HIDDEN, false);
        builder.define(DISMISS, 0);
        builder.define(LUNGING, false);
        builder.define(COMBO_HIT2, -1);
        builder.define(PUNCH_SIDE, 0);
        builder.define(SIDE, 1);
        builder.define(POSING, false);
        builder.define(HOP_START, -1000);
        builder.define(RAIN, 0);
        builder.define(CART_RIDE, false);
        builder.define(AURA, true);
        builder.define(OWNER_DEAD, false);
        builder.define(CART_BOOST, 0);
        builder.define(CART_BOOST_END, 0);
        builder.define(MOB_OWNER, -1);
        builder.define(MOB_APPEAR, -100000);
        builder.define(FAKE_DEAD, false);
        builder.define(ALLAYS, 1);
        builder.define(ROAM, false);
        builder.define(HURT_AT, -1000);
        builder.define(HOLDS, new CompoundTag());
        builder.define(LIFT_START, -1);
        builder.define(LIFT_END, -1);
    }

    // ------------------------------------------------------------------ accessors

    public void setOwner(Player owner) {
        entityData.set(OWNER, Optional.of(owner.getUUID()));
    }

    public Optional<UUID> getOwnerUUID() {
        return entityData.get(OWNER);
    }

    /** Ticks since a mob's Stand came out (drives its fade-in, like a summon). */
    public float mobAppearAge(float partial) {
        return (float) (level().getGameTime() - entityData.get(MOB_APPEAR)) + partial;
    }

    public void setCartBoost(int kind, int endTime) {
        entityData.set(CART_BOOST, kind);
        entityData.set(CART_BOOST_END, endTime);
    }

    /** 0 none, 1 Powered Rails, 2 Nitro (only while it lasts). */
    public int cartBoost() {
        return isCartRide() && level().getGameTime() < entityData.get(CART_BOOST_END) ? entityData.get(CART_BOOST) : 0;
    }

    /** Game time the current boost runs out. */
    public int cartBoostEnd() {
        return entityData.get(CART_BOOST_END);
    }

    public boolean auraOn() {
        return entityData.get(AURA);
    }

    public void setAura(boolean on) {
        if (entityData.get(AURA) != on) entityData.set(AURA, on);
    }

    public boolean isCartRide() {
        return entityData.get(CART_RIDE);
    }

    public void setCartRide(boolean on) {
        entityData.set(CART_RIDE, on);
    }

    public boolean isMobStand() {
        return entityData.get(MOB_OWNER) >= 0;
    }

    public void setMobOwner(LivingEntity mob) {
        entityData.set(MOB_OWNER, mob.getId());
        entityData.set(HIDDEN, true); // stays inside the Pillager until it starts a fight
    }

    public LivingEntity getMobOwner() {
        int id = entityData.get(MOB_OWNER);
        return id >= 0 && level().getEntity(id) instanceof LivingEntity l ? l : null;
    }

    // ---- Stands owned by mobs (Pillager Stand users) ----
    /** Enderman Stands: stay centred behind you after summoning until you first move. */
    private boolean behindUntilMoved = true;
    private Vec3 summonAnchor;
    private int mobAttackCd = 20;
    /** Ticks since the Pillager last had someone to fight; starts "calm" so a fresh Stand stays hidden. */
    private int mobCalmTicks = 1000;

    /** Server: follows its Pillager around and punches whatever the Pillager is fighting when it gets close. */
    private void mobStandTick() {
        if (com.mobstands.stand.TimeStop.isFrozen(this)) return; // caught in stopped time
        LivingEntity o = getMobOwner();
        StandType type = getStandType();
        ServerLevel sl = (ServerLevel) level();
        if (o == null || !o.isAlive() || o.isRemoved() || type == null) {
            sl.sendParticles(ParticleTypes.POOF, getX(), getY() + 1.0D, getZ(), 14, 0.3D, 0.6D, 0.3D, 0.02D);
            discard();
            return;
        }
        LivingEntity target = o instanceof Mob m ? m.getTarget() : null;
        if (target != null && (!target.isAlive() || (target instanceof Player tp && (tp.isCreative() || tp.isSpectator())))) target = null;
        // Only out while the Pillager is fighting: it vanishes a couple of seconds after it calms down,
        // and bursts out again the moment it turns hostile.
        if (target != null) mobCalmTicks = 0;
        else mobCalmTicks++;
        boolean shouldShow = mobCalmTicks < 50;
        if (shouldShow && (isHidden() || isDismissing())) {
            // Turns hostile: fades out of the Pillager like a summoned Stand (or straight back if it was leaving).
            if (isHidden()) {
                setPos(o.getX(), o.getY() + 0.15D, o.getZ());
                entityData.set(MOB_APPEAR, (int) level().getGameTime());
                sl.playSound(null, o.getX(), o.getY(), o.getZ(), com.mobstands.registry.ModSounds.STAND_SUMMON.get(), net.minecraft.sounds.SoundSource.HOSTILE, 0.7F, 0.9F);
            }
            entityData.set(HIDDEN, false);
            entityData.set(DISMISS, 0);
            mobAttackCd = Math.max(mobAttackCd, 12);
        } else if (!shouldShow && !isHidden() && !isDismissing()) {
            entityData.set(DISMISS, Math.max(1, (int) level().getGameTime())); // calms down: fades back into it
            lungeTicks = 0;
        }
        if (isDismissing()) {
            Vec3 into = o.position().add(0.0D, 0.15D, 0.0D);
            Vec3 cur = position();
            Vec3 n = cur.add(into.subtract(cur).scale(0.3D));
            setPos(n.x, n.y, n.z);
            setYRot(Mth.rotLerp(0.4F, getYRot(), o.getYRot()));
            entityData.set(LUNGING, false);
            if (dismissProgress(0.0F) >= 1.0F) {
                entityData.set(HIDDEN, true);
                entityData.set(DISMISS, 0);
            }
            return;
        }
        if (isHidden()) {
            setPos(o.getX(), o.getY() + 0.15D, o.getZ()); // waits inside the Pillager
            entityData.set(LUNGING, false);
            return;
        }
        if (mobAttackCd > 0) mobAttackCd--;
        boolean lunging = lungeTicks > 0;
        entityData.set(LUNGING, lunging);
        Vec3 dest;
        if (lunging) {
            lungeTicks--;
            dest = lungeTarget.add(0.0D, -0.9D, 0.0D);
        } else {
            dest = homePosition(o, type, tickCount, side());
        }
        Vec3 cur = position();
        Vec3 next = cur.distanceToSqr(dest) > 144.0D ? dest : cur.add(dest.subtract(cur).scale(lunging ? 0.5D : 0.35D));
        setPos(next.x, next.y, next.z);
        Vec3 face = target != null && target.distanceToSqr(this) < 64.0D ? target.position().subtract(position()) : null;
        float yaw = face != null && face.horizontalDistanceSqr() > 1.0E-4D
                ? (float) (Mth.atan2(face.z, face.x) * 57.2957763671875D) - 90.0F : o.getYRot();
        setYRot(Mth.rotLerp(0.4F, getYRot(), yaw));
        setXRot(0.0F);

        if (target != null && mobAttackCd <= 0 && tickCount > FADE_TICKS && target.distanceToSqr(o) < 6.0D * 6.0D && o.hasLineOfSight(target)) {
            mobAttackCd = 26 + random.nextInt(10);
            final LivingEntity victim = target;
            Vec3 dir = victim.position().subtract(o.position()).multiply(1.0D, 0.0D, 1.0D);
            dir = dir.lengthSqr() < 1.0E-4D ? Vec3.ZERO : dir.normalize();
            lunge(victim.position().add(0.0D, victim.getBbHeight() * 0.5D, 0.0D).subtract(dir.scale(0.9D)), 8);
            playAnim(0);
            sl.playSound(null, getX(), getY(), getZ(), net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_SWEEP, net.minecraft.sounds.SoundSource.HOSTILE, 0.5F, 1.3F);
            com.mobstands.stand.TickScheduler.later(StandAbilities.PUNCH_HIT_TICK, () -> {
                if (isRemoved() || !victim.isAlive() || !o.isAlive() || victim.distanceToSqr(this) > 4.5D * 4.5D) return;
                float dmg = type == StandType.IRON_GOLEM ? 6.0F : 4.5F;
                if (victim.hurt(sl.damageSources().mobAttack(o), dmg)) {
                    Vec3 push = victim.position().subtract(position()).multiply(1.0D, 0.0D, 1.0D);
                    if (push.lengthSqr() > 1.0E-4D) victim.knockback(0.6D, -push.x, -push.z);
                }
                sl.playSound(null, victim.getX(), victim.getY(), victim.getZ(), com.mobstands.registry.ModSounds.COMBO_PUNCH_1.get(), net.minecraft.sounds.SoundSource.HOSTILE, 0.9F, 1.0F);
                sl.sendParticles(ParticleTypes.CRIT, victim.getX(), victim.getY() + victim.getBbHeight() * 0.6D, victim.getZ(), 8, 0.2D, 0.2D, 0.2D, 0.3D);
            });
        }
    }

    public Player getOwnerPlayer() {
        return getOwnerUUID().map(u -> level().getPlayerByUUID(u)).orElse(null);
    }

    public void setStandType(StandType type) {
        entityData.set(TYPE, type == null ? -1 : type.ordinal());
    }

    public StandType getStandType() {
        int id = entityData.get(TYPE);
        StandType[] all = StandType.values();
        return id >= 0 && id < all.length ? all[id] : null;
    }

    public boolean isHidden() {
        return entityData.get(HIDDEN);
    }

    /** 0 = basic, 1..3 = abilities, -1 = none. */
    /** Squid Rain Shot: 0 normally, eases to 1 while the squid is down in front of you and tipped over. */
    public float rainShotBlend(float partial) {
        if (getStandType() != StandType.SQUID || animSlot() != 3) return 0.0F;
        float el = animElapsed(partial);
        float total = com.mobstands.stand.StandAbilities.RAIN_SHOT_TICKS + 8;
        if (el < 0.0F || el > total) return 0.0F;
        float in = Mth.clamp(el / com.mobstands.stand.StandAbilities.RAIN_SHOT_TILT, 0.0F, 1.0F);
        float out = Mth.clamp((total - el) / 8.0F, 0.0F, 1.0F);
        float k = Math.min(in, out);
        return k * k * (3.0F - 2.0F * k);
    }

    public int animSlot() {
        return entityData.get(ANIM) - 1;
    }

    public int animStart() {
        return entityData.get(ANIM_START);
    }

    /** Extra animation id (beyond the 4 ability slots): Iron Golem's summon-punch combo. */
    public static final int ANIM_SUMMON_COMBO = 4;
    /** Double-click charged special (wind-up, dash, right-left), timed to the full combo sound. */
    public static final int ANIM_CHARGED_COMBO = 5;

    public static int animDuration(StandType type, int slot) {
        if (slot == ANIM_SUMMON_COMBO) return com.mobstands.stand.StandAbilities.COMBO_HIT_2 + com.mobstands.stand.StandAbilities.COMBO_RECOVER;
        if (slot == ANIM_CHARGED_COMBO) return com.mobstands.stand.StandAbilities.CHARGED_HIT_2 + com.mobstands.stand.StandAbilities.COMBO_RECOVER;
        if (slot == 0 && type == StandType.SKELETON) return StandType.BOW_DRAW_TICKS + 4;
        if (slot == 1 && type != null && type.barrage()) return com.mobstands.stand.StandAbilities.BARRAGE_TICKS;
        if (slot == 3 && type == StandType.SQUID) return com.mobstands.stand.StandAbilities.RAIN_SHOT_TICKS + 8;
        return slot == 0 ? 12 : slot == 3 ? 34 : 18;
    }

    /** Ticks since the current animation started (only meaningful while one is playing). */
    public float animElapsed(float partial) {
        return Math.max(0.0F, (float) ((int) level().getGameTime() - animStart()) + partial);
    }

    /** Animation progress 0..1, or -1 when idle. */
    public float animProgress(float partial) {
        int slot = animSlot();
        if (slot < 0) return -1.0F;
        float elapsed = (float) ((int) level().getGameTime() - animStart()) + partial;
        if (elapsed < 0.0F) elapsed = 0.0F;
        int dur = slot == ANIM_SUMMON_COMBO ? comboDuration()
                : slot == 0 && getStandType() == StandType.ALLAY ? com.mobstands.stand.StandAbilities.allayRushTicks(com.mobstands.stand.StandAbilities.rushFollowUps(freeAllays()))
                : animDuration(getStandType(), slot);
        return elapsed > dur ? -1.0F : elapsed / dur;
    }

    public boolean isPosing() {
        return entityData.get(POSING);
    }

    public void setPosing(boolean posing) {
        entityData.set(POSING, posing);
    }

    public int side() {
        return entityData.get(SIDE) < 0 ? -1 : 1;
    }

    public void setSide(int side) {
        entityData.set(SIDE, side < 0 ? -1 : 1);
    }

    private long fakeDeadUntil;
    private Vec3 fakeDeadPos = Vec3.ZERO;

    public void setOwnerDead(boolean dead) {
        entityData.set(OWNER_DEAD, dead);
    }

    public boolean isOwnerDead() {
        return entityData.get(OWNER_DEAD);
    }

    /** Stop playing dead right away (it gets back up on the next tick). */
    public void endPlayDead() {
        fakeDeadUntil = 0L;
    }

    public boolean isFakeDead() {
        return entityData.get(FAKE_DEAD);
    }

    /** Server: dive to {@code pos} (in front of the owner) and play dead there until {@code until}. */
    public void playDead(Vec3 pos, long until, Vec3 facing) {
        fakeDeadPos = pos;
        fakeDeadUntil = until;
        lungeTicks = 0;
        entityData.set(FAKE_DEAD, true);
        setPos(pos.x, pos.y, pos.z);
        setYRot(Mth.wrapDegrees((float) (Mth.atan2(facing.z, facing.x) * 57.2957763671875D) - 90.0F));
    }

    public int rainLevel() {
        return entityData.get(RAIN);
    }

    public void setRainLevel(int level) {
        entityData.set(RAIN, level);
    }

    public int hopStart() {
        return entityData.get(HOP_START);
    }

    public int punchSide() {
        return entityData.get(PUNCH_SIDE);
    }

    public void setPunchSide(int side) {
        entityData.set(PUNCH_SIDE, side);
    }

    public int comboHit2() {
        return entityData.get(COMBO_HIT2);
    }

    public void setComboHit2(int tick) {
        entityData.set(COMBO_HIT2, tick);
    }

    /** Combo animation length: until punch 2 has recovered, or until the second-click window closes. */
    private int comboDuration() {
        int h2 = comboHit2();
        int end1 = com.mobstands.stand.StandAbilities.COMBO_HIT_1 + com.mobstands.stand.StandAbilities.COMBO_RECOVER;
        return h2 >= 0 ? Math.max(end1, h2 + com.mobstands.stand.StandAbilities.COMBO_RECOVER) : end1;
    }

    public void playAnim(int slot) {
        playAnimFrom(slot, 0);
    }

    /** Starts an animation as if it had already been playing for {@code skipTicks} (used for quick re-draws). */
    public void playAnimFrom(int slot, int skipTicks) {
        entityData.set(ANIM, slot + 1);
        entityData.set(ANIM_START, (int) level().getGameTime() - skipTicks);
    }

    // ------------------------------------------------------------------ server control API

    /** Dash toward a point for a number of ticks (used by attacks). */
    // ---- Piglin Snatch: a smooth, fast dash out to a mob, a grab, and a dash back to you ----
    public static final int SNATCH_OUT = 8, SNATCH_HOLD = 3, SNATCH_BACK = 10;
    private Entity snatchTarget;
    private Vec3 snatchFrom = Vec3.ZERO, snatchLast = Vec3.ZERO;
    private int snatchTick = -1;
    private Runnable snatchGrab;

    /** Dashes out to {@code target}, runs {@code onGrab} the moment it reaches it, then glides back to you. */
    public void snatch(Entity target, Runnable onGrab) {
        snatchTarget = target;
        snatchFrom = position();
        snatchLast = target.position();
        snatchGrab = onGrab;
        snatchTick = 0;
        lungeTicks = 0;
    }

    private static double easeInOut(double f) {
        f = Mth.clamp(f, 0.0D, 1.0D);
        return f < 0.5D ? 4.0D * f * f * f : 1.0D - Math.pow(-2.0D * f + 2.0D, 3.0D) / 2.0D;
    }

    private void snatchStep(ServerPlayer owner, StandType type) {
        snatchTick++;
        Entity t = snatchTarget;
        if (t != null && t.isAlive()) {
            // Stops just in front of the mob, on the side facing you.
            Vec3 toOwner = owner.position().subtract(t.position()).multiply(1.0D, 0.0D, 1.0D);
            toOwner = toOwner.lengthSqr() < 1.0E-4D ? Vec3.ZERO : toOwner.normalize();
            snatchLast = t.position().add(toOwner.scale(t.getBbWidth() * 0.5D + 0.55D));
        }
        Vec3 grab = snatchLast;
        Vec3 pos;
        Vec3 face;
        if (snatchTick <= SNATCH_OUT) {
            pos = snatchFrom.lerp(grab, easeInOut(snatchTick / (double) SNATCH_OUT));
            face = (t != null ? t.position() : grab).subtract(pos);
            if (snatchTick == SNATCH_OUT && snatchGrab != null) {
                snatchGrab.run();
                snatchGrab = null;
            }
        } else if (snatchTick <= SNATCH_OUT + SNATCH_HOLD) {
            pos = grab;
            face = owner.position().subtract(pos);
        } else {
            double f = (snatchTick - SNATCH_OUT - SNATCH_HOLD) / (double) SNATCH_BACK;
            Vec3 home = homePosition(owner, type, tickCount, side());
            pos = grab.lerp(home, easeInOut(f));
            face = home.subtract(grab);
            if (f >= 1.0D) {
                snatchTick = -1;
                snatchTarget = null;
                face = owner.getLookAngle();
            }
        }
        setPos(pos.x, pos.y, pos.z);
        if (face.horizontalDistanceSqr() > 1.0E-4D) {
            float yaw = (float) (Mth.atan2(face.z, face.x) * 57.2957763671875D) - 90.0F;
            setYRot(Mth.rotLerp(0.45F, getYRot(), yaw));
        }
        setXRot(0.0F);
    }

    public void lunge(Vec3 target, int ticks) {
        this.lungeTarget = target;
        this.lungeTicks = ticks;
    }

    public void snapTo(Vec3 pos) {
        setPos(pos.x, pos.y, pos.z);
    }

    /** Stand disappears (e.g. after self-detonation) and re-forms later. */
    public void reform(int ticks) {
        this.reformTicks = ticks;
        entityData.set(HIDDEN, true);
        if (level() instanceof ServerLevel sl) {
            sl.sendParticles(ParticleTypes.POOF, getX(), getY() + 1.0D, getZ(), 15, 0.3D, 0.5D, 0.3D, 0.05D);
        }
    }

    public boolean isReforming() {
        return reformTicks > 0;
    }

    // ------------------------------------------------------------------ position

    /** Behind and slightly to the right of the owner, hovering. */
    /** How far the Stand may drift from its spot behind you (blocks). Keeps it feeling attached. */
    private static final double MAX_DRIFT = 0.45D;

    /**
     * Glues the Stand to its owner: emerges from them on summon, then eases toward its spot each tick but
     * is never allowed more than MAX_DRIFT away from it, however fast the owner moves or flies.
     * Runs on both sides; on the client it uses the owner's own (smooth, lag-free) position.
     */
    private void followOwner(Player owner, StandType type) {
        Vec3 home = homePosition(owner, type, tickCount, side());
        if (type.isCart() && isCartRide()) home = owner.position(); // you're sitting in it
        if ((type == StandType.ENDERMAN || type == StandType.ENDER_WORLD) && behindUntilMoved) {
            // Summoned: it appears dead centre right behind you, and stays there until you move -
            // then it drifts out to its usual spot at your side.
            if (summonAnchor == null) summonAnchor = owner.position();
            Vec3 moved = owner.position().subtract(summonAnchor);
            if (moved.x * moved.x + moved.z * moved.z > 0.09D || Math.abs(moved.y) > 0.6D) {
                behindUntilMoved = false;
            } else {
                double yaw = Math.toRadians(owner.getYRot());
                home = new Vec3(owner.getX() + Math.sin(yaw) * 0.8D, home.y, owner.getZ() - Math.cos(yaw) * 0.8D);
            }
        }
        if (type == StandType.GER && isPosing() && tickCount >= FADE_TICKS) {
            // Posing: it steps up right beside you, shoulder to shoulder, standing on the ground.
            double yaw = Math.toRadians(owner.getYRot());
            double sx = -Math.cos(yaw), sz = -Math.sin(yaw); // your right
            home = new Vec3(owner.getX() + sx * 0.75D * side(), owner.getY(), owner.getZ() + sz * 0.75D * side());
        }
        if (type == StandType.ENDER_WORLD && isPosing() && tickCount >= FADE_TICKS) {
            // Posing: it steps in right behind you, dead centre, standing on the ground like a person would.
            double yaw = Math.toRadians(owner.getYRot());
            home = new Vec3(owner.getX() + Math.sin(yaw) * 0.75D, owner.getY(), owner.getZ() - Math.cos(yaw) * 0.75D);
        }
        if (type == StandType.SQUID) {
            // Rain Shot: the squid drops down from above your head to hover right in front of you, then floats back up.
            float k = rainShotBlend(0.0F);
            if (k > 0.0F) {
                double yaw = Math.toRadians(owner.getYRot());
                Vec3 front = new Vec3(owner.getX() - Math.sin(yaw) * 3.6D, owner.getY() - 0.6D, owner.getZ() + Math.cos(yaw) * 3.6D);
                home = home.add(front.subtract(home).scale(k));
            }
        }
        int emerge = type == StandType.SPIDER ? SPIDER_CLIMB_TICKS : fadeTicks(type);
        if (type == StandType.SLIME && tickCount >= FADE_TICKS) {
            if (!level().isClientSide && owner instanceof ServerPlayer sp) slimeTick(sp);
            return;
        }
        if (type == StandType.PIG && tickCount >= FADE_TICKS) {
            if (!level().isClientSide && owner instanceof ServerPlayer sp) pigTick(sp);
            return;
        }
        if (type == StandType.ALLAY && isLifting()) {
            // Holding you from above, wings spread, like a glider.
            setPos(owner.getX(), owner.getY() + owner.getBbHeight() + 0.3D, owner.getZ());
            setYRot(owner.getYRot());
            setXRot(0.0F);
            return;
        }
        if (type == StandType.ALLAY && tickCount >= FADE_TICKS) {
            Entity held = mainHoldTarget();
            if (held != null) {
                // Your own Allay is holding an enemy up: hovering right above it.
                Vec3 at = held.position().add(0.0D, held.getBbHeight() + 0.15D, 0.0D);
                Vec3 cur = position();
                Vec3 next = cur.distanceToSqr(at) > 64.0D ? at : cur.add(at.subtract(cur).scale(0.4D));
                setPos(next.x, next.y, next.z);
                return;
            }
            if (isRoaming()) {
                if (!level().isClientSide && owner instanceof ServerPlayer sp) allayTick(sp);
                return;
            }
            int count = freeAllays();
            if (count >= 2) {
                // In formation with the duplicates (circle / shoulders + head / either side): smooth follow.
                float t = tickCount;
                Vec3 at = allayFormation(owner.position(), owner.yBodyRot, owner.getBbHeight(), count, 0, t);
                Vec3 cur = position();
                Vec3 next = cur.distanceToSqr(at) > 64.0D ? at : cur.add(at.subtract(cur).scale(0.5D));
                setPos(next.x, next.y, next.z);
                setYRot(Mth.rotLerp(0.3F, getYRot(), allayFormationYaw(owner.yBodyRot, count, 0, t)));
                setXRot(0.0F);
                return;
            }
            // Otherwise it stands by your side like any other Stand (falls through).
        }
        if (tickCount < emerge) {
            float t = tickCount / (float) emerge;
            double e = t * t * t * (t * (t * 6.0D - 15.0D) + 10.0D);
            Vec3 from = owner.position().add(0.0D, 0.15D, 0.0D);
            if (type == StandType.ENDERMAN || type == StandType.ENDER_WORLD) {
                // Slides out of your body back and up in one smooth glide (fast out, gently settling), fading in.
                float k = Math.min(1.0F, (tickCount - 1) / (float) Math.max(1, emerge - 1));
                double ease = 1.0D - Math.pow(1.0D - k, 3.0D);
                Vec3 body = owner.position().add(0.0D, 0.15D, 0.0D);
                Vec3 pos = body.add(home.subtract(body).scale(ease));
                setPos(pos.x, pos.y, pos.z);
                if (tickCount <= 1) setOldPosAndRot(); // no streak from wherever the spawn packet put it
                setYRot(owner.getYRot());
                setXRot(0.0F);
                return;
            }
            if (type == StandType.SPIDER) {
                // Crawls out from the ground behind your heels, then up onto your back.
                double yaw = Math.toRadians(owner.getYRot());
                from = owner.position().add(Math.sin(yaw) * 0.55D, 0.0D, -Math.cos(yaw) * 0.55D);
                double up = Math.max(0.0D, (t - 0.35D) / 0.65D);
                Vec3 ground = new Vec3(home.x, owner.getY(), home.z);
                Vec3 pos = t < 0.35D ? from.add(ground.subtract(from).scale(t / 0.35D)) : ground.add(0.0D, (home.y - owner.getY()) * up, 0.0D);
                setPos(pos.x, pos.y, pos.z);
                setYRot(owner.getYRot());
                setXRot(0.0F);
                return;
            }
            Vec3 pos = from.add(home.subtract(from).scale(e));
            setPos(pos.x, pos.y, pos.z);
            setYRot(owner.getYRot());
            setXRot(0.0F);
            return;
        }
        Vec3 cur = position();
        Vec3 next;
        if (cur.distanceToSqr(home) > 64.0D) {
            next = home; // teleports, respawns, dimension changes
        } else {
            next = type == StandType.HORSE || (type.isCart() && isCartRide()) ? home : cur.add(home.subtract(cur).scale(0.45D)); // the horse head sits fixed
            Vec3 off = next.subtract(home);
            if (off.length() > MAX_DRIFT) next = home.add(off.normalize().scale(MAX_DRIFT));
        }
        setPos(next.x, next.y, next.z);
        setYRot(Mth.rotLerp(0.5F, getYRot(), owner.getYRot()));
        setXRot(owner.getXRot() * 0.6F);
    }


    private boolean clientFollowing() {
        // The Slime roams on its own (server-driven), so the client never glues it to you.
        return level().isClientSide && !isDismissing() && !entityData.get(LUNGING) && getOwnerPlayer() != null
                && getStandType() != StandType.SLIME && getStandType() != StandType.PIG && (getStandType() != StandType.ALLAY || isLifting() || (!isRoaming() && mainHoldTarget() == null)) && !isFakeDead();
    }

    /** Ticks the Spider takes to crawl from the ground up onto your back. */
    public static final int SPIDER_CLIMB_TICKS = 18;

    // ------------------------------------------------------------------ Slime: a wild Stand with a mind of its own

    private static final int HOP_TICKS = 10;
    private Vec3 hopFrom = Vec3.ZERO;
    private Vec3 hopTo = Vec3.ZERO;
    private int hopTick = -1;
    private int hopCooldown;
    private int slamCooldown;

    /**
     * Like Purple Haze: the Slime isn't glued to you. It hops after you on its own, wanders a little when idle,
     * and goes after nearby hostile mobs by itself, slamming down on them.
     */
    private void slimeTick(ServerPlayer owner) {
        ServerLevel sl = (ServerLevel) level();
        if (slamCooldown > 0) slamCooldown--;
        if (hopTick >= 0) {
            hopTick++;
            double t = Math.min(1.0D, hopTick / (double) HOP_TICKS);
            Vec3 pos = hopFrom.add(hopTo.subtract(hopFrom).scale(t)).add(0.0D, 0.9D * 4.0D * t * (1.0D - t), 0.0D);
            setPos(pos.x, pos.y, pos.z);
            if (hopTick >= HOP_TICKS) {
                hopTick = -1;
                slimeLand(owner, sl);
            }
            return;
        }
        if (hopCooldown > 0) {
            hopCooldown--;
            return;
        }
        Vec3 pos = position();
        net.minecraft.world.entity.monster.Monster prey = null;
        if (com.mobstands.config.StandConfig.AUTO_ASSIST.get()) {
            double best = 81.0D;
            for (net.minecraft.world.entity.monster.Monster m : sl.getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class,
                    owner.getBoundingBox().inflate(9.0D), x -> com.mobstands.stand.StandCombat.valid(owner, x))) {
                double d = m.distanceToSqr(owner);
                if (d < best) {
                    best = d;
                    prey = m;
                }
            }
        }
        Vec3 home = homePosition(owner, StandType.SLIME, 0, side());
        home = new Vec3(home.x, owner.getY(), home.z).add(owner.getLookAngle().multiply(1.0D, 0.0D, 1.0D).normalize().scale(-1.0D));
        Vec3 goal;
        if (prey != null) {
            goal = prey.position();
        } else {
            double dist = pos.distanceTo(home);
            if (dist > 20.0D) {
                setPos(home.x, home.y, home.z); // fell far behind: catch up
                return;
            }
            if (dist < 1.6D) {
                if (random.nextInt(60) != 0) return; // content, sitting still
                goal = pos.add((random.nextDouble() - 0.5D) * 3.0D, 0.0D, (random.nextDouble() - 0.5D) * 3.0D); // idle wander
            } else {
                goal = home;
            }
        }
        Vec3 flat = new Vec3(goal.x - pos.x, 0.0D, goal.z - pos.z);
        double len = flat.length();
        if (len < 0.2D) return;
        Vec3 to = pos.add(flat.scale(Math.min(len, prey != null ? 2.6D : 2.8D) / len));
        to = new Vec3(to.x, groundY(sl, to), to.z);
        hopFrom = pos;
        hopTo = to;
        hopTick = 0;
        hopCooldown = prey != null ? 3 : 7;
        entityData.set(HOP_START, (int) sl.getGameTime());
        setYRot(Mth.wrapDegrees((float) (Mth.atan2(flat.z, flat.x) * 57.2957763671875D) - 90.0F));
        sl.playSound(null, getX(), getY(), getZ(), net.minecraft.sounds.SoundEvents.SLIME_JUMP,
                net.minecraft.sounds.SoundSource.PLAYERS, 0.6F, 0.9F + random.nextFloat() * 0.2F);
    }

    private void slimeLand(ServerPlayer owner, ServerLevel sl) {
        sl.playSound(null, getX(), getY(), getZ(), net.minecraft.sounds.SoundEvents.SLIME_SQUISH,
                net.minecraft.sounds.SoundSource.PLAYERS, 0.7F, 0.9F + random.nextFloat() * 0.2F);
        if (slamCooldown > 0) return;
        boolean hit = false;
        for (LivingEntity e : sl.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(1.2D, 0.6D, 1.2D),
                x -> x instanceof net.minecraft.world.entity.monster.Monster && com.mobstands.stand.StandCombat.valid(owner, x))) {
            com.mobstands.stand.StandCombat.hurt(owner, e, 4.0F);
            com.mobstands.stand.StandCombat.push(e, e.position().subtract(position()), 0.9D, 0.4D);
            hit = true;
        }
        if (hit) {
            slamCooldown = 12;
            sl.playSound(null, getX(), getY(), getZ(), net.minecraft.sounds.SoundEvents.SLIME_ATTACK,
                    net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 0.8F);
        }
    }

    // ------------------------------------------------------------------ Pig: just a pig, trotting around you

    private Vec3 pigGoal;
    private int pigRest;
    /** Oink: the spot the pig is trotting off to before it oinks. */
    private Vec3 oinkSpot;
    private int oinkTimer;
    /** After oinking the pig stays put for a while (so lured monsters can reach it). */
    private int pigStay;

    /** Server: Oink - the pig trots away from you to {@code spot}, then oinks there. */
    public void pigSendOff(Vec3 spot) {
        oinkSpot = spot;
        oinkTimer = 0;
        pigStay = 0;
        pigGoal = spot;
    }

    private void arriveAndOink(ServerPlayer owner) {
        oinkSpot = null;
        pigGoal = null;
        pigStay = com.mobstands.stand.StandAbilities.OINK_TICKS;
        com.mobstands.stand.StandManager.pigOink(owner, this);
    }

    /** The Pig wanders around you freely like a real pig: ambles to spots near you, sniffs around, follows when you leave. */
    private void pigTick(ServerPlayer owner) {
        ServerLevel sl = (ServerLevel) level();
        Vec3 pos = position();
        double toOwner = pos.distanceTo(owner.position());
        if (toOwner > 24.0D) {
            Vec3 home = homePosition(owner, StandType.PIG, 0, side());
            setPos(home.x, groundY(sl, home), home.z); // fell far behind: catch up
            pigGoal = null;
            return;
        }
        if (oinkSpot != null) {
            pigGoal = oinkSpot;
            if (++oinkTimer > 70) { // couldn't get there: oink where it is
                arriveAndOink(owner);
                return;
            }
        }
        if (pigStay > 0) pigStay--;
        boolean busy = oinkSpot != null || pigStay > 0;
        boolean far = toOwner > (busy ? 14.0D : 5.0D);
        if (far) {
            oinkSpot = null;
            pigStay = 0;
            Vec3 back = owner.position().subtract(pos).multiply(1.0D, 0.0D, 1.0D).normalize().scale(-1.8D);
            pigGoal = owner.position().add(back);
        } else if (pigGoal == null && !busy) {
            if (pigRest > 0) {
                pigRest--;
            } else if (random.nextInt(25) == 0) {
                double a = random.nextDouble() * Math.PI * 2.0D;
                double r = 1.5D + random.nextDouble() * 2.5D;
                pigGoal = owner.position().add(Math.cos(a) * r, 0.0D, Math.sin(a) * r);
            }
        }
        if (random.nextInt(160) == 0) {
            sl.playSound(null, getX(), getY(), getZ(), net.minecraft.sounds.SoundEvents.PIG_AMBIENT,
                    net.minecraft.sounds.SoundSource.PLAYERS, 0.6F, 0.9F + random.nextFloat() * 0.2F);
        }
        if (pigGoal != null && tickCount % 7 == 0) {
            sl.playSound(null, getX(), getY(), getZ(), net.minecraft.sounds.SoundEvents.PIG_STEP,
                    net.minecraft.sounds.SoundSource.PLAYERS, 0.15F, 1.0F); // little trotter steps
        }
        if (pigGoal == null) {
            setPos(pos.x, Mth.lerp(0.5D, pos.y, groundY(sl, pos)), pos.z);
            return;
        }
        Vec3 flat = new Vec3(pigGoal.x - pos.x, 0.0D, pigGoal.z - pos.z);
        double len = flat.length();
        if (len < 0.3D) {
            if (oinkSpot != null) {
                arriveAndOink(owner);
                return;
            }
            pigGoal = null;
            pigRest = 20 + random.nextInt(60);
            return;
        }
        double speed = far ? Math.min(0.45D, 0.2D + (toOwner - 5.0D) * 0.05D) : oinkSpot != null ? 0.28D : 0.11D;
        Vec3 step = pos.add(flat.scale(Math.min(len, speed) / len));
        double gy = groundY(sl, step);
        if (gy - pos.y > 1.1D) { // a wall: give up on that spot
            if (oinkSpot != null) {
                arriveAndOink(owner);
                return;
            }
            pigGoal = null;
            pigRest = 10;
            return;
        }
        setPos(step.x, Mth.lerp(0.5D, pos.y, gy), step.z);
        float yaw = (float) (Mth.atan2(flat.z, flat.x) * (180.0D / Math.PI)) - 90.0F;
        setYRot(Mth.rotLerp(0.3F, getYRot(), yaw));
        setXRot(0.0F);
    }

    // ------------------------------------------------------------------ Allay: flutters around you freely

    private Vec3 allayGoal;
    private Vec3 allayVel = Vec3.ZERO;
    private int allayHitCooldown;
    private int allayHits;
    private long allayLastHit;
    private int allayRest;
    /** Hits it takes to "kill" the roaming Allay (and make it duplicate). */
    public static final int ALLAY_HITS_TO_KILL = 5;

    private void allayTick(ServerPlayer owner) {
        Vec3 pos = position();
        Vec3 center = owner.position().add(0.0D, 1.2D, 0.0D);
        double toOwner = pos.distanceTo(center);
        if (toOwner > 24.0D) {
            Vec3 home = homePosition(owner, StandType.ALLAY, tickCount, side());
            setPos(home.x, home.y, home.z);
            allayGoal = null;
            return;
        }
        boolean far = toOwner > 6.0D;
        if (far) {
            Vec3 away = pos.subtract(center).multiply(1.0D, 0.0D, 1.0D);
            away = away.lengthSqr() < 1.0E-4D ? Vec3.ZERO : away.normalize().scale(1.6D);
            allayGoal = center.add(away).add(0.0D, 0.3D, 0.0D);
        } else if (allayGoal == null) {
            if (allayRest > 0) {
                allayRest--;
            } else {
                double a = random.nextDouble() * Math.PI * 2.0D;
                double r = 1.5D + random.nextDouble() * 2.5D;
                allayGoal = owner.position().add(Math.cos(a) * r, 1.0D + random.nextDouble() * 1.2D, Math.sin(a) * r);
            }
        }
        // Glides with smooth acceleration and a soft stop, instead of zipping at a fixed speed.
        Vec3 vel = allayVel;
        if (allayGoal != null) {
            Vec3 delta = allayGoal.subtract(pos);
            double len = delta.length();
            if (len < 0.2D && !far) {
                allayGoal = null;
                allayRest = 30 + random.nextInt(60); // hangs around a bit before moving on
            } else {
                double max = far ? Math.min(0.75D, 0.25D + (toOwner - 6.0D) * 0.08D) : 0.11D;
                Vec3 want = delta.scale(Math.min(max, len * 0.12D) / Math.max(len, 1.0E-4D));
                vel = vel.add(want.subtract(vel).scale(0.12D));
            }
        } else {
            vel = vel.scale(0.85D);
        }
        if (!far && !level().noCollision(this, getBoundingBox().move(vel))) {
            allayGoal = null; // would fly into a block: stop and pick somewhere else
            vel = Vec3.ZERO;
        }
        allayVel = vel;
        double bob = Math.sin(tickCount * 0.1D) * 0.006D; // gentle hover
        setPos(pos.x + vel.x, pos.y + vel.y + bob, pos.z + vel.z);
        if (vel.horizontalDistanceSqr() > 4.0E-4D) {
            float yaw = (float) (Mth.atan2(vel.z, vel.x) * (180.0D / Math.PI)) - 90.0F;
            setYRot(Mth.rotLerp(0.12F, getYRot(), yaw)); // turns gently toward where it's going
        } else {
            setYRot(Mth.rotLerp(0.05F, getYRot(), owner.getYRot()));
        }
        setXRot(0.0F);
    }

    /** Ground height under a point (so hops land on blocks, up or down slopes). */
    private double groundY(ServerLevel sl, Vec3 p) {
        Vec3 top = p.add(0.0D, 2.5D, 0.0D);
        Vec3 bottom = p.add(0.0D, -6.0D, 0.0D);
        net.minecraft.world.phys.BlockHitResult hit = sl.clip(new net.minecraft.world.level.ClipContext(top, bottom,
                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, this));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS ? p.y : hit.getLocation().y;
    }

    /** While the client is gluing the Stand to its owner, ignore the server's (slightly behind) positions. */
    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
        if (clientFollowing()) return;
        StandType t = getStandType();
        if (t == StandType.PIG || t == StandType.ALLAY || t == StandType.SLIME || t == StandType.PIGLIN || t == StandType.PIGLIN_REQUIEM || isMobStand()) {
            // Smooth it out over a few ticks instead of snapping.
            lerpX = x;
            lerpY = y;
            lerpZ = z;
            lerpYRot = yRot;
            lerpSteps = 3;
            return;
        }
        super.lerpTo(x, y, z, yRot, xRot, steps);
    }

    private double lerpX, lerpY, lerpZ, lastClientX, lastClientZ;
    private float lerpYRot;
    private int lerpSteps;

    /** Spider Stand rests on your back: distance behind your centre and height of its anchor. */
    public static final double SPIDER_BACK = 0.06D;
    public static final double SPIDER_HEIGHT = 1.15D;

    public boolean isDismissing() {
        return entityData.get(DISMISS) != 0;
    }

    /** 0 while visible, rising to 1 as the dismiss fade completes. */
    public float dismissProgress(float partial) {
        int start = entityData.get(DISMISS);
        if (start == 0) return 0.0F;
        float t = ((int) level().getGameTime() - start + partial) / (float) FADE_OUT_TICKS;
        return Math.max(0.0F, Math.min(1.0F, t));
    }

    /** Server: begin the dismiss fade. The Stand drifts in a random direction, biased toward its owner. */
    public void startDismiss(Player owner) {
        if (isDismissing()) return;
        entityData.set(DISMISS, Math.max(1, (int) level().getGameTime()));
        dismissOffset = position().subtract(owner.position());
        Vec3 toward = owner.position().add(0.0D, owner.getBbHeight() * 0.5D, 0.0D)
                .subtract(position().add(0.0D, getBbHeight() * 0.5D, 0.0D));
        toward = toward.lengthSqr() < 1.0E-4D ? new Vec3(0.0D, 0.0D, 1.0D) : toward.normalize();
        Vec3 rnd = new Vec3(random.nextDouble() - 0.5D, (random.nextDouble() - 0.5D) * 0.6D, random.nextDouble() - 0.5D);
        rnd = rnd.lengthSqr() < 1.0E-4D ? Vec3.ZERO : rnd.normalize();
        dismissDir = toward.scale(0.75D).add(rnd.scale(0.5D)).normalize();
        lungeTicks = 0;
    }

    private void dismissTick(ServerPlayer owner) {
        float t = dismissProgress(0.0F);
        if (t >= 1.0F) {
            discard();
            return;
        }
        double e = 1.0D - Math.pow(1.0D - t, 3.0D); // fast start, gentle stop
        Vec3 pos = owner.position().add(dismissOffset).add(dismissDir.scale(1.4D * e));
        setPos(pos.x, pos.y, pos.z);
    }

    public static Vec3 homePosition(LivingEntity owner, StandType type, int tick, int sideSign) {
        double yaw = Math.toRadians(owner.getYRot());
        double fx = -Math.sin(yaw);
        double fz = Math.cos(yaw);
        double rx = -Math.cos(yaw) * sideSign;
        double rz = -Math.sin(yaw) * sideSign;
        if (type == StandType.HORSE) {
            return new Vec3(owner.getX(), owner.getY() + owner.getBbHeight() + 0.02D, owner.getZ()); // on top of your head
        }
        if (type == StandType.SQUID) {
            // Floats right above your head, tentacles hanging down over you.
            return new Vec3(owner.getX() - fx * 0.15D, owner.getY() + 4.1D + Math.sin(tick * 0.06D) * 0.1D, owner.getZ() - fz * 0.15D);
        }
        if (type == StandType.SPIDER) {
            // Clinging to your back: belly against you, the model is turned upright by the renderer.
            return new Vec3(owner.getX() - fx * SPIDER_BACK, owner.getY() + SPIDER_HEIGHT, owner.getZ() - fz * SPIDER_BACK);
        }
        double back = type.closeBehind() ? 0.5D : 1.15D;
        double side = type.closeBehind() ? 0.6D : 0.85D;
        double x = owner.getX() - fx * back + rx * side;
        double z = owner.getZ() - fz * back + rz * side;
        double y = owner.getY() + type.hover() + Math.sin(tick * 0.08D) * 0.08D;
        BlockPos bp = BlockPos.containing(x, y + 0.9D, z);
        BlockState state = owner.level().getBlockState(bp);
        if (!state.getCollisionShape(owner.level(), bp).isEmpty()) {
            // Wall behind the owner: tuck the Stand into the owner's own space instead.
            x = owner.getX() - fx * 0.2D;
            z = owner.getZ() - fz * 0.2D;
        }
        return new Vec3(x, y, z);
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            clientTick();
        } else {
            serverTick();
        }
    }

    private void clientTick() {
        Player owner = getOwnerPlayer();
        StandType followType = getStandType();
        if (followType != null && clientFollowing()) followOwner(owner, followType);
        // Server-driven Stands (pig, roaming allay...) glide smoothly toward the latest server position.
        if (lerpSteps > 0 && !clientFollowing()) {
            double k = 1.0D / lerpSteps;
            setPos(getX() + (lerpX - getX()) * k, getY() + (lerpY - getY()) * k, getZ() + (lerpZ - getZ()) * k);
            setYRot(getYRot() + Mth.wrapDegrees(lerpYRot - getYRot()) * (float) k);
            lerpSteps--;
        }
        // How far it really moved since last tick (xo is reset before network updates land, so track it ourselves).
        double movedX = getX() - lastClientX, movedZ = getZ() - lastClientZ;
        lastClientX = getX();
        lastClientZ = getZ();

        float speed;
        if (owner != null) {
            speed = owner.onGround() ? Math.min(1.0F, owner.walkAnimation.speed()) : 0.0F;
            if (getStandType() == StandType.SPIDER && tickCount < SPIDER_CLIMB_TICKS + 4) speed = 1.0F; // scurrying up
            if (getStandType() == StandType.SLIME) speed = 0.0F; // the slime animates from its own hops
            if (getStandType() == StandType.PIG || (getStandType() == StandType.ALLAY && isRoaming())) { // moves on its own
                speed = (float) Math.min(1.0D, Math.sqrt(movedX * movedX + movedZ * movedZ) * 6.0D);
            }
        } else {
            double dx = getX() - xo;
            double dz = getZ() - zo;
            speed = (float) Math.min(1.0D, Math.sqrt(dx * dx + dz * dz) * 4.0D);
        }
        if (isFakeDead()) {
            if (fakeDeadStart < 0) fakeDeadStart = tickCount;
        } else {
            fakeDeadStart = -1;
        }
        rainGrowO = rainGrow;
        rainGrow += ((getStandType() == StandType.SQUID && rainLevel() > 0 ? 1.0F : 0.0F) - rainGrow) * 0.08F;
        limbSpeedO = limbSpeed;
        limbSpeed += (speed - limbSpeed) * 0.4F;
        limbPos += limbSpeed;

        StandType type = getStandType();
        if (type == null || isHidden()) return;
        boolean lifting = type == StandType.ALLAY && isLifting();
        if (!isDismissing()) getOwnerUUID().ifPresent(u -> StandPresence.mark(u, type, level().getGameTime(), isPosing(), lifting,
                type.isCart() && isCartRide(), type.isCart() ? cartBoost() : 0, false, cartBoostEnd()));
        if (lifting) {
            Player liftee = getOwnerPlayer();
            if (liftee != null && liftee.isLocalPlayer()) liftOwner(liftee);
        }
        if (!isDismissing() && tickCount <= FADE_TICKS + 6) summonSparkles(type);
        if (type == StandType.SQUID && rainLevel() > 0) rainParticles();
        if (type == StandType.ENDER_WORLD && auraOn() && !isDismissing() && random.nextInt(3) == 0) {
            // Enchanting glyphs drift in and are drawn into the clock on its chest.
            double yaw = Math.toRadians(getYRot());
            double cx = getX() - Math.sin(yaw) * 0.3D, cz = getZ() + Math.cos(yaw) * 0.3D, cy = getY() + 2.15D * getStandType().scale();
            level().addParticle(net.minecraft.core.particles.ParticleTypes.ENCHANT, cx, cy, cz,
                    (random.nextDouble() - 0.5D) * 2.0D, random.nextDouble() * 1.2D - 0.3D, (random.nextDouble() - 0.5D) * 2.0D);
        }
    }

    /** Client: the November Rain itself, falling in a circle around the owner (heavier during Downpour). */
    private void rainParticles() {
        Player o = getOwnerPlayer();
        if (o == null) return;
        int lvl = rainLevel();
        int n = lvl == 1 ? 40 : 120; // spread over the wider area
        // Pours straight down out of the squid's body, only in the area right under it.
        double r = com.mobstands.stand.StandAbilities.RAIN_FALL_RADIUS;
        for (int i = 0; i < n; i++) {
            double a = random.nextDouble() * Math.PI * 2.0D;
            double d = Math.sqrt(random.nextDouble()) * r;
            double x = getX() + Math.cos(a) * d;
            double z = getZ() + Math.sin(a) * d;
            double y = getY() + 0.2D + random.nextDouble() * 0.6D;
            level().addParticle(net.minecraft.core.particles.ParticleTypes.FALLING_WATER, x, y, z, 0.0D, 0.0D, 0.0D);
            if (lvl >= 2) {
                // Downpour: sheets of water driven down hard, splashing up off the ground.
                // (Plain falling water: dripstone drips play a "plink" every time one lands, which flooded the
                // sound engine - hundreds a second - and cut out every other sound.)
                if (i % 2 == 0) level().addParticle(net.minecraft.core.particles.ParticleTypes.FALLING_WATER, x, y - random.nextDouble() * 2.5D, z, 0.0D, -0.6D, 0.0D);
                if (i % 3 == 0) level().addParticle(net.minecraft.core.particles.ParticleTypes.SPLASH, x, o.getY() + 0.1D, z, 0.0D, 0.15D, 0.0D);
                if (i % 4 == 0) level().addParticle(net.minecraft.core.particles.ParticleTypes.RAIN, x, o.getY() + 0.1D, z, 0.0D, 0.0D, 0.0D);
            }
        }
    }

    // ------------------------------------------------------------------ summon sparkles (client)

    /** Rare and rarer Stands burst into sparkles in their own style while they materialize. */
    private void summonSparkles(StandType type) {
        int tier = type.rarity().ordinal(); // 0 common .. 4 legendary
        if (!type.summonEffects()) return; // only Iron Golem, Enderman and Warden
        int count = type == StandType.AXOLOTL ? (random.nextInt(3) == 0 ? 1 : 0) // nonchalant: a few soft glints
                : tier >= 4 ? 7 : tier == 3 ? 5 : 3;
        int c = type.color();
        org.joml.Vector3f rgb = new org.joml.Vector3f((c >> 16 & 255) / 255.0F, (c >> 8 & 255) / 255.0F, (c & 255) / 255.0F);
        net.minecraft.core.particles.DustParticleOptions dust = new net.minecraft.core.particles.DustParticleOptions(rgb, 1.4F);
        net.minecraft.core.particles.ParticleOptions sparkle = switch (type) {
            case WARDEN -> net.minecraft.core.particles.ParticleTypes.GLOW;
            case ENDERMAN, ENDER_WORLD -> net.minecraft.core.particles.ParticleTypes.REVERSE_PORTAL;
            case AXOLOTL -> net.minecraft.core.particles.ParticleTypes.GLOW;
            case BLAZE -> net.minecraft.core.particles.ParticleTypes.FLAME;
            default -> net.minecraft.core.particles.ParticleTypes.END_ROD; // white stars (Iron Golem, Phantom)
        };
        double height = (type == StandType.AXOLOTL ? 0.6D : 2.0D) * type.scale();
        for (int i = 0; i < count; i++) {
            double a = random.nextDouble() * Math.PI * 2.0D;
            double r = 0.4D + random.nextDouble() * 1.1D;
            double x = getX() + Math.cos(a) * r;
            double y = getY() + random.nextDouble() * height * 1.15D;
            double z = getZ() + Math.sin(a) * r;
            double vx = Math.cos(a) * 0.02D, vz = Math.sin(a) * 0.02D;
            level().addParticle(sparkle, x, y, z, vx, 0.01D + random.nextDouble() * 0.02D, vz);
            if (random.nextBoolean()) level().addParticle(dust, x, y, z, 0.0D, 0.0D, 0.0D);
            if (type == StandType.WARDEN && random.nextInt(3) == 0) {
                level().addParticle(net.minecraft.core.particles.ParticleTypes.SCULK_CHARGE_POP, x, y, z, 0.0D, 0.03D, 0.0D);
            }
        }
    }

    // ------------------------------------------------------------------ punch trail (client)

    private float lastTrailProgress = -1.0F;
    private int lastTrailStart = Integer.MIN_VALUE;

    /** Stands that attack by punching/slashing/slamming get a glowing trail; archers and fire-shooters do not. */
    private static boolean punches(StandType type, int slot) {
        if (slot == 0) {
            return type != StandType.SKELETON && type != StandType.BLAZE && type != StandType.ALLAY;
        }
        return type == StandType.IRON_GOLEM || type == StandType.GER || type == StandType.WARDEN || type == StandType.PHANTOM
                || type == StandType.SLIME || type == StandType.CREEPER || type == StandType.ENDERMAN || type == StandType.ENDER_WORLD;
    }

    /** Where the fist is at swing progress p (0..1): sweeps from the shoulder, out to the front. */
    private net.minecraft.world.phys.Vec3 fistPoint(float p, StandType type, int slot) {
        double yaw = Math.toRadians(getYRot());
        double fx = -Math.sin(yaw);
        double fz = Math.cos(yaw);
        double rx = -Math.cos(yaw);
        double rz = -Math.sin(yaw);
        double scale = type.scale();
        double swing = Math.sin(p * Math.PI);
        double reach = (0.35D + 1.45D * swing) * scale;
        double side = (0.55D * (1.0D - p) - 0.1D) * scale;
        double height = (1.05D + 0.25D * Math.sin(p * Math.PI * 2.0D)) * scale + (type == StandType.PHANTOM ? 0.2D : 0.0D);
        return new net.minecraft.world.phys.Vec3(
                getX() + fx * reach + rx * side,
                getY() + height,
                getZ() + fz * reach + rz * side);
    }

    /**
     * A white-hot streak that fades into the Stand's colour. Drawn by interpolating between last tick's fist
     * position and this tick's, so the trail is continuous even though the swing lasts only a few ticks.
     */
    private void punchTrail(StandType type) {
        int slot = animSlot();
        float p = animProgress(0.0F);
        if (slot < 0 || p < 0.0F || !punches(type, slot)) {
            lastTrailProgress = -1.0F;
            return;
        }
        if (animStart() != lastTrailStart) {
            lastTrailStart = animStart();
            lastTrailProgress = Math.max(0.0F, p - 0.12F);
        }
        float from = lastTrailProgress < 0.0F ? Math.max(0.0F, p - 0.12F) : lastTrailProgress;
        lastTrailProgress = p;

        org.joml.Vector3f white = new org.joml.Vector3f(1.0F, 1.0F, 1.0F);
        int c = type.color();
        org.joml.Vector3f tint = new org.joml.Vector3f((c >> 16 & 255) / 255.0F, (c >> 8 & 255) / 255.0F, (c & 255) / 255.0F);
        net.minecraft.core.particles.DustColorTransitionOptions streak =
                new net.minecraft.core.particles.DustColorTransitionOptions(white, tint, 1.3F);
        net.minecraft.core.particles.DustParticleOptions glow =
                new net.minecraft.core.particles.DustParticleOptions(tint, 1.9F);

        int steps = 6;
        for (int i = 0; i <= steps; i++) {
            float q = from + (p - from) * i / steps;
            net.minecraft.world.phys.Vec3 pt = fistPoint(q, type, slot);
            // bright core
            level().addParticle(streak, pt.x, pt.y, pt.z, 0.0D, 0.0D, 0.0D);
            // wide, soft coloured halo
            level().addParticle(glow, pt.x + (random.nextDouble() - 0.5D) * 0.12D, pt.y + (random.nextDouble() - 0.5D) * 0.12D,
                    pt.z + (random.nextDouble() - 0.5D) * 0.12D, 0.0D, 0.0D, 0.0D);
            if (i % 3 == 0) {
                level().addParticle(net.minecraft.core.particles.ParticleTypes.END_ROD, pt.x, pt.y, pt.z, 0.0D, 0.0D, 0.0D);
            }
        }
    }

    private void serverTick() {
        if (isMobStand()) {
            mobStandTick();
            return;
        }
        Player owner = getOwnerPlayer();
        if (!(owner instanceof ServerPlayer sp) || !sp.isAlive() || sp.isRemoved()) {
            discard();
            return;
        }
        if (isDismissing()) {
            dismissTick(sp);
            return;
        }
        if (isFakeDead()) {
            if (level().getGameTime() >= fakeDeadUntil) {
                // Revives: "just kidding" and swims back to your side.
                entityData.set(FAKE_DEAD, false);
                ServerLevel rl = (ServerLevel) level();
                rl.playSound(null, getX(), getY(), getZ(), net.minecraft.sounds.SoundEvents.AXOLOTL_IDLE_AIR,
                        net.minecraft.sounds.SoundSource.PLAYERS, 1.2F, 1.2F);
                rl.playSound(null, getX(), getY(), getZ(), net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_CHIME,
                        net.minecraft.sounds.SoundSource.PLAYERS, 1.2F, 1.4F);
                rl.sendParticles(net.minecraft.core.particles.ParticleTypes.HEART, getX(), getY() + 0.5D, getZ(), 1, 0.2D, 0.1D, 0.2D, 0.0D);
            } else {
                setPos(fakeDeadPos.x, fakeDeadPos.y, fakeDeadPos.z); // lies still where it fell
                return;
            }
        }
        StandData data = StandManager.data(sp);
        StandType type = getStandType();
        if (!data.isSummoned() || type == null || data.getType() != type || !getUUID().equals(data.activeStand)) {
            // Stale / duplicate / dismissed Stand: remove it so only one ever exists per owner.
            discard();
            return;
        }

        if (allayHitCooldown > 0) allayHitCooldown--;
        if (type == StandType.ALLAY) syncAllays(data);
        if (reformTicks > 0 && --reformTicks == 0) {
            entityData.set(HIDDEN, false);
            ((ServerLevel) level()).sendParticles(ParticleTypes.POOF, getX(), getY() + 1.0D, getZ(), 20, 0.3D, 0.5D, 0.3D, 0.05D);
        }

        boolean snatching = snatchTick >= 0;
        boolean lunging = lungeTicks > 0 || snatching;
        entityData.set(LUNGING, lunging);
        if (snatching) {
            snatchStep(sp, type);
        } else if (lunging && tickCount >= FADE_TICKS) {
            lungeTicks--;
            Vec3 t = lungeTarget.add(0.0D, -0.9D, 0.0D);
            Vec3 fromOwner = t.subtract(sp.position());
            if (fromOwner.length() > 11.0D) t = sp.position().add(fromOwner.normalize().scale(11.0D));
            Vec3 cur = position();
            Vec3 delta = t.subtract(cur);
            Vec3 next = delta.length() > 20.0D ? t : cur.add(delta.scale(0.6D));
            setPos(next.x, next.y, next.z);
            float targetYaw = delta.length() > 0.5D
                    ? Mth.wrapDegrees((float) (Mth.atan2(delta.z, delta.x) * 57.2957763671875D) - 90.0F)
                    : sp.getYRot();
            setYRot(Mth.rotLerp(0.35F, getYRot(), targetYaw));
            setXRot(sp.getXRot() * 0.6F);
        } else {
            followOwner(sp, type);
        }

        if (autoCooldown > 0) autoCooldown--;
        if (tickCount % 5 == 0) autoAssist(sp, data, type);
    }

    /** The Stand reacts on its own when a hostile is attacking its owner. */
    private void autoAssist(ServerPlayer owner, StandData data, StandType type) {
        if (autoCooldown > 0 || isHidden() || lungeTicks > 0 || !StandConfig.AUTO_ASSIST.get() || !type.hasBasic()) return;
        if (animProgress(0.0F) >= 0.0F) return;
        ServerLevel sl = (ServerLevel) level();
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (Mob m : sl.getEntitiesOfClass(Mob.class, owner.getBoundingBox().inflate(8.0D), Mob::isAlive)) {
            boolean threat = m.getTarget() == owner
                    || (owner.getLastHurtByMob() == m && owner.tickCount - owner.getLastHurtByMobTimestamp() < 100)
                    || (owner.getLastHurtMob() == m && owner.tickCount - owner.getLastHurtMobTimestamp() < 60);
            if (!threat || !com.mobstands.stand.StandCombat.valid(owner, m)) continue;
            double d = m.distanceToSqr(owner);
            if (d < bestD) {
                bestD = d;
                best = m;
            }
        }
        if (best == null) return;
        AbilityContext ctx = new AbilityContext(owner, this, sl, type, data);
        StandAbilities.autoStrike(ctx, best);
        autoCooldown = 20;
    }

    // ------------------------------------------------------------------ non-interactive entity

    @Override
    public boolean hurt(DamageSource source, float amount) {
        // Hitting the Allay Stand "kills" it for a moment and it comes back duplicated (up to 4).
        if (allayHittable() && !level().isClientSide && source.getEntity() != null && allayHitCooldown <= 0 && tickCount > FADE_TICKS && getOwnerPlayer() instanceof ServerPlayer sp) {
            allayHitCooldown = 8;
            long now = level().getGameTime();
            if (now - allayLastHit > 100L) allayHits = 0; // hits must keep coming (within 5 s of each other)
            allayLastHit = now;
            allayHits++;
            ServerLevel sl = (ServerLevel) level();
            entityData.set(HURT_AT, (int) now); // flashes red
            if (source.getEntity() != null) {
                // Knocked back from the hit.
                Vec3 away = position().subtract(source.getEntity().position()).multiply(1.0D, 0.0D, 1.0D);
                away = away.lengthSqr() < 1.0E-4D ? new Vec3(0.0D, 0.0D, 1.0D) : away.normalize();
                allayVel = away.scale(0.55D).add(0.0D, 0.18D, 0.0D);
                allayGoal = null;
                allayRest = 15;
            }
            if (allayHits < ALLAY_HITS_TO_KILL) {
                sl.playSound(null, getX(), getY(), getZ(), net.minecraft.sounds.SoundEvents.ALLAY_HURT, net.minecraft.sounds.SoundSource.PLAYERS, 0.8F,
                        0.9F + allayHits * 0.08F);
                sl.sendParticles(ParticleTypes.DAMAGE_INDICATOR, getX(), getY() + 0.5D, getZ(), 1, 0.1D, 0.1D, 0.1D, 0.0D);
                return true;
            }
            allayHits = 0;
            StandManager.allayKilled(sp, this);
            return true;
        }
        return false;
    }

    /** Only the Phantom and Skeleton Stands can catch fire (they burn in daylight). */
    @Override
    public boolean fireImmune() {
        StandType t = getStandType();
        return t != StandType.PHANTOM && t != StandType.SKELETON;
    }

    /** Only a roaming Allay can be hit (that's how it duplicates). */
    private boolean allayHittable() {
        return getStandType() == StandType.ALLAY && isRoaming() && !isHidden() && !isDismissing() && !isLifting();
    }

    @Override
    public boolean isPickable() {
        return allayHittable();
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean isAttackable() {
        return allayHittable();
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {}

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {}
}
