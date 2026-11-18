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
