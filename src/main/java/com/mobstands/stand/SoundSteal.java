package com.mobstands.stand;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.VibrationParticleOption;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.EntityPositionSource;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Gilded Silence (Piglin Requiem): steal a creature's sound and, with it, the power that sound came with.
 * Stolen sounds are kept in {@link StandData#stolenSounds} (entity type ids, or "thunder"), up to {@link #MAX}.
 */
public final class SoundSteal {
    private SoundSteal() {}

    public static final int MAX = 3;
    public static final String THUNDER = "thunder";
    public static final String SILENCED = "mobstands_silenced";
    private static final int SILENCE_TICKS = 1200;

    /** Silenced mobs and when their voice comes back. */
    private static final Map<LivingEntity, Long> SILENCED_UNTIL = new HashMap<>();

    public static boolean steal(AbilityContext c) {
        ServerPlayer p = c.owner;
        LivingEntity target = c.aim(16.0D);
        if (target == null) {
            // Thunder: look up into a thunderstorm.
            if (c.level.isThundering() && p.getXRot() < -45.0F && c.level.canSeeSky(p.blockPosition())) {
                add(p, c.data, THUNDER);
                Vec3 sky = p.getEyePosition().add(c.look().scale(20.0D));
                c.level.sendParticles(new VibrationParticleOption(new EntityPositionSource(c.stand, 1.0F), 20), sky.x, sky.y, sky.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
                c.sound(SoundEvents.SCULK_CLICKING, 1.0F, 0.8F);
                c.later(20, () -> c.sound(SoundEvents.LIGHTNING_BOLT_THUNDER, 0.4F, 1.6F));
                return true;
            }
            p.displayClientMessage(Component.literal("Look at a creature to steal its sound."), true);
            return false;
        }
        if (target instanceof Player || target.getTags().contains(SILENCED)) {
            p.displayClientMessage(Component.literal(target instanceof Player ? "You can't steal that." : "It has no sound left to steal."), true);
            return false;
        }
        c.sound(SoundEvents.PIGLIN_ADMIRING_ITEM, 1.0F, 0.8F);
        c.stand.snatch(target, () -> {
            if (!target.isAlive()) return;
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString();
            silence(target, c.level.getGameTime());
            // The sound travels back like a sculk vibration.
            Vec3 from = target.position().add(0.0D, target.getBbHeight() * 0.7D, 0.0D);
            c.level.sendParticles(new VibrationParticleOption(new EntityPositionSource(c.stand, 1.0F), 14), from.x, from.y, from.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
            c.level.sendParticles(ParticleTypes.ANGRY_VILLAGER, from.x, from.y + 0.4D, from.z, 3, 0.25D, 0.15D, 0.25D, 0.0D);
            c.level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.SCULK_CLICKING, SoundSource.PLAYERS, 1.2F, 1.0F);
            c.later(14, () -> c.sound(SoundEvents.SCULK_CLICKING_STOP, 1.0F, 1.2F));
            add(p, c.data, id);
        });
        return true;
    }

    private static void add(ServerPlayer p, StandData d, String id) {
        if (d.stolenSounds.size() >= MAX) d.stolenSounds.remove(0); // the oldest one slips away
        d.stolenSounds.add(id);
        d.soundSel = d.stolenSounds.size() - 1;
        StandManager.sync(p);
        p.displayClientMessage(Component.literal("Stole: " + name(id)).withStyle(s -> s.withColor(0xFFD24A)), true);
    }

    public static String name(String id) {
        if (THUNDER.equals(id)) return "Thunder";
        EntityType<?> t = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(id));
        return t.getDescription().getString();
    }

    public static boolean cycle(AbilityContext c) {
        StandData d = c.data;
        if (d.stolenSounds.isEmpty()) {
            c.owner.displayClientMessage(Component.literal("You haven't stolen any sounds."), true);
            return false;
        }
        d.soundSel = (d.soundSel + 1) % d.stolenSounds.size();
        StandManager.sync(c.owner);
        c.sound(SoundEvents.NOTE_BLOCK_CHIME.value(), 0.6F, 1.2F + d.soundSel * 0.2F);
        c.owner.displayClientMessage(Component.literal("Ready: " + name(d.stolenSounds.get(d.soundSel))).withStyle(s -> s.withColor(0xFFD24A)), true);
        return true;
    }

    public static boolean release(AbilityContext c) {
        StandData d = c.data;
        if (d.stolenSounds.isEmpty()) {
            c.owner.displayClientMessage(Component.literal("You haven't stolen any sounds."), true);
            return false;
        }
        int i = Math.max(0, Math.min(d.soundSel, d.stolenSounds.size() - 1));
        String id = d.stolenSounds.remove(i);
        d.soundSel = d.stolenSounds.isEmpty() ? 0 : Math.min(i, d.stolenSounds.size() - 1);
        StandManager.sync(c.owner);
        // The Stand claps the sound loose.
        c.stand.playAnim(0);
        c.level.sendParticles(ParticleTypes.NOTE, c.stand.getX(), c.stand.getY() + 2.0D, c.stand.getZ(), 6, 0.4D, 0.2D, 0.4D, 1.0D);
        unleash(c, id);
        return true;
    }

    private static void unleash(AbilityContext c, String id) {
        ServerLevel sl = c.level;
        ServerPlayer p = c.owner;
        Vec3 aim = c.aimPoint(28.0D);
        switch (id) {
            case "minecraft:creeper" -> {
                c.sound(SoundEvents.CREEPER_PRIMED, 1.0F, 1.0F);
                c.later(12, () -> c.explode(aim, 3.0D));
            }
            case "minecraft:ghast" -> {
                c.sound(SoundEvents.GHAST_SHOOT, 1.0F, 1.0F);
                Vec3 dir = c.look();
                var ball = new net.minecraft.world.entity.projectile.LargeFireball(sl, p, dir, 2);
                Vec3 o = p.getEyePosition().add(dir.scale(1.5D));
                ball.setPos(o.x, o.y, o.z);
                sl.addFreshEntity(ball);
            }
            case "minecraft:warden" -> {
                c.sound(SoundEvents.WARDEN_SONIC_BOOM, 2.0F, 1.0F);
                Vec3 from = p.getEyePosition();
                Vec3 end = from.add(c.look().scale(20.0D));
                for (int k = 1; k <= 20; k++) {
                    Vec3 q = from.add(c.look().scale(k));
                    sl.sendParticles(ParticleTypes.SONIC_BOOM, q.x, q.y, q.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
                }
                for (LivingEntity e : c.beam(from, c.look(), 20.0D, 0.8D)) {
                    c.hurtSonic(e, c.dmg(12.0D));
                    c.push(e, c.look(), 1.2D, 0.4D);
                }
            }
            case "minecraft:blaze" -> {
                c.sound(SoundEvents.BLAZE_SHOOT, 1.2F, 0.8F);
                c.ring(ParticleTypes.FLAME, p.position().add(0.0D, 0.3D, 0.0D), 4.0D, 40);
                for (LivingEntity e : c.enemies(p.position(), 6.0D)) {
                    e.igniteForSeconds(6.0F);
                    c.hurt(e, c.dmg(4.0D));
                    c.push(e, e.position().subtract(p.position()), 0.7D, 0.3D);
                }
            }
            case "minecraft:enderman" -> {
                Vec3 dest = c.blinkDestination(32.0D);
                if (dest != null) {
                    c.sound(SoundEvents.ENDERMAN_TELEPORT, 1.0F, 1.0F);
                    sl.sendParticles(ParticleTypes.PORTAL, p.getX(), p.getY() + 1.0D, p.getZ(), 40, 0.4D, 0.8D, 0.4D, 0.3D);
                    p.teleportTo(dest.x, dest.y, dest.z);
                    p.resetFallDistance();
                    sl.sendParticles(ParticleTypes.PORTAL, dest.x, dest.y + 1.0D, dest.z, 40, 0.4D, 0.8D, 0.4D, 0.3D);
                }
            }
            case "minecraft:skeleton", "minecraft:stray", "minecraft:bogged", "minecraft:pillager" -> {
                c.sound(SoundEvents.ARROW_SHOOT, 1.0F, 0.9F);
                for (int k = 0; k < 6; k++) {
                    final int n = k;
                    c.later(k * 3, () -> {
                        var arrow = new net.minecraft.world.entity.projectile.Arrow(sl, p, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ARROW), null);
                        arrow.pickup = net.minecraft.world.entity.projectile.AbstractArrow.Pickup.DISALLOWED;
                        arrow.shootFromRotation(p, p.getXRot(), p.getYRot() + (n - 2.5F) * 3.0F, 0.0F, 3.0F, 1.0F);
                        arrow.setBaseDamage(c.dmg(3.0D));
                        sl.addFreshEntity(arrow);
                        c.sound(SoundEvents.ARROW_SHOOT, 0.7F, 1.1F + n * 0.05F);
                    });
                }
            }
            case "minecraft:goat" -> {
                c.sound(SoundEvents.GOAT_RAM_IMPACT, 1.2F, 1.0F);
                Vec3 dash = c.flatLook().scale(1.6D);
                p.setDeltaMovement(dash.x, 0.2D, dash.z);
                p.hurtMarked = true;
                c.later(4, () -> {
                    for (LivingEntity e : c.enemies(p.position().add(0.0D, 1.0D, 0.0D), 3.0D)) {
                        c.hurt(e, c.dmg(6.0D));
                        c.push(e, c.flatLook(), 1.8D, 0.7D);
                    }
                });
            }
            case "minecraft:wolf" -> {
                c.sound(SoundEvents.WOLF_GROWL, 1.5F, 0.7F);
                for (LivingEntity e : c.enemies(p.position(), 12.0D)) {
                    e.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 160, 1));
                    if (e instanceof PathfinderMob m) {
                        Vec3 away = e.position().add(e.position().subtract(p.position()).normalize().scale(10.0D));
                        m.getNavigation().moveTo(away.x, away.y, away.z, 1.5D);
                        m.setTarget(null);
                    }
                    sl.sendParticles(ParticleTypes.SMOKE, e.getX(), e.getY() + e.getBbHeight(), e.getZ(), 5, 0.2D, 0.1D, 0.2D, 0.01D);
                }
            }
            case "minecraft:bee" -> {
                c.sound(SoundEvents.BEE_STING, 1.2F, 1.0F);
                for (LivingEntity e : c.cone(8.0D, 0.6D)) {
                    e.addEffect(new MobEffectInstance(MobEffects.POISON, 160, 1));
                    c.hurt(e, c.dmg(2.0D));
                }
            }
            case "minecraft:cow", "minecraft:mooshroom" -> {
                c.sound(SoundEvents.COW_AMBIENT, 1.2F, 1.0F);
                c.sound(SoundEvents.GENERIC_DRINK, 1.0F, 1.0F);
                p.getActiveEffects().stream().filter(e -> !e.getEffect().value().isBeneficial()).map(MobEffectInstance::getEffect)
                        .toList().forEach(p::removeEffect);
                p.setRemainingFireTicks(0);
            }
            case "minecraft:slime" -> {
                // Boing: launched high into the air, landing soft.
                c.sound(SoundEvents.SLIME_JUMP, 1.4F, 0.8F);
                p.setDeltaMovement(p.getDeltaMovement().x, 1.5D, p.getDeltaMovement().z);
                p.hurtMarked = true;
                c.data.spinRocketAt = sl.getGameTime(); // no fall damage until you land
                sl.sendParticles(ParticleTypes.ITEM_SLIME, p.getX(), p.getY() + 0.2D, p.getZ(), 30, 0.5D, 0.1D, 0.5D, 0.2D);
            }
            case "minecraft:magma_cube" -> {
                c.sound(SoundEvents.MAGMA_CUBE_JUMP, 1.4F, 0.8F);
                p.setDeltaMovement(p.getDeltaMovement().x, 1.3D, p.getDeltaMovement().z);
                p.hurtMarked = true;
                c.data.spinRocketAt = sl.getGameTime();
                c.ring(ParticleTypes.FLAME, p.position().add(0.0D, 0.2D, 0.0D), 3.0D, 30);
                for (LivingEntity e : c.enemies(p.position(), 4.0D)) e.igniteForSeconds(4.0F);
            }
            case "minecraft:shulker" -> {
                LivingEntity t = c.aim(28.0D);
                c.sound(SoundEvents.SHULKER_SHOOT, 1.2F, 1.0F);
                if (t != null) {
                    var bullet = new net.minecraft.world.entity.projectile.ShulkerBullet(sl, p, t, net.minecraft.core.Direction.Axis.Y);
                    Vec3 o = p.getEyePosition().add(c.look().scale(1.2D));
                    bullet.setPos(o.x, o.y, o.z);
                    sl.addFreshEntity(bullet);
                }
            }
            case "minecraft:evoker", "minecraft:illusioner" -> {
                c.sound(SoundEvents.EVOKER_CAST_SPELL, 1.2F, 1.0F);
                Vec3 dir = c.flatLook();
                float yaw = (float) Math.atan2(dir.z, dir.x);
                for (int k = 0; k < 14; k++) {
                    Vec3 q = p.position().add(dir.scale(1.5D + k * 1.25D));
                    var fang = new net.minecraft.world.entity.projectile.EvokerFangs(sl, q.x, Math.floor(q.y), q.z, yaw, k * 2, p);
                    sl.addFreshEntity(fang);
                }
            }
            case "minecraft:guardian", "minecraft:elder_guardian" -> {
                LivingEntity t = c.aim(24.0D);
                if (t != null) {
                    boolean elder = id.equals("minecraft:elder_guardian");
                    c.sound(SoundEvents.GUARDIAN_ATTACK, 1.2F, 1.0F);
                    for (int k = 0; k < 6; k++) {
                        final int n = k;
                        c.later(k * 5, () -> {
                            if (t.isAlive()) c.trail(ParticleTypes.BUBBLE, p.getEyePosition(), t.position().add(0.0D, t.getBbHeight() * 0.5D, 0.0D), 0.5D);
                            if (n == 5 && t.isAlive()) {
                                c.hurtSonic(t, c.dmg(elder ? 12.0D : 8.0D));
                                if (elder) t.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN, 600, 2));
                            }
                        });
                    }
                }
            }
            case "minecraft:witch" -> {
                c.sound(SoundEvents.WITCH_THROW, 1.2F, 1.0F);
                var options = java.util.List.of(net.minecraft.world.item.alchemy.Potions.STRONG_POISON, net.minecraft.world.item.alchemy.Potions.STRONG_HARMING,
                        net.minecraft.world.item.alchemy.Potions.STRONG_SLOWNESS, net.minecraft.world.item.alchemy.Potions.LONG_WEAKNESS);
                var potion = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SPLASH_POTION);
                potion.set(net.minecraft.core.component.DataComponents.POTION_CONTENTS,
                        new net.minecraft.world.item.alchemy.PotionContents(options.get(p.getRandom().nextInt(options.size()))));
                var thrown = new net.minecraft.world.entity.projectile.ThrownPotion(sl, p);
                thrown.setItem(potion);
                thrown.shootFromRotation(p, p.getXRot(), p.getYRot(), -15.0F, 0.9F, 1.0F);
                sl.addFreshEntity(thrown);
            }
            case "minecraft:ravager", "minecraft:polar_bear" -> {
                boolean rav = id.equals("minecraft:ravager");
                c.sound(rav ? SoundEvents.RAVAGER_ROAR : SoundEvents.POLAR_BEAR_WARNING, 1.6F, 1.0F);
                c.ring(ParticleTypes.POOF, p.position().add(0.0D, 0.5D, 0.0D), 4.0D, 40);
                for (LivingEntity e : c.enemies(p.position(), rav ? 7.0D : 5.0D)) {
                    c.hurt(e, c.dmg(rav ? 6.0D : 4.0D));
                    c.push(e, e.position().subtract(p.position()), rav ? 1.6D : 1.0D, 0.5D);
                }
            }
            case "minecraft:breeze" -> {
                c.sound(SoundEvents.BREEZE_WIND_CHARGE_BURST.value(), 1.4F, 1.0F);
                Vec3 f = c.flatLook().scale(0.6D);
                p.setDeltaMovement(f.x, 1.6D, f.z);
                p.hurtMarked = true;
                c.data.spinRocketAt = sl.getGameTime();
                sl.sendParticles(ParticleTypes.GUST_EMITTER_SMALL, p.getX(), p.getY(), p.getZ(), 1, 0.0D, 0.0D, 0.0D, 0.0D);
                for (LivingEntity e : c.enemies(p.position(), 4.0D)) c.push(e, e.position().subtract(p.position()), 1.2D, 0.6D);
            }
            case "minecraft:phantom" -> {
                c.sound(SoundEvents.PHANTOM_SWOOP, 1.2F, 1.0F);
                Vec3 f = c.look().scale(1.4D);
                p.setDeltaMovement(f.x, Math.max(0.4D, f.y), f.z);
                p.hurtMarked = true;
                p.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 80, 0));
            }
            case "minecraft:villager", "minecraft:wandering_trader" -> {
                c.sound(SoundEvents.VILLAGER_YES, 1.2F, 1.0F);
                p.addEffect(new MobEffectInstance(MobEffects.HERO_OF_THE_VILLAGE, 2400, 0));
                sl.sendParticles(ParticleTypes.HAPPY_VILLAGER, p.getX(), p.getY() + 1.0D, p.getZ(), 15, 0.4D, 0.6D, 0.4D, 0.0D);
            }
            case "minecraft:cat", "minecraft:ocelot" -> {
                c.sound(SoundEvents.CAT_HISS, 1.5F, 1.0F);
                for (Mob m : sl.getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(16.0D),
                        m -> m instanceof Creeper || m instanceof net.minecraft.world.entity.monster.Phantom)) {
                    if (m instanceof Creeper cr) cr.setSwellDir(-1);
                    m.setTarget(null);
                    Vec3 away = m.position().add(m.position().subtract(p.position()).normalize().scale(12.0D));
                    m.getNavigation().moveTo(away.x, away.y, away.z, 1.4D);
                }
            }
            case "minecraft:allay" -> {
                c.sound(SoundEvents.ALLAY_ITEM_TAKEN, 1.2F, 1.0F);
                for (var item : sl.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, p.getBoundingBox().inflate(16.0D))) {
                    item.teleportTo(p.getX(), p.getY() + 0.5D, p.getZ());
                    sl.sendParticles(ParticleTypes.NOTE, item.getX(), item.getY() + 0.3D, item.getZ(), 1, 0.0D, 0.0D, 0.0D, 0.5D);
                }
            }
            case "minecraft:spider", "minecraft:cave_spider" -> {
                c.sound(SoundEvents.SPIDER_AMBIENT, 1.2F, 0.8F);
                for (LivingEntity e : c.cone(10.0D, 0.7D)) {
                    e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 100, 3));
                    if (id.equals("minecraft:cave_spider")) e.addEffect(new MobEffectInstance(MobEffects.POISON, 120, 1));
                    sl.sendParticles(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, net.minecraft.world.level.block.Blocks.COBWEB.defaultBlockState()),
                            e.getX(), e.getY() + 0.5D, e.getZ(), 20, 0.3D, 0.4D, 0.3D, 0.0D);
                }
            }
            case "minecraft:zombie", "minecraft:husk", "minecraft:zombie_villager" -> {
                c.sound(SoundEvents.ZOMBIE_AMBIENT, 1.4F, 0.7F);
                for (LivingEntity e : c.cone(8.0D, 0.6D)) {
                    e.addEffect(new MobEffectInstance(MobEffects.HUNGER, 300, 2));
                    e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 120, 1));
                    c.hurt(e, c.dmg(3.0D));
                }
            }
            case "minecraft:drowned" -> {
                c.sound(SoundEvents.DROWNED_SHOOT, 1.2F, 1.0F);
                var trident = new net.minecraft.world.entity.projectile.ThrownTrident(sl, p, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TRIDENT));
                trident.shootFromRotation(p, p.getXRot(), p.getYRot(), 0.0F, 2.5F, 1.0F);
                trident.pickup = net.minecraft.world.entity.projectile.AbstractArrow.Pickup.DISALLOWED;
                sl.addFreshEntity(trident);
            }
            case "minecraft:iron_golem" -> {
                LivingEntity t = c.aim(6.0D);
                c.sound(SoundEvents.IRON_GOLEM_ATTACK, 1.5F, 0.8F);
                if (t != null) {
                    c.hurt(t, c.dmg(10.0D));
                    t.setDeltaMovement(t.getDeltaMovement().x, 1.1D, t.getDeltaMovement().z);
                    t.hurtMarked = true;
                }
            }
            case "minecraft:snow_golem" -> {
                c.sound(SoundEvents.SNOW_GOLEM_SHOOT, 1.2F, 1.0F);
                for (int k = 0; k < 8; k++) {
                    var ball = new net.minecraft.world.entity.projectile.Snowball(sl, p);
                    ball.shootFromRotation(p, p.getXRot(), p.getYRot() + (k - 3.5F) * 4.0F, 0.0F, 1.8F, 2.0F);
                    sl.addFreshEntity(ball);
                }
                for (LivingEntity e : c.cone(12.0D, 0.6D)) e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 100, 2));
            }
            case "minecraft:llama", "minecraft:trader_llama" -> {
                LivingEntity t = c.aim(16.0D);
                c.sound(SoundEvents.LLAMA_SPIT, 1.5F, 1.0F);
                if (t != null) {
                    c.trail(ParticleTypes.SPIT, p.getEyePosition(), t.position().add(0.0D, t.getBbHeight() * 0.7D, 0.0D), 0.4D);
                    c.hurt(t, c.dmg(2.0D));
                    t.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 160, 0));
                    t.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 160, 0));
                }
            }
            case "minecraft:dolphin" -> {
                c.sound(SoundEvents.DOLPHIN_PLAY, 1.2F, 1.0F);
                p.addEffect(new MobEffectInstance(MobEffects.DOLPHINS_GRACE, 600, 0));
                p.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING, 600, 0));
            }
            case "minecraft:turtle" -> {
                c.sound(SoundEvents.TURTLE_AMBIENT_LAND, 1.2F, 0.8F);
                p.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 200, 2));
                p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 200, 2));
            }
            case "minecraft:armadillo" -> {
                c.sound(SoundEvents.ARMADILLO_ROLL, 1.2F, 1.0F);
                p.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 120, 2));
            }
            case "minecraft:horse", "minecraft:donkey", "minecraft:mule", "minecraft:skeleton_horse", "minecraft:zombie_horse" -> {
                c.sound(SoundEvents.HORSE_GALLOP, 1.4F, 1.0F);
                p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 300, 2));
                p.addEffect(new MobEffectInstance(MobEffects.JUMP, 300, 1));
            }
            case "minecraft:camel" -> {
                c.sound(SoundEvents.CAMEL_DASH, 1.4F, 1.0F);
                Vec3 f = c.flatLook().scale(1.8D);
                p.setDeltaMovement(f.x, 0.35D, f.z);
                p.hurtMarked = true;
            }
            case "minecraft:chicken" -> {
                c.sound(SoundEvents.CHICKEN_AMBIENT, 1.4F, 1.0F);
                p.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 300, 0));
                sl.sendParticles(new net.minecraft.core.particles.ItemParticleOption(ParticleTypes.ITEM, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.FEATHER)),
                        p.getX(), p.getY() + 1.0D, p.getZ(), 15, 0.4D, 0.5D, 0.4D, 0.05D);
            }
            case "minecraft:frog" -> {
                LivingEntity t = c.aim(14.0D);
                c.sound(SoundEvents.FROG_TONGUE, 1.5F, 1.0F);
                if (t != null) {
                    Vec3 pull = p.position().subtract(t.position());
                    t.setDeltaMovement(pull.scale(0.25D).add(0.0D, 0.3D, 0.0D));
                    t.hurtMarked = true;
                    c.hurt(t, c.dmg(2.0D));
                }
            }
            case "minecraft:hoglin", "minecraft:zoglin" -> {
                LivingEntity t = c.aim(6.0D);
                c.sound(SoundEvents.HOGLIN_ATTACK, 1.4F, 1.0F);
                if (t != null) {
                    c.hurt(t, c.dmg(7.0D));
                    t.setDeltaMovement(c.flatLook().x * 0.5D, 1.0D, c.flatLook().z * 0.5D);
                    t.hurtMarked = true;
                }
            }
            case "minecraft:piglin_brute", "minecraft:vindicator" -> {
                LivingEntity t = c.aim(6.0D);
                c.sound(SoundEvents.PLAYER_ATTACK_STRONG, 1.4F, 0.7F);
                if (t != null) {
                    c.hurt(t, c.dmg(13.0D));
                    sl.sendParticles(ParticleTypes.SWEEP_ATTACK, t.getX(), t.getY() + t.getBbHeight() * 0.5D, t.getZ(), 2, 0.2D, 0.2D, 0.2D, 0.0D);
                }
            }
            case "minecraft:vex" -> {
                c.sound(SoundEvents.VEX_CHARGE, 1.2F, 1.0F);
                p.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 140, 0));
                for (Mob m : sl.getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(20.0D), m -> m.getTarget() == p)) m.setTarget(null);
            }
            case "minecraft:wither_skeleton" -> {
                c.sound(SoundEvents.WITHER_SKELETON_AMBIENT, 1.4F, 0.8F);
                for (LivingEntity e : c.cone(8.0D, 0.6D)) {
                    e.addEffect(new MobEffectInstance(MobEffects.WITHER, 160, 1));
                    c.hurt(e, c.dmg(3.0D));
                }
            }
            case "minecraft:wither" -> {
                c.sound(SoundEvents.WITHER_SHOOT, 1.4F, 1.0F);
                for (int k = 0; k < 3; k++) {
                    final int n = k;
                    c.later(k * 4, () -> {
                        Vec3 dir = c.look();
                        var skull = new net.minecraft.world.entity.projectile.WitherSkull(sl, p, dir);
                        Vec3 o = p.getEyePosition().add(dir.scale(1.5D)).add(0.0D, (n - 1) * 0.4D, 0.0D);
                        skull.setPos(o.x, o.y, o.z);
                        sl.addFreshEntity(skull);
                    });
                }
            }
            case "minecraft:ender_dragon" -> {
                c.sound(SoundEvents.ENDER_DRAGON_SHOOT, 1.6F, 1.0F);
                var cloud = new net.minecraft.world.entity.AreaEffectCloud(sl, aim.x, aim.y, aim.z);
                cloud.setOwner(p);
                cloud.setParticle(ParticleTypes.DRAGON_BREATH);
                cloud.setRadius(3.5F);
                cloud.setDuration(200);
                cloud.setRadiusPerTick((7.0F - cloud.getRadius()) / cloud.getDuration());
                cloud.addEffect(new MobEffectInstance(MobEffects.HARM, 1, 1));
                sl.addFreshEntity(cloud);
            }
            case "minecraft:squid" -> {
                c.sound(SoundEvents.SQUID_SQUIRT, 1.4F, 1.0F);
                for (LivingEntity e : c.cone(10.0D, 0.6D)) e.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 120, 0));
                sl.sendParticles(ParticleTypes.SQUID_INK, p.getX(), p.getEyeY(), p.getZ(), 40, 0.6D, 0.4D, 0.6D, 0.1D);
            }
            case "minecraft:glow_squid", "minecraft:bat" -> {
                // Echoes: everything around you lights up through walls.
                c.sound(id.equals("minecraft:bat") ? SoundEvents.BAT_TAKEOFF : SoundEvents.GLOW_SQUID_SQUIRT, 1.4F, 1.0F);
                for (LivingEntity e : sl.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(32.0D), e -> e != p)) {
                    e.addEffect(new MobEffectInstance(MobEffects.GLOWING, 300, 0));
                }
                if (id.equals("minecraft:bat")) p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 1200, 0));
            }
            case "minecraft:axolotl" -> {
                c.sound(SoundEvents.AXOLOTL_SPLASH, 1.2F, 1.0F);
                p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 120, 1));
            }
            case "minecraft:sniffer" -> {
                c.sound(SoundEvents.SNIFFER_DIGGING, 1.4F, 1.0F);
                var seed = new net.minecraft.world.item.ItemStack(p.getRandom().nextBoolean() ? net.minecraft.world.item.Items.TORCHFLOWER_SEEDS : net.minecraft.world.item.Items.PITCHER_POD);
                if (!p.getInventory().add(seed)) p.drop(seed, false);
            }
            case "minecraft:endermite", "minecraft:silverfish" -> {
                c.sound(SoundEvents.SILVERFISH_AMBIENT, 1.4F, 1.0F);
                for (LivingEntity e : c.enemies(p.position(), 5.0D)) e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 80, 1));
                p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 100, 1));
            }
            case THUNDER -> {
                var bolt = EntityType.LIGHTNING_BOLT.create(sl);
                if (bolt != null) {
                    bolt.moveTo(aim.x, aim.y, aim.z);
                    bolt.setCause(p);
                    sl.addFreshEntity(bolt);
                }
            }
            default -> {
                // Any other sound: a decoy. Everything nearby goes to see what made it.
                sl.playSound(null, aim.x, aim.y, aim.z, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 2.0F, 0.8F);
                sl.sendParticles(ParticleTypes.NOTE, aim.x, aim.y + 0.5D, aim.z, 8, 0.4D, 0.4D, 0.4D, 1.0D);
                for (Mob m : sl.getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(24.0D), m -> m.isAlive() && !(m.getTags().contains(SILENCED)))) {
                    if (m.getTarget() == p) m.setTarget(null);
                    m.getNavigation().moveTo(aim.x, aim.y, aim.z, 1.2D);
                }
            }
        }
    }

    /** Go Beyond: straight through armour. */
    public static boolean goBeyond(AbilityContext c) {
        LivingEntity t = c.aim(30.0D);
        if (t == null) {
            c.owner.displayClientMessage(Component.literal("Look at a target."), true);
            return false;
        }
        Vec3 from = c.owner.getEyePosition();
        Vec3 to = t.position().add(0.0D, t.getBbHeight() * 0.5D, 0.0D);
        c.trail(ParticleTypes.ELECTRIC_SPARK, from, to, 0.3D);
        c.trail(ParticleTypes.WAX_ON, from, to, 0.6D);
        c.sound(SoundEvents.TRIDENT_RIPTIDE_1.value(), 1.0F, 1.8F);
        c.sound(SoundEvents.AMETHYST_BLOCK_CHIME, 1.0F, 1.6F);
        c.later(3, () -> {
            if (!t.isAlive()) return;
            c.hurtSonic(t, c.dmg(14.0D)); // ignores armour
            c.level.sendParticles(ParticleTypes.CRIT, to.x, to.y, to.z, 25, 0.3D, 0.3D, 0.3D, 0.4D);
            c.level.playSound(null, to.x, to.y, to.z, SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1.0F, 1.3F);
        });
        return true;
    }

    // ------------------------------------------------------------------ silence

    private static void silence(LivingEntity e, long now) {
        e.setSilent(true);
        e.addTag(SILENCED);
        SILENCED_UNTIL.put(e, now + SILENCE_TICKS);
    }

    /** Every tick: silenced creepers can't swell; voices come back after a minute. */
    public static void tick(ServerLevel sl) {
        long now = sl.getGameTime();
        Iterator<Map.Entry<LivingEntity, Long>> it = SILENCED_UNTIL.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<LivingEntity, Long> en = it.next();
            LivingEntity e = en.getKey();
            if (e.level() != sl) continue;
            if (e.isRemoved() || now >= en.getValue()) {
                it.remove();
                if (!e.isRemoved()) {
                    e.setSilent(false);
                    e.removeTag(SILENCED);
                }
                continue;
            }
            if (e instanceof Creeper cr) cr.setSwellDir(-1);
        }
        // Notes drifting off a Stand that's holding stolen sounds.
        if (now % 20L == 0L) {
            for (ServerPlayer p : sl.players()) {
                StandData d = StandManager.data(p);
                if (d.getType() != StandType.PIGLIN_REQUIEM || d.stolenSounds.isEmpty()) continue;
                var s = StandManager.getStand(p);
                if (s == null) continue;
                sl.sendParticles(ParticleTypes.NOTE, s.getX(), s.getY() + s.getBbHeight() + 0.3D, s.getZ(), d.stolenSounds.size(), 0.3D, 0.1D, 0.3D, 1.0D);
            }
        }
    }

    /** A silenced mob's projectiles fizzle: true = cancel this one. */
    public static boolean fizzles(Entity e) {
        if (!(e instanceof Projectile pr) || !(pr.getOwner() instanceof LivingEntity o) || !o.getTags().contains(SILENCED)) return false;
        if (e.level() instanceof ServerLevel sl) sl.sendParticles(ParticleTypes.SMOKE, e.getX(), e.getY(), e.getZ(), 5, 0.1D, 0.1D, 0.1D, 0.01D);
        return true;
    }

    public static void clear() {
        SILENCED_UNTIL.clear();
    }

    /** For the HUD: an item that stands for a stolen sound (its spawn egg). */
    public static net.minecraft.world.item.ItemStack icon(String id) {
        if (THUNDER.equals(id)) return new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.LIGHTNING_ROD);
        EntityType<?> t = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(id));
        var egg = net.minecraft.world.item.SpawnEggItem.byId(t);
        return egg == null ? new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.NOTE_BLOCK) : new net.minecraft.world.item.ItemStack(egg);
    }
}
