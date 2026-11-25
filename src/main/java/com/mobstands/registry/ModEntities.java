package com.mobstands.registry;

import com.mobstands.MobStands;
import com.mobstands.entity.StandEntity;
import com.mobstands.entity.StandProjectile;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, MobStands.MODID);

    // MobCategory.MISC + no spawn placement => never spawns naturally and never counts toward mob caps.
    public static final DeferredHolder<EntityType<?>, EntityType<StandEntity>> STAND = ENTITIES.register("stand",
            () -> EntityType.Builder.<StandEntity>of(StandEntity::new, MobCategory.MISC)
                    .sized(0.8F, 1.8F)
                    .clientTrackingRange(10)
                    .updateInterval(1)
                    .noSave()
                    .noSummon()
                    .build("stand"));

    public static final DeferredHolder<EntityType<?>, EntityType<StandProjectile>> STAND_PROJECTILE = ENTITIES.register("stand_projectile",
            () -> EntityType.Builder.<StandProjectile>of(StandProjectile::new, MobCategory.MISC)
                    .sized(0.3F, 0.3F)
                    .clientTrackingRange(6)
                    .updateInterval(2)
                    .fireImmune()
                    .noSave()
                    .noSummon()
                    .build("stand_projectile"));

    /** A Spin item spinning on the floor (turns back into a normal item when it stops). */
    public static final DeferredHolder<EntityType<?>, EntityType<com.mobstands.entity.SpinItemEntity>> SPIN_ITEM = ENTITIES.register("spin_item",
            () -> EntityType.Builder.<com.mobstands.entity.SpinItemEntity>of(com.mobstands.entity.SpinItemEntity::new, MobCategory.MISC)
                    .sized(0.25F, 0.25F)
                    .clientTrackingRange(8)
                    .updateInterval(1)
                    .noSummon()
                    .build("spin_item"));

    /** The Creeper Stand's Sheer Heart Attack: a tiny creeper in a tiny minecart. */
    public static final DeferredHolder<EntityType<?>, EntityType<com.mobstands.entity.HeartAttackEntity>> HEART_ATTACK = ENTITIES.register("heart_attack",
            () -> EntityType.Builder.<com.mobstands.entity.HeartAttackEntity>of(com.mobstands.entity.HeartAttackEntity::new, MobCategory.MISC)
                    .sized(0.5F, 0.55F)
                    .clientTrackingRange(8)
                    .updateInterval(1)
                    .fireImmune()
                    .noSave()
                    .noSummon()
                    .build("heart_attack"));

    /** Pigstep's Null Bubble: a golden bubble with a gold nugget spinning inside. */
    public static final DeferredHolder<EntityType<?>, EntityType<com.mobstands.entity.NullBubbleEntity>> NULL_BUBBLE = ENTITIES.register("null_bubble",
            () -> EntityType.Builder.<com.mobstands.entity.NullBubbleEntity>of(com.mobstands.entity.NullBubbleEntity::new, MobCategory.MISC)
                    .sized(0.22F, 0.22F)
                    .clientTrackingRange(10)
                    .updateInterval(1)
                    .fireImmune()
                    .noSave()
                    .noSummon()
                    .build("null_bubble"));

    /** GER's Iron Shot: the nugget, then its blazing trail. */
    public static final DeferredHolder<EntityType<?>, EntityType<com.mobstands.entity.NuggetShotEntity>> NUGGET_SHOT = ENTITIES.register("nugget_shot",
            () -> EntityType.Builder.<com.mobstands.entity.NuggetShotEntity>of(com.mobstands.entity.NuggetShotEntity::new, MobCategory.MISC)
                    .sized(0.2F, 0.2F)
                    .clientTrackingRange(10)
                    .updateInterval(1)
                    .fireImmune()
                    .noSave()
                    .noSummon()
                    .build("nugget_shot"));

    /** Silver Fingers' zipper (on a wall, a floor, or a creature). */
    public static final DeferredHolder<EntityType<?>, EntityType<com.mobstands.entity.ZipperEntity>> ZIPPER = ENTITIES.register("zipper",
            () -> EntityType.Builder.<com.mobstands.entity.ZipperEntity>of(com.mobstands.entity.ZipperEntity::new, MobCategory.MISC)
                    .sized(0.2F, 0.2F)
                    .clientTrackingRange(10)
                    .updateInterval(1)
                    .fireImmune()
                    .noSave()
                    .noSummon()
                    .build("zipper"));

    /** Cursed Stands (Cheap Trick, Survivor, Notorious B.I.G., Boy II Man). */
    public static final DeferredHolder<EntityType<?>, EntityType<com.mobstands.entity.CurseEntity>> CURSE = ENTITIES.register("curse",
            () -> EntityType.Builder.<com.mobstands.entity.CurseEntity>of(com.mobstands.entity.CurseEntity::new, MobCategory.MISC)
                    .sized(0.3F, 0.3F)
                    .clientTrackingRange(10)
                    .updateInterval(1)
                    .fireImmune()
                    .noSave()
                    .noSummon()
                    .build("curse"));

    /** Iron Express: a car another player rides at the back of your train. */
    public static final DeferredHolder<EntityType<?>, EntityType<com.mobstands.entity.TrainCarEntity>> TRAIN_CAR = ENTITIES.register("train_car",
            () -> EntityType.Builder.<com.mobstands.entity.TrainCarEntity>of(com.mobstands.entity.TrainCarEntity::new, MobCategory.MISC)
                    .sized(0.98F, 0.7F)
                    .clientTrackingRange(10)
                    .updateInterval(1)
                    .fireImmune()
                    .noSave()
                    .noSummon()
                    .build("train_car"));

    private ModEntities() {}
}
