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
 * pops inside them: nothing softens it - armor, Resistance, Protection, absorption - and it always leaves them at
 * exactly half a heart. Never a kill: it gives them a chance. Then they knit back together fast - up to half their health
 * in 2 seconds - unless they're hit again first. That's the window to reach them and finish it.
 * Not even calamity can follow it back to you.
 */
public final class NullBubble {
    private NullBubble() {}

    private static final DustParticleOptions SPECK = new DustParticleOptions(new Vector3f(1.0F, 0.95F, 0.75F), 0.45F);
    /** Slow (about 3 blocks a second) - but it never stops coming. */
    public static final double SPEED = 0.15D;
    /** How sharply it turns toward its target each tick (0..1): it curves after them rather than snapping. */
    public static final double TURN = 0.12D;
    public static final int LIFE = 20 * 40;

    private static final class Bubble {
        final ServerLevel level;
        final UUID owner, target;
        Vec3 pos;
        final float damage;
        int age;
        /** Where it's drifting: it bends toward the target a little every tick (homing). */
        Vec3 vel = Vec3.ZERO;
        /** What everyone sees: the golden bubble with its nugget. */
        com.mobstands.entity.NullBubbleEntity body;

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
        Bubble b = new Bubble(c.level, c.owner.getUUID(), t.getUUID(), from, c.dmg(12.0D));
        b.vel = c.look().scale(SPEED); // it sets off the way you're looking, then curves after them
        b.body = com.mobstands.registry.ModEntities.NULL_BUBBLE.get().create(c.level);
        if (b.body != null) {
            b.body.moveTo(from.x, from.y - 0.11D, from.z, 0.0F, 0.0F);
            c.level.addFreshEntity(b.body);
        }
        BUBBLES.add(b);
        c.sound(SoundEvents.BUBBLE_COLUMN_BUBBLE_POP, 0.6F, 0.5F);
        c.sound(SoundEvents.AMETHYST_BLOCK_RESONATE, 0.5F, 0.6F);
        return true;
    }

    /** What a Null Bubble leaves them on: half a heart. */
    public static final float HALF_HEART = 1.0F;
    /** How long they take to knit back up to half their health (ticks): a 2 second window. */
    public static final int REGEN_TICKS = 40;

    private static final class Regen {
        final ServerLevel level;
        float last;
        int age;

        Regen(ServerLevel level, float last) {
            this.level = level;
            this.last = last;
        }
    }

    private static final java.util.Map<UUID, Regen> REGEN = new java.util.HashMap<>();

    /** Popped creatures healing back up - until they're hit again, or they're back to half health. */
    private static void regenTick(ServerLevel sl) {
        if (REGEN.isEmpty()) return;
        java.util.Iterator<java.util.Map.Entry<UUID, Regen>> it = REGEN.entrySet().iterator();
        while (it.hasNext()) {
            var en = it.next();
            Regen r = en.getValue();
            if (r.level != sl) continue;
            Entity e = sl.getEntity(en.getKey());
            if (!(e instanceof LivingEntity t) || !t.isAlive() || ++r.age > REGEN_TICKS) {
                it.remove();
                continue;
            }
            if (t.getHealth() < r.last - 0.01F) { // hit again: the window closes - no more healing
                it.remove();
                continue;
            }
            float goal = t.getMaxHealth() * 0.5F;
            if (t.getHealth() >= goal) {
                it.remove();
                continue;
            }
            t.heal((goal - HALF_HEART) / REGEN_TICKS);
            r.last = t.getHealth();
            if (r.age % 6 == 0) sl.sendParticles(SPECK, t.getX(), t.getY() + t.getBbHeight() * 0.6D, t.getZ(), 3, 0.3D, 0.4D, 0.3D, 0.0D);
        }
    }

    public static void tick(ServerLevel sl) {
        regenTick(sl);
        if (BUBBLES.isEmpty()) return;
        Iterator<Bubble> it = BUBBLES.iterator();
        while (it.hasNext()) {
            Bubble b = it.next();
            if (b.level != sl) continue;
            Entity raw = sl.getEntity(b.target);
            if (++b.age > LIFE || !(raw instanceof LivingEntity t) || !t.isAlive()) {
                it.remove();
                if (b.body != null) {
                    sl.sendParticles(ParticleTypes.BUBBLE_POP, b.pos.x, b.pos.y, b.pos.z, 8, 0.2D, 0.2D, 0.2D, 0.02D);
                    b.body.discard();
                }
                continue;
            }
            Vec3 aim = t.position().add(0.0D, t.getBbHeight() * 0.55D, 0.0D);
            Vec3 to = aim.subtract(b.pos);
            double dist = to.length();
            if (dist <= SPEED + 0.3D || t.getBoundingBox().inflate(0.1D).contains(b.pos)) {
                it.remove();
                if (b.body != null) b.body.discard();
                pop(sl, b, t);
                continue;
            }
            // Homing: it bends toward them a little each tick, at a slow, steady drift - through everything,
            // blocks don't exist to it.
            Vec3 want = to.scale(SPEED / dist);
            b.vel = b.vel.scale(1.0D - TURN).add(want.scale(TURN));
            double sp = b.vel.length();
            b.vel = sp < 1.0E-4D ? want : b.vel.scale(SPEED / sp);
            b.pos = b.pos.add(b.vel);
            if (b.body != null) b.body.setPos(b.pos.x, b.pos.y - 0.11D, b.pos.z); // (its middle is at pos)
            if (b.age % 3 == 0) sl.sendParticles(SPECK, b.pos.x, b.pos.y, b.pos.z, 1, 0.15D, 0.15D, 0.15D, 0.0D); // a faint golden trail
        }
    }

    private static void pop(ServerLevel sl, Bubble b, LivingEntity t) {
        ServerPlayer owner = sl.getServer().getPlayerList().getPlayer(b.owner);
        if (t instanceof Player pl && pl.isBlocking()) pl.stopUsingItem(); // a shield doesn't stop what isn't there
        if (owner != null) StandCombat.credit(owner, t);
        if (t.getHealth() > HALF_HEART) {
            // The hit itself (so it flinches and counts as yours)...
            t.invulnerableTime = 0;
            popping = true;
            try {
                t.hurt(owner != null ? sl.damageSources().indirectMagic(owner, owner) : sl.damageSources().magic(),
                        Math.min(1.0F, t.getHealth() - HALF_HEART));
            } finally {
                popping = false;
            }
            // ...then exactly half a heart left, whatever stood in the way.
            if (t.isAlive()) {
                t.setAbsorptionAmount(0.0F);
                t.setHealth(HALF_HEART);
            }
        }
        // And then it knits back together, fast - unless it's hit again first.
        if (t.isAlive()) REGEN.put(t.getUUID(), new Regen(sl, HALF_HEART));
        Vec3 at = t.position().add(0.0D, t.getBbHeight() * 0.55D, 0.0D);
        sl.sendParticles(ParticleTypes.FLASH, at.x, at.y, at.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        sl.sendParticles(SPECK, at.x, at.y, at.z, 20, 0.3D, 0.3D, 0.3D, 0.0D);
        sl.playSound(null, at.x, at.y, at.z, SoundEvents.BUBBLE_COLUMN_BUBBLE_POP, SoundSource.PLAYERS, 1.5F, 0.4F);
        sl.playSound(null, at.x, at.y, at.z, SoundEvents.AMETHYST_CLUSTER_BREAK, SoundSource.PLAYERS, 1.2F, 0.6F);
    }

    /** Is this bubble body still flying? */
    public static boolean owns(Entity e) {
        for (Bubble b : BUBBLES) if (b.body == e) return true;
        return false;
    }

    public static void clear() {
        for (Bubble b : BUBBLES) if (b.body != null) b.body.discard();
        BUBBLES.clear();
        REGEN.clear();
    }
}
