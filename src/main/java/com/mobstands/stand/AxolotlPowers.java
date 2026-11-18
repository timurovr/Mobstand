package com.mobstands.stand;

import com.mobstands.entity.StandEntity;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Axolotl's bizarre side. It never fights - it bends things:
 *  - Neoteny (see Mandom): undo the last 6 seconds around you.
 *  - Soul Swap: your health and a mob's trade places (as a share of each one's max).
 *  - Bubble Nursery: a mob gets sealed in a floating bubble of water, helpless, slowly drowning, until it pops.
 */
public final class AxolotlPowers {
    private AxolotlPowers() {}

    // ------------------------------------------------------------------ Soul Swap

    /**
     * Your health and the target's trade places - as a share of each one's maximum. Nearly dead and staring at a
     * healthy zombie? Now it's nearly dead and you're fine. But swap with something that's worse off than you are
     * and it backfires.
     */
    public static boolean soulSwap(AbilityContext c) {
        ServerPlayer p = c.owner;
        LivingEntity t = c.aim(12.0D);
        if (t == null || t instanceof net.minecraft.world.entity.boss.wither.WitherBoss
                || t instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon || t instanceof net.minecraft.world.entity.monster.warden.Warden) {
            p.displayClientMessage(Component.literal(t == null ? "Look at a mob." : "Its life is too big to trade."), true);
            return false;
        }
        float mine = p.getHealth() / p.getMaxHealth();
        float theirs = t.getHealth() / t.getMaxHealth();
        p.setHealth(Math.max(1.0F, theirs * p.getMaxHealth()));
        t.setHealth(Math.max(1.0F, mine * t.getMaxHealth()));
        ServerLevel sl = (ServerLevel) p.level();
        // Two pink threads cross between you.
        Vec3 a = p.position().add(0.0D, 1.0D, 0.0D), b = t.position().add(0.0D, t.getBbHeight() * 0.6D, 0.0D);
        int n = (int) Math.min(40, a.distanceTo(b) * 3) + 1;
        for (int i = 0; i <= n; i++) {
            double k = i / (double) n;
            Vec3 q = a.add(b.subtract(a).scale(k));
            double wave = Math.sin(k * Math.PI) * 0.6D;
            sl.sendParticles(ParticleTypes.CHERRY_LEAVES, q.x, q.y + wave, q.z, 1, 0.02D, 0.02D, 0.02D, 0.0D);
            sl.sendParticles(ParticleTypes.HEART, q.x, q.y - wave, q.z, i % 4 == 0 ? 1 : 0, 0.0D, 0.0D, 0.0D, 0.0D);
        }
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.AXOLOTL_ATTACK, SoundSource.PLAYERS, 1.0F, 0.6F);
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 1.2F, 0.8F);
        sl.playSound(null, t.getX(), t.getY(), t.getZ(), SoundEvents.SOUL_ESCAPE.value(), SoundSource.PLAYERS, 1.2F, 1.0F);
        p.displayClientMessage(Component.literal(theirs >= mine ? "You took its life... and it took yours." : "You traded down...")
                .withStyle(st -> st.withColor(0xF7A1C4)), true);
        return true;
    }

    // ------------------------------------------------------------------ Bubble Nursery

    public static final int BUBBLE_TICKS = 120;

    private static final class Bubble {
        LivingEntity e;
        UUID owner;
        Vec3 anchor;
        long until;
        boolean hadNoAi;
    }

    private static final List<Bubble> BUBBLES = new ArrayList<>();

    public static boolean isBubbled(LivingEntity e) {
        for (Bubble b : BUBBLES) if (b.e == e) return true;
        return false;
    }

    public static boolean bubble(AbilityContext c) {
        LivingEntity t = c.aim(16.0D);
        if (t == null) {
            c.owner.displayClientMessage(Component.literal("Look at a mob."), true);
            return false;
        }
        if (isBubbled(t)) return false;
        Bubble b = new Bubble();
        b.e = t;
        b.owner = c.owner.getUUID();
        b.anchor = t.position().add(0.0D, 1.6D, 0.0D);
        b.until = c.level.getGameTime() + BUBBLE_TICKS;
        if (t instanceof Mob m) {
            b.hadNoAi = m.isNoAi();
            m.setNoAi(true);
            m.setTarget(null);
        }
        BUBBLES.add(b);
        c.sound(SoundEvents.BUBBLE_COLUMN_UPWARDS_INSIDE, 1.2F, 0.8F);
        c.sound(SoundEvents.AXOLOTL_ATTACK, 1.0F, 1.2F);
        return true;
    }

    // ------------------------------------------------------------------ ticking

    public static void tick(ServerLevel sl) {
        long now = sl.getGameTime();
        // Bubbles: float, struggle, slowly drown, pop.
        Iterator<Bubble> bi = BUBBLES.iterator();
        while (bi.hasNext()) {
            Bubble b = bi.next();
            if (b.e.level() != sl) continue;
            if (!b.e.isAlive() || now >= b.until) {
                bi.remove();
                pop(sl, b);
                continue;
            }
            double bob = Math.sin(now * 0.12D) * 0.08D;
            b.e.setPos(b.anchor.x, b.anchor.y + bob, b.anchor.z);
            b.e.setDeltaMovement(Vec3.ZERO);
            b.e.hurtMarked = true;
            b.e.resetFallDistance();
            double r = Math.max(b.e.getBbWidth(), b.e.getBbHeight()) * 0.7D + 0.3D;
            Vec3 c = b.e.position().add(0.0D, b.e.getBbHeight() * 0.5D, 0.0D);
            for (int i = 0; i < 6; i++) {
                double u = sl.random.nextDouble() * Math.PI * 2.0D, v = Math.acos(2.0D * sl.random.nextDouble() - 1.0D);
                sl.sendParticles(ParticleTypes.BUBBLE_POP, c.x + r * Math.sin(v) * Math.cos(u), c.y + r * Math.cos(v), c.z + r * Math.sin(v) * Math.sin(u),
                        1, 0.0D, 0.0D, 0.0D, 0.0D);
            }
            if (now % 4L == 0L) sl.sendParticles(ParticleTypes.SPLASH, c.x, c.y, c.z, 3, r * 0.5D, r * 0.5D, r * 0.5D, 0.0D);
            if ((b.until - now) % 20L == 0L) {
                b.e.hurt(sl.damageSources().drown(), 1.0F);
                sl.playSound(null, c.x, c.y, c.z, SoundEvents.BUBBLE_COLUMN_BUBBLE_POP, SoundSource.PLAYERS, 0.8F, 1.0F);
            }
        }
    }

    private static void pop(ServerLevel sl, Bubble b) {
        if (b.e instanceof Mob m && !b.hadNoAi) m.setNoAi(false);
        Vec3 c = b.e.position().add(0.0D, b.e.getBbHeight() * 0.5D, 0.0D);
        sl.sendParticles(ParticleTypes.SPLASH, c.x, c.y, c.z, 30, 0.5D, 0.5D, 0.5D, 0.1D);
        sl.sendParticles(ParticleTypes.BUBBLE_POP, c.x, c.y, c.z, 20, 0.5D, 0.5D, 0.5D, 0.05D);
        sl.playSound(null, c.x, c.y, c.z, SoundEvents.BUBBLE_COLUMN_BUBBLE_POP, SoundSource.PLAYERS, 1.4F, 0.6F);
        sl.playSound(null, c.x, c.y, c.z, SoundEvents.GENERIC_SPLASH, SoundSource.PLAYERS, 0.8F, 1.2F);
    }

    public static void clear() {
        for (Bubble b : BUBBLES) if (b.e instanceof Mob m && !b.hadNoAi) m.setNoAi(false);
        BUBBLES.clear();
    }
}
