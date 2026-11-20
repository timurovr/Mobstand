package com.mobstands.stand;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Pigstep: Plunder - Soft &amp; Wet style. Your Stand snatches the friction and the sight right out of the
 * mob you're looking at. For a while it can't see (it loses track of everyone and wanders blind) and it can't grip
 * the ground (it skates around like it's on ice, barely able to steer or stop).
 */
public final class Plunder {
    private Plunder() {}

    public static final int TICKS = 160; // 8 seconds
    private static final ResourceLocation SLOW = ResourceLocation.fromNamespaceAndPath("mobstands", "plundered_grip");

    private static final class Victim {
        final UUID id;
        final long until;
        Vec3 glide = Vec3.ZERO;

        Victim(UUID id, long until) {
            this.id = id;
            this.until = until;
        }
    }

    private static final Map<UUID, Victim> VICTIMS = new HashMap<>();

    public static boolean use(AbilityContext c) {
        LivingEntity t = c.aim(20.0D);
        if (t == null) {
            c.owner.displayClientMessage(net.minecraft.network.chat.Component.literal("Look at a mob to plunder it.")
                    .withStyle(s -> s.withColor(0xB07CFF)), true);
            return false;
        }
        ServerLevel sl = c.level;
        long now = sl.getGameTime();
        c.stand.playAnim(0);
        // The theft: a streak of gold from the mob back to your Stand, a chime, and its eyes and footing are gone.
        c.trail(ParticleTypes.WAX_ON, t.getEyePosition(), c.stand.position().add(0.0D, 1.0D, 0.0D), 0.4D);
        sl.playSound(null, t.getX(), t.getY(), t.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.5F, 0.6F);
        sl.playSound(null, t.getX(), t.getY(), t.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 1.0F, 0.5F);
        Victim v = new Victim(t.getUUID(), now + TICKS);
        // A shove on the way out, so it starts sliding.
        Vec3 push = t.position().subtract(c.owner.position()).multiply(1.0D, 0.0D, 1.0D);
        v.glide = push.lengthSqr() < 1.0E-4D ? Vec3.ZERO : push.normalize().scale(0.35D);
        VICTIMS.put(t.getUUID(), v);
        var speed = t.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            speed.removeModifier(SLOW);
            speed.addTransientModifier(new AttributeModifier(SLOW, -0.7D, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
        if (t instanceof Mob m) {
            m.setTarget(null);
            m.getNavigation().stop();
        }
        return true;
    }

    /** Blind: can't lock onto anyone. */
    public static boolean blind(Entity e) {
        Victim v = VICTIMS.get(e.getUUID());
        return v != null && e.level().getGameTime() < v.until;
    }

    public static void tick(ServerLevel sl) {
        if (VICTIMS.isEmpty()) return;
        if (!sl.tickRateManager().runsNormally()) return;
        long now = sl.getGameTime();
        Iterator<Victim> it = VICTIMS.values().iterator();
        while (it.hasNext()) {
            Victim v = it.next();
            Entity e = sl.getEntity(v.id);
            if (e == null) continue; // another dimension
            if (!(e instanceof LivingEntity le) || !le.isAlive() || now >= v.until) {
                if (e instanceof LivingEntity le2) {
                    var speed = le2.getAttribute(Attributes.MOVEMENT_SPEED);
                    if (speed != null) speed.removeModifier(SLOW);
                }
                it.remove();
                continue;
            }
            // No sight: blind, and it forgets who it was after.
            le.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 30, 0, false, false));
            if (e instanceof net.minecraft.world.entity.player.Player) le.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 30, 0, false, false));
            if (e instanceof Mob m) {
                m.setTarget(null);
                if (now % 20L == 0L) m.getNavigation().stop();
            }
            // No friction: it keeps whatever speed it had and only barely changes it - skating, sliding past.
            Vec3 cur = le.getDeltaMovement();
            Vec3 flat = new Vec3(cur.x, 0.0D, cur.z);
            v.glide = v.glide.scale(0.97D).add(flat.subtract(v.glide).scale(0.08D));
            if (le.onGround()) {
                le.setDeltaMovement(v.glide.x, cur.y, v.glide.z);
                le.hurtMarked = true;
            }
            if (now % 5L == 0L) {
                sl.sendParticles(ParticleTypes.WAX_ON, le.getX(), le.getEyeY() + 0.2D, le.getZ(), 1, 0.2D, 0.1D, 0.2D, 0.0D);
                if (le.onGround() && v.glide.lengthSqr() > 0.002D) sl.sendParticles(ParticleTypes.SNOWFLAKE, le.getX(), le.getY() + 0.05D, le.getZ(), 1, 0.2D, 0.0D, 0.2D, 0.0D);
            }
        }
    }

    public static void clear() {
        VICTIMS.clear();
    }
}
