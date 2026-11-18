package com.mobstands.stand;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** Shared damage / targeting rules so Stands never hurt their owner, pets or villagers. */
public final class StandCombat {
    private StandCombat() {}

    public static boolean valid(ServerPlayer owner, Entity e) {
        if (!(e instanceof LivingEntity le)) return false;
        if (e == owner || !le.isAlive() || le.isSpectator() || le.isInvulnerable()) return false;
        if (e instanceof ArmorStand || e instanceof AbstractVillager) return false;
        if (e instanceof OwnableEntity oe && owner.getUUID().equals(oe.getOwnerUUID())) return false;
        if (e instanceof Player p && (!owner.canHarmPlayer(p) || p.isCreative())) return false;
        return true;
    }

    /** Damage is attributed to the owner, so kills give loot, XP and credit. */
    public static boolean hurt(ServerPlayer owner, LivingEntity target, float amount) {
        DamageSource src = owner.level().damageSources().playerAttack(owner);
        return target.hurt(src, amount);
    }

    public static boolean hurtSonic(ServerPlayer owner, LivingEntity target, float amount) {
        DamageSource src = owner.level().damageSources().sonicBoom(owner);
        return target.hurt(src, amount);
    }

    /** Global knockback scale for every Stand hit (lower = targets fly less far). */
    private static final double KNOCKBACK_SCALE = 0.5D;
    private static final double LIFT_SCALE = 0.7D;

    public static void push(LivingEntity target, Vec3 dir, double horizontal, double up) {
        horizontal *= KNOCKBACK_SCALE;
        up *= LIFT_SCALE;
        Vec3 flat = new Vec3(dir.x, 0.0D, dir.z);
        if (flat.lengthSqr() > 1.0E-4D) flat = flat.normalize();
        double resist = 1.0D - target.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE);
        if (resist <= 0.0D) return;
        target.setDeltaMovement(target.getDeltaMovement().add(flat.scale(horizontal * resist)).add(0.0D, up * resist, 0.0D));
        target.hurtMarked = true;
    }
}
