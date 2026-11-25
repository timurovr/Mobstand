package com.mobstands.stand;

import com.mobstands.entity.StandEntity;
import com.mobstands.network.GooPayload;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Green Temperance (the Slime): Yellow Temperance, in slime green. Its body is a flesh-eating green goo.
 * <ul>
 * <li>Goo on a creature eats it alive - a little at a time, slowing it - and spreads to anything that touches it.</li>
 * <li>Temperance Armor: worn over your body, it soaks up most of every hit, and whatever touches you gets eaten.</li>
 * <li>Masquerade: it takes the shape of any creature you look at. Everyone sees that creature instead of you, and
 * monsters leave you alone - until you hit something.</li>
 * </ul>
 */
public final class Goo {
    private Goo() {}

    // (named for Yellow Temperance - but this goo is slime green)
    static final DustParticleOptions YELLOW = new DustParticleOptions(new Vector3f(0.45F, 0.95F, 0.35F), 1.3F);
    static final DustParticleOptions AMBER = new DustParticleOptions(new Vector3f(0.2F, 0.6F, 0.15F), 1.0F);

    /** How long a Masquerade lasts. */
    public static final int DISGUISE_TICKS = 20 * 60;

    private record Eaten(UUID owner, long until, float dps, boolean spreads) {}

    private static final Map<LivingEntity, Eaten> EATEN = new HashMap<>();
    private static final Set<UUID> ARMOR = new HashSet<>();
    private record Disguise(int typeId, long until) {}
    private static final Map<UUID, Disguise> DISGUISED = new HashMap<>();

    // ------------------------------------------------------------------ being eaten

    /** Cover a creature in goo: it's eaten {@code dps} health a second for {@code ticks}, slowed, and (if {@code spreads}) it passes on to whatever it touches. */
    public static void engulf(LivingEntity e, ServerPlayer owner, int ticks, float dps, boolean spreads) {
        if (e == owner || !e.isAlive()) return;
        ServerLevel sl = (ServerLevel) e.level();
        Eaten old = EATEN.get(e);
        long until = sl.getGameTime() + ticks;
        if (old != null && old.until() > until) until = old.until();
        EATEN.put(e, new Eaten(owner.getUUID(), until, Math.max(dps, old != null ? old.dps() : 0.0F), spreads || (old != null && old.spreads())));
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(e, new GooPayload(e.getId(), (int) (until - sl.getGameTime())));
        sl.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.HONEY_BLOCK_SLIDE, SoundSource.PLAYERS, 1.2F, 0.6F);
        sl.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.SLIME_SQUISH, SoundSource.PLAYERS, 1.0F, 0.7F);
        sl.sendParticles(YELLOW, e.getX(), e.getY() + e.getBbHeight() * 0.5D, e.getZ(), 30, e.getBbWidth() * 0.5D, e.getBbHeight() * 0.4D, e.getBbWidth() * 0.5D, 0.0D);
    }

    public static boolean eaten(Entity e) {
        return !EATEN.isEmpty() && e instanceof LivingEntity le && EATEN.containsKey(le);
    }

    public static void tick(MinecraftServer server) {
        if (!EATEN.isEmpty()) tickEaten(server);
        if (!DISGUISED.isEmpty()) tickDisguises(server);
        if (!ARMOR.isEmpty() && server.getTickCount() % 10 == 0) tickArmor(server);
    }

    private static void tickEaten(MinecraftServer server) {
        Map<LivingEntity, Eaten> spreadTo = new HashMap<>();
        Iterator<Map.Entry<LivingEntity, Eaten>> it = EATEN.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<LivingEntity, Eaten> en = it.next();
            LivingEntity e = en.getKey();
            Eaten g = en.getValue();
            ServerLevel sl = (ServerLevel) e.level();
            long now = sl.getGameTime();
            if (!e.isAlive() || e.isRemoved() || now > g.until()) {
                it.remove();
                if (!e.isRemoved()) PacketDistributor.sendToPlayersTrackingEntityAndSelf(e, new GooPayload(e.getId(), 0));
                continue;
            }
            ServerPlayer owner = server.getPlayerList().getPlayer(g.owner());
            // it eats: a bite every half second
            if (now % 10 == 0) {
                e.invulnerableTime = 0;
                if (owner != null) StandCombat.hurt(owner, e, g.dps() * 0.5F);
                else e.hurt(sl.damageSources().magic(), g.dps() * 0.5F);
                sl.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.GENERIC_EAT, SoundSource.PLAYERS, 0.6F, 0.6F + sl.random.nextFloat() * 0.3F);
            }
            e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 15, 2, false, false));
            if (now % 3 == 0) {
                sl.sendParticles(YELLOW, e.getX(), e.getY() + e.getBbHeight() * 0.5D, e.getZ(), 3, e.getBbWidth() * 0.45D, e.getBbHeight() * 0.4D, e.getBbWidth() * 0.45D, 0.0D);
                sl.sendParticles(ParticleTypes.ITEM_SLIME, e.getX(), e.getY() + e.getBbHeight() * 0.6D, e.getZ(), 1, e.getBbWidth() * 0.4D, e.getBbHeight() * 0.3D, e.getBbWidth() * 0.4D, 0.0D);
            }
            // whatever touches it gets it too
            if (g.spreads() && now % 5 == 0) {
                for (LivingEntity o : sl.getEntitiesOfClass(LivingEntity.class, e.getBoundingBox().inflate(0.4D),
                        x -> x != e && x.isAlive() && !EATEN.containsKey(x) && !x.getUUID().equals(g.owner()))) {
                    spreadTo.put(o, new Eaten(g.owner(), now + 60, g.dps(), false));
                }
            }
        }
        for (Map.Entry<LivingEntity, Eaten> en : spreadTo.entrySet()) {
            ServerPlayer owner = server.getPlayerList().getPlayer(en.getValue().owner());
            if (owner != null) engulf(en.getKey(), owner, 60, en.getValue().dps(), false);
        }
    }

    // ------------------------------------------------------------------ Temperance Armor

    public static boolean armored(Entity e) {
        return !ARMOR.isEmpty() && e instanceof ServerPlayer && ARMOR.contains(e.getUUID());
    }

    public static boolean toggleArmor(AbilityContext c) {
        ServerPlayer p = c.owner;
        boolean on = !ARMOR.contains(p.getUUID());
        if (on) ARMOR.add(p.getUUID());
        else ARMOR.remove(p.getUUID());
        if (c.stand != null) c.stand.setGoo(on);
        c.level.playSound(null, p.getX(), p.getY(), p.getZ(), on ? SoundEvents.HONEY_BLOCK_PLACE : SoundEvents.HONEY_BLOCK_BREAK, SoundSource.PLAYERS, 1.2F, 0.6F);
        c.level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.SLIME_SQUISH, SoundSource.PLAYERS, 1.0F, on ? 0.6F : 0.9F);
        c.level.sendParticles(YELLOW, p.getX(), p.getY() + 1.0D, p.getZ(), 40, 0.35D, 0.6D, 0.35D, 0.0D);
        p.displayClientMessage(Component.literal(on ? "Temperance Armor: on. Go ahead - hit me." : "Temperance Armor: off.")
                .withStyle(s -> s.withColor(0x7EFF6E)), true);
        return true;
    }

    /** A hit on someone wearing it: most of it is soaked up, and a hand that touched it gets eaten. Returns the damage that gets through. */
    public static float armorHit(ServerPlayer p, DamageSource src, float amount) {
        ServerLevel sl = p.serverLevel();
        boolean melee = src.getDirectEntity() instanceof LivingEntity && src.getDirectEntity() == src.getEntity();
        if (melee && src.getEntity() instanceof LivingEntity attacker && attacker != p) engulf(attacker, p, 80, 2.0F, true);
        sl.sendParticles(YELLOW, p.getX(), p.getY() + 1.0D, p.getZ(), 10, 0.3D, 0.5D, 0.3D, 0.0D);
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.HONEY_BLOCK_HIT, SoundSource.PLAYERS, 1.0F, 0.7F);
        float soak = src.is(net.minecraft.tags.DamageTypeTags.IS_FIRE) || src.is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION) ? 0.5F : 0.35F;
        return amount * soak;
    }

    private static void tickArmor(MinecraftServer server) {
        Iterator<UUID> it = ARMOR.iterator();
        while (it.hasNext()) {
            ServerPlayer p = server.getPlayerList().getPlayer(it.next());
            StandData d = p == null ? null : StandManager.data(p);
            if (p == null || d.getType() != StandType.SLIME || !d.isSummoned() || !p.isAlive()) {
                it.remove();
                continue;
            }
            StandEntity s = StandManager.getStand(p);
            if (s != null && !s.isGoo()) s.setGoo(true);
            // it's always hungry
            if (p.tickCount % 80 == 0) p.causeFoodExhaustion(1.0F);
        }
    }

    // ------------------------------------------------------------------ Masquerade

    public static boolean disguised(Entity e) {
        return !DISGUISED.isEmpty() && e instanceof ServerPlayer && DISGUISED.containsKey(e.getUUID());
    }

    /** Take the shape of the creature you're looking at (press again to drop it). */
    public static boolean disguise(AbilityContext c) {
        ServerPlayer p = c.owner;
        if (DISGUISED.containsKey(p.getUUID())) {
            undisguise(p, true);
            return true;
        }
        LivingEntity t = c.aim(24.0D);
        if (t == null || t instanceof Player) {
            c.fail = "Look at a creature to take its shape.";
            return false;
        }
        EntityType<?> type = t.getType();
        int id = BuiltInRegistries.ENTITY_TYPE.getId(type);
        DISGUISED.put(p.getUUID(), new Disguise(id, c.level.getGameTime() + DISGUISE_TICKS));
        if (c.stand != null) c.stand.setDisguise(id);
        ServerLevel sl = c.level;
        sl.sendParticles(YELLOW, p.getX(), p.getY() + 1.0D, p.getZ(), 60, 0.4D, 0.8D, 0.4D, 0.0D);
        sl.sendParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + 1.0D, p.getZ(), 15, 0.4D, 0.6D, 0.4D, 0.02D);
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ILLUSIONER_MIRROR_MOVE, SoundSource.PLAYERS, 1.2F, 0.8F);
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.SLIME_SQUISH, SoundSource.PLAYERS, 1.0F, 0.5F);
        // monsters that were after you look straight past you now
        for (Mob m : sl.getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(48.0D), x -> x.getTarget() == p)) m.setTarget(null);
        p.displayClientMessage(Component.literal("Masquerade: you look like a " + type.getDescription().getString() + ". Hitting anything breaks it.")
                .withStyle(s -> s.withColor(0x7EFF6E)), true);
        return true;
    }

    public static void undisguise(ServerPlayer p, boolean loud) {
        if (DISGUISED.remove(p.getUUID()) == null) return;
        StandEntity s = StandManager.getStand(p);
        if (s != null) s.setDisguise(-1);
        if (loud) {
            ServerLevel sl = p.serverLevel();
            sl.sendParticles(YELLOW, p.getX(), p.getY() + 1.0D, p.getZ(), 40, 0.4D, 0.8D, 0.4D, 0.0D);
            sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.HONEY_BLOCK_BREAK, SoundSource.PLAYERS, 1.2F, 0.6F);
        }
    }

    private static void tickDisguises(MinecraftServer server) {
        Iterator<Map.Entry<UUID, Disguise>> it = DISGUISED.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Disguise> en = it.next();
            ServerPlayer p = server.getPlayerList().getPlayer(en.getKey());
            StandData d = p == null ? null : StandManager.data(p);
            if (p == null || !p.isAlive() || d.getType() != StandType.SLIME || !d.isSummoned()
                    || p.serverLevel().getGameTime() > en.getValue().until()) {
                it.remove();
                if (p != null) {
                    StandEntity s = StandManager.getStand(p);
                    if (s != null) s.setDisguise(-1);
                }
                continue;
            }
            StandEntity s = StandManager.getStand(p);
            if (s != null && s.disguise() != en.getValue().typeId()) s.setDisguise(en.getValue().typeId());
        }
    }

    // ------------------------------------------------------------------ abilities

    /** Devour Touch: the goo bites and clings - it keeps eating for a few seconds. */
    public static boolean touch(AbilityContext c) {
        LivingEntity t = c.meleeTarget(5.0D);
        if (t == null) {
            c.fail = "Nothing to touch.";
            return false;
        }
        c.stand.lunge(t.position().add(0.0D, t.getBbHeight() * 0.5D, 0.0D), 5);
        c.stand.playAnim(0);
        c.later(4, () -> {
            if (!t.isAlive()) return;
            t.invulnerableTime = 0;
            c.hurt(t, c.dmg(3.0D));
            engulf(t, c.owner, 50, (float) c.dmg(1.5D), false);
        });
        return true;
    }

    /** Devour: the goo leaps off you onto the creature you're looking at and swallows it whole. */
    public static boolean devour(AbilityContext c) {
        LivingEntity t = c.aim(14.0D);
        if (t == null) {
            c.fail = "Look at something to devour.";
            return false;
        }
        ServerLevel sl = c.level;
        Vec3 from = c.owner.getEyePosition().add(0.0D, -0.4D, 0.0D);
        Vec3 to = t.position().add(0.0D, t.getBbHeight() * 0.5D, 0.0D);
        // a glob flying across
        for (int k = 0; k < 6; k++) {
            final int kk = k;
            c.later(k, () -> {
                Vec3 at = from.add(to.subtract(from).scale((kk + 1) / 6.0D));
                sl.sendParticles(YELLOW, at.x, at.y, at.z, 14, 0.18D, 0.18D, 0.18D, 0.0D);
                sl.sendParticles(AMBER, at.x, at.y, at.z, 6, 0.12D, 0.12D, 0.12D, 0.0D);
            });
        }
        sl.playSound(null, c.owner.getX(), c.owner.getY(), c.owner.getZ(), SoundEvents.SLIME_JUMP, SoundSource.PLAYERS, 1.2F, 0.5F);
        c.later(6, () -> {
            if (!t.isAlive()) return;
            engulf(t, c.owner, 120, (float) c.dmg(4.0D), true);
            t.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 120, 4, false, false));
            t.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 120, 1, false, false));
        });
        return true;
    }


    public static void clear(ServerPlayer p) {
        ARMOR.remove(p.getUUID());
        DISGUISED.remove(p.getUUID());
    }

    public static void shutdown() {
        EATEN.clear();
        ARMOR.clear();
        DISGUISED.clear();
    }
}
