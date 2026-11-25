package com.mobstands.stand;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** Shared damage / targeting rules so Stands never hurt their owner or pets. */
public final class StandCombat {
    private StandCombat() {}

    public static boolean valid(ServerPlayer owner, Entity e) {
        if (!(e instanceof LivingEntity le)) return false;
        if (e == owner || !le.isAlive() || le.isSpectator() || le.isInvulnerable()) return false;
        if (e instanceof ArmorStand) return false; // (villagers and traders are fair game now)
        if (ScaryMonsters.inHerdOf(owner, e)) return false; // your own dinosaurs
        if (e instanceof OwnableEntity oe && owner.getUUID().equals(oe.getOwnerUUID())) return false;
        if (e instanceof Player p && (!owner.canHarmPlayer(p) || p.isCreative())) return false;
        return true;
    }

    /** Damage is attributed to the owner, so kills give loot, XP and credit. */
    /** Who last hurt each mob with a Stand ability, and when - so kills by rain, bleed, fire or falls still count. */
    private static final java.util.Map<java.util.UUID, Object[]> CREDIT = new java.util.HashMap<>();

    public static void credit(ServerPlayer owner, LivingEntity target) {
        CREDIT.put(target.getUUID(), new Object[]{owner.getUUID(), owner.level().getGameTime()});
        if (CREDIT.size() > 512) CREDIT.clear();
    }

    /** The player whose Stand hurt this mob in the last 10 seconds (null if nobody). */
    public static ServerPlayer creditFor(LivingEntity victim) {
        Object[] c = CREDIT.remove(victim.getUUID());
        if (c == null || victim.level().getGameTime() - (Long) c[1] > 200L || victim.getServer() == null) return null;
        return victim.getServer().getPlayerList().getPlayer((java.util.UUID) c[0]);
    }

    public static boolean hurt(ServerPlayer owner, LivingEntity target, float amount) {
        credit(owner, target);
        DamageSource src = owner.level().damageSources().playerAttack(owner);
        standHits++;
        try {
            return target.hurt(src, amount);
        } finally {
            standHits--;
        }
    }

    /** > 0 while a Stand's own hit is landing (so it isn't mistaken for a plain sword swing). */
    private static int standHits;

    /** No single Stand hit takes more than this share of a player's health: nothing one-taps a player. */
    public static final float PVP_CAP = 0.35F;

    /**
     * A player is about to be hit by someone else's Stand: the big finishers (Primer, Iron Shot, Big Bang, time-stop
     * damage landing all at once...) are capped so they can't kill a player in one go. Plain sword swings and arrows
     * from a normal bow are left alone - that's just PvP.
     */
    public static float pvpCap(LivingEntity victim, DamageSource src, float amount) {
        if (!(victim instanceof Player) || amount <= 0.0F) return amount;
        if (src.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)) return amount;
        Entity att = src.getEntity(), direct = src.getDirectEntity();
        if (att == victim) return amount;
        boolean standUser = att instanceof ServerPlayer sp && StandManager.data(sp).getType() != null;
        boolean fromStand = att instanceof com.mobstands.entity.StandEntity || direct instanceof com.mobstands.entity.StandEntity
                || direct instanceof com.mobstands.entity.StandProjectile;
        if (!standUser && !fromStand) return amount;
        if (!fromStand && standHits == 0) {
            // their own sword or fist
            if (direct == att && src.is(net.minecraft.world.damagesource.DamageTypes.PLAYER_ATTACK)) return amount;
            // an arrow or trident they shot themselves
            if (direct instanceof net.minecraft.world.entity.projectile.Projectile) return amount;
        }
        return Math.min(amount, victim.getMaxHealth() * PVP_CAP);
    }

    public static boolean hurtSonic(ServerPlayer owner, LivingEntity target, float amount) {
        credit(owner, target);
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
