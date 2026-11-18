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
            case WARDEN -> c.sound(SoundEvents.WARDEN_ATTACK_IMPACT, 0.9F, p);
            case ENDERMAN, ENDER_WORLD -> c.sound(SoundEvents.ENDERMAN_HURT, 0.4F, 1.3F);
            case CREEPER -> c.sound(SoundEvents.CREEPER_HURT, 0.7F, p);
            case SPIDER -> c.sound(SoundEvents.SPIDER_AMBIENT, 0.8F, 1.2F);
            case PHANTOM -> c.sound(SoundEvents.PHANTOM_BITE, 0.8F, p);
            case SQUID -> c.sound(SoundEvents.SQUID_HURT, 0.8F, p);
            case PIGLIN -> c.sound(SoundEvents.PIGLIN_ANGRY, 0.6F, p);
            case ALLAY -> c.sound(SoundEvents.ALLAY_HURT, 0.6F, 1.3F);
            case ZOMBIFIED_PIGLIN -> c.sound(SoundEvents.ZOMBIFIED_PIGLIN_ANGRY, 0.6F, p);
            default -> { }
        }
    }

    private static void punchHit(AbilityContext c, LivingEntity target, double dmg, double knock, double up,
                                 Consumer<LivingEntity> onHit) {
        if (target != null) {
            if (!target.isAlive()) return;
            target.invulnerableTime = 0;
            c.hurt(target, c.dmg(dmg));
            c.push(target, target.position().subtract(c.owner.position()), knock, up);
            mobHitSound(c); // only when it actually connects
            if (onHit != null) onHit.accept(target);
        } else if (comboHit(c, dmg, knock, up) > 0) {
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
            c.push(e, look, knock, up);
            n++;
        }
        return n;
    }

    /** Tick (after the click) a regular punch connects. A second click before this makes it a double. */
    public static final int PUNCH_HIT_TICK = 4;

    /** Ticks a Barrage lasts (the Stand stays out in front, punching, for this long). */
    public static final int BARRAGE_TICKS = 32;

    /**
     * ORA barrage: the Stand plants itself in front of you and pummels everything in a short cone,
     * a hit every 2 ticks, then finishes with one heavy blow that sends them flying.
     */
    private static boolean barrage(AbilityContext c, double perHit, double finisher) {
        LivingEntity focus = c.meleeTarget(6.0D);
        Vec3 look = c.flatLook();
        Vec3 aim = focus != null
                ? focus.position().add(0.0D, focus.getBbHeight() * 0.5D, 0.0D).subtract(look.scale(1.3D))
                : c.owner.position().add(look.scale(1.8D)).add(0.0D, 1.0D, 0.0D);
        c.stand.lunge(aim, BARRAGE_TICKS);
        c.sound(SoundEvents.PLAYER_ATTACK_SWEEP, 1.0F, 0.6F);
        final int hits = 14;
        for (int i = 0; i < hits; i++) {
            final int hitIndex = i;
            c.later(2 + i * 2, () -> {
                Vec3 center = c.stand.position().add(look.scale(1.2D)).add(0.0D, 1.0D, 0.0D);
                boolean connected = false;
                for (LivingEntity e : c.enemies(center, 2.6D)) {
                    e.invulnerableTime = 0;
                    c.hurt(e, c.dmg(perHit));
                    c.push(e, look, 0.06D, 0.02D);
                    connected = true;
                }
                if (connected && hitIndex % 3 == 0) mobHitSound(c);
                c.sound(SoundEvents.PLAYER_ATTACK_KNOCKBACK, 0.45F, 1.1F + c.owner.getRandom().nextFloat() * 0.5F);
            });
        }
        c.later(BARRAGE_TICKS - 1, () -> {
            Vec3 center = c.stand.position().add(look.scale(1.2D)).add(0.0D, 1.0D, 0.0D);
            boolean connected = false;
            for (LivingEntity e : c.enemies(center, 2.8D)) {
                e.invulnerableTime = 0;
                c.hurt(e, c.dmg(finisher));
                c.push(e, look, 1.5D, 0.4D);
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

    public static Ability[] creeper() {
        return new Ability[]{
                ab("Fuse Punch", "A quick punch that leaves crackling sparks.", 1, 0.7, c ->
                        melee(c, 4.0D, 0.5D, 0.15D, t -> c.fx(ParticleTypes.ELECTRIC_SPARK, t.position().add(0.0D, 1.0D, 0.0D), 12, 0.4D, 0.1D))),

                ab("Hissing Burst", "Your Stand swells and bursts, blasting nearby enemies. You are never hurt by it.", 2, 12, c -> {
                    c.sound(SoundEvents.CREEPER_PRIMED, 1.2F, 1.0F);
                    c.fx(ParticleTypes.HAPPY_VILLAGER, c.stand.position().add(0.0D, 1.0D, 0.0D), 20, 0.6D, 0.05D);
                    c.later(12, () -> c.explode(c.stand.position().add(0.0D, 1.0D, 0.0D), 1.6D));
                    return true;
                }),

                ab("Kamikaze Rush", "Your Stand dashes to your aim point and detonates itself. It reforms after a few seconds.", 5, 18, c -> {
                    Vec3 target = c.aimPoint(14.0D);
                    c.stand.lunge(target, 10);
                    c.sound(SoundEvents.CREEPER_PRIMED, 1.4F, 1.4F);
                    c.later(10, () -> {
                        c.explode(c.stand.position().add(0.0D, 1.0D, 0.0D), 2.6D);
                        c.stand.reform(70);
                    });
                    return true;
                }),

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
                }),
                ab("Heart Attack", "A little creeper in a little minecart rolls out and hunts down whoever you're looking at (or the nearest monster). It can't be hurt and it doesn't stop: it blows up on its victim, then rolls on after the next one, for 20 seconds. Its blasts never hurt you or break blocks.", 10, 45, c ->
                        HeartAttack.release(c))
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

                ab("Void Collapse", "Rip a hole in space: enemies are dragged to your aim point, damaged and weakened.", 12, 40, c -> {
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
                })
        };
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
                    c.owner.teleportTo(dest.x, dest.y, dest.z);
                    if (t != null) {
                        Vec3 look = t.position().subtract(dest);
                        float yaw = (float) (Math.atan2(look.z, look.x) * 57.2957763671875D) - 90.0F;
                        c.owner.connection.teleport(dest.x, dest.y, dest.z, yaw, c.owner.getXRot());
                    }
                    c.owner.fallDistance = 0.0F;
                    c.sound(SoundEvents.ENDERMAN_TELEPORT, 0.8F, 1.6F);
                    c.sound(SoundEvents.NOTE_BLOCK_HAT.value(), 1.0F, 1.8F);
                    c.sound(SoundEvents.BELL_BLOCK, 1.0F, 1.4F);
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
                ab("Rail Rider", "Hop in and drive your Minecart anywhere, no rails needed: rails fade in under you and vanish behind you. W to speed up, S to brake, steer with your mouse, Shift (or Z again) to hop out.", 1, 0.5, c -> {
                    StandManager.cartToggle(c.owner);
                    return true;
                }),
                ab("Powered Rails", "Golden powered rails with redstone underneath lay themselves under you: a steady extra burst of speed that lasts a long time (12 s).", 3, 20, c ->
                        StandManager.cartBoost(c.owner, 1)),
                ab("Nitro", "Light the cart up: flames roar out the back and you blast forward far faster than anything else - but it burns out quickly (3 s).", 6, 25, c ->
                        StandManager.cartBoost(c.owner, 2))
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
                })
        };
    }

    // ================================================================== BLAZE (Rare): fire and ranged

    public static Ability[] blaze() {
        return new Ability[]{
                ab("Rod Strike", "Jabs one of its burning rods into the target, setting it on fire.", 1, 0.7, c ->
                        melee(c, 4.5D, 0.5D, 0.15D, t -> t.setRemainingFireTicks(Math.max(t.getRemainingFireTicks(), 60)))),

                ab("Flame Barrage", "Three fireballs in a fan.", 2, 7, c -> {
                    shoot(c, StandProjectile.Kind.FIREBALL, 3.5D, -0.2D);
                    shoot(c, StandProjectile.Kind.FIREBALL, 3.5D, 0.0D);
                    shoot(c, StandProjectile.Kind.FIREBALL, 3.5D, 0.2D);
                    c.sound(SoundEvents.BLAZE_SHOOT, 1.2F, 0.8F);
                    return true;
                }),

                ab("Ring of Fire", "A ring of flames erupts around you, burning and repelling enemies.", 5, 14, c -> {
                    Vec3 center = c.owner.position();
                    c.sound(SoundEvents.FIRECHARGE_USE, 1.2F, 0.8F);
                    c.ring(ParticleTypes.FLAME, center.add(0.0D, 0.2D, 0.0D), 2.5D, 32);
                    c.ring(ParticleTypes.FLAME, center.add(0.0D, 0.5D, 0.0D), 4.5D, 56);
                    for (LivingEntity e : c.enemies(center.add(0.0D, 1.0D, 0.0D), 4.5D)) {
                        c.hurt(e, c.dmg(4.0D));
                        e.setRemainingFireTicks(Math.max(e.getRemainingFireTicks(), 100));
                        c.push(e, e.position().subtract(center), 0.7D, 0.2D);
                    }
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

                ab("Sonic Devastation", "Two shockwaves of sound tear through everything within 11 blocks.", 14, 55, c -> {
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
                })
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

                ab("Wing Lift", "Soar into the sky and glide gently back down.", 5, 12, c -> {
                    Vec3 look = c.flatLook();
                    c.owner.setDeltaMovement(look.x * 0.5D, 1.05D, look.z * 0.5D);
                    c.owner.hurtMarked = true;
                    c.owner.fallDistance = 0.0F;
                    c.owner.addEffect(new net.minecraft.world.effect.MobEffectInstance(MobEffects.SLOW_FALLING, 280, 0));
                    c.sound(SoundEvents.PHANTOM_FLAP, 1.5F, 0.9F);
                    c.fx(ParticleTypes.SOUL_FIRE_FLAME, c.owner.position().add(0.0D, 0.5D, 0.0D), 30, 0.5D, 0.1D);
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
                })
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
                })
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
                ab("Allay Hold", "Point at an enemy: one of your free Allays grabs it and holds it up in the air (great for bow shots). Use it on the same enemy again to send another Allay: each one adds 8 seconds and lifts it higher. Hold the key to make them let go (just the one you look at, or all of them).", 1, 0.4, c -> {
                    LivingEntity target = c.aim(24.0D);
                    if (target == null) return false;
                    StandManager.allayHold(c.owner, target);
                    return true;
                }),
                ab("Roam", "Toggle: your Allay leaves your side and flutters around freely. While it roams you can hit it to make it duplicate (up to 4 Allays; duplicates last 3 minutes).", 1, 0.5, c -> {
                    StandManager.allayRoamToggle(c.owner);
                    return true;
                })};
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

    public static Ability[] pig() {
        return new Ability[]{
                Ability.none(),
                ab("Oink", "Your Pig trots off ahead of you, then oinks loudly. Each monster nearby has a 30% chance to forget you and go to the pig for 6 seconds.", 1, 8, c -> {
                    c.stand.pigSendOff(c.owner.position().add(c.flatLook().scale(6.0D)));
                    c.level.playSound(null, c.stand.getX(), c.stand.getY(), c.stand.getZ(), SoundEvents.PIG_STEP,
                            net.minecraft.sounds.SoundSource.PLAYERS, 0.8F, 1.0F);
                    return true;
                }),
                Ability.none(), Ability.none()};
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
                Ability.none(), Ability.none(), Ability.none()};
    }

    public static Ability[] piglin() {
        return new Ability[]{
                ab("Gilded Jab", "A quick, proud punch.", 1, 0.65, c ->
                        melee(c, 5.0D, 0.8D, 0.25D, null)),
                snatch(),
                Ability.none(), Ability.none()};
    }

    // ================================================================== GILDED SILENCE (Piglin Requiem)

    public static Ability[] piglinRequiem() {
        return new Ability[]{
                ab("Gilded Jab", "A quick, proud punch.", 1, 0.6, c ->
                        melee(c, 7.0D, 0.8D, 0.25D, null)),
                ab("Steal Sound", "Your Stand lunges at the mob you're looking at and tears the sound right out of it - a sculk vibration carries it back to you. The mob goes silent for a minute, and loses what its sound did: a creeper can't explode, archers' shots fizzle. You can hold 3 sounds. Look at the sky in a thunderstorm to steal the thunder.", 1, 6, c ->
                        SoundSteal.steal(c)),
                ab("Switch Sound", "Cycle which of your stolen sounds is ready.", 1, 0.3, c ->
                        SoundSteal.cycle(c)),
                ab("Release Sound", "Unleash the ready sound and the power that came with it: a creeper's explosion, a ghast's fireball, a warden's sonic boom, a blaze's fire, an enderman's teleport, a skeleton's volley, a goat's ram, a wolf's growl, a bee's sting, a cow's moo (clears your bad effects), thunder... Any other sound becomes a decoy that pulls mobs to it.", 1, 1.5, c ->
                        SoundSteal.release(c)),
                ab("Go Beyond", "A perfectly spinning sliver of gold so thin it isn't there: it goes straight through armour and shields into whatever you're looking at.", 1, 14, c ->
                        SoundSteal.goBeyond(c))
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
                ab("Life Giver", "Two Iron Golems come to life beside you to defend the village for 40 seconds, and every villager and golem nearby is healed.", 1, 60, c ->
                        Ger.lifeGiver(c)),
                ab("Golden Barrier", "A golden dome over everything around you for 20 seconds: nothing inside can be hurt by raiders or monsters, and they're pushed back out of it.", 1, 90, c ->
                        Ger.goldenBarrier(c))
        };
    }

    /** Piglin Snatch (Gilded Swine, and its Requiem). */
    static Ability snatch() {
        return
                ab("Snatch", "Your Piglin darts at the mob you're looking at and steals what it's holding (its sword, bow, crossbow...) and hands it to you.", 1, 12, c -> {
                    LivingEntity target = c.aim(14.0D);
                    if (!(target instanceof net.minecraft.world.entity.Mob mob)) {
                        c.owner.displayClientMessage(net.minecraft.network.chat.Component.literal("Look at a mob to steal from."), true);
                        return false;
                    }
                    net.minecraft.world.entity.EquipmentSlot slot = !mob.getMainHandItem().isEmpty() ? net.minecraft.world.entity.EquipmentSlot.MAINHAND
                            : !mob.getOffhandItem().isEmpty() ? net.minecraft.world.entity.EquipmentSlot.OFFHAND : null;
                    if (slot == null) {
                        c.owner.displayClientMessage(net.minecraft.network.chat.Component.literal("It has nothing to steal."), true);
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
                ab("Bubble Nursery", "Seal the mob you're looking at in a floating bubble of water: it hangs helpless in the air, slowly drowning, for 6 seconds - then the bubble pops and it drops.", 6, 40, c ->
                        AxolotlPowers.bubble(c)),
ab("Neoteny", "Like a watch wound back: while your Axolotl is out, the world around you is quietly remembered. Use it and the last 6 seconds are undone for everything within 40 blocks - every mob and player (you too) goes back where it was with the health it had, mobs that died come back, drops and arrows vanish, broken, placed or blown-up blocks return. Only you remember it happened.", 10, 45, c ->
                        Mandom.rewind(c.owner))
        };
    }

    public static Ability[] squid() {
        return new Ability[]{
                Ability.none(),

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

                ab("Rain Shot", "Your Squid tips itself over sideways and fires a stream of hard rain straight at your target for 2 seconds: rapid small hits that push them back and slow them.", 3, 10, c -> {
                    LivingEntity target = c.aim(20.0D);
                    c.sound(SoundEvents.SQUID_SQUIRT, 1.2F, 0.7F);
                    // Tilt first (the animation), then the barrage.
                    for (int i = RAIN_SHOT_TILT; i < RAIN_SHOT_TICKS; i += 2) {
                        final int tick = i;
                        c.later(i, () -> rainShotTick(c, target, tick));
                    }
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
        Vec3 dir = target != null && target.isAlive()
                ? target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D).subtract(from)
                : c.owner.getLookAngle();
        if (dir.lengthSqr() < 1.0E-4D) return;
        dir = dir.normalize();
        // Stops at the first wall.
        net.minecraft.world.phys.BlockHitResult wall = c.level.clip(new net.minecraft.world.level.ClipContext(from, from.add(dir.scale(RAIN_SHOT_RANGE)),
                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, c.owner));
        double len = wall.getType() == net.minecraft.world.phys.HitResult.Type.MISS ? RAIN_SHOT_RANGE : wall.getLocation().distanceTo(from);
        // The drops: a jittery stream of water with splashes at the end.
        for (double d = 0.6D; d < len; d += 0.45D) {
            Vec3 p = from.add(dir.scale(d));
            double j = 0.08D + d * 0.012D;
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
            StandCombat.hurt(c.owner, e, c.dmg(1.0D));
            if (e instanceof net.minecraft.world.entity.monster.EnderMan) {
                // Water burns Endermen: the rain stream hits them extra hard.
                e.invulnerableTime = 0;
                e.hurt(c.level.damageSources().drown(), 2.0F);
            }
            e.setDeltaMovement(e.getDeltaMovement().add(dir.x * 0.18D, 0.02D, dir.z * 0.18D));
            e.hurtMarked = true;
            e.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN, 40, 0));
            e.clearFire();
        }
    }

    public static Ability[] slime() {
        return new Ability[]{
                ab("Squish Slam", "A gooey slam with big knockback.", 1, 0.8, c ->
                        melee(c, 3.5D, 1.6D, 0.4D, t -> c.sound(SoundEvents.SLIME_ATTACK, 1.0F, 1.0F))),

                ab("Ricochet Blob", "Launch a blob that bounces off walls, damaging and shoving enemies.", 2, 7, c -> {
                    shoot(c, StandProjectile.Kind.SLIME, 5.0D, 0.0D);
                    c.sound(SoundEvents.SLIME_JUMP, 1.0F, 1.2F);
                    return true;
                }),

                ab("Super Bounce", "Launch yourself high into the air. Fall damage is cancelled.", 4, 10, c -> {
                    Vec3 look = c.flatLook();
                    c.owner.setDeltaMovement(look.x * 0.55D, 1.25D, look.z * 0.55D);
                    c.owner.hurtMarked = true;
                    c.owner.fallDistance = 0.0F;
                    c.owner.addEffect(new net.minecraft.world.effect.MobEffectInstance(MobEffects.SLOW_FALLING, 60, 0));
                    c.sound(SoundEvents.SLIME_JUMP, 1.5F, 0.8F);
                    c.fx(ParticleTypes.ITEM_SLIME, c.owner.position().add(0.0D, 0.2D, 0.0D), 30, 0.5D, 0.1D);
                    return true;
                }),

                ab("Mega Quake", "A giant jelly shockwave around you; you gain Jump Boost afterwards.", 8, 40, c -> {
                    Vec3 center = c.owner.position();
                    c.sound(SoundEvents.SLIME_SQUISH, 1.6F, 0.5F);
                    c.ring(ParticleTypes.ITEM_SLIME, center.add(0.0D, 0.2D, 0.0D), 3.0D, 32);
                    c.ring(ParticleTypes.ITEM_SLIME, center.add(0.0D, 0.2D, 0.0D), 6.0D, 56);
                    c.ring(ParticleTypes.ITEM_SLIME, center.add(0.0D, 0.2D, 0.0D), 8.0D, 70);
                    for (LivingEntity e : c.enemies(center.add(0.0D, 1.0D, 0.0D), 8.0D)) {
                        c.hurt(e, c.dmg(10.0D));
                        c.push(e, e.position().subtract(center), 2.0D, 0.8D);
                        c.effect(e, MobEffects.MOVEMENT_SLOWDOWN, 80, 2);
                    }
                    c.owner.addEffect(new net.minecraft.world.effect.MobEffectInstance(MobEffects.JUMP, 200, 1));
                    return true;
                })
        };
    }
}
