package com.mobstands.stand;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

/** Passive perks that apply while the Stand is out and the required level is reached. */
public final class StandPassives {
    private StandPassives() {}

    public static boolean active(StandData d, StandType t) {
        return d.getType() == t && d.isSummoned() && d.getLevel() >= t.passive().level();
    }

    public static boolean active(ServerPlayer p, StandType t) {
        return active(StandManager.data(p), t);
    }

    /** Effect-based passives, refreshed once a second. Event-based ones (Creeper, Slime) live in StandEvents. */
    public static void tick(ServerPlayer p, StandData d) {
        StandType t = d.getType();
        if (t == null || !active(d, t)) return;
        switch (t) {
            case ENDERMAN, ENDER_WORLD -> eff(p, MobEffects.MOVEMENT_SPEED, 0, 80);
            case IRON_GOLEM -> eff(p, MobEffects.DAMAGE_RESISTANCE, 0, 80);
            case GER -> eff(p, MobEffects.DAMAGE_RESISTANCE, 1, 80);
            case BLAZE -> eff(p, MobEffects.FIRE_RESISTANCE, 0, 80);
            case WARDEN -> eff(p, MobEffects.NIGHT_VISION, 0, 260);
            case SKELETON -> eff(p, MobEffects.DIG_SPEED, 0, 80);
            case SPIDER -> eff(p, MobEffects.JUMP, 0, 80);
            case SQUID -> eff(p, MobEffects.WATER_BREATHING, 0, 80);
            default -> { }
        }
    }

    private static void eff(ServerPlayer p, Holder<MobEffect> effect, int amp, int duration) {
        p.addEffect(new MobEffectInstance(effect, duration, amp, true, false, true));
    }
}
