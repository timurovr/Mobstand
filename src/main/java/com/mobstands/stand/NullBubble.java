package com.mobstands.stand;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Pigstep's Null Bubble (its Go Beyond): a bubble made of something that doesn't exist. It drifts after the creature you
 * looked at - slowly - straight through walls, shields and armor, and nothing can turn it aside. When it reaches them it
 * pops inside them: damage no armor or Resistance can soften. Not even calamity can follow it back to you.
 */
public final class NullBubble {
    private NullBubble() {}

    private static final DustParticleOptions SPECK = new DustParticleOptions(new Vector3f(1.0F, 0.95F, 0.75F), 0.45F);
    public static final double SPEED = 0.32D;
    public static final int LIFE = 300;

    private static final class Bubble {
        final ServerLevel level;
        final UUID owner, target;
        Vec3 pos;
        final float damage;
        int age;

        Bubble(ServerLevel level, UUID owner, UUID target, Vec3 pos, float damage) {
            this.level = level;
            this.owner = owner;
            this.target = target;
            this.pos = pos;
            this.damage = damage;
        }
    }

    private static final List<Bubble> BUBBLES = new ArrayList<>();
    /** True while a Null Bubble's pop is being dealt (calamity can't see it). */
    private static boolean popping;

    public static boolean popping() {
        return popping;
    }

    public static boolean use(AbilityContext c) {
        LivingEntity t = c.aim(48.0D);
        if (t == null) return false;
        Vec3 from = c.stand.position().add(0.0D, c.stand.getBbHeight() * 0.7D, 0.0D);
        BUBBLES.add(new Bubble(c.level, c.owner.getUUID(), t.getUUID(), from, c.dmg(12.0D)));
        c.sound(SoundEvents.BUBBLE_COLUMN_BUBBLE_POP, 0.6F, 0.5F);
        c.sound(SoundEvents.AMETHYST_BLOCK_RESONATE, 0.5F, 0.6F);
        return true;
    }

    public static void tick(ServerLevel sl) {
        if (BUBBLES.isEmpty()) return;
        Iterator<Bubble> it = BUBBLES.iterator();
        while (it.hasNext()) {
            Bubble b = it.next();
            if (b.level != sl) continue;
            Entity raw = sl.getEntity(b.target);
            if (++b.age > LIFE || !(raw instanceof LivingEntity t) || !t.isAlive()) {
                it.remove();
                continue;
            }
            Vec3 aim = t.position().add(0.0D, t.getBbHeight() * 0.55D, 0.0D);
            Vec3 to = aim.subtract(b.pos);
            double dist = to.length();
            if (dist <= SPEED + 0.3D || t.getBoundingBox().inflate(0.1D).contains(b.pos)) {
                it.remove();
                pop(sl, b, t);
                continue;
            }
            // Through everything - blocks don't exist to it.
            b.pos = b.pos.add(to.scale(SPEED / dist));
            sl.sendParticles(SPECK, b.pos.x, b.pos.y, b.pos.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
            if (b.age % 6 == 0) sl.sendParticles(ParticleTypes.END_ROD, b.pos.x, b.pos.y, b.pos.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }

    private static void pop(ServerLevel sl, Bubble b, LivingEntity t) {
        ServerPlayer owner = sl.getServer().getPlayerList().getPlayer(b.owner);
        float dmg = b.damage;
        // Resistance can't soften it either.
        MobEffectInstance res = t.getEffect(MobEffects.DAMAGE_RESISTANCE);
        if (res != null) dmg /= Math.max(0.2F, 1.0F - 0.2F * (res.getAmplifier() + 1));
        if (t instanceof Player pl && pl.isBlocking()) pl.stopUsingItem(); // a shield doesn't stop what isn't there
        if (owner != null) StandCombat.credit(owner, t);
        t.invulnerableTime = 0;
        popping = true;
        try {
            t.hurt(owner != null ? sl.damageSources().indirectMagic(owner, owner) : sl.damageSources().magic(), dmg);
        } finally {
            popping = false;
        }
        Vec3 at = t.position().add(0.0D, t.getBbHeight() * 0.55D, 0.0D);
        sl.sendParticles(ParticleTypes.FLASH, at.x, at.y, at.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        sl.sendParticles(SPECK, at.x, at.y, at.z, 20, 0.3D, 0.3D, 0.3D, 0.0D);
        sl.playSound(null, at.x, at.y, at.z, SoundEvents.BUBBLE_COLUMN_BUBBLE_POP, SoundSource.PLAYERS, 1.5F, 0.4F);
        sl.playSound(null, at.x, at.y, at.z, SoundEvents.AMETHYST_CLUSTER_BREAK, SoundSource.PLAYERS, 1.2F, 0.6F);
    }

    public static void clear() {
        BUBBLES.clear();
    }
}
