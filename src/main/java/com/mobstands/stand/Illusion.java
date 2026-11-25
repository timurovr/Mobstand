package com.mobstands.stand;

import com.mobstands.entity.StandEntity;
import net.minecraft.core.particles.ParticleTypes;
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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.SpectralArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Smoke and Mirrors (the Illusioner): a Stand that never fights fair. It shoots glowing arrows, splits you into
 * mirror images that soak up hits, blinds whoever you point at, makes you vanish, and turns a crowd against itself.
 */
public final class Illusion {
    private Illusion() {}

    public static final int MIRROR_TICKS = 20 * 12, VANISH_TICKS = 20 * 10, MAZE_TICKS = 20 * 8;
    /** Chance a hit lands on one of your mirror images instead of you. */
    public static final float MIRROR_DODGE = 0.6F;

    private record Images(int left, long until) {}

    private static final Map<UUID, Images> MIRRORS = new HashMap<>();
    private static final Map<UUID, Long> VANISHED = new HashMap<>();
    private static final Map<Mob, Long> CONFUSED = new HashMap<>();
    private static final Map<UUID, Long> LAST_RESORT = new HashMap<>();

    // ------------------------------------------------------------------ Phantom Arrow (basic)

    public static boolean arrow(AbilityContext c) {
        ServerPlayer p = c.owner;
        ServerLevel sl = c.level;
        LivingEntity t = c.aim(48.0D);
        Vec3 from = c.stand.position().add(0.0D, 1.4D, 0.0D);
        Vec3 aim = t != null ? t.position().add(0.0D, t.getBbHeight() * 0.6D, 0.0D) : p.getEyePosition().add(c.look().scale(40.0D));
        Vec3 dir = aim.subtract(from).normalize();
        SpectralArrow a = new SpectralArrow(sl, p, new ItemStack(Items.SPECTRAL_ARROW), null);
        a.setPos(from.x, from.y, from.z);
        a.shoot(dir.x, dir.y, dir.z, 3.2F, 0.5F);
        a.setBaseDamage(c.dmg(2.2D));
        a.setCritArrow(true);
        a.pickup = AbstractArrow.Pickup.DISALLOWED;
        sl.addFreshEntity(a);
        c.stand.playAnim(0);
        c.sound(SoundEvents.ARROW_SHOOT, 1.0F, 1.3F);
        c.sound(SoundEvents.ILLUSIONER_AMBIENT, 0.5F, 1.4F);
        return true;
    }

    // ------------------------------------------------------------------ Mirror Images

    public static boolean mirror(AbilityContext c) {
        ServerPlayer p = c.owner;
        MIRRORS.put(p.getUUID(), new Images(4, c.level.getGameTime() + MIRROR_TICKS));
        if (c.stand != null) c.stand.setMirror(4);
        c.level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ILLUSIONER_MIRROR_MOVE, SoundSource.PLAYERS, 1.4F, 1.0F);
        c.level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ILLUSIONER_CAST_SPELL, SoundSource.PLAYERS, 1.0F, 1.2F);
        c.level.sendParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + 1.0D, p.getZ(), 30, 1.0D, 0.6D, 1.0D, 0.02D);
        return true;
    }

    /** A hit coming at someone with mirror images up: often it hits an image instead. True = cancel it. */
    public static boolean intercept(ServerPlayer p, DamageSource src) {
        if (MIRRORS.isEmpty()) return false;
        Images im = MIRRORS.get(p.getUUID());
        if (im == null || im.left() <= 0) return false;
        if (src.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY) || src.getEntity() == null) return false;
        ServerLevel sl = p.serverLevel();
        if (sl.random.nextFloat() >= MIRROR_DODGE) return false;
        int left = im.left() - 1;
        if (left <= 0) MIRRORS.remove(p.getUUID());
        else MIRRORS.put(p.getUUID(), new Images(left, im.until()));
        StandEntity s = StandManager.getStand(p);
        if (s != null) s.setMirror(Math.max(0, left));
        // that one was an illusion: it bursts
        double a = sl.random.nextDouble() * Math.PI * 2.0D;
        Vec3 at = p.position().add(Math.cos(a) * 1.3D, 1.0D, Math.sin(a) * 1.3D);
        sl.sendParticles(ParticleTypes.CLOUD, at.x, at.y, at.z, 20, 0.25D, 0.5D, 0.25D, 0.03D);
        sl.sendParticles(ParticleTypes.WITCH, at.x, at.y, at.z, 10, 0.25D, 0.5D, 0.25D, 0.05D);
        sl.playSound(null, at.x, at.y, at.z, SoundEvents.ILLUSIONER_MIRROR_MOVE, SoundSource.PLAYERS, 1.0F, 1.5F);
        return true;
    }

    // ------------------------------------------------------------------ Blinding Spell

    public static boolean blind(AbilityContext c) {
        LivingEntity t = c.aim(24.0D);
        if (t == null) {
            c.fail = "Look at someone to blind.";
            return false;
        }
        ServerLevel sl = c.level;
        c.stand.playAnim(0);
        sl.playSound(null, c.owner.getX(), c.owner.getY(), c.owner.getZ(), SoundEvents.ILLUSIONER_PREPARE_BLINDNESS, SoundSource.PLAYERS, 1.2F, 1.0F);
        c.later(10, () -> {
            if (!t.isAlive()) return;
            t.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 20 * 7, 0), c.owner);
            t.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 20 * 7, 0), c.owner);
            t.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 20 * 4, 1), c.owner);
            if (t instanceof Player) t.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 20 * 6, 0), c.owner);
            // a blinded creature loses track of you
            if (t instanceof Mob m) m.setTarget(null);
            sl.sendParticles(ParticleTypes.SQUID_INK, t.getX(), t.getY() + t.getBbHeight() * 0.8D, t.getZ(), 30, 0.3D, 0.3D, 0.3D, 0.05D);
            sl.sendParticles(ParticleTypes.EFFECT, t.getX(), t.getY() + t.getBbHeight() * 0.8D, t.getZ(), 20, 0.3D, 0.3D, 0.3D, 0.1D);
            sl.playSound(null, t.getX(), t.getY(), t.getZ(), SoundEvents.ILLUSIONER_CAST_SPELL, SoundSource.PLAYERS, 1.0F, 0.8F);
        });
        return true;
    }

    // ------------------------------------------------------------------ Vanish

    public static boolean vanished(Entity e) {
        return !VANISHED.isEmpty() && e instanceof ServerPlayer && VANISHED.containsKey(e.getUUID());
    }

    public static boolean vanish(AbilityContext c) {
        ServerPlayer p = c.owner;
        ServerLevel sl = c.level;
        VANISHED.put(p.getUUID(), sl.getGameTime() + VANISH_TICKS);
        p.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, VANISH_TICKS, 0, false, false));
        if (c.stand != null) c.stand.setVanish(true);
        for (Mob m : sl.getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(32.0D), x -> x.getTarget() == p)) m.setTarget(null);
        sl.sendParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + 1.0D, p.getZ(), 30, 0.4D, 0.8D, 0.4D, 0.04D);
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ILLUSIONER_PREPARE_MIRROR, SoundSource.PLAYERS, 1.2F, 1.0F);
        p.displayClientMessage(Component.literal("Vanished. Attacking brings you back.").withStyle(s -> s.withColor(0x5B8DD9)), true);
        return true;
    }

    public static void unvanish(ServerPlayer p) {
        if (VANISHED.remove(p.getUUID()) == null) return;
        p.removeEffect(MobEffects.INVISIBILITY);
        StandEntity s = StandManager.getStand(p);
        if (s != null) s.setVanish(false);
        p.serverLevel().sendParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + 1.0D, p.getZ(), 20, 0.4D, 0.8D, 0.4D, 0.03D);
    }

    // ------------------------------------------------------------------ Mirror Maze

    /** Every enemy around you is lost in a maze of illusions: they see you everywhere and lash out at each other. */
    public static boolean maze(AbilityContext c) {
        ServerPlayer p = c.owner;
        ServerLevel sl = c.level;
        List<Mob> mobs = sl.getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(14.0D), m -> m.isAlive());
        long until = sl.getGameTime() + MAZE_TICKS;
        for (Mob m : mobs) {
            CONFUSED.put(m, until);
            m.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, MAZE_TICKS, 0), p);
            sl.sendParticles(ParticleTypes.WITCH, m.getX(), m.getY() + m.getBbHeight(), m.getZ(), 10, 0.3D, 0.3D, 0.3D, 0.05D);
        }
        for (ServerPlayer o : sl.getEntitiesOfClass(ServerPlayer.class, p.getBoundingBox().inflate(14.0D), x -> x != p && StandCombat.valid(p, x))) {
            o.addEffect(new MobEffectInstance(MobEffects.CONFUSION, MAZE_TICKS, 0), p);
            o.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 40, 0), p);
        }
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ILLUSIONER_CAST_SPELL, SoundSource.PLAYERS, 1.6F, 0.6F);
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ILLUSIONER_MIRROR_MOVE, SoundSource.PLAYERS, 1.4F, 0.7F);
        // a ring of mirror-flashes rolling out
        for (int k = 0; k < 10; k++) {
            final double r = 1.5D + k * 1.3D;
            c.later(k, () -> {
                int n = (int) (r * 4);
                for (int i = 0; i < n; i++) {
                    double a = i * Math.PI * 2.0D / n;
                    sl.sendParticles(ParticleTypes.END_ROD, p.getX() + Math.cos(a) * r, p.getY() + 1.0D, p.getZ() + Math.sin(a) * r, 1, 0.0D, 0.2D, 0.0D, 0.0D);
                }
            });
        }
        return true;
    }

    // ------------------------------------------------------------------ every tick

    public static void tick(MinecraftServer server) {
        long now = server.overworld().getGameTime();
        if (!MIRRORS.isEmpty()) {
            Iterator<Map.Entry<UUID, Images>> it = MIRRORS.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, Images> en = it.next();
                ServerPlayer p = server.getPlayerList().getPlayer(en.getKey());
                if (p == null || !p.isAlive() || p.serverLevel().getGameTime() > en.getValue().until() || !active(p)) {
                    it.remove();
                    if (p != null) {
                        StandEntity s = StandManager.getStand(p);
                        if (s != null) s.setMirror(0);
                    }
                }
            }
        }
        if (!VANISHED.isEmpty()) {
            List<ServerPlayer> back = new ArrayList<>();
            for (Map.Entry<UUID, Long> en : VANISHED.entrySet()) {
                ServerPlayer p = server.getPlayerList().getPlayer(en.getKey());
                if (p == null) continue;
                if (!p.isAlive() || p.serverLevel().getGameTime() > en.getValue() || !active(p)) back.add(p);
            }
            for (ServerPlayer p : back) unvanish(p);
            VANISHED.keySet().removeIf(u -> server.getPlayerList().getPlayer(u) == null);
        }
        if (!CONFUSED.isEmpty() && server.getTickCount() % 20 == 0) {
            Iterator<Map.Entry<Mob, Long>> it = CONFUSED.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Mob, Long> en = it.next();
                Mob m = en.getKey();
                if (!m.isAlive() || m.level().getGameTime() > en.getValue()) {
                    it.remove();
                    continue;
                }
                // it sees you in the face of the nearest creature - and goes for it
                List<Mob> near = m.level().getEntitiesOfClass(Mob.class, m.getBoundingBox().inflate(10.0D), x -> x != m && x.isAlive());
                if (!near.isEmpty()) m.setTarget(near.get(m.getRandom().nextInt(near.size())));
                else m.setTarget(null);
            }
        }
    }

    private static boolean active(ServerPlayer p) {
        StandData d = StandManager.data(p);
        return d.getType() == StandType.ILLUSIONER && d.isSummoned();
    }

    /** Last Illusion (passive, level 5): about to die - you vanish for a moment and everything after you loses you. */
    public static void lastResort(ServerPlayer p) {
        StandData d = StandManager.data(p);
        if (d.getType() != StandType.ILLUSIONER || !d.isSummoned() || d.getLevel() < 5) return;
        long now = p.serverLevel().getGameTime();
        Long last = LAST_RESORT.get(p.getUUID());
        if (last != null && now - last < 20 * 60) return;
        if (p.getHealth() > p.getMaxHealth() * 0.3F) return;
        LAST_RESORT.put(p.getUUID(), now);
        p.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 60, 0, false, false));
        ServerLevel sl = p.serverLevel();
        for (Mob m : sl.getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(32.0D), x -> x.getTarget() == p)) m.setTarget(null);
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ILLUSIONER_CAST_SPELL, SoundSource.PLAYERS, 1.2F, 1.0F);
        sl.sendParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + 1.0D, p.getZ(), 30, 0.5D, 0.8D, 0.5D, 0.05D);
    }

    public static void clear(ServerPlayer p) {
        MIRRORS.remove(p.getUUID());
        VANISHED.remove(p.getUUID());
    }

    public static void shutdown() {
        MIRRORS.clear();
        VANISHED.clear();
        CONFUSED.clear();
        LAST_RESORT.clear();
    }
}
