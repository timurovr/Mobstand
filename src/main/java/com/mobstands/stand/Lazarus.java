package com.mobstands.stand;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lazarus Smile's Lazarus: an axolotl never really gives up on the dead. Players who died nearby and are still on the
 * death screen get up again where they fell; pets and peaceful creatures that died nearby come back, whole.
 * Monsters stay dead - it only brings back what it would want back.
 */
public final class Lazarus {
    private Lazarus() {}

    /** How long the dead can still be called back (ticks), and how far from you. */
    public static final long WINDOW = 20L * 60L;
    public static final double RADIUS = 16.0D;

    private record Grave(long time, ResourceKey<Level> dim, Vec3 pos, CompoundTag mob) {}
    /** The recently dead: players (mob = null) and creatures (mob = everything about them). */
    private static final Map<UUID, Grave> GRAVES = new HashMap<>();

    public static void onDeath(LivingEntity e) {
        if (e.level().isClientSide) return;
        long now = e.level().getGameTime();
        if (GRAVES.size() > 32) GRAVES.values().removeIf(g -> now - g.time > WINDOW);
        if (e instanceof Player) {
            GRAVES.put(e.getUUID(), new Grave(now, e.level().dimension(), e.position(), null));
            return;
        }
        if (e instanceof net.minecraft.world.entity.monster.Enemy || Calamity.boss(e)) return;
        boolean axolotlNear = false;
        for (Player p : e.level().players()) {
            if (p instanceof ServerPlayer sp && StandManager.data(sp).getType() == StandType.AXOLOTL
                    && sp.distanceToSqr(e) < (RADIUS + 32.0D) * (RADIUS + 32.0D)) {
                axolotlNear = true;
                break;
            }
        }
        if (!axolotlNear) return; // animal farms everywhere else cost nothing
        if (!e.getType().canSerialize()) return;
        CompoundTag tag = e.saveWithoutId(new CompoundTag());
        tag.putString("id", EntityType.getKey(e.getType()).toString());
        tag.remove("DeathTime");
        tag.remove("HurtTime");
        tag.putFloat("Health", e.getMaxHealth());
        tag.putShort("Fire", (short) -1);
        tag.putFloat("FallDistance", 0.0F);
        GRAVES.put(e.getUUID(), new Grave(now, e.level().dimension(), e.position(), tag));
        if (GRAVES.size() > 512) GRAVES.clear();
    }

    public static boolean use(AbilityContext c) {
        ServerPlayer user = c.owner;
        ServerLevel sl = c.level;
        long now = sl.getGameTime();
        List<Runnable> raise = new ArrayList<>();
        Iterator<Map.Entry<UUID, Grave>> it = GRAVES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Grave> en = it.next();
            Grave g = en.getValue();
            if (now - g.time > WINDOW) {
                it.remove();
                continue;
            }
            if (g.dim != sl.dimension() || g.pos.distanceToSqr(user.position()) > RADIUS * RADIUS) continue;
            UUID id = en.getKey();
            if (g.mob == null) {
                ServerPlayer dead = sl.getServer().getPlayerList().getPlayer(id);
                if (dead == null || dead == user || !dead.isDeadOrDying() || dead.hasDisconnected()) continue; // already respawned
                it.remove();
                raise.add(() -> {
                    ServerPlayer back = sl.getServer().getPlayerList().respawn(dead, true, Entity.RemovalReason.KILLED);
                    if (back.level() != sl) back.teleportTo(sl, g.pos.x, g.pos.y, g.pos.z, back.getYRot(), back.getXRot());
                    else back.connection.teleport(g.pos.x, g.pos.y, g.pos.z, back.getYRot(), back.getXRot());
                    back.setHealth(back.getMaxHealth() * 0.5F);
                    back.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 1));
                    rise(sl, g.pos, back.getBbHeight());
                });
            } else {
                if (sl.getEntity(id) != null) { // it's somehow still here
                    it.remove();
                    continue;
                }
                it.remove();
                raise.add(() -> {
                    Entity back = EntityType.loadEntityRecursive(g.mob.copy(), sl, x -> x);
                    if (back == null) return;
                    long since = sl.getGameTime() - g.time + 2L;
                    for (net.minecraft.world.entity.item.ItemEntity drop : sl.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                            new net.minecraft.world.phys.AABB(g.pos, g.pos).inflate(3.0D), i -> i.tickCount <= since)) drop.discard();
                    for (net.minecraft.world.entity.ExperienceOrb orb : sl.getEntitiesOfClass(net.minecraft.world.entity.ExperienceOrb.class,
                            new net.minecraft.world.phys.AABB(g.pos, g.pos).inflate(3.0D), o -> o.tickCount <= since)) orb.discard();
                    back.moveTo(g.pos.x, g.pos.y, g.pos.z, back.getYRot(), back.getXRot());
                    sl.addFreshEntity(back);
                    if (back instanceof LivingEntity le) le.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 1));
                    rise(sl, g.pos, back.getBbHeight());
                });
            }
        }
        if (raise.isEmpty()) {
            c.fail = "No one here to bring back.";
            return false;
        }
        // The axolotl smiles... and one by one, they get up.
        sl.playSound(null, user.getX(), user.getY(), user.getZ(), com.mobstands.registry.ModSounds.LAZARUS.get(), SoundSource.PLAYERS, 2.0F, 1.0F);
        c.sound(SoundEvents.AXOLOTL_SPLASH, 1.2F, 0.7F);
        c.sound(SoundEvents.BEACON_ACTIVATE, 1.0F, 1.5F);
        for (int i = 0; i < raise.size(); i++) c.later(10 + i * 6, raise.get(i));
        return true;
    }

    private static void rise(ServerLevel sl, Vec3 at, double h) {
        sl.sendParticles(ParticleTypes.HEART, at.x, at.y + h + 0.3D, at.z, 6, 0.4D, 0.3D, 0.4D, 0.0D);
        sl.sendParticles(ParticleTypes.CHERRY_LEAVES, at.x, at.y + h * 0.5D, at.z, 30, 0.4D, h * 0.4D, 0.4D, 0.0D);
        sl.sendParticles(ParticleTypes.BUBBLE_POP, at.x, at.y + 0.2D, at.z, 20, 0.5D, 0.1D, 0.5D, 0.05D);
        sl.playSound(null, at.x, at.y, at.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.5F, 0.6F);
        sl.playSound(null, at.x, at.y, at.z, SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 0.5F, 1.4F);
    }

    public static void clear() {
        GRAVES.clear();
    }
}
