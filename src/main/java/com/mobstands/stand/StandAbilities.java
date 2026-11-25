package com.mobstands.stand;

import com.mobstands.entity.StandProjectile;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.Consumer;

/**
 * All 36 abilities (4 per Stand). Slot 0 = basic attack (right click), 1 = Z, 2 = X, 3 = C (ultimate).
 * Unlock levels differ per Stand, so every Stand has its own progression path.
 */
public final class StandAbilities {
    private StandAbilities() {}

    private static Ability ab(String name, String desc, int unlock, double cooldownSeconds, AbilityAction action) {
        return new Ability(name, desc, unlock, (int) Math.round(cooldownSeconds * 20.0D), action);
    }

    private static ParticleOptions stoneDust() {
        return new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE.defaultBlockState());
    }

    // ================================================================== shared helpers

    /** Stand lunges at the target and hits it a few ticks later (so the punch visually lands). */
    private static boolean melee(AbilityContext c, double dmg, double knockback, double up, Consumer<LivingEntity> onHit) {
        LivingEntity target = c.meleeTarget(6.0D);
        Vec3 reach = target != null
                ? target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D)
                : c.owner.getEyePosition().add(c.look().scale(3.0D));
        c.stand.lunge(reach, 5);
        mobRushSound(c);
        startPunch(c, PUNCH_HIT_TICK, dmg * 1.05D, knockback, up);
        c.later(PUNCH_HIT_TICK, () -> landPunch(c, target, dmg * 1.05D, knockback, up, onHit));
        return true;
    }

    /**
     * Summon-rush combo (any melee Stand): summon and hit straight away and the Stand charges up while the
     * charge sound plays, dashes in during its pause, and throws punch 1. Hit again (before or just after punch 1)
     * and it follows up with punch 2. Timings are in ticks after the first click and match the sound.
     */
    public static final int COMBO_DASH = 4;
    public static final int COMBO_HIT_1 = 8;
    public static final int COMBO_HIT_2 = 10;
    /** Animation tail after the last punch. */
    public static final int COMBO_RECOVER = 9;

    public static void comboOpen(AbilityContext c) {
        LivingEntity target = c.meleeTarget(6.0D);
        Vec3 look = c.flatLook();
        startPunch(c, COMBO_HIT_1, 7.0D * 0.8D, 0.6D, 0.2D);
        c.later(COMBO_DASH, () -> {
            Vec3 aim = target != null && target.isAlive()
                    ? target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D).subtract(look.scale(1.3D))
                    : c.owner.position().add(look.scale(1.6D)).add(0.0D, 1.0D, 0.0D);
            c.stand.lunge(aim, COMBO_HIT_1 - COMBO_DASH + DOUBLE_GAP + 6);
            mobRushSound(c);
        });
        c.later(COMBO_HIT_1, () -> landPunch(c, null, 7.0D * 0.8D, 0.6D, 0.2D, null));
    }

    /**
     * Double-click special, timed to the full combo sound: the Stand charges up while the charge part plays,
     * dashes in on the pause, then lands right and left hits on the two punches in the audio.
     */
    public static final int CHARGED_DASH = 17;
    public static final int CHARGED_HIT_1 = 21;
    public static final int CHARGED_HIT_2 = 23;

    public static void chargedCombo(AbilityContext c) {
        c.sound(com.mobstands.registry.ModSounds.COMBO_FULL.get(), 1.0F, 1.0F);
        LivingEntity target = c.meleeTarget(6.0D);
        Vec3 look = c.flatLook();
        double dmg = c.data.punchDmg, knock = c.data.punchKnock, up = c.data.punchUp;
        c.later(CHARGED_DASH, () -> {
            Vec3 aim = target != null && target.isAlive()
                    ? target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D).subtract(look.scale(1.3D))
                    : c.owner.position().add(look.scale(1.6D)).add(0.0D, 1.0D, 0.0D);
            c.stand.lunge(aim, CHARGED_HIT_2 - CHARGED_DASH + 6);
            mobRushSound(c);
        });
        c.later(CHARGED_HIT_1, () -> punchHit(c, target, dmg * 1.2D, knock * 0.3D, up * 0.2D, null));
        c.later(CHARGED_HIT_2, () -> punchHit(c, target, dmg * 1.5D, knock * 1.6D + 0.5D, up + 0.2D, null));
    }

    /** Ticks between the right and the left hit of a double (matches the two punches in the clip). */
    public static final int DOUBLE_GAP = 2;

    /** Registers a punch that lands in {@code hitAt} ticks; a second click before then makes it a double. */
    private static void startPunch(AbilityContext c, int hitAt, double dmg, double knock, double up) {
        c.data.punchStart = c.level.getGameTime();
        c.data.punchHitAt = hitAt;
        c.data.punchDouble = false;
        c.data.followUpUntil = 0L;
        c.data.punchDmg = dmg;
        c.data.punchKnock = knock;
        c.data.punchUp = up;
        c.stand.setComboHit2(-1);
        c.stand.setPunchSide(0);
    }

    /** How long after a single punch lands a second click still throws the left follow-up (ticks). */
    public static final int FOLLOW_UP_WINDOW = 16;

    /**
     * Second click after the first punch already landed: the left fist comes out right away (no cooldown),
     * with the second-punch sound, and hits harder.
     */
    public static void followUp(AbilityContext c) {
        c.data.followUpUntil = 0L;
        LivingEntity target = c.meleeTarget(6.0D);
        Vec3 reach = target != null
                ? target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D)
                : c.owner.getEyePosition().add(c.look().scale(3.0D));
        c.stand.lunge(reach, 4);
        mobRushSound(c);
        c.stand.setComboHit2(-1);
        c.stand.setPunchSide(1);
        c.stand.playAnimFrom(0, PUNCH_HIT_TICK - 1); // left fist reaches full extension next tick
        double dmg = c.data.punchDmg, knock = c.data.punchKnock, up = c.data.punchUp;
        c.later(1, () -> {
            c.sound(com.mobstands.registry.ModSounds.COMBO_PUNCH_2.get(), 1.0F, 1.0F);
            punchHit(c, target, dmg * 1.1D, knock * 1.4D + 0.4D, up + 0.15D, null);
        });
    }

    /**
     * Lands the pending punch. Single click: one right hit with the punch sound. Double click: the full two-punch
     * clip plays and a left hit follows the right one, matching the audio. {@code target} null = hit what's in front.
     */
    private static void landPunch(AbilityContext c, LivingEntity target, double dmg, double knock, double up,
                                  Consumer<LivingEntity> onHit) {
        boolean dbl = c.data.punchDouble;
        c.data.punchStart = -1L;
        if (dbl) {
            // Double-click turned this into the charged combo (chargedCombo), which lands its own hits.
            c.data.followUpUntil = 0L;
            return;
        }
        // A single punch leaves the door open for the left follow-up; a double is already the full pair.
        c.data.followUpUntil = dbl ? 0L : c.level.getGameTime() + FOLLOW_UP_WINDOW;
        c.sound(com.mobstands.registry.ModSounds.COMBO_PUNCH_1.get(), 1.0F, 1.0F);
        // In a double the first hit only nudges, so the second one still connects and does the launching.
        punchHit(c, target, dmg, dbl ? knock * 0.3D : knock, dbl ? up * 0.2D : up, onHit);
        if (dbl) c.later(DOUBLE_GAP, () -> {
            c.sound(com.mobstands.registry.ModSounds.COMBO_PUNCH_2.get(), 1.0F, 1.0F);
            punchHit(c, target, dmg * 1.1D, knock * 1.4D + 0.4D, up + 0.15D, null);
        });
    }

    /** The mob's own sound as the Stand rushes in (layered under the punch sounds). */
    static void mobRushSound(AbilityContext c) {
        switch (c.type) {
            case SLIME -> c.sound(SoundEvents.SLIME_JUMP, 0.9F, 0.9F + c.owner.getRandom().nextFloat() * 0.2F);
            case ENDERMAN, ENDER_WORLD -> c.sound(SoundEvents.ENDERMAN_TELEPORT, 0.4F, 1.4F);
            case PHANTOM -> c.sound(SoundEvents.PHANTOM_SWOOP, 0.7F, 1.2F);
            case SQUID -> c.sound(SoundEvents.SQUID_SQUIRT, 0.7F, 1.0F);
            case DOLPHIN -> c.sound(SoundEvents.DOLPHIN_SWIM, 0.7F, 1.2F);
            case SCARY_MONSTERS -> c.sound(SoundEvents.RAVAGER_STEP, 0.8F, 1.2F);
            case SPIDER -> c.sound(SoundEvents.SPIDER_STEP, 0.8F, 1.0F);
            case BLAZE -> c.sound(SoundEvents.BLAZE_BURN, 0.6F, 1.2F);
            default -> { }
        }
    }

    /** The mob's own sound when the strike connects (Slime squelch, Blaze rods clattering, Golem clang...). */
    static void mobHitSound(AbilityContext c) {
        float p = 0.9F + c.owner.getRandom().nextFloat() * 0.2F;
        switch (c.type) {
            case SLIME -> c.sound(SoundEvents.SLIME_ATTACK, 0.9F, p);
            case BLAZE -> c.sound(SoundEvents.BLAZE_HURT, 0.8F, p);           // the clatter of its rods
            case IRON_GOLEM -> c.sound(SoundEvents.IRON_GOLEM_ATTACK, 0.8F, p);
            case WARDEN, WARDEN_REQUIEM -> c.sound(SoundEvents.WARDEN_ATTACK_IMPACT, 0.9F, p);
            case ENDERMAN, ENDER_WORLD -> c.sound(SoundEvents.ENDERMAN_HURT, 0.4F, 1.3F);
            case CREEPER, CREEPER_REQUIEM -> c.sound(SoundEvents.CREEPER_HURT, 0.7F, p);
            case SPIDER -> c.sound(SoundEvents.SPIDER_AMBIENT, 0.8F, 1.2F);
            case PHANTOM -> c.sound(SoundEvents.PHANTOM_BITE, 0.8F, p);
            case SQUID -> c.sound(SoundEvents.SQUID_HURT, 0.8F, p);
            case PIGLIN -> c.sound(SoundEvents.PIGLIN_ANGRY, 0.6F, p);
            case ALLAY -> c.sound(SoundEvents.ALLAY_HURT, 0.6F, 1.3F);
            case ZOMBIFIED_PIGLIN -> c.sound(SoundEvents.ZOMBIFIED_PIGLIN_ANGRY, 0.6F, p);
            case SCARY_MONSTERS -> c.sound(SoundEvents.RAVAGER_HURT, 0.6F, 1.3F);
            default -> { }
        }
    }

    /** Every Stand punch (single, follow-up, combos) hits for only a quarter of its listed power. */
    public static final double PUNCH_SCALE = 0.25D;
    /** Punches only nudge: the big launch is saved for an enemy that eats a full Barrage. */
    public static final double PUNCH_KNOCK_SCALE = 0.3D;

    private static void punchHit(AbilityContext c, LivingEntity target, double dmg, double knock, double up,
                                 Consumer<LivingEntity> onHit) {
        if (target != null) {
            if (!target.isAlive()) return;
            target.invulnerableTime = 0;
            c.hurt(target, c.dmg(dmg * PUNCH_SCALE));
            c.push(target, target.position().subtract(c.owner.position()), knock * PUNCH_KNOCK_SCALE, up * PUNCH_KNOCK_SCALE);
            if (c.type == StandType.SHULKER) GravityFlip.onPunch(c.owner, target, c.data.getLevel());
            mobHitSound(c); // only when it actually connects
            if (onHit != null) onHit.accept(target);
        } else if (comboHit(c, dmg * PUNCH_SCALE, knock, up) > 0) {
            mobHitSound(c);
        }
    }

    /** Hits everything just in front of the Stand; returns how many got hit. */
    private static int comboHit(AbilityContext c, double damage, double knock, double up) {
        Vec3 look = c.flatLook();
        Vec3 center = c.stand.position().add(look.scale(1.2D)).add(0.0D, 1.0D, 0.0D);
        int n = 0;
        for (LivingEntity e : c.enemies(center, 2.4D)) {
            e.invulnerableTime = 0;
            c.hurt(e, c.dmg(damage));
            c.push(e, look, knock * PUNCH_KNOCK_SCALE, up * PUNCH_KNOCK_SCALE);
            if (c.type == StandType.SHULKER) GravityFlip.onPunch(c.owner, e, c.data.getLevel());
            n++;
        }
        return n;
    }

    /** Tick (after the click) a regular punch connects. A second click before this makes it a double. */
    public static final int PUNCH_HIT_TICK = 4;

    /**
     * Barrage damage is fixed and light, the same for every Stand: 14 x 0.6 + 4 = about 12 in all - enough to
     * finish a cow (10 HP), not a zombie (20 HP). It's a trap, not a killer.
     */
    public static final float BARRAGE_HIT_DMG = 0.6F, BARRAGE_FINISH_DMG = 4.0F;

    /** Ticks a Barrage lasts (the Stand stays out in front, punching, for this long). */
    public static final int BARRAGE_TICKS = 32;
    /** Extra ticks the Barrage animation keeps playing after the finisher lands (the big fist's impact). */
    public static final int BARRAGE_AFTER = 7;

    /**
     * ORA barrage: the Stand plants itself in front of you and pummels everything in a short cone,
     * a hit every 2 ticks, then finishes with one heavy blow that sends them flying.
     */
    private static boolean barrage(AbilityContext c, double perHit, double finisher) {
        return barrage(c, perHit, finisher, null);
    }

    /** @param onHit run on every enemy each punch (and the finisher) connects with - e.g. the Blaze's rods set them alight. */
    private static boolean barrage(AbilityContext c, double perHit, double finisher, Consumer<LivingEntity> onHit) {
        // Someone in front of you threw theirs a moment ago: the fists meet - Barrage Clash.
        if (Clash.tryStart(c)) return true;
        LivingEntity focus = c.meleeTarget(6.0D);
        Vec3 look = c.flatLook();
        Vec3 aim = focus != null
                ? focus.position().add(0.0D, focus.getBbHeight() * 0.5D, 0.0D).subtract(look.scale(1.3D))
                : c.owner.position().add(look.scale(1.8D)).add(0.0D, 1.0D, 0.0D);
        c.stand.lunge(aim, BARRAGE_TICKS);
        c.sound(SoundEvents.PLAYER_ATTACK_SWEEP, 1.0F, 0.6F);
        final int hits = 14;
        // Everyone caught in the flurry, and the spot they're pinned to until it ends.
        final java.util.Map<LivingEntity, Vec3> caught = new java.util.HashMap<>();
        for (int t = 2; t < BARRAGE_TICKS - 1; t++) {
            c.later(t, () -> {
                if (Clash.inClash(c.owner)) return;
                for (java.util.Map.Entry<LivingEntity, Vec3> en : caught.entrySet()) {
                    LivingEntity e = en.getKey();
                    if (!e.isAlive()) continue;
                    Vec3 at = en.getValue();
                    if (e instanceof net.minecraft.world.entity.Mob mob) mob.getNavigation().stop();
                    if (e.position().distanceToSqr(at.x, e.getY(), at.z) > 0.0025D) e.teleportTo(at.x, e.getY(), at.z);
                    e.setDeltaMovement(0.0D, Math.min(e.getDeltaMovement().y, 0.0D), 0.0D);
                    e.hurtMarked = true;
                }
            });
        }
        for (int i = 0; i < hits; i++) {
            final int hitIndex = i;
            c.later(2 + i * 2, () -> {
                if (Clash.inClash(c.owner)) return; // caught in a clash: the fists are busy meeting theirs
                Vec3 center = c.stand.position().add(look.scale(1.2D)).add(0.0D, 1.0D, 0.0D);
                boolean connected = false;
                for (LivingEntity e : c.enemies(center, 2.6D)) {
                    e.invulnerableTime = 0;
                    // No knockback during the flurry: they're caught in it. (The hit's own vanilla knockback is
                    // cancelled too, and they're held where they stand - they can't walk out of it.)
                    Vec3 before = e.getDeltaMovement();
                    caught.putIfAbsent(e, e.position());
                    c.hurt(e, BARRAGE_HIT_DMG);
                    e.setDeltaMovement(0.0D, Math.min(before.y, 0.0D), 0.0D);
                    e.hurtMarked = true;
                    if (onHit != null) onHit.accept(e);
                    connected = true;
                }
                if (connected && hitIndex % 3 == 0) mobHitSound(c);
                c.sound(SoundEvents.PLAYER_ATTACK_KNOCKBACK, 0.45F, 1.1F + c.owner.getRandom().nextFloat() * 0.5F);
            });
        }
        c.later(BARRAGE_TICKS - 1, () -> {
            if (Clash.inClash(c.owner)) return;
            Vec3 center = c.stand.position().add(look.scale(1.2D)).add(0.0D, 1.0D, 0.0D);
            boolean connected = false;
            for (LivingEntity e : c.enemies(center, 2.8D)) {
                e.invulnerableTime = 0;
                c.hurt(e, BARRAGE_FINISH_DMG);
                // The barrage ends: everyone caught in it is sent flying.
                c.push(e, look, 1.5D, 0.4D);
                if (onHit != null) onHit.accept(e);
                connected = true;
            }
            if (connected) mobHitSound(c);
            c.sound(SoundEvents.PLAYER_ATTACK_STRONG, 1.0F, 0.7F);
            c.sound(SoundEvents.ANVIL_LAND, 0.35F, 1.6F);
        });
        return true;
    }

    /** Ticks after the release before the Double Shot window opens, and how long it stays open. */
    public static final int DOUBLE_SHOT_DELAY = 2;
    public static final int DOUBLE_SHOT_WINDOW = 5;
    /** The follow-up arrow is drawn this fast. */
    private static final int DOUBLE_SHOT_DRAW = 3;

    /**
     * Skeleton tech: clicking inside the window right after an arrow leaves snaps off a second arrow almost
     * instantly. A "ding" confirms the timing. Only one bonus arrow per shot.
     */
    public static void doubleShot(AbilityContext c) {
        c.data.doubleShotClose = 0L;
        c.stand.playAnimFrom(0, StandType.BOW_DRAW_TICKS - DOUBLE_SHOT_DRAW);
        c.later(DOUBLE_SHOT_DRAW, () -> {
            shoot(c, StandProjectile.Kind.GLOW_ARROW, 5.5D, 0.0D);
            c.sound(com.mobstands.registry.ModSounds.DOUBLE_SHOT.get(), 1.0F, 1.0F); // only on a perfect-timing shot
        });
    }

    private static void shoot(AbilityContext c, StandProjectile.Kind kind, double dmg, double yawSpread) {
        Vec3 origin = c.shotOrigin();
        Vec3 dir = c.aimPoint(48.0D).subtract(origin).normalize();
        if (yawSpread != 0.0D) dir = dir.yRot((float) yawSpread);
        c.fire(kind, origin, dir, dmg);
    }

    /** Called by the Stand AI when a hostile mob is attacking the owner. Weaker than manual attacks. */
    public static void autoStrike(AbilityContext c, LivingEntity target) {
        c.stand.playAnim(0);
        Vec3 center = target.position().add(0.0D, target.getBbHeight() * 0.6D, 0.0D);
        if (c.type.autoKind() != null) {
            int delay = c.type == StandType.SKELETON ? StandType.BOW_DRAW_TICKS : 1;
            c.later(delay, () -> {
                if (!target.isAlive()) return;
                Vec3 aim = target.position().add(0.0D, target.getBbHeight() * 0.6D, 0.0D);
                Vec3 origin = c.shotOrigin();
                c.fire(c.type.autoKind(), origin, aim.subtract(origin).normalize(), 2.5D);
                c.sound(SoundEvents.ARROW_SHOOT, 0.7F, 1.2F);
            });
        } else {
            c.stand.lunge(center, 5);
            c.later(3, () -> {
                if (!target.isAlive()) return;
                c.hurt(target, c.dmg(2.8D));
                c.push(target, target.position().subtract(c.owner.position()), 0.5D, 0.15D);
                mobHitSound(c);
            });
            c.sound(SoundEvents.PLAYER_ATTACK_SWEEP, 0.7F, 1.2F);
        }
    }

    // ================================================================== CREEPER (Uncommon): explosions

    /** Green Detonator: Bites the Dust - the Creeper Requiem. */
    public static Ability[] creeperRequiem() {
        Ability[] base = creeper();
        return new Ability[]{
                base[0], // (no punch)
                ab("Primer", "Look at a mob and your Stand touches it: it's a bomb now (it fizzes with sparks). Prime as many as you "
                        + "like, then press with no new mob in sight - click - and every primed mob fizzes, then goes up in a huge, slow blast "
                        + "that harms nothing around it and leaves not even a trace behind. Hold SHIFT when you click and they really "
                        + "blow up: a true explosion that tears up the area and hurts everything around them (never you).", 1, 1, c -> CreeperRequiem.primer(c)),
                ab("Heart Attack", "A little creeper in a little minecart rolls out. Its blasts never hurt you or break blocks - but it can be knocked back and destroyed (stop time and smash it!). "
                        + "Two modes. SHIFT + this key - AGGRESSIVE: it hunts down whoever you're looking at (or the nearest monster), "
                        + "blows up on them, then on 2 more nearby enemies, and disappears. Just this key - PASSIVE: it rolls to 3 enemies, just "
                        + "touching them, then disappears. Each one becomes a Primer bomb: it stays primed until you click Primer to detonate.", 10, 45, c ->
                        HeartAttack.release(c)), // Heart Attack (Requiem only)
                bigBangExplosive(), // Big Bang (the Requiem keeps real explosions)
                ab("Bites the Dust", "The clock winds back: the last 6 seconds are undone for everything within 40 blocks - every mob and "
                        + "player goes back where it was with the health and items it had, the dead come back (players too, if they "
                        + "haven't pressed Respawn), broken blocks return. "
                        + "Only you remember. And whoever hurt you in those seconds still carries the charge: when time snaps back, "
                        + "they explode.", 1, 12, c -> CreeperRequiem.bitesTheDust(c))
        };
    }

    /** The original Big Bang with real explosions - only Green Detonator Requiem uses this one now. */
    private static Ability bigBangExplosive() {
        return
                ab("Big Bang", "A chain of massive explosions at your aim point. The Stand is consumed and takes a while to reform.", 9, 55, c -> {
                    Vec3 target = c.aimPoint(22.0D);
                    c.stand.lunge(target, 22);
                    c.sound(SoundEvents.CREEPER_PRIMED, 1.5F, 0.6F);
                    c.ring(ParticleTypes.HAPPY_VILLAGER, target.add(0.0D, 0.2D, 0.0D), 5.0D, 40);
                    c.later(20, () -> c.explode(target, 4.5D));
                    c.later(24, () -> c.explode(target.add(3.0D, 0.0D, 0.0D), 2.4D));
                    c.later(27, () -> c.explode(target.add(-3.0D, 0.0D, 0.0D), 2.4D));
                    c.later(30, () -> {
                        c.explode(target.add(0.0D, 0.0D, 3.0D), 2.4D);
                        c.stand.reform(120);
                    });
                    return true;
                });
    }

    public static Ability[] creeper() {
        return new Ability[]{
                Ability.none(), // no punch: it's a creeper

                ab("Hissing Burst", "Your Stand swells and bursts, dealing explosive damage to nearby enemies. You are never hurt by it and no blocks break.", 2, 12, c -> {
                    c.sound(SoundEvents.CREEPER_PRIMED, 1.2F, 1.0F);
                    c.fx(ParticleTypes.HAPPY_VILLAGER, c.stand.position().add(0.0D, 1.0D, 0.0D), 20, 0.6D, 0.05D);
                    c.later(12, () -> c.blast(c.stand.position().add(0.0D, 1.0D, 0.0D), 3.5D, 5.0D));
                    return true;
                }),

                ab("Kamikaze Rush", "Your Stand dashes to your aim point and bursts in a green shockwave - its strongest hit. No explosion, no broken blocks, and you are never hurt by it. It reforms after a few seconds.", 5, 18, c -> {
                    Vec3 target = c.aimPoint(14.0D);
                    c.stand.lunge(target, 10);
                    c.sound(SoundEvents.CREEPER_PRIMED, 1.4F, 1.4F);
                    c.later(10, () -> {
                        c.blast(c.stand.position().add(0.0D, 1.0D, 0.0D), 4.5D, 14.0D, false); // no real explosion, but still the strongest hit
                        c.stand.reform(70);
                    });
                    return true;
                }),

                ab("Big Bang", "A chain of blasts at your aim point. The Stand is consumed and takes a while to reform. Deals explosive damage without breaking blocks.", 9, 55, c -> {
                    Vec3 target = c.aimPoint(22.0D);
                    c.stand.lunge(target, 22);
                    c.sound(SoundEvents.CREEPER_PRIMED, 1.5F, 0.6F);
                    c.ring(ParticleTypes.HAPPY_VILLAGER, target.add(0.0D, 0.2D, 0.0D), 5.0D, 40);
                    c.later(20, () -> c.blast(target, 5.0D, 9.0D));
                    c.later(24, () -> c.blast(target.add(3.0D, 0.0D, 0.0D), 3.0D, 4.0D));
                    c.later(27, () -> c.blast(target.add(-3.0D, 0.0D, 0.0D), 3.0D, 4.0D));
                    c.later(30, () -> {
                        c.blast(target.add(0.0D, 0.0D, 3.0D), 3.0D, 4.0D);
                        c.stand.reform(120);
                    });
                    return true;
                }),
                ab("Sheer Heart Attack", "A tiny bomb of a creeper that hunts on its own: it chases the warmest thing nearby (anything burning first) and blows up on it, then reforms and keeps hunting - up to 3 blasts in 20 seconds. Never breaks blocks, never hurts you.", 1, 40, Signatures::sheerHeartAttack)
        };
    }

    // ================================================================== ENDERMAN (Epic): mobility, surprise attacks

    public static Ability[] enderman() {
        return new Ability[]{
                ab("Void Slash", "A razor-fast strike from the void.", 1, 0.6, c ->
                        melee(c, 5.0D, 0.3D, 0.1D, t -> c.fx(ParticleTypes.PORTAL, t.position().add(0.0D, 1.0D, 0.0D), 20, 0.4D, 0.5D))),

                ab("Barrage", "Your Stand unleashes a storm of punches on everything in front of you, ending with a heavy blow.", 2, 8, c ->
                        barrage(c, 1.0D, 5.0D)),

                ab("Blink", "Teleport to where you are looking (up to 18 blocks).", 4, 6, c -> {
                    Vec3 dest = c.blinkDestination(18.0D);
                    if (dest == null) return false;
                    Vec3 from = c.owner.position().add(0.0D, 1.0D, 0.0D);
                    c.fx(ParticleTypes.PORTAL, from, 40, 0.4D, 0.8D);
                    c.owner.teleportTo(dest.x, dest.y, dest.z);
                    c.owner.fallDistance = 0.0F;
                    c.fx(ParticleTypes.REVERSE_PORTAL, dest.add(0.0D, 1.0D, 0.0D), 40, 0.4D, 0.4D);
                    c.sound(SoundEvents.ENDERMAN_TELEPORT, 1.0F, 1.0F);
                    return true;
                }),

                ab("Void Collapse", "Rip a hole in space: enemies are dragged to your aim point, damaged and weakened.", 10, 40, c -> {
                    Vec3 center = c.aimPoint(12.0D);
                    c.sound(SoundEvents.ENDERMAN_SCREAM, 1.4F, 0.7F);
                    c.fx(ParticleTypes.REVERSE_PORTAL, center, 80, 1.5D, 0.3D);
                    c.ring(ParticleTypes.PORTAL, center.add(0.0D, 0.2D, 0.0D), 8.0D, 48);
                    for (LivingEntity e : c.enemies(center, 9.0D)) {
                        Vec3 pull = center.subtract(e.position()).scale(0.32D);
                        e.setDeltaMovement(pull.x, 0.25D, pull.z);
                        e.hurtMarked = true;
                        c.hurt(e, c.dmg(14.0D));
                        c.effect(e, MobEffects.WEAKNESS, 100, 1);
                        c.effect(e, MobEffects.LEVITATION, 30, 0);
                    }
                    return true;
                }),

                ab("Scatter", "Your Enderman touches the mob you're looking at and it's flung through the void to a random spot "
                        + "up to 16 blocks away. Looking at nothing: every enemy within 8 blocks of you is scattered at once.", 6, 10, c -> {
                    LivingEntity target = c.aim(20.0D);
                    List<LivingEntity> victims = target != null ? List.of(target) : c.enemies(c.owner.position().add(0.0D, 1.0D, 0.0D), 8.0D);
                    if (victims.isEmpty()) {
                        c.fail = "No one to scatter.";
                        return false;
                    }
                    boolean any = false;
                    for (LivingEntity e : victims) any |= scatter(c, e);
                    if (!any) c.fail = "Nowhere safe to send them.";
                    return any;
                }),

                ab("Swap", "Trade places with whatever you're looking at, up to 32 blocks away: any mob, a player, your own pet, "
                        + "even an armor stand, a boat or a minecart.", 8, 6, c -> {
                    net.minecraft.world.entity.Entity t = swapTarget(c, 32.0D);
                    if (t == null) {
                        c.fail = "Look at something to swap with.";
                        return false;
                    }
                    Vec3 me = c.owner.position(), it = t.position();
                    float myYaw = c.owner.getYRot(), myPitch = c.owner.getXRot();
                    c.level.sendParticles(ParticleTypes.PORTAL, me.x, me.y + 1.0D, me.z, 40, 0.3D, 0.8D, 0.3D, 0.4D);
                    c.level.sendParticles(ParticleTypes.PORTAL, it.x, it.y + t.getBbHeight() * 0.5D, it.z, 40, 0.3D, 0.6D, 0.3D, 0.4D);
                    if (t.isPassenger()) t.stopRiding();
                    if (c.owner.isPassenger()) c.owner.stopRiding();
                    // It lands where you stood, still facing the way it faced; you land where it was, still looking where you looked.
                    if (t instanceof net.minecraft.server.level.ServerPlayer sp) sp.teleportTo(me.x, me.y, me.z);
                    else t.teleportTo(me.x, me.y, me.z);
                    t.setDeltaMovement(Vec3.ZERO);
                    t.hurtMarked = true;
                    if (t instanceof net.minecraft.world.entity.Mob mob) mob.getNavigation().stop();
                    c.owner.teleportTo(c.level, it.x, it.y, it.z, java.util.Set.of(), myYaw, myPitch);
                    c.owner.setDeltaMovement(Vec3.ZERO);
                    c.owner.fallDistance = 0.0F;
                    t.fallDistance = 0.0F;
                    c.sound(SoundEvents.ENDERMAN_TELEPORT, 1.0F, 1.0F);
                    c.level.playSound(null, me.x, me.y, me.z, SoundEvents.ENDERMAN_TELEPORT, net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 1.3F);
                    c.level.sendParticles(ParticleTypes.REVERSE_PORTAL, me.x, me.y + 1.0D, me.z, 30, 0.3D, 0.8D, 0.3D, 0.1D);
                    c.level.sendParticles(ParticleTypes.REVERSE_PORTAL, it.x, it.y + 1.0D, it.z, 30, 0.3D, 0.8D, 0.3D, 0.1D);
                    return true;
                })
        };
    }

    /** Whatever entity your crosshair is on (anything at all but you and your Stand), through no walls. */
    private static net.minecraft.world.entity.Entity swapTarget(AbilityContext c, double range) {
        Vec3 eye = c.owner.getEyePosition();
        Vec3 end = c.beamEnd(eye, c.look(), range);
        net.minecraft.world.phys.EntityHitResult hit = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(
                c.level, c.owner, eye, end, new net.minecraft.world.phys.AABB(eye, end).inflate(1.0D),
                e -> e != c.owner && e != c.stand && !(e instanceof com.mobstands.entity.StandEntity) && !e.isSpectator()
                        && e.isAlive() && !(e instanceof net.minecraft.world.entity.projectile.Projectile)
                        && !(e instanceof net.minecraft.world.entity.item.ItemEntity) && !(e instanceof net.minecraft.world.entity.ExperienceOrb),
                0.3F);
        return hit == null ? null : hit.getEntity();
    }

    /** Throws {@code e} to a random safe spot within 16 blocks (the way an enderman teleports). */
    private static boolean scatter(AbilityContext c, LivingEntity e) {
        Vec3 from = e.position();
        for (int i = 0; i < 16; i++) {
            double x = from.x + (c.owner.getRandom().nextDouble() - 0.5D) * 32.0D;
            double y = net.minecraft.util.Mth.clamp(from.y + (c.owner.getRandom().nextInt(17) - 8), c.level.getMinBuildHeight(), c.level.getMaxBuildHeight() - 1);
            double z = from.z + (c.owner.getRandom().nextDouble() - 0.5D) * 32.0D;
            if (Math.abs(x - from.x) + Math.abs(z - from.z) < 4.0D) continue; // somewhere actually different
            if (e.randomTeleport(x, y, z, true)) {
                if (e instanceof net.minecraft.world.entity.Mob mob) mob.getNavigation().stop();
                c.level.sendParticles(ParticleTypes.PORTAL, from.x, from.y + e.getBbHeight() * 0.5D, from.z, 40, 0.4D, 0.6D, 0.4D, 0.4D);
                c.level.sendParticles(ParticleTypes.REVERSE_PORTAL, e.getX(), e.getY() + e.getBbHeight() * 0.5D, e.getZ(), 30, 0.4D, 0.6D, 0.4D, 0.1D);
                c.level.playSound(null, from.x, from.y, from.z, SoundEvents.ENDERMAN_TELEPORT, net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 1.0F);
                c.level.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.ENDERMAN_TELEPORT, net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 1.2F);
                StandCombat.credit(c.owner, e);
                return true;
            }
        }
        return false;
    }

    // ================================================================== THE ENDER WORLD (Legendary): stops time

    public static Ability[] enderWorld() {
        return new Ability[]{
                ab("Void Jab", "A blindingly fast punch.", 1, 0.55, c ->
                        melee(c, 6.0D, 0.5D, 0.15D, t -> c.fx(ParticleTypes.END_ROD, t.position().add(0.0D, 1.0D, 0.0D), 8, 0.3D, 0.1D))),

                ab("Barrage", "Your Stand unleashes a storm of punches on everything in front of you, ending with a heavy blow.", 2, 8, c ->
                        barrage(c, 1.15D, 6.0D)),

                ab("Time Skip", "Step through a split second: you reappear right behind the enemy you're looking at (or 10 blocks ahead).", 4, 7, c -> {
                    LivingEntity t = c.aim(16.0D);
                    Vec3 dest;
                    if (t != null) {
                        Vec3 back = t.getLookAngle().multiply(1.0D, 0.0D, 1.0D);
                        back = back.lengthSqr() < 1.0E-4D ? c.flatLook() : back.normalize();
                        dest = t.position().subtract(back.scale(t.getBbWidth() * 0.5D + 1.0D));
                    } else {
                        dest = c.blinkDestination(10.0D);
                    }
                    if (dest == null) return false;
                    // Where you were: a deep, swallowed whoosh as you vanish.
                    Vec3 was = c.owner.position();
                    c.level.playSound(null, was.x, was.y, was.z, SoundEvents.ENDERMAN_TELEPORT, net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 0.45F);
                    c.level.playSound(null, was.x, was.y, was.z, SoundEvents.WARDEN_SONIC_CHARGE, net.minecraft.sounds.SoundSource.PLAYERS, 0.5F, 2.0F);
                    c.owner.teleportTo(dest.x, dest.y, dest.z);
                    if (t != null) {
                        Vec3 look = t.position().subtract(dest);
                        float yaw = (float) (Math.atan2(look.z, look.x) * 57.2957763671875D) - 90.0F;
                        c.owner.connection.teleport(dest.x, dest.y, dest.z, yaw, c.owner.getXRot());
                    }
                    c.owner.fallDistance = 0.0F;
                    // Where you appear - right behind them: a deep bell toll, a distorted Enderman shriek and a
                    // heartbeat, then the clock ticks on (tick... tock) and something rumbles in the dark.
                    final Vec3 at = dest;
                    java.util.function.BiConsumer<net.minecraft.sounds.SoundEvent, float[]> here = (snd, vp) ->
                            c.level.playSound(null, at.x, at.y, at.z, snd, net.minecraft.sounds.SoundSource.PLAYERS, vp[0], vp[1]);
                    here.accept(SoundEvents.BELL_BLOCK, new float[]{1.3F, 0.5F});
                    here.accept(SoundEvents.ENDERMAN_SCREAM, new float[]{0.55F, 0.6F});
                    here.accept(SoundEvents.WARDEN_HEARTBEAT, new float[]{1.2F, 0.7F});
                    here.accept(SoundEvents.ENDERMAN_TELEPORT, new float[]{0.7F, 0.7F});
                    c.later(4, () -> c.sound(SoundEvents.NOTE_BLOCK_BASEDRUM.value(), 0.9F, 0.6F));
                    c.later(9, () -> c.sound(SoundEvents.NOTE_BLOCK_HAT.value(), 0.8F, 0.6F));
                    c.later(12, () -> c.sound(SoundEvents.AMBIENT_CAVE.value(), 0.7F, 0.6F));
                    // The same inverted-colour flash as Time Stop, just for a split second.
                    c.data.timeSkipAt = c.level.getGameTime();
                    StandManager.sync(c.owner);
                    return true;
                }),

                ab("Time Stop", "THE ENDER WORLD! You step inside the instant of a teleport and stay there - to everything else, time stops. Nothing moves except you and your Stand: mobs, players, arrows, items, falling blocks... Damage you deal to frozen enemies piles up and lands all at once when time moves again. Lasts longer as your Stand grows.", 3, 60, c -> {
                    int ticks = TimeStop.durationFor(c.data.getLevel());
                    return TimeStop.start(c.owner, ticks);
                })
        };
    }

    // ================================================================== MINECART (Uncommon): rides anywhere

    public static Ability[] minecart() {
        return new Ability[]{
                Ability.none(),
                ab("Rail Rider", "Hop in and drive your Minecart anywhere, no rails needed: rails fade in under you and vanish behind you. W to speed up, S to brake, steer with your mouse, Shift (or Z again) to hop out. "
                        + "Other players can right-click you to climb aboard: up to 4 cars couple on behind and follow your exact path, like a train.", 1, 0.5, c -> {
                    StandManager.cartToggle(c.owner);
                    return true;
                }),
                ab("Powered Rails", "Golden powered rails with redstone underneath lay themselves under you: a steady extra burst of speed that lasts a long time (12 s).", 3, 20, c ->
                        StandManager.cartBoost(c.owner, 1)),
                ab("Nitro", "Light the cart up: flames roar out the back and you blast forward far faster than anything else - but it burns out quickly (3 s).", 6, 25, c ->
                        StandManager.cartBoost(c.owner, 2)),
                ab("Cattle Car", "A minecart slams into the creature you're looking at (24 blocks), scoops it up and hauls it off in the direction you're facing. It can't climb out for 6 seconds.", 1, 15, Signatures::cattleCar)
        };
    }

    // ================================================================== IRON GOLEM (Rare): power and defence

    public static Ability[] ironGolem() {
        return new Ability[]{
                ab("Iron Fist", "A heavy punch with huge knockback.", 1, 0.9, c ->
                        melee(c, 7.0D, 1.0D, 0.35D, null)),

                ab("Barrage", "Your Stand unleashes a storm of punches on everything in front of you, ending with a heavy blow.", 2, 8, c ->
                        barrage(c, 1.2D, 6.0D)),

                ab("Ground Slam", "Slam the ground in front of you, launching nearby enemies.", 5, 9, c -> {
                    Vec3 center = c.owner.position().add(c.flatLook().scale(3.0D));
                    c.stand.lunge(center.add(0.0D, 1.0D, 0.0D), 5);
                    c.sound(SoundEvents.IRON_GOLEM_ATTACK, 1.2F, 0.7F);
                    c.later(4, () -> {
                        for (LivingEntity e : c.enemies(center.add(0.0D, 1.0D, 0.0D), 4.5D)) {
                            c.hurt(e, c.dmg(6.0D));
                            c.push(e, e.position().subtract(center), 0.6D, 0.65D);
                        }
                        c.fx(stoneDust(), center, 50, 1.5D, 0.2D);
                        c.ring(ParticleTypes.POOF, center.add(0.0D, 0.1D, 0.0D), 3.5D, 28);
                        c.sound(SoundEvents.ANVIL_LAND, 0.8F, 0.6F);
                    });
                    return true;
                }),

                ab("World Breaker", "A colossal punch that crushes everything in front of you.", 10, 40, c -> {
                    Vec3 dir = c.flatLook();
                    Vec3 from = c.owner.position().add(0.0D, 0.2D, 0.0D);
                    List<LivingEntity> hit = c.cone(9.0D, 0.55D);
                    c.stand.lunge(c.owner.position().add(dir.scale(4.0D)).add(0.0D, 1.0D, 0.0D), 8);
                    c.sound(SoundEvents.ANVIL_LAND, 1.5F, 0.5F);
                    for (int i = 1; i <= 9; i++) {
                        c.fx(stoneDust(), from.add(dir.scale(i)), 14, 0.5D, 0.15D);
                    }
                    c.later(5, () -> {
                        c.sound(SoundEvents.IRON_GOLEM_ATTACK, 1.6F, 0.5F);
                        for (LivingEntity e : hit) {
                            if (!e.isAlive()) continue;
                            c.hurt(e, c.dmg(20.0D));
                            c.push(e, dir, 2.6D, 0.9D);
                            c.effect(e, MobEffects.MOVEMENT_SLOWDOWN, 60, 2);
                        }
                    });
                    return true;
                }),

                ab("Iron Launch", "Your Golem cups its hand under you and hurls you into the sky (about 12 blocks). Coming down, "
                        + "you're a mace: hit someone on the way down, or land next to them, for a mace smash - the further you "
                        + "fell, the harder it hits, and everyone around the impact is thrown back. No fall damage.", 3, 10, GolemLaunch::launch)
        };
    }

    // ================================================================== BLAZE (Rare): fire and ranged

    public static Ability[] blaze() {
        return new Ability[]{
                ab("Rod Strike", "Jabs one of its burning rods into the target, setting it on fire.", 1, 0.7, c ->
                        melee(c, 4.5D, 0.5D, 0.15D, t -> t.setRemainingFireTicks(Math.max(t.getRemainingFireTicks(), 60)))),

                ab("Rod Barrage", "Your Blaze plants itself in front of you and jabs its burning rods into everything ahead, again and again, setting them alight - then one last blazing thrust. Take the full barrage and you're sent flying.", 2, 8, c ->
                        barrage(c, 1.2D, 6.0D, e -> {
                            e.setRemainingFireTicks(Math.max(e.getRemainingFireTicks(), 80));
                            c.fx(ParticleTypes.FLAME, e.position().add(0.0D, e.getBbHeight() * 0.5D, 0.0D), 4, 0.25D, 0.03D);
                        })),

                ab("Ring of Fire", "A ring of flames erupts around you, burning and repelling enemies, and 8 fireballs burst out of it in every direction.", 5, 14, c -> {
                    Vec3 center = c.owner.position();
                    c.sound(SoundEvents.FIRECHARGE_USE, 1.2F, 0.8F);
                    c.ring(ParticleTypes.FLAME, center.add(0.0D, 0.2D, 0.0D), 2.5D, 32);
                    c.ring(ParticleTypes.FLAME, center.add(0.0D, 0.5D, 0.0D), 4.5D, 56);
                    for (LivingEntity e : c.enemies(center.add(0.0D, 1.0D, 0.0D), 4.5D)) {
                        c.hurt(e, c.dmg(4.0D));
                        e.setRemainingFireTicks(Math.max(e.getRemainingFireTicks(), 100));
                        c.push(e, e.position().subtract(center), 0.7D, 0.2D);
                    }
                    // ...and 8 fireballs burst out of the ring in every direction.
                    for (int i = 0; i < 8; i++) {
                        double a = (Math.PI * 2.0D) * i / 8.0D;
                        Vec3 dir = new Vec3(Math.cos(a), -0.03D, Math.sin(a));
                        c.fire(StandProjectile.Kind.FIREBALL, center.add(0.0D, 1.0D, 0.0D).add(dir.scale(2.5D)), dir, 3.0D);
                    }
                    c.sound(SoundEvents.BLAZE_SHOOT, 1.0F, 1.0F);
                    return true;
                }),

                ab("Inferno Burst", "Two waves of fire explode outward in every direction.", 10, 45, c -> {
                    c.sound(SoundEvents.BLAZE_SHOOT, 1.6F, 0.6F);
                    for (int wave = 0; wave < 2; wave++) {
                        final int w = wave;
                        c.later(wave * 10, () -> {
                            Vec3 center = c.owner.position();
                            c.ring(ParticleTypes.FLAME, center.add(0.0D, 0.4D, 0.0D), 6.5D, 60);
                            for (int i = 0; i < 12; i++) {
                                double a = (Math.PI * 2.0D) * i / 12.0D + w * (Math.PI / 12.0D);
                                Vec3 dir = new Vec3(Math.cos(a), -0.03D, Math.sin(a));
                                c.fire(StandProjectile.Kind.FIREBALL, center.add(0.0D, 1.2D, 0.0D).add(dir.scale(1.5D)), dir, 5.0D);
                            }
                            for (LivingEntity e : c.enemies(center.add(0.0D, 1.0D, 0.0D), 6.5D)) {
                                c.hurt(e, c.dmg(5.0D));
                                e.setRemainingFireTicks(Math.max(e.getRemainingFireTicks(), 160));
                                c.push(e, e.position().subtract(center), 0.9D, 0.3D);
                            }
                            c.sound(SoundEvents.FIRECHARGE_USE, 1.4F, 0.6F);
                        });
                    }
                    return true;
                }),

                ab("Flame Barrage", "Three fireballs in a fan.", 2, 7, c -> {
                    shoot(c, StandProjectile.Kind.FIREBALL, 3.5D, -0.2D);
                    shoot(c, StandProjectile.Kind.FIREBALL, 3.5D, 0.0D);
                    shoot(c, StandProjectile.Kind.FIREBALL, 3.5D, 0.2D);
                    c.sound(SoundEvents.BLAZE_SHOOT, 1.2F, 0.8F);
                    return true;
                })
        };
    }

    // ================================================================== WARDEN (Legendary): sonic and brutality

    public static Ability[] warden() {
        return new Ability[]{
                ab("Dark Fist", "A devastating close-range blow.", 1, 0.9, c ->
                        melee(c, 9.0D, 1.4D, 0.3D, t -> {
                            c.fx(ParticleTypes.SCULK_SOUL, t.position().add(0.0D, 1.0D, 0.0D), 10, 0.4D, 0.1D);
                            c.sound(SoundEvents.WARDEN_ATTACK_IMPACT, 1.0F, 1.0F);
                        })),

                ab("Barrage", "Your Stand unleashes a storm of punches on everything in front of you, ending with a heavy blow.", 3, 8, c ->
                        barrage(c, 1.4D, 7.0D)),

                ab("Sonic Boom", "A piercing sonic beam (18 blocks) that ignores armor.", 5, 8, c -> {
                    Vec3 origin = c.shotOrigin();
                    Vec3 dir = c.aimPoint(30.0D).subtract(origin).normalize();
                    Vec3 end = c.beamEnd(origin, dir, 18.0D);
                    c.sound(SoundEvents.WARDEN_SONIC_CHARGE, 1.2F, 1.0F);
                    c.trail(ParticleTypes.SONIC_BOOM, origin, end, 2.0D);
                    for (LivingEntity e : c.beam(origin, dir, 18.0D, 1.0D)) {
                        c.hurtSonic(e, c.dmg(11.0D));
                        c.push(e, dir, 1.2D, 0.35D);
                    }
                    c.sound(SoundEvents.WARDEN_SONIC_BOOM, 1.5F, 1.0F);
                    return true;
                }),

                ab("Sonic Devastation", "Two shockwaves of sound tear through everything within 11 blocks.", 10, 55, c -> {
                    c.sound(SoundEvents.WARDEN_ROAR, 1.6F, 0.9F);
                    for (int wave = 0; wave < 2; wave++) {
                        final int w = wave;
                        c.later(6 + wave * 10, () -> {
                            Vec3 center = c.owner.position();
                            c.sound(SoundEvents.WARDEN_SONIC_BOOM, 1.8F, 0.8F + 0.2F * w);
                            c.ring(ParticleTypes.SONIC_BOOM, center.add(0.0D, 1.0D, 0.0D), 6.0D, 8);
                            c.fx(ParticleTypes.SCULK_CHARGE_POP, center.add(0.0D, 0.5D, 0.0D), 60, 4.0D, 0.2D);
                            for (LivingEntity e : c.enemies(center.add(0.0D, 1.0D, 0.0D), 11.0D)) {
                                c.hurtSonic(e, c.dmg(9.0D));
                                c.push(e, e.position().subtract(center), 1.6D, 0.4D);
                                c.effect(e, MobEffects.MOVEMENT_SLOWDOWN, 100, 1);
                            }
                        });
                    }
                    return true;
                }),

                ab("Stop Heart", "Like Jotaro against DIO: your Stand stops your heart. To everything around you, you're dead - mobs hunting you lose interest, nothing new comes for you, and everyone sees you die in chat. The first 5 seconds are free; after that your body fails fast - a heavy hit every second, a quarter of your health each, so about four and you're dead for real. Press it again (or strike something) to restart your heart with a huge thump and a rush of adrenaline. Lasts at most 30 seconds.", 7, 45,
                        HeartStop::use)
        };
    }

    // ================================================================== PHANTOM (Rare): flight and speed

    public static Ability[] phantom() {
        return new Ability[]{
                ab("Talon Swipe", "A fast slash with long reach.", 1, 0.55, c ->
                        melee(c, 4.5D, 0.4D, 0.2D, t -> c.fx(ParticleTypes.SOUL, t.position().add(0.0D, 1.0D, 0.0D), 8, 0.3D, 0.05D))),

                ab("Sky Rush", "Dash forward at high speed, slashing enemies in your path.", 2, 7, c -> {
                    Vec3 look = c.look();
                    c.owner.setDeltaMovement(look.scale(1.7D).add(0.0D, 0.15D, 0.0D));
                    c.owner.hurtMarked = true;
                    c.owner.fallDistance = 0.0F;
                    c.sound(SoundEvents.PHANTOM_SWOOP, 1.2F, 1.2F);
                    c.fx(ParticleTypes.CLOUD, c.owner.position().add(0.0D, 1.0D, 0.0D), 20, 0.4D, 0.1D);
                    for (LivingEntity e : c.cone(6.0D, 0.35D)) {
                        c.hurt(e, c.dmg(6.0D));
                        c.push(e, look, 0.8D, 0.3D);
                    }
                    return true;
                }),

                ab("Aerosmith", "Pilot your Phantom from afar like a fighter plane. You stay where you are and see through its eyes. "
                        + "Fly it like creative mode: WASD to move, Space up, Shift down, Ctrl to go faster. Right click (hold) to fire arrows. "
                        + "Press again to bring it back. It can fly up to 64 blocks from you.", 5, 1, c -> {
                    boolean on = !c.stand.isPiloting();
                    if (on) {
                        Vec3 at = c.owner.getEyePosition().add(c.owner.getLookAngle().scale(1.5D));
                        c.stand.setPos(at.x, at.y - 0.3D, at.z);
                        c.owner.displayClientMessage(net.minecraft.network.chat.Component.literal("Aerosmith! WASD + Space/Shift to fly, right click to fire, press again to land.")
                                .withStyle(s -> s.withColor(0x6F8CFF)), true);
                    }
                    c.stand.setPiloting(on);
                    if (on) StandManager.pilotReload(c.owner);
                    c.sound(on ? SoundEvents.PHANTOM_FLAP : SoundEvents.PHANTOM_SWOOP, 1.0F, on ? 0.8F : 1.2F);
                    return true;
                }),

                ab("Midnight Swarm", "A swarm of night terrors blinds and mauls enemies, heals you and grants Speed II.", 10, 45, c -> {
                    Vec3 center = c.owner.position();
                    c.sound(SoundEvents.PHANTOM_BITE, 1.4F, 0.7F);
                    c.sound(SoundEvents.PHANTOM_SWOOP, 1.4F, 0.6F);
                    c.ring(ParticleTypes.SOUL, center.add(0.0D, 1.0D, 0.0D), 3.0D, 30);
                    c.ring(ParticleTypes.SOUL_FIRE_FLAME, center.add(0.0D, 2.0D, 0.0D), 6.0D, 50);
                    c.ring(ParticleTypes.SMOKE, center.add(0.0D, 0.5D, 0.0D), 9.0D, 60);
                    int hits = 0;
                    for (LivingEntity e : c.enemies(center.add(0.0D, 1.0D, 0.0D), 10.0D)) {
                        c.hurt(e, c.dmg(9.0D));
                        c.effect(e, MobEffects.BLINDNESS, 80, 0);
                        c.effect(e, MobEffects.WEAKNESS, 100, 0);
                        hits++;
                    }
                    c.owner.heal(2.0F * Math.min(hits, 3));
                    c.owner.addEffect(new net.minecraft.world.effect.MobEffectInstance(MobEffects.MOVEMENT_SPEED, 160, 1));
                    return true;
                })
                ,
                ab("Dream Walk", "At night, look at a sleeping villager to slip into its nightmare: find its nightmare self in the fog and end it, and the villager wakes up grateful (big discounts). Villagers you've saved dream of a peaceful shared creative world instead. Press again to wake up. Nothing from a dream comes back with you.", DreamWalk.UNLOCK_LEVEL, 5, c ->
                        DreamWalk.use(c.owner))
        };
    }

    // ================================================================== SKELETON (Common): ranged

    public static Ability[] skeleton() {
        return new Ability[]{
                ab("Bone Arrow", "Draws its bow and looses an arrow at your aim point.", 1, 1.0, c -> {
                    // The Stand visibly draws the bow first; the arrow leaves on release.
                    c.data.doubleShotFumbled = false;
                    c.later(StandType.BOW_DRAW_TICKS, () -> {
                        shoot(c, StandProjectile.Kind.BONE_ARROW, 5.0D, 0.0D);
                        c.sound(SoundEvents.ARROW_SHOOT, 1.0F, 0.9F + c.owner.getRandom().nextFloat() * 0.2F);
                        c.sound(SoundEvents.SKELETON_SHOOT, 0.9F, 0.9F + c.owner.getRandom().nextFloat() * 0.2F);
                        // Open the Double Shot window: click again just after the release for a bonus arrow.
                        long now = c.level.getGameTime();
                        if (c.data.doubleShotFumbled) return;
                        c.data.doubleShotOpen = now + DOUBLE_SHOT_DELAY;
                        c.data.doubleShotClose = now + DOUBLE_SHOT_DELAY + DOUBLE_SHOT_WINDOW;
                    });
                    return true;
                }),

                ab("Rapid Fire", "Six arrows in quick succession.", 2, 8, c -> {
                    for (int i = 0; i < 6; i++) {
                        c.later(i * 3, () -> {
                            shoot(c, StandProjectile.Kind.BONE_ARROW, 3.0D, 0.0D);
                            c.sound(SoundEvents.ARROW_SHOOT, 0.8F, 1.3F);
                        });
                    }
                    return true;
                }),

                ab("Piercing Shot", "A charged shot that pierces every enemy in a line (30 blocks).", 4, 10, c -> {
                    Vec3 origin = c.shotOrigin();
                    Vec3 dir = c.aimPoint(40.0D).subtract(origin).normalize();
                    Vec3 end = c.beamEnd(origin, dir, 30.0D);
                    c.sound(SoundEvents.CROSSBOW_SHOOT, 1.4F, 0.8F);
                    c.trail(ParticleTypes.CRIT, origin, end, 0.5D);
                    c.trail(ParticleTypes.END_ROD, origin, end, 1.5D);
                    for (LivingEntity e : c.beam(origin, dir, 30.0D, 0.6D)) {
                        c.hurt(e, c.dmg(12.0D));
                        c.push(e, dir, 0.7D, 0.15D);
                    }
                    return true;
                }),

                ab("Arrow Storm", "Rain arrows from the sky onto your aim point for several seconds.", 8, 45, c -> {
                    Vec3 target = c.aimPoint(26.0D);
                    c.sound(SoundEvents.ARROW_SHOOT, 1.5F, 0.6F);
                    c.ring(ParticleTypes.CRIT, target.add(0.0D, 0.3D, 0.0D), 4.5D, 40);
                    for (int i = 0; i < 24; i++) {
                        c.later(i * 2, () -> {
                            RandomSource rnd = c.owner.getRandom();
                            double a = rnd.nextDouble() * Math.PI * 2.0D;
                            double r = Math.sqrt(rnd.nextDouble()) * 4.5D;
                            Vec3 ground = target.add(Math.cos(a) * r, 0.0D, Math.sin(a) * r);
                            Vec3 from = c.skyPoint(ground, 16.0D);
                            c.fire(StandProjectile.Kind.BONE_ARROW, from, new Vec3(0.0D, -1.0D, 0.0D), 4.5D);
                        });
                    }
                    return true;
                }),

                ab("Bone Cage", "Fires a bone at the creature you're looking at (30 blocks): a cage of bone blocks snaps shut around it. It can't get out for 5 seconds, then the bones crumble.", 1, 20, Signatures::boneCage)
        };
    }

    // ================================================================== SPIDER (Uncommon): webs, climbing, traps

    public static Ability[] spider() {
        return new Ability[]{
                ab("Fang Strike", "A venomous bite that poisons the target.", 1, 0.7, c ->
                        melee(c, 4.0D, 0.4D, 0.1D, t -> c.effect(t, MobEffects.POISON, 60, 0))),

                ab("Web Shot", "Fires a web that slows the target and entangles it in cobweb.", 2, 8, c -> {
                    shoot(c, StandProjectile.Kind.WEB, 1.5D, 0.0D);
                    c.sound(SoundEvents.SPIDER_AMBIENT, 1.0F, 1.4F);
                    return true;
                }),

                ab("Spider Climb", "For 8 seconds you can climb walls by pushing against them.", 5, 18, c -> {
                    c.data.climbUntil = c.level.getGameTime() + 160L;
                    c.sound(SoundEvents.SPIDER_AMBIENT, 1.0F, 0.8F);
                    c.fx(ParticleTypes.ITEM_COBWEB, c.owner.position().add(0.0D, 1.0D, 0.0D), 20, 0.5D, 0.05D);
                    return true;
                }),

                ab("Web Cocoon", "Entangle every enemy within 9 blocks: heavy slow, weakness, poison and cobwebs.", 9, 40, c -> {
                    Vec3 center = c.owner.position();
                    c.sound(SoundEvents.SPIDER_AMBIENT, 1.5F, 0.6F);
                    c.ring(ParticleTypes.ITEM_COBWEB, center.add(0.0D, 0.2D, 0.0D), 8.0D, 48);
                    for (LivingEntity e : c.enemies(center.add(0.0D, 1.0D, 0.0D), 9.0D)) {
                        c.hurt(e, c.dmg(5.0D));
                        c.effect(e, MobEffects.MOVEMENT_SLOWDOWN, 120, 4);
                        c.effect(e, MobEffects.WEAKNESS, 120, 0);
                        c.effect(e, MobEffects.POISON, 80, 0);
                        StandProjectile.placeWeb(c.level, e.blockPosition(), 120);
                    }
                    return true;
                }),

                ab("Silk Swing", "Shoot a web line at any block you're looking at (36 blocks) and swing up to it. Press again or sneak to let go early. You always land softly after a swing.", 1, 2.5, Signatures::silkSwing)
        };
    }

    // ================================================================== SLIME (Common): bounce and knockback

    // ================================================================== SQUID (Rare): November Rain

    /** Radius the Squid's rain hurts in (around you, under the squid). */
    public static final double RAIN_RADIUS = 4.2D;
    /** Radius the rain visibly falls in: straight down from the squid's body only. */
    public static final double RAIN_FALL_RADIUS = 3.8D;
    /** The Squid keeps its normal size while it rains. */
    public static final float RAIN_GROW = 1.0F;

    // ================================================================== AXOLOTL (Uncommon): plays dead for you

    /** How long the Axolotl "plays dead" after taking a lethal hit for you (also its recharge). */
    public static final int AXOLOTL_DEAD_TICKS = 400;

    // ================================================================== ALLAY: a flurry of light slashes

    /** When the two rushing Allays hit (ticks after the click). */
    public static final int ALLAY_HIT_1 = 4;
    /** Most Allays you can have. */
    public static final int MAX_ALLAYS = 4;

    /** Ticks between the rushing Allays' hits: quicker with more of them. */
    public static int allayGap(int allays) {
        return allays >= 3 ? 3 : 4;
    }

    /**
     * Hits after your Allay's own: each free duplicate flies in for one (so every Allay punches once). With no
     * duplicates out, a ghost Allay appears for the second hit instead.
     */
    public static int rushFollowUps(int freeAllays) {
        return Math.max(1, Math.min(MAX_ALLAYS, freeAllays) - 1);
    }

    /** How long the whole Allay Rush animation lasts. */
    public static int allayRushTicks(int allays) {
        return ALLAY_HIT_1 + Math.max(1, allays) * allayGap(allays) + 7;
    }

    public static Ability[] allay() {
        return new Ability[]{
                ab("Allay Rush", "Your Allay rams the enemy, then every free Allay you have rushes in for one more hit each.", 1, 1.0, c -> {
                    LivingEntity target = c.meleeTarget(6.0D);
                    Vec3 reach = target != null
                            ? target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D)
                            : c.owner.getEyePosition().add(c.look().scale(3.0D));
                    c.stand.lunge(reach, 5);
                    c.sound(SoundEvents.ALLAY_AMBIENT_WITHOUT_ITEM, 0.7F, 1.4F);
                    c.later(ALLAY_HIT_1, () -> { c.sound(com.mobstands.registry.ModSounds.COMBO_PUNCH_1.get(), 0.8F, 1.3F); punchHit(c, target, 1.8D, 0.25D, 0.08D, null); });
                    int n = rushFollowUps(c.data.freeAllays());
                    int gap = allayGap(n);
                    for (int k = 1; k <= n; k++) {
                        boolean last = k == n;
                        boolean odd = k % 2 == 1;
                        c.later(ALLAY_HIT_1 + k * gap, () -> {
                            c.sound((odd ? com.mobstands.registry.ModSounds.COMBO_PUNCH_2 : com.mobstands.registry.ModSounds.COMBO_PUNCH_1).get(), 0.8F, 1.3F);
                            punchHit(c, target, 1.2D, last ? 0.6D : 0.2D, last ? 0.2D : 0.06D, null);
                        });
                    }
                    return true;
                }),
                ab("Allay Lift", "Your Allay grabs you from above and lifts you up, then carries you like a glider. Hold forward to glide where you look; look up or down to climb or sink. More Allays: longer, faster and easier to steer.", 1, 12, c -> {
                    long now = c.level.getGameTime();
                    int n = Math.max(1, Math.min(MAX_ALLAYS, c.data.allayCount));
                    long until = now + liftTicks(n);
                    c.data.liftStart = now;
                    c.data.liftUntil = until;
                    c.stand.startLift((int) now, (int) until);
                    c.owner.resetFallDistance();
                    c.sound(SoundEvents.ALLAY_ITEM_TAKEN, 1.0F, 1.1F);
                    c.sound(SoundEvents.PHANTOM_FLAP, 0.6F, 1.6F);
                    return true;
                }),
                ab("Allay Hold", "Point at an enemy: one of your free Allays grabs it and holds it up in the air (great for bow shots). Use it on the same enemy again to send another Allay: each one adds 8 seconds and lifts it higher. Hold the key while looking at a held enemy to drag it around with your crosshair - swing your view down hard while dragging to SLAM it into the ground, hurting everything (and everyone) around the impact. Spam the key 3 times to make them let go (just the one you look at, or all of them).", 1, 0.4, c -> {
                    LivingEntity target = c.aim(24.0D);
                    if (target == null) return false;
                    StandManager.allayHold(c.owner, target);
                    return true;
                }),
                ab("Roam", "Toggle: your Allay leaves your side and flutters around freely. While it roams you can hit it to make it duplicate (up to 4 Allays; duplicates last 3 minutes).", 1, 0.5, c -> {
                    StandManager.allayRoamToggle(c.owner);
                    return true;
                }),

                ab("Lullaby", "Your Allay sings: every creature within 12 blocks falls asleep for 6 seconds - it can't move or come after you until something hits it. Players nearby just get very, very drowsy. Bosses don't listen.", 1, 25, Signatures::lullaby)};
    }

    /** How long each Allay holding an enemy keeps it up (ticks). */
    public static final int ALLAY_HOLD_TICKS = 160;
    /** How long a duplicate Allay lasts (ticks): 3 minutes. */
    public static final int ALLAY_DUPE_TICKS = 3600;

    /** How long the Allay carries you: 5 seconds with one Allay, +1 second per extra Allay. */
    public static int liftTicks(int allays) {
        return 80 + 20 * Math.max(1, allays);
    }
    /** Ticks of the initial lift before it turns into a glide. */
    public static final int LIFT_RISE_TICKS = 14;

    // ================================================================== PIG: Oink

    /** Chance each nearby monster is lured to the pig, and how long it stays distracted. */
    public static final float OINK_CHANCE = 0.3F;
    public static final int OINK_TICKS = 120;

    public static Ability[] sniffer() {
        return new Ability[]{
                ab("Snout Bump", "A heavy shove with its big snout.", 1, 0.9, c ->
                        melee(c, 3.0D, 1.4D, 0.3D, t -> c.sound(SoundEvents.SNIFFER_STEP, 1.0F, 0.8F))),
                ab("Sniff Out", "Costs a spyglass: like a camera, Hermit Purple's vines crush it, and the shards roll three random "
                        + "structures. Pick one and your Sniffer finds the nearest. Pass, and the next roll is in 10 minutes. "
                        + "Pass three times and its nose is trained: the next sniff lets you search for anything.", 1, 600,
                        c -> com.mobstands.stand.Sniff.use(c.owner)),
                ab("Thorn Vine", "Hermit Purple lashes out (16 blocks). On a creature: the thorns snare it and drag it to you, "
                        + "slowed. On a wall or the ground: the vines catch it and haul you there.", 2, 5, Hermit::vine),
                ab("Spirit Photograph", "Costs a spyglass. Crush it and the shards show you where something is - whatever's in "
                        + "your other hand: a block (nearest within 48), a spawn egg (nearest of that creature), any other item "
                        + "(nearest lying on the ground), or nothing: the nearest other player. You keep the photo, and the "
                        + "vines point the way until you get there.", 4, 20, Hermit::photograph),
                ab("Vine Sense", "The vines spread across the ground: every creature within 32 blocks glows for 10 seconds, "
                        + "even through walls.", 6, 30, Hermit::sense)};
    }

    public static Ability[] wardenRequiem() {
        return new Ability[]{
                Ability.none(), // it never lifts a finger: calamity does the work
                ab("Dark Arrival", "Turns on or off the darkness that falls on everyone nearby (everyone but you) when you summon "
                        + "Wonder of Deep. A deep sculk pulse: it's on. Sculk going quiet: it's off.", 1, 0.5, c -> {
                    c.data.wodDarkOff = !c.data.wodDarkOff;
                    if (c.data.wodDarkOff) c.sound(SoundEvents.SCULK_CLICKING_STOP, 1.2F, 0.6F);
                    else c.sound(SoundEvents.WARDEN_HEARTBEAT, 1.6F, 0.6F);
                    StandManager.sync(c.owner);
                    return true;
                }),
                Ability.none(), Ability.none(), Ability.none(), Ability.none()};
    }

    public static Ability[] dolphin() {
        return new Ability[]{
                ab("Fin Strike", "A quick slap of its tail fin.", 1, 0.9, c ->
                        melee(c, 6.0D, 1.2D, 0.25D, t -> c.sound(SoundEvents.DOLPHIN_ATTACK, 0.9F, 1.1F))),
                ab("Rain Call", "Your Dolphin calls the rain: 30 seconds of it (or more, if it's already raining). "
                        + "Everything else it does needs rain falling on you.", 1, 180, RainPowers::rainCall),
                ab("Rain Walk", "Toggle. In the rain, the drops under your feet stop in the air and hold you: walk and run "
                        + "on them. Jump to climb higher, sneak to drop. If the rain stops under you, you float down.", 1, 1.0, RainPowers::rainWalk),
                ab("Rain Dash", "Slip through the rain: a fast dash where you're looking, cutting what you pass.", 7, 4, RainPowers::rainDash)};
    }

    // ================================================================== SILVERFISH (Epic): Silver Fingers - Sticky Fingers

    public static Ability[] silverfish() {
        return new Ability[]{
                ab("Swarm Bite", "The swarm pours over the creature in front of you and bites, slowing it down.", 1, 0.7, c -> {
                    LivingEntity t = c.meleeTarget(6.0D);
                    Vec3 reach = t != null ? t.position().add(0.0D, t.getBbHeight() * 0.5D, 0.0D)
                            : c.owner.getEyePosition().add(c.look().scale(3.0D));
                    c.stand.lunge(reach, 6);
                    c.sound(SoundEvents.SILVERFISH_AMBIENT, 1.0F, 1.3F);
                    c.later(4, () -> {
                        if (t == null || !t.isAlive()) return;
                        t.invulnerableTime = 0;
                        c.hurt(t, c.dmg(4.0D));
                        c.effect(t, MobEffects.MOVEMENT_SLOWDOWN, 30, 0);
                        c.sound(SoundEvents.SILVERFISH_HURT, 0.9F, 1.4F);
                        c.fx(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK,
                                net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()), reach, 12, 0.3D, 0.05D);
                    });
                    return true;
                }),

                ab("Zipper Punch", "A punch that leaves a zipper on whatever it hits. It unzips - and the creature comes apart: "
                        + "a heavy hit, then Weakness and Slowness while it's open.", 2, 6, c -> {
                    LivingEntity t = c.meleeTarget(6.0D);
                    if (t == null) {
                        c.fail = "Nothing to hit.";
                        return false;
                    }
                    c.stand.lunge(t.position().add(0.0D, t.getBbHeight() * 0.5D, 0.0D), 5);
                    c.later(4, () -> {
                        if (!t.isAlive()) return;
                        t.invulnerableTime = 0;
                        c.hurt(t, c.dmg(6.0D));
                        c.push(t, t.position().subtract(c.owner.position()), 0.5D, 0.1D);
                        Zipper.zipMob(c, t);
                        Zipper.apart(c, t);
                        c.later(com.mobstands.entity.ZipperEntity.OPEN_TICKS, () -> {
                            if (!t.isAlive()) return;
                            t.invulnerableTime = 0;
                            c.hurt(t, c.dmg(4.0D));
                        });
                    });
                    return true;
                }),

                ab("Zip Away", "Look at a wall or the ground close by: a zipper runs down it and splits open onto a purple pocket "
                        + "dimension. You slide in and it zips shut behind you. In there nothing can see, find or hurt you - "
                        + "whatever was after you loses you. Press again to unzip and step back out.", 3, 4, Zipper::hide),

                ab("Zip Through", "Zip open a wall, floor or ceiling and come out the other side (up to 12 blocks thick).",
                        6, 12, Zipper::through)
        };
    }

    // ================================================================== ILLUSIONER (Epic): Smoke and Mirrors

    public static Ability[] illusioner() {
        return new Ability[]{
                ab("Phantom Arrow", "Your Stand draws its bow: a glowing arrow at whatever you're looking at - it lights up whatever it hits.", 1, 0.9, Illusion::arrow),
                ab("Mirror Images", "Four copies of you shimmer into being around you for 12 seconds. Each time something hits you there's a "
                        + "good chance it hits a copy instead - the copy bursts and the hit never lands. Four copies, four saves.", 2, 20, Illusion::mirror),
                ab("Blinding Spell", "Point at someone (24 blocks): your Stand casts blindness on them - they can't see, they're slowed, "
                        + "and a monster loses track of you.", 3, 12, Illusion::blind),
                ab("Vanish", "You disappear completely for 10 seconds - armour, items and all. Everything chasing you loses you. "
                        + "Attacking brings you back.", 5, 30, Illusion::vanish),
                ab("Mirror Maze", "Every creature within 14 blocks is lost in illusions for 8 seconds: blind, and seeing you in every "
                        + "face around it - so it attacks whatever's nearest. Other players get dizzy.", 7, 40, Illusion::maze)
        };
    }

    // ================================================================== LLAMA (Rare): Oh! Lonesome Llama - Mountain Tim

    public static Ability[] llama() {
        return new Ability[]{
                ab("Spit", "A glob of spit at whatever you're looking at: it stings and slows.", 1, 0.9, StandTools::spit),
                ab("Lone Rider", "Ride your rope: you're hauled to whatever you've lassoed and kick it - or, with nothing roped, lasso the "
                        + "block you're looking at and zip there.", 2, 6, StandTools::ride),
                ab("Oh! Lonesome Me", "The body on the end of your rope comes apart along it: a huge hit, then it's weak and can barely "
                        + "move. The rope's used up.", 4, 15, StandTools::lonesome),
                ab("Stampede", "A herd of phantom llamas charges out ahead of you, trampling and flinging everything in its path.", 6, 25, StandTools::stampede)
        };
    }

    // ================================================================== GUARDIAN (Rare): Reef Boy - Beach Boy

    public static Ability[] guardian() {
        return new Ability[]{
                ab("Guardian Laser", "A beam charges for a moment, then burns whatever you're looking at (16 blocks).", 1, 1.4, StandTools::laser),
                ab("Heartbeat", "You feel every heartbeat within 32 blocks: everything alive glows for 8 seconds, even through walls.", 2, 20, StandTools::heartbeat),
                ab("Deep Reel", "Whatever's on your hook is torn all the way to you and slammed down. The line's used up.", 4, 12, StandTools::deepReel),
                ab("Elder Curse", "Everyone within 16 blocks is weighed down by the deep for 10 seconds: slowed, weakened, too tired to mine.", 6, 30, StandTools::curse)
        };
    }

    // ================================================================== RAVAGER: Scary Monsters (Diego Brando)

    public static Ability[] scaryMonsters() {
        return new Ability[]{
                ab("Raptor Bite", "Your dinosaur lunges and snaps its jaws shut on what's in front of you.", 1, 0.9, c ->
                        melee(c, 7.0D, 1.2D, 0.2D, t -> c.sound(SoundEvents.RAVAGER_ATTACK, 0.9F, 1.1F))),
                ab("Stampede", "Every dinosaur in your herd charges the creature you're looking at, faster than they've ever "
                        + "run. They keep after it for 10 seconds - as long as it keeps moving.", 1, 12, ScaryMonsters::stampede),
                ab("Living Wall", "Toggle. The herd closes in around you in a ring and only goes for what comes close. "
                        + "The nearest dinosaur takes 70% of every hit meant for you.", 3, 1.0, ScaryMonsters::livingWall),
                ab("Call the Herd", "Every dinosaur comes back to you at once and roars, throwing back whatever is around it.",
                        5, 20, ScaryMonsters::callHerd)};
    }

    public static Ability[] shulker() {
        return new Ability[]{
                ab("Shell Strike", "Your Shulker pops its lid and strikes.", 1, 0.9, c ->
                        melee(c, 7.0D, 1.4D, 0.3D, t -> c.sound(SoundEvents.SHULKER_BULLET_HIT, 0.9F, 1.1F))),
                ab("Gravity Zone", "Mark out a sphere of turned gravity at your aim point for 15 seconds. Gravity inside points the way "
                        + "you were looking: look at a wall and anything inside (you included) falls onto that wall; look up and it falls "
                        + "into the sky. Look down for crushing gravity: pinned to the ground, can't jump. Step out and you fall normally again.", 1, 20, MoonPowers::zone),
                ab("Gravity Punch", "Hold the key and tap a direction: W away from you, A left, D right, Space up into the sky, "
                        + "S back toward you (just tapping the key = away). Your next punch turns that creature's gravity that way: it falls off toward "
                        + "it and has to live on whatever it lands on. Lasts 3 minutes.", 1, 8, c -> false),
                ab("Gravity Turn", "Hold the key and tap a direction: your own gravity turns right away. W the wall in front, A the left "
                        + "wall, D the right wall, Space the ceiling, S back to the normal floor (just tapping the key also puts you back).", 1, 0.25, c -> false),
                ab("Orbit", "Your Shulker becomes a tiny moon: up to 12 nearby enemies and dropped items are pulled into orbit around "
                        + "you. Press again to fling them all where you're looking (it hurts). After 8 seconds they fly off on their own.", 4, 18, MoonPowers::orbit)};
    }

    public static Ability[] pig() {
        return new Ability[]{
                Ability.none(),
                ab("Oink", "Your Pig trots off ahead of you, then oinks loudly. Each monster nearby has a 30% chance to forget you and go to the pig for 6 seconds.", 1, 8, c -> {
                    c.stand.pigSendOff(c.owner.position().add(c.flatLook().scale(6.0D)));
                    c.level.playSound(null, c.stand.getX(), c.stand.getY(), c.stand.getZ(), SoundEvents.PIG_STEP,
                            net.minecraft.sounds.SoundSource.PLAYERS, 0.8F, 1.0F);
                    return true;
                }),
                ab("Truffle Hunt", "Your pig roots around in the ground and digs something up: usually food or gold nuggets, sometimes emeralds or a diamond... and once in a long while, a Stand Arrow.", 1, 120, Signatures::truffleHunt),
                Ability.none()};
    }

    // ================================================================== PIGLINS (from a Pig Stand struck by lightning)

    public static Ability[] zombifiedPiglin() {
        return new Ability[]{
                ab("Rusted Thrust", "Pokes straight forward with its golden sword: a quick stab that pierces armor a little.", 1, 0.8, c -> {
                    c.sound(SoundEvents.PLAYER_ATTACK_SWEEP, 0.5F, 1.6F);
                    return melee(c, 6.5D, 0.6D, 0.1D, e -> {
                        Vec3 at = e.position().add(0.0D, e.getBbHeight() * 0.6D, 0.0D);
                        c.level.sendParticles(net.minecraft.core.particles.ParticleTypes.CRIT, at.x, at.y, at.z, 10, 0.1D, 0.1D, 0.1D, 0.4D);
                        c.sound(SoundEvents.TRIDENT_HIT, 0.8F, 1.3F);
                    });
                }),
                snatch(true), // ACT 2: armor only
                Ability.none(), Ability.none()};
    }

    public static Ability[] piglin() {
        return new Ability[]{
                ab("Gilded Jab", "A quick, proud punch.", 1, 0.65, c ->
                        melee(c, 5.0D, 0.8D, 0.25D, null)),
                snatch(false), // ACT 3: armor and whatever it's holding
                Ability.none(), Ability.none()};
    }

    // ================================================================== GILDED SILENCE (Piglin Requiem)

    public static Ability[] piglinRequiem() {
        return new Ability[]{
                ab("Brute Jab", "A quick, proud punch.", 1, 0.6, c ->
                        melee(c, 7.0D, 0.8D, 0.25D, null)),
                ab("Steal", "Look at a creature and choose what to take. What makes it what it is - a slime's bounce, a ghast's "
                        + "lightness, a creeper's volatility, an enderman's teleport... - or its Sound, are held by your Stand until you "
                        + "Release them. Or take its Sight, Friction, Heat, Water, Breath or Footing: it goes without for 10 seconds.",
                        1, 12, c -> PropertySwap.steal(c)),
                ab("Release", "Use what your Stand holds. A property goes onto whatever you look at (or yourself - a creeper's "
                        + "Volatility goes off where you're looking, as a creeper's blast). Teleport and "
                        + "Volatility make a mob hunt - if it isn't after anyone, press Release again on who it should go after. "
                        + "A sound is let loose as its power: a creeper's blast, a ghast's fireball, a warden's sonic boom...", 1, 1.5,
                        c -> PropertySwap.release(c)),
                ab("Another World", "Slip into the world next door with the mob you're looking at: the same place, but nobody else is there - "
                        + "every other mob around you is simply gone. Settle it one on one. When it dies (or after a minute, or if you walk "
                        + "too far, or you press it again) you slip back, and everyone you left behind is right where they were.", 1, 40, c -> OtherWorld.use(c)),
                ab("Null Bubble", "A bubble of something that doesn't exist drifts after the creature you look at - slowly, straight "
                        + "through walls, shields and armor - and pops inside it. Nothing softens it: it always leaves them at exactly half "
                        + "a heart (never a kill). Then they heal fast, back to half their health in just 2 seconds - unless they're "
                        + "hit again first. That's your window to reach them.", 1, 25,
                        NullBubble::use),
                Ability.none()
        };
    }

    // ================================================================== GUARDIAN EXPERIENCE REQUIEM (Iron Golem Requiem)

    public static Ability[] ger() {
        return new Ability[]{
                ab("Requiem Fist", "A heavy golden punch.", 1, 0.85, c ->
                        melee(c, 8.5D, 1.1D, 0.35D, null)),
                ab("MUDA Barrage", "Your Stand unleashes a golden storm of punches on everything in front of you, ending with a heavy blow.", 1, 7, c ->
                        barrage(c, 1.4D, 7.0D)),
                ab("Return to Zero", "A 2-second counter stance. If anything strikes you inside it, the attack never happens: it's dragged back to before it acted, and it dies - again and again, a different way each time - before it's lost in the loop forever. What it carried comes to you. Bosses: the attack is simply nullified... unless the boss is nearly dead. (Press X or Shift to skip the scene.)", 1, 12, c ->
                        Ger.returnToZero(c)),
                ab("Iron Shot", "GER holds up an iron nugget between two fingers... and flicks it. It flies so fast it leaves a blazing "
                        + "white-gold trail straight through whatever you're looking at - and it doesn't stop: it pierces every creature "
                        + "in its path (up to 64 blocks, or the first wall). Each one takes the huge hit and is hurled back.", 1, 10, GerShot::shoot),
                ab("Golden Barrier", "Like Love Train: a curtain of golden light falls from the sky over everything around you for 20 seconds. Nothing inside can be hurt by raiders or monsters, and they're pushed back out of the light.", 1, 90, c ->
                        Ger.goldenBarrier(c))
        };
    }

    /** The armor slots, best piece first. */
    private static final net.minecraft.world.entity.EquipmentSlot[] ARMOR_ORDER = {
            net.minecraft.world.entity.EquipmentSlot.CHEST, net.minecraft.world.entity.EquipmentSlot.HEAD,
            net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET};

    /**
     * Snatch. ACT 2 (Thunderborn Swine, {@code armorOnly}): rips off a piece of the mob's armor.
     * ACT 3 (Gilded Swine): takes what it's holding (its sword, bow...) first, otherwise a piece of its armor.
     */
    static Ability snatch(boolean armorOnly) {
        return
                ab("Snatch", armorOnly
                        ? "Your Stand darts at the mob you're looking at and rips off a piece of its armor (chestplate first, then helmet, leggings, boots) and hands it to you."
                        : "Your Piglin darts at the mob you're looking at and steals what it's holding (its sword, bow, crossbow...) - or, if its hands are empty, a piece of its armor - and hands it to you.", 1, 12, c -> {
                    LivingEntity target = c.aim(14.0D);
                    if (!(target instanceof net.minecraft.world.entity.Mob mob)) {
                        c.owner.displayClientMessage(net.minecraft.network.chat.Component.literal("Look at a mob to steal from."), true);
                        return false;
                    }
                    net.minecraft.world.entity.EquipmentSlot pick = null;
                    if (!armorOnly) {
                        pick = !mob.getMainHandItem().isEmpty() ? net.minecraft.world.entity.EquipmentSlot.MAINHAND
                                : !mob.getOffhandItem().isEmpty() ? net.minecraft.world.entity.EquipmentSlot.OFFHAND : null;
                    }
                    if (pick == null) {
                        for (net.minecraft.world.entity.EquipmentSlot a : ARMOR_ORDER) {
                            if (!mob.getItemBySlot(a).isEmpty()) {
                                pick = a;
                                break;
                            }
                        }
                    }
                    final net.minecraft.world.entity.EquipmentSlot slot = pick;
                    if (slot == null) {
                        c.owner.displayClientMessage(net.minecraft.network.chat.Component.literal(armorOnly ? "It has no armor to steal." : "It has nothing to steal."), true);
                        return false;
                    }
                    c.sound(SoundEvents.PIGLIN_ADMIRING_ITEM, 1.0F, 1.2F);
                    c.stand.snatch(mob, () -> {
                        if (!mob.isAlive()) return;
                        net.minecraft.world.item.ItemStack loot = mob.getItemBySlot(slot);
                        if (loot.isEmpty()) return;
                        mob.setItemSlot(slot, net.minecraft.world.item.ItemStack.EMPTY);
                        mob.setDropChance(slot, 0.0F);
                        Vec3 from = mob.position().add(0.0D, mob.getBbHeight() * 0.6D, 0.0D);
                        Vec3 to = c.owner.position().add(0.0D, 1.0D, 0.0D);
                        // A golden streak from the mob back to you, carrying the item.
                        for (int i = 0; i <= 12; i++) {
                            Vec3 p = from.add(to.subtract(from).scale(i / 12.0D));
                            c.level.sendParticles(new net.minecraft.core.particles.ItemParticleOption(net.minecraft.core.particles.ParticleTypes.ITEM, loot),
                                    p.x, p.y, p.z, 1, 0.05D, 0.05D, 0.05D, 0.0D);
                            c.level.sendParticles(net.minecraft.core.particles.ParticleTypes.WAX_ON, p.x, p.y, p.z, 1, 0.05D, 0.05D, 0.05D, 0.0D);
                        }
                        c.level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.ITEM_PICKUP, net.minecraft.sounds.SoundSource.PLAYERS, 0.8F, 0.8F);
                        c.sound(SoundEvents.PIGLIN_CELEBRATE, 1.0F, 1.0F);
                        if (!c.owner.getInventory().add(loot)) c.owner.drop(loot, false);
                        RequiemTrials.onSnatch(c.owner, mob);
                        c.owner.displayClientMessage(net.minecraft.network.chat.Component.literal("Stole " + loot.getHoverName().getString() + "!"), true);
                    });
                    return true;
                });
    }

    public static Ability[] axolotl() {
        return new Ability[]{
                // Passive: shown on the HUD with its recharge, can't be activated by hand.
                new Ability("Play Dead", "When a hit would kill you, your Axolotl dives in front of you, takes it and plays dead for 20 seconds. You get a little Regeneration.",
                        1, AXOLOTL_DEAD_TICKS, c -> false),
                ab("Soul Swap", "Look at a mob and trade lives with it: your health and its health swap places (as a share of each one's maximum). Nearly dead against a healthy mob? Now it's the one nearly dead. Swap with something worse off than you and it backfires. Doesn't work on bosses.", 3, 30, c ->
                        AxolotlPowers.soulSwap(c)),
                ab("Soul Control", "Slip your soul into the mob you're looking at and take over its body: see through its eyes, "
                        + "walk it with WASD, jump with Space, attack with left click, and right click to use its own power: Creepers explode, Wardens Sonic Boom (in darkness), Skeletons shoot, Blazes throw fire, Endermen teleport and more. Fliers go up with Space and down with Shift. Walk it off a cliff or into lava - when its body dies, "
                        + "your soul snaps back into yours. You can stay 2 seconds at first, 2 more each level, up to 10 seconds at level 10. Press again to come back any time. Your own body stands there defenceless meanwhile.", 6, 40, c ->
                        SoulControl.use(c)),
                ab("Lazarus", "Your Axolotl smiles, and the dead nearby get up again: players who died within 16 blocks of you in "
                        + "the last minute and haven't pressed Respawn come back where they fell (half health, some Regeneration), and pets "
                        + "and peaceful creatures that died nearby come back whole. Monsters stay dead.", 8, 90, c -> Lazarus.use(c))
        };
    }

    /** Squid's Tidal Slam: how long it charges before crashing down (ticks). */
    public static final int SQUID_SLAM_CHARGE = 30;

    public static Ability[] squid() {
        return new Ability[]{
                ab("Tidal Slam", "Your Squid rises high and gathers water for a long moment (1.5 s), then crashes down on the spot you're "
                        + "looking at like an anvil: everything close takes a solid hit, is knocked flat and slowed. Long cooldown.", 1, 12, c -> {
                    LivingEntity target = c.aim(18.0D);
                    Vec3 spot = target != null ? target.position() : c.aimPoint(18.0D);
                    c.sound(SoundEvents.SQUID_SQUIRT, 1.2F, 0.5F);
                    c.sound(SoundEvents.AMBIENT_UNDERWATER_ENTER, 1.2F, 0.6F);
                    // The charge: water gathers into a spinning column over the spot, louder and tighter as it builds.
                    for (int t = 0; t < SQUID_SLAM_CHARGE; t += 3) {
                        final int k = t;
                        c.later(t, () -> {
                            Vec3 at = target != null && target.isAlive() ? target.position() : spot;
                            double r = 2.2D - 1.6D * k / SQUID_SLAM_CHARGE;
                            c.ring(ParticleTypes.SPLASH, at.add(0.0D, 4.0D - 3.0D * k / SQUID_SLAM_CHARGE, 0.0D), r, 16);
                            c.fx(ParticleTypes.FALLING_WATER, at.add(0.0D, 5.0D, 0.0D), 8, 0.6D, 0.0D);
                            if (k % 9 == 0) c.sound(SoundEvents.WATER_AMBIENT, 1.0F, 0.6F + k * 0.03F);
                        });
                    }
                    c.later(SQUID_SLAM_CHARGE, () -> {
                        Vec3 at = target != null && target.isAlive() ? target.position() : spot;
                        // CRASH: a loud anvil and a tolling bell.
                        c.level.playSound(null, at.x, at.y, at.z, SoundEvents.ANVIL_LAND, net.minecraft.sounds.SoundSource.PLAYERS, 2.5F, 0.6F);
                        c.level.playSound(null, at.x, at.y, at.z, SoundEvents.BELL_BLOCK, net.minecraft.sounds.SoundSource.PLAYERS, 3.0F, 0.6F);
                        c.level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_SPLASH, net.minecraft.sounds.SoundSource.PLAYERS, 2.0F, 0.6F);
                        c.fx(ParticleTypes.SPLASH, at.add(0.0D, 0.3D, 0.0D), 80, 1.6D, 0.4D);
                        c.fx(ParticleTypes.BUBBLE_POP, at.add(0.0D, 0.5D, 0.0D), 30, 1.2D, 0.1D);
                        c.ring(ParticleTypes.CLOUD, at.add(0.0D, 0.2D, 0.0D), 3.0D, 28);
                        for (LivingEntity e : c.enemies(at, 3.5D)) {
                            double d = e.position().distanceTo(at);
                            float dmg = c.dmg(3.5D) * (float) Math.max(0.5D, 1.0D - d / 4.0D); // (was 9: it one-tapped people)
                            c.hurt(e, dmg);
                            c.push(e, e.position().subtract(at), 0.6D, 0.5D);
                            c.effect(e, net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN, 60, 2);
                        }
                    });
                    return true;
                }),

                ab("November Rain", "Toggle: your Squid rains on the area under it. Enemies who stay in it for 3 seconds get hit by a relentless stream of small hits that slowly grows; leaving resets them. Drains your hunger a bit faster while it's on.", 2, 0.5, c -> {
                    long now = c.level.getGameTime();
                    c.data.rainOn = !c.data.rainOn;
                    Vec3 sq = c.stand.position();
                    if (c.data.rainOn) {
                        // Opens up: a burst of spray out of the squid, then the rain starts.
                        c.level.sendParticles(net.minecraft.core.particles.ParticleTypes.SPLASH, sq.x, sq.y + 0.3D, sq.z, 40, 0.8D, 0.3D, 0.8D, 0.3D);
                        c.level.sendParticles(net.minecraft.core.particles.ParticleTypes.BUBBLE_POP, sq.x, sq.y + 0.5D, sq.z, 15, 0.6D, 0.4D, 0.6D, 0.05D);
                        c.sound(SoundEvents.SQUID_SQUIRT, 1.0F, 0.8F);
                        c.sound(SoundEvents.WEATHER_RAIN_ABOVE, 1.2F, 0.8F);
                    } else {
                        // Closes up: a little puff of ink and the rain stops.
                        if (c.data.surgeUntil <= now) c.data.rainUntil = 0L;
                        c.level.sendParticles(net.minecraft.core.particles.ParticleTypes.SQUID_INK, sq.x, sq.y + 0.4D, sq.z, 12, 0.4D, 0.2D, 0.4D, 0.02D);
                        c.sound(SoundEvents.SQUID_AMBIENT, 1.0F, 1.2F);
                    }
                    return true;
                }),

                ab("Downpour", "For 8 seconds the rain turns into a hammering downpour: it doesn't hurt, but it pins every enemy under it to the spot and beats projectiles into the ground.", 5, 22, c -> {
                    long now = c.level.getGameTime();
                    c.data.rainUntil = Math.max(c.data.rainUntil, now + 160L);
                    c.data.surgeUntil = now + 160L;
                    c.sound(SoundEvents.WEATHER_RAIN_ABOVE, 2.0F, 0.6F);
                    c.sound(SoundEvents.AMBIENT_UNDERWATER_ENTER, 1.0F, 0.8F);
                    return true;
                }),

                ab("Rain Shot", "Your Squid tips itself over sideways and fires a stream of hard rain straight where you're aiming for 2 seconds: rapid small hits that push them back and slow them. Free: no cooldown - fire again as soon as it ends.", 1, 2.4, c -> {
                    LivingEntity target = c.aim(20.0D);
                    c.sound(SoundEvents.SQUID_SQUIRT, 1.2F, 0.7F);
                    // Tilt first (the animation), then the barrage.
                    for (int i = RAIN_SHOT_TILT; i < RAIN_SHOT_TICKS; i += 2) {
                        final int tick = i;
                        c.later(i, () -> rainShotTick(c, target, tick));
                    }
                    return true;
                }),

                ab("Drift", "Steer your Squid yourself: you see through it and float it around with WASD, Space up, Shift down, Ctrl faster - "
                        + "but only close by (up to 8 blocks from you). All its other moves still work while you steer it, so you can park "
                        + "November Rain or Downpour right where you want them. Press again to call it back.", 4, 1, c -> {
                    boolean on = !c.stand.isPiloting();
                    if (on) {
                        Vec3 at = c.owner.getEyePosition().add(c.owner.getLookAngle().scale(1.5D));
                        c.stand.setPos(at.x, at.y - 0.3D, at.z);
                        c.owner.displayClientMessage(net.minecraft.network.chat.Component.literal("Drift: WASD + Space/Shift to steer your Squid (8 blocks), press again to call it back.")
                                .withStyle(s -> s.withColor(0x5B8FD6)), true);
                    }
                    c.stand.setPiloting(on);
                    c.sound(on ? SoundEvents.SQUID_SQUIRT : SoundEvents.SQUID_AMBIENT, 1.0F, on ? 0.8F : 1.1F);
                    c.fx(ParticleTypes.BUBBLE_POP, c.stand.position().add(0.0D, 0.5D, 0.0D), 12, 0.3D, 0.05D);
                    return true;
                })
        };
    }

    /** Rain Shot: ticks the squid takes to tip over, and how long the whole thing lasts. */
    public static final int RAIN_SHOT_TILT = 8, RAIN_SHOT_TICKS = 48;
    public static final double RAIN_SHOT_RANGE = 14.0D;

    /** One pulse of the rain barrage: a stream of drops from the squid toward the target (or where you look). */
    private static void rainShotTick(AbilityContext c, LivingEntity target, int tick) {
        if (c.stand == null || !c.stand.isAlive() || !c.owner.isAlive()) return;
        Vec3 from = c.stand.position().add(0.0D, 0.9D, 0.0D);
        // Aim: the mob you were looking at (while it lives), otherwise whatever your crosshair is on right now - so you
        // can sweep the stream around. (It used to fire along your look direction but from the Squid, which floats
        // above your head - a parallel line well above where you were aiming, hitting nothing.)
        LivingEntity lock = target != null && target.isAlive() && target.distanceTo(c.stand) < RAIN_SHOT_RANGE ? target : c.aim(RAIN_SHOT_RANGE);
        Vec3 aimAt = lock != null ? lock.position().add(0.0D, lock.getBbHeight() * 0.5D, 0.0D) : c.aimPoint(RAIN_SHOT_RANGE);
        Vec3 dir = aimAt.subtract(from);
        // The Squid turns to face it (and keeps facing it, so its tentacles point where the water goes).
        c.stand.setFaceYaw((float) Math.toDegrees(Math.atan2(-dir.x, dir.z)));
        if (dir.lengthSqr() < 1.0E-4D) return;
        dir = dir.normalize();
        // Stops at the first wall.
        net.minecraft.world.phys.BlockHitResult wall = c.level.clip(new net.minecraft.world.level.ClipContext(from, from.add(dir.scale(RAIN_SHOT_RANGE)),
                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, c.owner));
        double len = wall.getType() == net.minecraft.world.phys.HitResult.Type.MISS ? RAIN_SHOT_RANGE : wall.getLocation().distanceTo(from);
        // The drops: a tight, straight stream of water with splashes at the end.
        for (double d = 0.6D; d < len; d += 0.3D) {
            Vec3 p = from.add(dir.scale(d));
            double j = 0.02D + d * 0.004D;
            c.level.sendParticles(net.minecraft.core.particles.ParticleTypes.FALLING_WATER, p.x, p.y, p.z, 1, j, j, j, 0.0D);
            if (c.owner.getRandom().nextInt(3) == 0) c.level.sendParticles(net.minecraft.core.particles.ParticleTypes.SPLASH, p.x, p.y, p.z, 1, j, j, j, 0.05D);
        }
        Vec3 end = from.add(dir.scale(len));
        c.level.sendParticles(net.minecraft.core.particles.ParticleTypes.SPLASH, end.x, end.y, end.z, 6, 0.2D, 0.2D, 0.2D, 0.2D);
        if (tick % 6 == 0) c.sound(SoundEvents.WEATHER_RAIN, 0.7F, 1.5F);
        if (tick % 4 != 0) return;
        // Every other pulse: hits whoever is in the stream.
        for (LivingEntity e : c.level.getEntitiesOfClass(LivingEntity.class, new net.minecraft.world.phys.AABB(from, end).inflate(1.0D),
                x -> x != c.owner && x.isAlive() && StandCombat.valid(c.owner, x))) {
            Vec3 center = e.position().add(0.0D, e.getBbHeight() * 0.5D, 0.0D);
            double along = center.subtract(from).dot(dir);
            if (along < 0.0D || along > len) continue;
            if (from.add(dir.scale(along)).distanceTo(center) > e.getBbWidth() * 0.5D + 0.6D) continue;
            e.invulnerableTime = 0;
            StandCombat.hurt(c.owner, e, c.dmg(1.2D));
            if (e instanceof net.minecraft.world.entity.monster.EnderMan) {
                // Water burns Endermen: the rain stream hits them extra hard.
                e.invulnerableTime = 0;
                e.hurt(c.level.damageSources().drown(), 2.0F);
            }
            e.setDeltaMovement(e.getDeltaMovement().add(dir.x * 0.25D, 0.03D, dir.z * 0.25D));
            c.level.sendParticles(net.minecraft.core.particles.ParticleTypes.SPLASH, center.x, center.y, center.z, 8, 0.25D, 0.25D, 0.25D, 0.1D);
            e.hurtMarked = true;
            e.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN, 40, 0));
            e.clearFire();
        }
    }

    /** Green Temperance (Slime): Yellow Temperance, in slime green. */
    public static Ability[] slime() {
        return new Ability[]{
                ab("Devour Touch", "The goo bites and clings: a hit, then it keeps eating the creature for a few seconds.", 1, 0.8, Goo::touch),
                ab("Temperance Armor", "Toggle: wear the goo over your whole body. Hits barely get through (about a third; half for fire "
                        + "and explosions), and anything that punches you gets the goo on it and starts being eaten - and passes it on "
                        + "to whatever it touches. It's always hungry, though: you get hungry faster.", 2, 1, Goo::toggleArmor),
                ab("Masquerade", "Look at a creature: the goo takes its shape. Everyone sees that creature instead of you, and monsters "
                        + "look straight past you, for a minute - or until you hit something. Press again to drop it.", 3, 15, Goo::disguise),
                ab("Devour", "The goo leaps off you onto the creature you're looking at (14 blocks) and swallows it: it's eaten alive "
                        + "for 6 seconds, barely able to move - and anything that touches it gets eaten too.", 6, 25, Goo::devour)
        };
    }
}
