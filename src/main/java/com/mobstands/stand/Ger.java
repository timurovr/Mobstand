package com.mobstands.stand;

import com.mobstands.network.GerCutscenePayload;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Guardian Experience Requiem - the Iron Golem's Requiem. A protector whose defense is infinite: it doesn't block
 * an attack, it returns it to zero.
 *
 * <ul>
 *   <li><b>Return to Zero</b> (X): a 2-second counter stance. Get hit inside it and the attack never happens: the
 *   attacker is dragged back to before it acted, its copies look behind it, and it dies over and over - 3 different
 *   ways - then is lost in the loop for good. Its loot comes to you. Bosses: the attack is just nullified, unless the
 *   boss is down to 10% health - then it's the finisher.</li>
 *   <li><b>Life Giver</b> (C): two Iron Golems come to life beside you; villagers around you are healed.</li>
 *   <li><b>Golden Barrier</b> (V): a dome over the village where raiders can't hurt anything.</li>
 *   <li>Awakening: an Iron Golem Stand user, in a raid, at death's door, uses a Stand Arrow: 50/50.</li>
 * </ul>
 */
public final class Ger {
    private Ger() {}

    public static final String LOOP_TAG = "mobstands_ger_loop";
    public static final String LIFE_TAG = "mobstands_life_giver";
    /** Lost in the loop: being finished off, its drops go to the owner. */
    public static final String LOST_TAG = "mobstands_ger_lost";

    // ------------------------------------------------------------------ Return to Zero

    public static final int STANCE_TICKS = 40;
    /** The sequence, in ticks (the client cutscene uses the same timeline). */
    public static final int T_REWIND = 24, T_LOOK = 40, T_DEATHS = 64, DEATH_LEN = 30, T_END = T_DEATHS + 3 * DEATH_LEN, T_DONE = T_END + 8;
    /** Death kinds: 0 burn, 1 lightning, 2 drown, 3 fall, 4 anvil, 5 stab. */
    public static final int DEATH_KINDS = 6;
    private static final float FINISHER_HEALTH = 0.10F;

    private static final Map<UUID, Long> STANCE = new HashMap<>();

    private static final class Loop {
        ServerPlayer owner;
        LivingEntity mob;
        Vec3 hitPos, origin;
        int[] deaths;
        int t;
        boolean boss;
        boolean wasNoAi, wasInvulnerable, wasNoGravity;
    }

    private static final List<Loop> LOOPS = new ArrayList<>();

    public static boolean returnToZero(AbilityContext c) {
        long now = c.level.getGameTime();
        STANCE.put(c.owner.getUUID(), now + STANCE_TICKS);
        c.stand.setPosing(false);
        c.sound(SoundEvents.BEACON_POWER_SELECT, 1.0F, 1.6F);
        c.sound(SoundEvents.AMETHYST_BLOCK_RESONATE, 1.0F, 0.5F);
        c.level.sendParticles(new DustParticleOptions(new Vector3f(1.0F, 0.83F, 0.25F), 1.4F),
                c.owner.getX(), c.owner.getY() + 1.0D, c.owner.getZ(), 30, 0.5D, 0.8D, 0.5D, 0.0D);
        c.owner.displayClientMessage(Component.literal("Return to Zero...").withStyle(s -> s.withColor(0xF2C744).withItalic(true)), true);
        return true;
    }

    private static boolean inStance(ServerPlayer p) {
        Long until = STANCE.get(p.getUUID());
        return until != null && p.level().getGameTime() <= until && StandManager.data(p).getType() == StandType.GER;
    }

    public static boolean isBoss(Entity e) {
        return e instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon
                || e instanceof net.minecraft.world.entity.boss.wither.WitherBoss
                || e instanceof net.minecraft.world.entity.monster.warden.Warden
                || e instanceof net.minecraft.world.entity.monster.ElderGuardian;
    }

    /** From the damage event. True = the hit is cancelled. */
    public static boolean onHurt(LivingEntity victim, DamageSource src) {
        if (victim.level().isClientSide) return false;
        // Anything already caught in a loop can't be hurt by anything else.
        if (victim.getTags().contains(LOOP_TAG)) return true;
        if (protectedByBarrier(victim, src)) return true;
        if (!(victim instanceof ServerPlayer p) || !inStance(p)) return false;
        Entity att = src.getEntity();
        if (!(att instanceof LivingEntity attacker) || attacker == p) return false;
        STANCE.remove(p.getUUID());
        ServerLevel sl = p.serverLevel();
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ZOMBIE_VILLAGER_CURE, SoundSource.PLAYERS, 0.6F, 2.0F);
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BELL_RESONATE, SoundSource.PLAYERS, 1.2F, 1.8F);
        sl.sendParticles(ParticleTypes.FLASH, p.getX(), p.getY() + 1.0D, p.getZ(), 1, 0.0D, 0.0D, 0.0D, 0.0D);
        boolean boss = isBoss(attacker);
        boolean finisher = !boss || attacker.getHealth() <= attacker.getMaxHealth() * FINISHER_HEALTH;
        if (attacker instanceof Player || !finisher || attacker.getTags().contains(LOOP_TAG)) {
            // Nullified: the attack simply never landed.
            sl.sendParticles(new DustParticleOptions(new Vector3f(1.0F, 0.83F, 0.25F), 1.6F),
                    p.getX(), p.getY() + 1.0D, p.getZ(), 40, 0.6D, 0.8D, 0.6D, 0.0D);
            return true;
        }
        begin(p, attacker, boss);
        return true;
    }

    private static void begin(ServerPlayer p, LivingEntity mob, boolean boss) {
        Loop l = new Loop();
        l.owner = p;
        l.mob = mob;
        l.boss = boss;
        l.hitPos = mob.position();
        Vec3 away = mob.position().subtract(p.position()).multiply(1.0D, 0.0D, 1.0D);
        away = away.lengthSqr() < 1.0E-4D ? p.getLookAngle().multiply(-1.0D, 0.0D, -1.0D) : away.normalize();
        l.origin = mob.position().add(away.scale(boss ? 0.0D : 5.0D));
        // Three different deaths.
        List<Integer> kinds = new ArrayList<>();
        for (int i = 0; i < DEATH_KINDS; i++) kinds.add(i);
        java.util.Collections.shuffle(kinds, new java.util.Random(p.getRandom().nextLong()));
        l.deaths = new int[]{kinds.get(0), kinds.get(1), kinds.get(2)};
        l.wasInvulnerable = mob.isInvulnerable();
        l.wasNoGravity = mob.isNoGravity();
        mob.setInvulnerable(true);
        mob.setNoGravity(true);
        mob.addTag(LOOP_TAG);
        if (mob instanceof Mob m) {
            l.wasNoAi = m.isNoAi();
            m.setNoAi(true);
            m.getNavigation().stop();
        }
        LOOPS.add(l);
        GerCutscenePayload pkt = new GerCutscenePayload(mob.getId(), p.getId(), l.origin.x, l.origin.y, l.origin.z, l.deaths[0], l.deaths[1], l.deaths[2], false);
        PacketDistributor.sendToPlayer(p, new GerCutscenePayload(mob.getId(), p.getId(), l.origin.x, l.origin.y, l.origin.z, l.deaths[0], l.deaths[1], l.deaths[2], true));
        PacketDistributor.sendToPlayersTrackingEntity(mob, pkt);
    }

    /** The player pressed skip: jump straight to the end. */
    public static void skip(ServerPlayer p) {
        for (Loop l : LOOPS) if (l.owner == p && l.t < T_END) l.t = T_END;
    }

    public static void tick(ServerLevel sl) {
        long now = sl.getGameTime();
        STANCE.entrySet().removeIf(e -> e.getValue() < now - 5);
        tickBarriers(sl, now);
        if (LOOPS.isEmpty()) return;
        Iterator<Loop> it = LOOPS.iterator();
        while (it.hasNext()) {
            Loop l = it.next();
            LivingEntity mob = l.mob;
            if (mob.level() != sl) continue;
            if (mob.isRemoved() || l.owner.isRemoved()) {
                it.remove();
                release(l);
                continue;
            }
            int t = l.t++;
            mob.setDeltaMovement(Vec3.ZERO);
            if (t < T_REWIND) {
                hold(mob, l.hitPos);
            } else if (t < T_LOOK) {
                // Dragged back to before it ever acted.
                float k = (t - T_REWIND) / (float) (T_LOOK - T_REWIND);
                k = k * k * (3.0F - 2.0F * k);
                hold(mob, l.hitPos.add(l.origin.subtract(l.hitPos).scale(k)));
                if (t == T_REWIND) sl.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.CHORUS_FRUIT_TELEPORT, SoundSource.PLAYERS, 1.0F, 0.5F);
            } else if (t < T_DEATHS) {
                hold(mob, l.origin);
            } else if (t < T_END) {
                int i = (t - T_DEATHS) / DEATH_LEN, d = (t - T_DEATHS) % DEATH_LEN;
                deathTick(sl, l, l.deaths[i], d);
            } else if (t >= T_END) {
                it.remove();
                finish(sl, l);
            }
        }
    }

    private static void hold(LivingEntity mob, Vec3 at) {
        if (mob instanceof ServerPlayer sp) sp.connection.teleport(at.x, at.y, at.z, sp.getYRot(), sp.getXRot());
        else mob.setPos(at.x, at.y, at.z);
        mob.hurtMarked = true;
    }

    /** One death, d = 0..DEATH_LEN-1 ticks into it. */
    private static void deathTick(ServerLevel sl, Loop l, int kind, int d) {
        LivingEntity mob = l.mob;
        Vec3 o = l.origin;
        double h = mob.getBbHeight();
        switch (kind) {
            case 3 -> { // falls from the sky
                if (d < 10) hold(mob, o.add(0.0D, 9.0D - d * 0.9D, 0.0D));
                else hold(mob, o);
                if (d == 10) sl.playSound(null, o.x, o.y, o.z, SoundEvents.GENERIC_BIG_FALL, SoundSource.HOSTILE, 1.4F, 0.8F);
            }
            default -> hold(mob, o);
        }
        if (d == 0) {
            switch (kind) {
                case 0 -> { // burns
                    mob.setRemainingFireTicks(60);
                    sl.sendParticles(ParticleTypes.FLAME, o.x, o.y + h * 0.5D, o.z, 40, 0.4D, h * 0.4D, 0.4D, 0.03D);
                    sl.playSound(null, o.x, o.y, o.z, SoundEvents.FIRECHARGE_USE, SoundSource.HOSTILE, 1.0F, 0.8F);
                }
                case 1 -> { // struck by lightning
                    var bolt = EntityType.LIGHTNING_BOLT.create(sl);
                    if (bolt != null) {
                        bolt.moveTo(o.x, o.y, o.z);
                        bolt.setVisualOnly(true);
                        sl.addFreshEntity(bolt);
                    }
                }
                case 2 -> { // drowns
                    sl.sendParticles(ParticleTypes.BUBBLE_COLUMN_UP, o.x, o.y + h * 0.5D, o.z, 60, 0.3D, h * 0.5D, 0.3D, 0.1D);
                    sl.sendParticles(ParticleTypes.SPLASH, o.x, o.y + h, o.z, 30, 0.4D, 0.2D, 0.4D, 0.1D);
                    sl.playSound(null, o.x, o.y, o.z, SoundEvents.PLAYER_SPLASH_HIGH_SPEED, SoundSource.HOSTILE, 1.0F, 0.7F);
                }
                case 3 -> sl.sendParticles(ParticleTypes.CLOUD, o.x, o.y + 9.0D, o.z, 10, 0.3D, 0.1D, 0.3D, 0.02D);
                case 4 -> { // an anvil out of nowhere
                    sl.playSound(null, o.x, o.y + 4.0D, o.z, SoundEvents.ANVIL_FALL, SoundSource.HOSTILE, 1.0F, 1.0F);
                    sl.sendParticles(new BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.ANVIL.defaultBlockState()),
                            o.x, o.y + h + 3.0D, o.z, 30, 0.3D, 0.3D, 0.3D, 0.0D);
                }
                case 5 -> { // stabbed from nowhere
                    sl.sendParticles(ParticleTypes.SWEEP_ATTACK, o.x, o.y + h * 0.6D, o.z, 3, 0.3D, 0.2D, 0.3D, 0.0D);
                    sl.playSound(null, o.x, o.y, o.z, SoundEvents.TRIDENT_HIT, SoundSource.HOSTILE, 1.0F, 0.8F);
                }
                default -> { }
            }
        }
        if (d == (kind == 4 ? 8 : 6)) {
            // The blow lands: it flinches, and dies.
            sl.broadcastDamageEvent(mob, sl.damageSources().generic());
            if (kind == 4) {
                sl.playSound(null, o.x, o.y, o.z, SoundEvents.ANVIL_LAND, SoundSource.HOSTILE, 1.0F, 0.9F);
                sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.ANVIL.defaultBlockState()), o.x, o.y + h, o.z, 30, 0.3D, 0.2D, 0.3D, 0.1D);
            }
            if (kind == 5) {
                sl.sendParticles(ParticleTypes.CRIT, o.x, o.y + h * 0.6D, o.z, 20, 0.2D, 0.3D, 0.2D, 0.3D);
                sl.playSound(null, o.x, o.y, o.z, SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.HOSTILE, 1.0F, 0.8F);
            }
            sl.playSound(null, o.x, o.y, o.z, SoundEvents.GENERIC_HURT, SoundSource.HOSTILE, 1.0F, 0.7F);
        }
        if (d == 22) {
            // It's dead... and back where it started, again.
            mob.clearFire();
            sl.sendParticles(ParticleTypes.POOF, o.x, o.y + h * 0.5D, o.z, 18, 0.3D, h * 0.3D, 0.3D, 0.02D);
            sl.playSound(null, o.x, o.y, o.z, SoundEvents.GENERIC_DEATH, SoundSource.HOSTILE, 0.8F, 0.8F);
        }
    }

    /** It never arrives. Lost in the loop; what it carried is yours. */
    private static void finish(ServerLevel sl, Loop l) {
        LivingEntity mob = l.mob;
        ServerPlayer p = l.owner;
        Vec3 o = mob.position();
        sl.sendParticles(new DustParticleOptions(new Vector3f(1.0F, 0.83F, 0.25F), 1.8F), o.x, o.y + mob.getBbHeight() * 0.5D, o.z,
                60, 0.4D, mob.getBbHeight() * 0.4D, 0.4D, 0.0D);
        sl.sendParticles(ParticleTypes.END_ROD, o.x, o.y + mob.getBbHeight() * 0.5D, o.z, 25, 0.3D, 0.5D, 0.3D, 0.08D);
        sl.playSound(null, o.x, o.y, o.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.5F, 0.6F);
        mob.clearFire();
        release(l);
        mob.addTag(LOST_TAG); // its drops are redirected to the owner (see onDrops)
        mob.getPersistentData().putUUID("mobstands_ger_owner", p.getUUID());
        int xp = l.boss ? 0 : mob.getExperienceReward(sl, p);
        mob.setInvulnerable(false);
        DamageSource kill = sl.damageSources().playerAttack(p);
        if (mob instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon dragon) {
            dragon.hurt(dragon.head, kill, Float.MAX_VALUE / 4.0F);
        } else {
            mob.invulnerableTime = 0;
            mob.hurt(kill, Float.MAX_VALUE / 4.0F);
            if (mob.isAlive()) mob.kill();
        }
        if (!l.boss) {
            mob.discard(); // no body: it's gone, still dying somewhere you can't see
            if (xp > 0) p.giveExperiencePoints(xp);
        }
    }

    private static void release(Loop l) {
        LivingEntity mob = l.mob;
        mob.removeTag(LOOP_TAG);
        mob.setInvulnerable(l.wasInvulnerable);
        mob.setNoGravity(l.wasNoGravity);
        if (mob instanceof Mob m) m.setNoAi(l.wasNoAi);
    }

    /** Loot from a mob lost in the loop goes straight into its killer's inventory (or at their feet). */
    public static boolean onDrops(LivingEntity mob, java.util.Collection<ItemEntity> drops) {
        if (!mob.getTags().contains(LOST_TAG) || !mob.getPersistentData().hasUUID("mobstands_ger_owner")) return false;
        if (!(mob.level() instanceof ServerLevel sl)) return false;
        Player p = sl.getPlayerByUUID(mob.getPersistentData().getUUID("mobstands_ger_owner"));
        if (p == null) return false;
        for (ItemEntity ie : drops) {
            var stack = ie.getItem().copy();
            if (!p.getInventory().add(stack) && !stack.isEmpty()) p.drop(stack, false);
        }
        drops.clear();
        return true;
    }

    // ------------------------------------------------------------------ Life Giver

    public static boolean lifeGiver(AbilityContext c) {
        ServerLevel sl = c.level;
        ServerPlayer p = c.owner;
        Vec3 side = new Vec3(-c.flatLook().z, 0.0D, c.flatLook().x);
        for (int i = 0; i < 2; i++) {
            IronGolem g = EntityType.IRON_GOLEM.create(sl);
            if (g == null) continue;
            Vec3 at = p.position().add(side.scale(i == 0 ? 2.2D : -2.2D)).add(c.flatLook().scale(1.0D));
            g.moveTo(at.x, at.y, at.z, p.getYRot(), 0.0F);
            g.setPlayerCreated(true);
            g.addTag(LIFE_TAG);
            sl.addFreshEntity(g);
            sl.sendParticles(ParticleTypes.HAPPY_VILLAGER, at.x, at.y + 1.3D, at.z, 30, 0.6D, 1.0D, 0.6D, 0.0D);
            sl.sendParticles(new DustParticleOptions(new Vector3f(1.0F, 0.83F, 0.25F), 1.5F), at.x, at.y + 1.3D, at.z, 30, 0.6D, 1.0D, 0.6D, 0.0D);
            TickScheduler.later(800, () -> {
                if (g.isRemoved()) return;
                sl.sendParticles(ParticleTypes.POOF, g.getX(), g.getY() + 1.3D, g.getZ(), 20, 0.5D, 0.8D, 0.5D, 0.02D);
                g.discard();
            });
        }
        for (LivingEntity v : sl.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(24.0D),
                e -> e instanceof AbstractVillager || e instanceof IronGolem)) {
            v.setHealth(v.getMaxHealth());
            sl.sendParticles(ParticleTypes.HEART, v.getX(), v.getY() + v.getBbHeight() + 0.3D, v.getZ(), 4, 0.3D, 0.2D, 0.3D, 0.0D);
        }
        c.sound(SoundEvents.IRON_GOLEM_REPAIR, 1.2F, 0.8F);
        c.sound(SoundEvents.ZOMBIE_VILLAGER_CURE, 0.7F, 1.4F);
        return true;
    }

    // ------------------------------------------------------------------ Golden Barrier

    public static final int BARRIER_TICKS = 400;
    public static final double BARRIER_RADIUS = 14.0D;

    private record Barrier(ServerLevel level, Vec3 center, long until) {}

    private static final List<Barrier> BARRIERS = new ArrayList<>();

    public static boolean goldenBarrier(AbilityContext c) {
        BARRIERS.add(new Barrier(c.level, c.owner.position(), c.level.getGameTime() + BARRIER_TICKS));
        c.sound(SoundEvents.BEACON_ACTIVATE, 1.5F, 1.2F);
        c.sound(SoundEvents.BELL_BLOCK, 1.5F, 0.8F);
        c.owner.displayClientMessage(Component.literal("Nothing here will be harmed.").withStyle(s -> s.withColor(0xF2C744).withItalic(true)), true);
        return true;
    }

    private static boolean inBarrier(Entity e) {
        for (Barrier b : BARRIERS) {
            if (b.level == e.level() && e.position().distanceToSqr(b.center) <= BARRIER_RADIUS * BARRIER_RADIUS) return true;
        }
        return false;
    }

    /** Villagers, golems and players inside a barrier can't be hurt by raiders or monsters. */
    private static boolean protectedByBarrier(LivingEntity victim, DamageSource src) {
        if (BARRIERS.isEmpty()) return false;
        if (!(victim instanceof AbstractVillager || victim instanceof IronGolem || victim instanceof Player)) return false;
        Entity att = src.getEntity();
        boolean hostile = att instanceof Raider || att instanceof net.minecraft.world.entity.monster.Enemy
                || src.is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION);
        return hostile && inBarrier(victim);
    }

    private static void tickBarriers(ServerLevel sl, long now) {
        if (BARRIERS.isEmpty()) return;
        BARRIERS.removeIf(b -> b.level == sl && now >= b.until);
        for (Barrier b : BARRIERS) {
            if (b.level != sl) continue;
            // The dome: a shimmering golden shell.
            if (now % 4L == 0L) {
                for (int i = 0; i < 24; i++) {
                    double th = sl.random.nextDouble() * Math.PI * 2.0D, ph = Math.acos(sl.random.nextDouble());
                    double x = Math.sin(ph) * Math.cos(th), y = Math.cos(ph), z = Math.sin(ph) * Math.sin(th);
                    sl.sendParticles(new DustParticleOptions(new Vector3f(1.0F, 0.84F, 0.3F), 1.2F),
                            b.center.x + x * BARRIER_RADIUS, b.center.y + y * BARRIER_RADIUS, b.center.z + z * BARRIER_RADIUS, 1, 0.0D, 0.0D, 0.0D, 0.0D);
                }
            }
            // Raiders and monsters are pushed back out.
            for (LivingEntity e : sl.getEntitiesOfClass(LivingEntity.class, new net.minecraft.world.phys.AABB(b.center, b.center).inflate(BARRIER_RADIUS),
                    e -> (e instanceof Raider || e instanceof net.minecraft.world.entity.monster.Enemy) && !isBoss(e) && !e.getTags().contains(LOOP_TAG))) {
                Vec3 out = e.position().subtract(b.center);
                double dist = out.length();
                if (dist > BARRIER_RADIUS) continue;
                out = dist < 1.0E-3D ? new Vec3(1.0D, 0.0D, 0.0D) : out.scale(1.0D / dist);
                e.setDeltaMovement(out.x * 0.6D, 0.25D, out.z * 0.6D);
                e.hurtMarked = true;
            }
        }
    }

    // ------------------------------------------------------------------ the awakening

    /**
     * An Iron Golem Stand user, in a raid, at 3 hearts or less, uses a Stand Arrow. True = handled (no normal roll).
     */
    public static boolean tryAwaken(ServerPlayer p) {
        StandData d = StandManager.data(p);
        if (d.getType() != StandType.IRON_GOLEM || p.getHealth() > 6.0F) return false;
        var raid = p.serverLevel().getRaidAt(p.blockPosition());
        if (raid == null || raid.isOver()) return false;
        ServerLevel sl = p.serverLevel();
        if (p.getRandom().nextBoolean()) {
            p.setHealth(p.getMaxHealth());
            sl.broadcastEntityEvent(p, (byte) 35); // the totem of undying burst
            RequiemTrials.awaken(p, StandType.GER);
            return true;
        }
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ITEM_BREAK, SoundSource.PLAYERS, 1.0F, 0.6F);
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 1.0F, 0.8F);
        p.displayClientMessage(Component.literal("The arrow shatters... it rejected you.").withStyle(s -> s.withColor(0x9A9A9A).withItalic(true)), false);
        if (p.getRandom().nextFloat() < 0.15F) {
            d.resetProgress();
            StandManager.sync(p);
            p.displayClientMessage(Component.literal("Your Stand reels from the rejection... it's back to level 1.").withStyle(s -> s.withColor(0xC05050)), false);
        }
        return true;
    }

    public static void clear() {
        STANCE.clear();
        LOOPS.clear();
        BARRIERS.clear();
    }
}
