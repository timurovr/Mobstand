package com.mobstands.stand;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.*;
import net.minecraft.world.entity.animal.*;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Pigstep: Property Swap - Soft &amp; Wet style. Press once while looking at a mob: your Stand steals the
 * thing that makes it what it is (a slime's bounce, a ghast's lightness, a creeper's volatility, a squid's gills...)
 * and the mob is left drained. Press again: pop that property onto whatever you're looking at - another mob, or
 * yourself if you look at nothing. Give a zombie a squid's gills and it drowns on dry land; give yourself a
 * rabbit's leap; hand a creeper's volatility to a skeleton and watch it go off.
 */
public final class PropertySwap {
    private PropertySwap() {}

    public enum Prop {
        BOUNCE("Bounce", 0x7EFF6E), LIGHTNESS("Lightness", 0xE8E8FF), LEAP("Leap", 0xC9A27A), SPEED("Speed", 0x9FE0FF),
        TOUGHNESS("Toughness", 0xB0B0B0), FIREPROOF("Fireproofing", 0xFF8A3A), TELEPORT("Teleport", 0xC066FF),
        VOLATILITY("Volatility", 0x4CFF4C), COLD("Cold", 0xA8E4FF), GILLS("Gills", 0x5B8FD6), POTIONS("Potions", 0x9B59B6),
        GLOW("Glow", 0x7FFFD4), POISON("Poison", 0x6E8F2B), VIGOR("Vigor", 0xFF6E6E);

        public final String label;
        public final int color;

        Prop(String label, int color) {
            this.label = label;
            this.color = color;
        }
    }

    public static final int DURATION = 200; // 10 seconds

    /** What each player's Stand is holding. */
    private static final Map<UUID, Prop> HELD = new HashMap<>();

    /** A property (or a theft) at work on a creature. */
    private static final class Active {
        final UUID target;
        final Prop prop;    // null for a theft
        final Steal steal;  // null for a property
        long until;
        final UUID giver;
        /** Teleport / Volatility: who it's after (null = whoever it's angry at, or nobody). */
        UUID hunt;
        /** Teleport / Volatility on a peaceful animal: it can't be made to fight - it just blinks about / waits. */
        boolean passive;
        int fuse;
        boolean wasSilent;
        Vec3 slide = Vec3.ZERO;

        Active(UUID target, Prop prop, Steal steal, long until, UUID giver) {
            this.target = target;
            this.prop = prop;
            this.steal = steal;
            this.until = until;
            this.giver = giver;
        }
    }
    private static final List<Active> ACTIVE = new ArrayList<>();

    /** The things you can take from any creature (Soft &amp; Wet): it loses them for 10 seconds. Nothing comes to you. */
    public enum Steal {
        SIGHT("Sight", 0xE0E0E0), FRICTION("Friction", 0xA0E8FF), HEAT("Heat", 0x9FD8FF),
        WATER("Water", 0x3F76E4), BREATH("Breath", 0xC8F0FF), FOOTING("Footing", 0xC9A27A);

        public final String label;
        public final int color;

        Steal(String label, int color) {
            this.label = label;
            this.color = color;
        }
    }

    /** The menu is open for this player, on this creature. */
    private record Pending(UUID target, long until) {}
    private static final Map<UUID, Pending> PENDING = new HashMap<>();
    /** You just gave Teleport / Volatility: your next press picks who it hunts. */
    private static final Map<UUID, Active> AIMING = new HashMap<>();
    /** Creepers whose Volatility was stolen: they still try - swell up, then fizzle out. */
    private static final java.util.Set<UUID> DEFUSED = new java.util.HashSet<>();

    public static boolean defused(Entity e) {
        return e != null && DEFUSED.contains(e.getUUID());
    }
    private static final net.minecraft.resources.ResourceLocation NO_FOOTING = com.mobstands.MobStands.id("no_footing");

    /** What makes this mob what it is. */
    public static Prop of(LivingEntity e) {
        if (e instanceof Slime) return Prop.BOUNCE; // (magma cubes too)
        if (e instanceof Ghast || e instanceof Phantom || e instanceof net.minecraft.world.entity.ambient.Bat || e instanceof Bee
                || e instanceof Parrot || e instanceof net.minecraft.world.entity.animal.allay.Allay || e instanceof Vex || e instanceof Chicken) return Prop.LIGHTNESS;
        if (e instanceof Rabbit || e instanceof net.minecraft.world.entity.animal.goat.Goat || e instanceof net.minecraft.world.entity.animal.frog.Frog) return Prop.LEAP;
        if (e instanceof Fox || e instanceof Ocelot || e instanceof Cat || e instanceof Wolf || e instanceof net.minecraft.world.entity.animal.horse.AbstractHorse) return Prop.SPEED;
        if (e instanceof Turtle || e instanceof net.minecraft.world.entity.animal.armadillo.Armadillo || e instanceof IronGolem || e instanceof Ravager) return Prop.TOUGHNESS;
        if (e instanceof Blaze || e instanceof Strider || e instanceof net.minecraft.world.entity.monster.hoglin.Hoglin || e instanceof net.minecraft.world.entity.monster.piglin.AbstractPiglin) return Prop.FIREPROOF;
        if (e instanceof EnderMan || e instanceof Endermite || e instanceof Shulker) return Prop.TELEPORT;
        if (e instanceof Creeper) return Prop.VOLATILITY;
        if (e instanceof SnowGolem || e instanceof Stray || e instanceof PolarBear) return Prop.COLD;
        if (e instanceof net.minecraft.world.entity.GlowSquid) return Prop.GLOW;
        if (e instanceof net.minecraft.world.entity.animal.WaterAnimal || e instanceof net.minecraft.world.entity.animal.axolotl.Axolotl
                || e instanceof Drowned || e instanceof Guardian) return Prop.GILLS;
        if (e instanceof Witch) return Prop.POTIONS;
        if (e instanceof CaveSpider || e instanceof Bogged) return Prop.POISON;
        return Prop.VIGOR;
    }

    /** Slots in Pigstep's kit. */
    public static final int STEAL_SLOT = 1, RELEASE_SLOT = 2;

    /** Steal: look at a creature and choose what to take from it. */
    public static boolean steal(AbilityContext c) {
        ServerPlayer p = c.owner;
        ServerLevel sl = c.level;
        AIMING.remove(p.getUUID());
        LivingEntity t = c.aim(20.0D);
        if (t == null) {
            c.fail = "Look at a creature to steal from it.";
            return false;
        }
        PENDING.put(p.getUUID(), new Pending(t.getUUID(), sl.getGameTime() + 600L));
        Prop own = of(t);
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p,
                new com.mobstands.network.PropMenuPayload(t.getId(), own.label, own.color));
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 0.8F, 1.2F);
        long at = sl.getGameTime();
        c.later(1, () -> { c.data.setCooldownEnd(STEAL_SLOT, at); StandManager.sync(p); }); // no cooldown until something's taken
        return true;
    }

    /** Release: use whatever your Stand is holding - give a property away (or take it yourself), or let a sound loose. */
    public static boolean release(AbilityContext c) {
        ServerPlayer p = c.owner;
        ServerLevel sl = c.level;
        // The press after giving Teleport / Volatility: who should it go after?
        Active aiming = AIMING.remove(p.getUUID());
        if (aiming != null) return chooseHunt(c, aiming, c.aim(32.0D));
        Prop held = HELD.get(p.getUUID());
        if (held == null) {
            if (!c.data.stolenSounds.isEmpty()) return SoundSteal.release(c);
            c.fail = "Your Stand isn't holding anything.";
            return false;
        }
        LivingEntity t = c.aim(20.0D);
        LivingEntity to = t != null ? t : p;
        if (held == Prop.VOLATILITY && to == p) {
            // Keep it yourself and it goes off where you're looking: a creeper's blast.
            HELD.remove(p.getUUID());
            Vec3 aim = c.aimPoint(28.0D);
            c.sound(SoundEvents.CREEPER_PRIMED, 1.0F, 1.0F);
            c.level.sendParticles(ParticleTypes.SMOKE, aim.x, aim.y + 0.5D, aim.z, 10, 0.3D, 0.3D, 0.3D, 0.01D);
            c.later(12, () -> c.explode(aim, 3.0D));
            return true;
        }
        HELD.remove(p.getUUID());
        c.trail(ParticleTypes.WAX_OFF, c.stand.position().add(0.0D, 1.0D, 0.0D), to.getEyePosition(), 0.4D);
        sl.playSound(null, to.getX(), to.getY(), to.getZ(), SoundEvents.AMETHYST_CLUSTER_BREAK, SoundSource.PLAYERS, 1.4F, 1.2F);
        Active hunter = give(sl, p, to, held);
        if (hunter != null) {
            AIMING.put(p.getUUID(), hunter);
            p.displayClientMessage(Component.literal("Gave it " + held.label + ". Look at who it should go after and press Release again.")
                    .withStyle(s -> s.withColor(held.color)), true);
            return true;
        }
        p.displayClientMessage(Component.literal("Gave " + (to == p ? "yourself" : "it") + " " + held.label + ".")
                .withStyle(s -> s.withColor(held.color)), true);
        return true;
    }

    // ------------------------------------------------------------------ the menu

    /** The player picked from the menu: 0 = its own property, 1.. = Sight, Friction... (-1 = closed it). */
    public static void pick(ServerPlayer p, int choice) {
        Pending pend = PENDING.remove(p.getUUID());
        if (pend == null || choice < 0) return;
        ServerLevel sl = p.serverLevel();
        StandData d = StandManager.data(p);
        if (d.getType() != StandType.PIGLIN_REQUIEM || sl.getGameTime() > pend.until()) return;
        if (!(sl.getEntity(pend.target()) instanceof LivingEntity t) || !t.isAlive() || t.distanceToSqr(p) > 32.0D * 32.0D) {
            p.displayClientMessage(Component.literal("It got away.").withStyle(s -> s.withColor(0xB07CFF)), true);
            return;
        }
        sl.sendParticles(ParticleTypes.WAX_ON, t.getX(), t.getEyeY(), t.getZ(), 12, 0.3D, 0.3D, 0.3D, 0.05D);
        sl.playSound(null, t.getX(), t.getY(), t.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.5F, 0.6F);
        sl.playSound(null, t.getX(), t.getY(), t.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 1.0F, 0.5F);
        long cd = sl.getGameTime() + d.getType().ability(STEAL_SLOT).cooldownTicks(d.getLevel());
        if (choice == 1) {
            // Its Sound: your Stand holds that now (instead of anything else).
            if (SoundSteal.take(p, t)) {
                HELD.remove(p.getUUID());
                d.setCooldownEnd(STEAL_SLOT, cd);
                StandManager.sync(p);
            }
            return;
        }
        if (choice == 0) {
            Prop prop = of(t);
            HELD.put(p.getUUID(), prop);
            d.stolenSounds.clear(); // one thing at a time
            d.setCooldownEnd(STEAL_SLOT, cd);
            StandManager.sync(p);
            // The theft: it's left drained, slow and weak.
            t.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, DURATION, 1));
            t.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, DURATION, 0));
            if (prop == Prop.VIGOR) StandCombat.hurt(p, t, 4.0F); // its vigor - a chunk of its life - comes with it
            if (t instanceof Creeper) DEFUSED.add(t.getUUID()); // nothing left to blow up with
            p.displayClientMessage(Component.literal("Stole its " + prop.label + ". Press Release to give it to something (or look away to take it yourself).")
                    .withStyle(s -> s.withColor(prop.color)), true);
            return;
        }
        Steal steal = Steal.values()[Math.min(choice - 2, Steal.values().length - 1)];
        take(sl, p, t, steal);
        p.displayClientMessage(Component.literal("Took its " + steal.label + ".").withStyle(s -> s.withColor(steal.color)), true);
        d.setCooldownEnd(STEAL_SLOT, cd);
        StandManager.sync(p);
    }

    /** The creature loses this for 10 seconds. */
    private static void take(ServerLevel sl, ServerPlayer giver, LivingEntity t, Steal steal) {
        StandCombat.credit(giver, t);
        Active a = new Active(t.getUUID(), null, steal, sl.getGameTime() + DURATION, giver.getUUID());
        switch (steal) {
            case SIGHT -> {
                eff(t, MobEffects.BLINDNESS, DURATION, 0);
                eff(t, MobEffects.DARKNESS, DURATION, 0);
            }
            case FOOTING -> {
                var jump = t.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.JUMP_STRENGTH);
                if (jump != null) jump.addOrUpdateTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                        NO_FOOTING, -10.0D, net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE));
            }
            case WATER -> {
                eff(t, MobEffects.WEAKNESS, DURATION, 0);
                eff(t, MobEffects.MOVEMENT_SLOWDOWN, DURATION, 0);
            }
            case FRICTION -> {
                if (t instanceof net.minecraft.world.entity.player.Player) eff(t, MobEffects.MOVEMENT_SLOWDOWN, DURATION, 2);
                a.slide = t.getDeltaMovement().multiply(1.0D, 0.0D, 1.0D);
            }
            default -> { }
        }
        ACTIVE.add(a);
    }

    // ------------------------------------------------------------------ hunting (Teleport / Volatility)

    private static boolean hunter(LivingEntity e) {
        return e instanceof Wolf || e instanceof net.minecraft.world.entity.animal.axolotl.Axolotl || e instanceof Fox
                || e instanceof Cat || e instanceof Ocelot || e instanceof PolarBear
                || e instanceof net.minecraft.world.entity.animal.frog.Frog || e instanceof IronGolem;
    }

    /** What a natural hunter will go after: its real prey only. */
    private static boolean prey(LivingEntity hunter, LivingEntity t) {
        if (hunter instanceof Wolf) return t instanceof Sheep || t instanceof Rabbit || t instanceof Fox || t instanceof AbstractSkeleton;
        if (hunter instanceof net.minecraft.world.entity.animal.axolotl.Axolotl)
            return t instanceof Drowned || t instanceof Guardian || t instanceof AbstractFish || t instanceof Squid;
        if (hunter instanceof Fox) return t instanceof Chicken || t instanceof Rabbit || t instanceof AbstractFish;
        if (hunter instanceof Cat || hunter instanceof Ocelot) return t instanceof Chicken || t instanceof Rabbit;
        if (hunter instanceof PolarBear) return t instanceof AbstractFish || t == hunter.getLastHurtByMob();
        if (hunter instanceof net.minecraft.world.entity.animal.frog.Frog) return t instanceof Slime s && s.getSize() == 1;
        if (hunter instanceof IronGolem) return t instanceof Enemy;
        return true;
    }

    private static void setTarget(LivingEntity e, LivingEntity t) {
        if (!(e instanceof Mob m)) return;
        if (m.getTarget() != t) m.setTarget(t);
        var brain = m.getBrain();
        if (brain.checkMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.ATTACK_TARGET, net.minecraft.world.entity.ai.memory.MemoryStatus.REGISTERED)) {
            brain.setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.ATTACK_TARGET, t);
        }
    }

    private static boolean chooseHunt(AbilityContext c, Active a, LivingEntity t) {
        ServerPlayer p = c.owner;
        if (!(c.level.getEntity(a.target) instanceof LivingEntity mob) || !mob.isAlive()) {
            p.displayClientMessage(Component.literal("It's gone.").withStyle(s -> s.withColor(0xB07CFF)), true);
            return false;
        }
        if (t == null || t == mob) {
            p.displayClientMessage(Component.literal("It'll pick on its own.").withStyle(s -> s.withColor(0xB07CFF)), true);
            return true;
        }
        if (hunter(mob) && !prey(mob, t)) {
            AIMING.put(p.getUUID(), a); // still yours to aim
            c.fail = "A " + mob.getName().getString() + " won't hunt that.";
            return false;
        }
        a.hunt = t.getUUID();
        setTarget(mob, t);
        c.trail(ParticleTypes.WAX_OFF, mob.getEyePosition(), t.getEyePosition(), 0.5D);
        c.level.playSound(null, t.getX(), t.getY(), t.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.2F, 1.4F);
        String name = t.getName().getString();
        p.displayClientMessage(Component.literal("It's after " + name + " now.").withStyle(s -> s.withColor(a.prop.color)), true);
        return true;
    }

    private static void eff(LivingEntity e, Holder<MobEffect> m, int ticks, int amp) {
        e.addEffect(new MobEffectInstance(m, ticks, amp));
    }

    /** Returns the Teleport / Volatility it set going, when that still needs a target picked (else null). */
    private static Active give(ServerLevel sl, ServerPlayer giver, LivingEntity to, Prop prop) {
        long now = sl.getGameTime();
        boolean self = to == giver;
        switch (prop) {
            case LIGHTNESS -> {
                if (self) { eff(to, MobEffects.SLOW_FALLING, 600, 0); eff(to, MobEffects.JUMP, 600, 1); }
                else eff(to, MobEffects.LEVITATION, 70, 1); // floats up helplessly, then drops
            }
            case LEAP -> eff(to, MobEffects.JUMP, 400, 3);
            case SPEED -> eff(to, MobEffects.MOVEMENT_SPEED, 400, 1);
            case TOUGHNESS -> eff(to, MobEffects.DAMAGE_RESISTANCE, 400, 1);
            case FIREPROOF -> eff(to, MobEffects.FIRE_RESISTANCE, 600, 0);
            case COLD -> { eff(to, MobEffects.MOVEMENT_SLOWDOWN, DURATION, 3); to.setTicksFrozen(to.getTicksRequiredToFreeze() + 60); }
            case POTIONS -> {
                List<Holder<MobEffect>> bad = List.of(MobEffects.POISON, MobEffects.WEAKNESS, MobEffects.MOVEMENT_SLOWDOWN, MobEffects.HUNGER, MobEffects.BLINDNESS, MobEffects.WITHER);
                List<Holder<MobEffect>> good = List.of(MobEffects.REGENERATION, MobEffects.DAMAGE_BOOST, MobEffects.MOVEMENT_SPEED, MobEffects.FIRE_RESISTANCE, MobEffects.INVISIBILITY);
                List<Holder<MobEffect>> pool = self ? good : bad;
                eff(to, pool.get(sl.random.nextInt(pool.size())), DURATION, 1);
            }
            case GLOW -> { eff(to, MobEffects.GLOWING, 600, 0); if (self) eff(to, MobEffects.NIGHT_VISION, 600, 0); }
            case POISON -> eff(to, self ? MobEffects.REGENERATION : MobEffects.POISON, 120, 1);
            case VIGOR -> { if (self || !(to instanceof Mob)) { to.heal(4.0F); eff(to, MobEffects.ABSORPTION, 600, 0); } else to.heal(4.0F); }
            case GILLS -> { if (self) { eff(to, MobEffects.WATER_BREATHING, 1200, 0); eff(to, MobEffects.DOLPHINS_GRACE, 600, 0); } }
            default -> { }
        }
        // The ones that need watching over time.
        if (prop == Prop.BOUNCE || prop == Prop.TELEPORT || prop == Prop.VOLATILITY || (prop == Prop.GILLS && !self)) {
            boolean hunts = (prop == Prop.TELEPORT || prop == Prop.VOLATILITY) && !self;
            Active a = new Active(to.getUUID(), prop, null, now + (hunts ? HUNT_TICKS : DURATION), giver.getUUID());
            ACTIVE.add(a);
            if (hunts) {
                LivingEntity current = to instanceof Mob m ? m.getTarget() : null;
                if (current != null && current.isAlive()) {
                    a.hunt = current.getUUID(); // already after someone: it's just stronger against them now
                } else if (to instanceof Enemy || hunter(to)) {
                    return a; // you pick who it goes after
                } else if (prop == Prop.TELEPORT) {
                    a.passive = true; // a peaceful animal can't be made to fight: it just blinks about
                }
                // (Volatility turns anything into a creeper - even a sheep comes after the nearest player.)
            }
        }
        return null;
    }

    /** How long a hunting Teleport / Volatility lasts. */
    public static final int HUNT_TICKS = 600;
    /** Volatility's fuse, like a creeper's. */
    public static final int FUSE = 30;

    private static LivingEntity huntTarget(ServerLevel sl, Active a, LivingEntity le) {
        if (a.hunt != null && sl.getEntity(a.hunt) instanceof LivingEntity h && h.isAlive()) {
            setTarget(le, h);
            return h;
        }
        return le instanceof Mob m && m.getTarget() != null && m.getTarget().isAlive() ? m.getTarget() : null;
    }

    private static void sendFuse(LivingEntity le, int fuse) {
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntity(le,
                new com.mobstands.network.FusePayload(le.getId(), fuse));
    }

    /** Blink next to (or behind) the target, like an enderman closing in. */
    private static void blinkTo(ServerLevel sl, LivingEntity le, LivingEntity target) {
        Vec3 from = le.position();
        Vec3 behind = target.position().subtract(target.getLookAngle().multiply(1.0D, 0.0D, 1.0D).normalize().scale(1.5D));
        boolean ok = le.randomTeleport(behind.x, target.getY(), behind.z, true);
        for (int i = 0; i < 16 && !ok; i++) {
            double x = target.getX() + (sl.random.nextDouble() - 0.5D) * 4.0D;
            double z = target.getZ() + (sl.random.nextDouble() - 0.5D) * 4.0D;
            ok = le.randomTeleport(x, target.getY() + sl.random.nextInt(3) - 1, z, true);
        }
        if (!ok) return;
        sl.sendParticles(ParticleTypes.PORTAL, from.x, from.y + 1.0D, from.z, 20, 0.3D, 0.6D, 0.3D, 0.3D);
        sl.sendParticles(ParticleTypes.PORTAL, le.getX(), le.getY() + 1.0D, le.getZ(), 20, 0.3D, 0.6D, 0.3D, 0.3D);
        sl.playSound(null, from.x, from.y, from.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 0.9F, 1.0F);
        sl.playSound(null, le.getX(), le.getY(), le.getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 0.9F, 1.0F);
    }

    private static void randomBlink(ServerLevel sl, LivingEntity le) {
        Vec3 from = le.position();
        for (int i = 0; i < 16; i++) {
            double x = le.getX() + (sl.random.nextDouble() - 0.5D) * 16.0D;
            double y = le.getY() + sl.random.nextInt(8) - 4;
            double z = le.getZ() + (sl.random.nextDouble() - 0.5D) * 16.0D;
            if (le.randomTeleport(x, y, z, true)) break;
        }
        sl.playSound(null, from.x, from.y, from.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.NEUTRAL, 0.8F, 1.0F);
    }

    private static void end(LivingEntity le, Active a) {
        if (a.steal == Steal.FOOTING) {
            var jump = le.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.JUMP_STRENGTH);
            if (jump != null) jump.removeModifier(NO_FOOTING);
        }
        if (a.prop == Prop.VOLATILITY && a.fuse > 0) sendFuse(le, 0);
    }

    public static void tick(ServerLevel sl) {
        if (!sl.tickRateManager().runsNormally()) return;
        // A creeper with no Volatility: it swells... and fizzles.
        if (!DEFUSED.isEmpty()) {
            Iterator<UUID> di = DEFUSED.iterator();
            while (di.hasNext()) {
                UUID id = di.next();
                if (!(sl.getEntity(id) instanceof Creeper cr)) continue;
                if (!cr.isAlive()) { di.remove(); continue; }
                if (cr.getSwelling(1.0F) > 0.8F && cr.getSwellDir() > 0) {
                    cr.setSwellDir(-1);
                    sl.sendParticles(ParticleTypes.SMOKE, cr.getX(), cr.getY() + 1.2D, cr.getZ(), 8, 0.2D, 0.2D, 0.2D, 0.02D);
                    sl.playSound(null, cr.getX(), cr.getY(), cr.getZ(), SoundEvents.FIRE_EXTINGUISH, SoundSource.HOSTILE, 0.8F, 1.6F);
                }
            }
        }
        if (ACTIVE.isEmpty()) return;
        long now = sl.getGameTime();
        Iterator<Active> it = ACTIVE.iterator();
        List<Runnable> later = new ArrayList<>();
        while (it.hasNext()) {
            Active a = it.next();
            Entity e = sl.getEntity(a.target);
            if (e == null) continue;
            if (!(e instanceof LivingEntity le) || !le.isAlive()) {
                it.remove();
                continue;
            }
            boolean done = now >= a.until;
            if (a.steal != null) {
                if (done) {
                    end(le, a);
                    it.remove();
                    continue;
                }
                tickSteal(sl, now, a, le);
                continue;
            }
            switch (a.prop) {
                case BOUNCE -> {
                    // Boing: it can't stop bouncing (and never takes fall damage while it does).
                    le.resetFallDistance();
                    if (le.onGround() && now % 4L == 0L) {
                        le.setDeltaMovement(le.getDeltaMovement().x * 1.4D, 0.85D, le.getDeltaMovement().z * 1.4D);
                        le.hurtMarked = true;
                        sl.playSound(null, le.getX(), le.getY(), le.getZ(), SoundEvents.SLIME_JUMP, SoundSource.NEUTRAL, 0.8F, 1.0F);
                        sl.sendParticles(ParticleTypes.ITEM_SLIME, le.getX(), le.getY(), le.getZ(), 6, 0.3D, 0.0D, 0.3D, 0.1D);
                    }
                }
                case TELEPORT -> {
                    LivingEntity target = a.passive ? null : huntTarget(sl, a, le);
                    if (target != null) {
                        // It hunts: whenever its target is far or out of sight, it blinks right to them.
                        if (now % 40L == 0L && (le.distanceTo(target) > 5.0F || !le.hasLineOfSight(target))) blinkTo(sl, le, target);
                    } else if (now % 30L == 0L) {
                        randomBlink(sl, le);
                    }
                }
                case GILLS -> {
                    // A fish out of water: it drowns in the open air.
                    if (!le.isInWater()) {
                        le.setAirSupply(Math.max(-20, le.getAirSupply() - 10));
                        if (le.getAirSupply() <= -20 && now % 20L == 0L) le.hurt(sl.damageSources().drown(), 2.0F);
                        if (now % 6L == 0L) sl.sendParticles(ParticleTypes.BUBBLE_POP, le.getX(), le.getEyeY(), le.getZ(), 2, 0.2D, 0.1D, 0.2D, 0.0D);
                    }
                }
                case VOLATILITY -> {
                    if (tickVolatility(sl, now, a, le, later)) {
                        it.remove();
                        continue;
                    }
                    // It doesn't calm down mid-fuse.
                    if (done && a.fuse > 0) done = false;
                }
                default -> { }
            }
            if (done) {
                end(le, a);
                it.remove();
            }
        }
        later.forEach(Runnable::run);
    }

    /** A walking creeper: it goes after its target, hisses and swells up close, and backs off if they get away. True = it blew. */
    private static boolean tickVolatility(ServerLevel sl, long now, Active a, LivingEntity le, List<Runnable> later) {
        ServerPlayer giver = sl.getServer().getPlayerList().getPlayer(a.giver);
        LivingEntity target = null;
        if (!a.passive) {
            target = huntTarget(sl, a, le);
            if (target == null) {
                // Like a creeper: it goes for the nearest player - you too.
                double best = 16.0D * 16.0D;
                for (ServerPlayer o : sl.players()) {
                    if (!o.isAlive() || o.isSpectator() || o.isCreative()) continue;
                    double d = o.distanceToSqr(le);
                    if (d < best) { best = d; target = o; }
                }
                if (target != null) a.hunt = target.getUUID();
            }
        }
        boolean close;
        if (target != null) {
            double d = le.distanceTo(target);
            if (le instanceof Mob m && d > 2.5D && now % 5L == 0L) m.getNavigation().moveTo(target, 1.25D);
            if (le instanceof Mob m) m.getLookControl().setLookAt(target, 30.0F, 30.0F);
            close = d < 3.0D || (a.fuse > 0 && d < 7.0D);
        } else {
            // A live mine: anyone (but you) who comes close sets it off.
            close = !sl.getEntitiesOfClass(LivingEntity.class, le.getBoundingBox().inflate(3.0D),
                    x -> x != le && x.isAlive() && !x.getUUID().equals(a.giver)
                            && !(x instanceof net.minecraft.world.entity.decoration.ArmorStand) && !x.isSpectator()).isEmpty();
        }
        int before = a.fuse;
        if (close) {
            if (a.fuse == 0) sl.playSound(null, le.getX(), le.getY(), le.getZ(), SoundEvents.CREEPER_PRIMED, SoundSource.HOSTILE, 1.0F, 0.5F);
            a.fuse++;
            if (le instanceof Mob m) m.getNavigation().stop();
        } else if (a.fuse > 0) {
            a.fuse--;
        }
        if (a.fuse != before) sendFuse(le, a.fuse);
        if (now % 4L == 0L) sl.sendParticles(ParticleTypes.SMOKE, le.getX(), le.getY() + le.getBbHeight(), le.getZ(), 2, 0.1D, 0.1D, 0.1D, 0.0D);
        if (a.fuse < FUSE) return false;
        Vec3 at = le.position().add(0.0D, le.getBbHeight() * 0.5D, 0.0D);
        later.add(() -> {
            if (giver != null) for (LivingEntity o : sl.getEntitiesOfClass(LivingEntity.class, le.getBoundingBox().inflate(4.0D))) StandCombat.credit(giver, o);
            sl.explode(le, at.x, at.y, at.z, 3.0F, net.minecraft.world.level.Level.ExplosionInteraction.MOB); // a creeper's blast
            if (le.isAlive() && !(le instanceof net.minecraft.world.entity.player.Player)) le.kill(); // (a player just takes the blast)
        });
        return true;
    }

    /** What it's missing, every tick. */
    private static void tickSteal(ServerLevel sl, long now, Active a, LivingEntity le) {
        if (now % 10L == 0L) {
            int c = a.steal.color;
            sl.sendParticles(new net.minecraft.core.particles.DustParticleOptions(new org.joml.Vector3f((c >> 16 & 255) / 255.0F,
                            (c >> 8 & 255) / 255.0F, (c & 255) / 255.0F), 0.8F),
                    le.getX(), le.getY() + le.getBbHeight() * 0.6D, le.getZ(), 3, 0.25D, 0.3D, 0.25D, 0.0D);
        }
        switch (a.steal) {
            case SIGHT -> {
                // It can't see anyone to go after.
                if (le instanceof Mob m) {
                    m.setTarget(null);
                    m.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.ATTACK_TARGET);
                }
            }
            case HEAT -> {
                // Frozen through, like powder snow: slow, shaking, and the cold bites.
                le.setTicksFrozen(Math.max(le.getTicksFrozen(), le.getTicksRequiredToFreeze() + 2));
            }
            case WATER -> {
                // Water creatures dry out.
                if (le instanceof net.minecraft.world.entity.animal.WaterAnimal || le instanceof net.minecraft.world.entity.animal.axolotl.Axolotl
                        || le instanceof Guardian || le instanceof Drowned) {
                    if (now % 20L == 0L) le.hurt(sl.damageSources().dryOut(), 2.0F);
                }
            }
            case BREATH -> {
                // No air, even on dry land.
                le.setAirSupply(Math.max(-20, le.getAirSupply() - 10));
                if (le.getAirSupply() <= -20 && now % 20L == 0L) le.hurt(sl.damageSources().drown(), 2.0F);
                if (now % 6L == 0L) sl.sendParticles(ParticleTypes.BUBBLE_POP, le.getX(), le.getEyeY(), le.getZ(), 2, 0.2D, 0.1D, 0.2D, 0.0D);
            }
            case FOOTING -> {
                // It can't jump or climb: spiders slide off the walls.
                Vec3 v = le.getDeltaMovement();
                if (!le.onGround() && v.y > 0.0D && !(le instanceof net.minecraft.world.entity.player.Player)) le.setDeltaMovement(v.x, Math.min(v.y, -0.08D), v.z);
            }
            case FRICTION -> {
                // Like ice: once it moves, it can't stop or turn.
                if (le instanceof net.minecraft.world.entity.player.Player) break;
                Vec3 v = le.getDeltaMovement();
                Vec3 h = new Vec3(v.x, 0.0D, v.z);
                Vec3 slide = a.slide.scale(0.98D).add(h.scale(0.12D));
                if (slide.length() > 0.55D) slide = slide.normalize().scale(0.55D);
                a.slide = slide;
                if (le.onGround()) le.setDeltaMovement(slide.x, v.y, slide.z);
            }
        }
    }

    /** What your Stand is holding right now (for the HUD), or null. */
    public static Prop held(ServerPlayer p) {
        return HELD.get(p.getUUID());
    }

    public static void clear() {
        HELD.clear();
        ACTIVE.clear();
        PENDING.clear();
        AIMING.clear();
        DEFUSED.clear();
    }
}
