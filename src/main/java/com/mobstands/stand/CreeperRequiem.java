package com.mobstands.stand;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Green Detonator: Bites the Dust - the Creeper's Requiem, a charged creeper.
 *
 * <p>Primer: touch a mob with your Stand and it becomes the bomb; press again and every primed mob goes off.
 * <p>Bites the Dust: the clock winds back 6 seconds around you - and everyone who hurt you in those seconds still
 * carries the charge: when the world snaps back, they blow up.
 *
 * <p>How you get it: a level 10 Creeper Stand (or you, with it out) struck by lightning - a charged creeper.
 */
public final class CreeperRequiem {
    private CreeperRequiem() {}

    /** Mobs primed as bombs, per player. */
    private static final Map<UUID, Set<UUID>> PRIMED = new HashMap<>();
    /** Who hurt each Bites the Dust user, and when. */
    private static final Map<UUID, Map<UUID, Long>> HURT_BY = new HashMap<>();

    // ---- Primer ----

    /** Heart Attack (Passive) tags a mob: it joins your Primer bombs, waiting for the click. */
    public static void addPrimed(ServerPlayer p, LivingEntity t) {
        PRIMED.computeIfAbsent(p.getUUID(), k -> new HashSet<>()).add(t.getUUID());
    }

    /** Is this mob anyone's primed bomb? */
    public static boolean isPrimed(Entity e) {
        for (Set<UUID> set : PRIMED.values()) if (set.contains(e.getUUID())) return true;
        return false;
    }

    public static boolean primer(AbilityContext c) {
        ServerPlayer p = c.owner;
        ServerLevel sl = c.level;
        Set<UUID> set = PRIMED.computeIfAbsent(p.getUUID(), k -> new HashSet<>());
        set.removeIf(u -> !(sl.getEntity(u) instanceof LivingEntity le) || !le.isAlive());
        LivingEntity t = c.aim(16.0D);
        if (t != null && !set.contains(t.getUUID())) {
            // Primes it: a click, sparks, and it's a bomb now.
            set.add(t.getUUID());
            c.stand.playAnim(0);
            sl.playSound(null, t.getX(), t.getY(), t.getZ(), SoundEvents.STONE_BUTTON_CLICK_ON, SoundSource.PLAYERS, 1.2F, 0.6F);
            sl.playSound(null, t.getX(), t.getY(), t.getZ(), SoundEvents.CREEPER_PRIMED, SoundSource.PLAYERS, 0.5F, 1.6F);
            sl.sendParticles(ParticleTypes.ELECTRIC_SPARK, t.getX(), t.getY() + t.getBbHeight() * 0.6D, t.getZ(), 20, 0.3D, 0.4D, 0.3D, 0.1D);
            p.displayClientMessage(Component.literal("Primed (" + set.size() + "). Press again with nothing new in sight to detonate.")
                    .withStyle(s -> s.withColor(0x7FD4FF)), true);
            return true;
        }
        if (set.isEmpty()) {
            p.displayClientMessage(Component.literal("Look at a mob to prime it.").withStyle(s -> s.withColor(0x7FD4FF)), true);
            return false;
        }
        // Click: every primed mob goes off. Shift + click: and they really blow up - a true blast that tears up
        // the area and hurts everything around them (never you).
        boolean loud = p.isShiftKeyDown();
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.STONE_BUTTON_CLICK_OFF, SoundSource.PLAYERS, 1.5F, 0.5F);
        for (UUID u : set) {
            if (sl.getEntity(u) instanceof LivingEntity le && le.isAlive()) vaporize(c, le, loud);
        }
        set.clear();
        return true;
    }

    /**
     * Killer Queen's first bomb: only the bomb itself goes. No blast, no broken blocks, nobody else hurt, no body and
     * no drops - a flash, a muffled thump, a wisp of smoke, and it's simply gone. (Players and bosses can't just
     * vanish: they take a huge hit instead.)
     */
    /** Bumped by every Bites the Dust: a detonation still fizzing when time winds back never goes off. */
    private static int rewinds;
    /** Mobs fizzing right now (so a rewind can put out their fuses). */
    private static final Set<LivingEntity> FUSING = new HashSet<>();

    /** Time wound back: every Primer bomb still on its fuse is put out - those blasts never happen. */
    public static void defuseAll() {
        rewinds++;
        for (LivingEntity le : FUSING) if (le.isAlive() && !le.isRemoved()) sendFuse(le, 0);
        FUSING.clear();
    }

    private static void vaporize(AbilityContext c, LivingEntity le, boolean loud) {
        ServerLevel sl = c.level;
        final int gen = rewinds;
        FUSING.add(le);
        // The fuse: it swells with sparks and flickers, a hiss rising, for about a second...
        sl.playSound(null, le.getX(), le.getY(), le.getZ(), SoundEvents.CREEPER_PRIMED, SoundSource.PLAYERS, 1.8F, 0.6F);
        // It swells up and flashes white, wobbling, exactly like a creeper about to blow.
        for (int i = 0; i <= 20; i++) {
            final int fuse = 1 + Math.round(i * (PropertySwap.FUSE - 3) / 20.0F);
            TickScheduler.later(i, () -> {
                if (gen == rewinds && le.isAlive() && !le.isRemoved()) sendFuse(le, fuse);
            });
        }
        for (int i = 0; i < 20; i += 2) {
            final int k = i;
            TickScheduler.later(i, () -> {
                if (gen != rewinds || !le.isAlive()) return;
                Vec3 m = le.position().add(0.0D, le.getBbHeight() * 0.5D, 0.0D);
                sl.sendParticles(ParticleTypes.ELECTRIC_SPARK, m.x, m.y, m.z, 4 + k, le.getBbWidth() * 0.5D, le.getBbHeight() * 0.4D, le.getBbWidth() * 0.5D, 0.05D);
                if (k % 6 == 0) sl.sendParticles(ParticleTypes.FLASH, m.x, m.y, m.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
                if (k % 4 == 0) sl.playSound(null, m.x, m.y, m.z, SoundEvents.NOTE_BLOCK_HAT.value(), SoundSource.PLAYERS, 1.0F, 0.6F + k * 0.04F);
            });
        }
        // ...then the big, slow blast - fireball, shockwave, mushroom cloud - and it's simply gone. The blast is only
        // a show: no blocks break, nobody else is hurt, nothing is left of it.
        TickScheduler.later(20, () -> {
            FUSING.remove(le);
            if (gen != rewinds || !le.isAlive() || le.isRemoved()) return; // (wound back before it went off)
            sendFuse(le, 0);
            Vec3 at = le.position().add(0.0D, le.getBbHeight() * 0.5D, 0.0D);
            AbilityContext.bigBlastFx(sl, at, 2.8D);
            AbilityContext.juicyBlastFx(sl, at, 2.8D, true);
            // Shift: a real explosion - blocks (if Stand block damage is on) and everyone around it, but never you.
            if (loud) c.explode(at, 3.5D);
            boolean vanish = !(le instanceof net.minecraft.world.entity.player.Player)
                    && !(le instanceof net.minecraft.world.entity.boss.wither.WitherBoss)
                    && !(le instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon);
            if (vanish) {
                le.discard();
            } else {
                le.invulnerableTime = 0;
                c.hurt(le, 30.0F);
            }
        });
    }

    private static void sendFuse(LivingEntity le, int fuse) {
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntityAndSelf(le,
                new com.mobstands.network.FusePayload(le.getId(), fuse));
    }

    /** Server tick: primed mobs fizz with sparks so you can see them. */
    public static void tick(ServerLevel sl) {
        if (PRIMED.isEmpty() || sl.getGameTime() % 6L != 0L) return;
        for (Set<UUID> set : PRIMED.values()) {
            for (UUID u : set) {
                Entity e = sl.getEntity(u);
                if (e instanceof LivingEntity le && le.isAlive()) {
                    sl.sendParticles(ParticleTypes.ELECTRIC_SPARK, e.getX(), e.getY() + e.getBbHeight() + 0.2D, e.getZ(), 2, 0.15D, 0.1D, 0.15D, 0.02D);
                }
            }
        }
    }

    // ---- Bites the Dust ----

    /** The Requiem's user just got hurt: remember by whom (they're the ones who'll carry the bomb back). */
    public static void onOwnerHurt(ServerPlayer p, DamageSource src) {
        if (StandManager.data(p).getType() != StandType.CREEPER_REQUIEM) return;
        Entity by = src.getEntity();
        if (!(by instanceof LivingEntity) || by == p) return;
        HURT_BY.computeIfAbsent(p.getUUID(), k -> new HashMap<>()).put(by.getUUID(), p.level().getGameTime());
    }

    public static boolean bitesTheDust(AbilityContext c) {
        ServerPlayer p = c.owner;
        ServerLevel sl = c.level;
        long now = sl.getGameTime();
        // Who hurt you in the seconds about to be undone.
        List<UUID> marked = new ArrayList<>();
        Map<UUID, Long> hurt = HURT_BY.getOrDefault(p.getUUID(), Map.of());
        for (Map.Entry<UUID, Long> e : hurt.entrySet()) if (now - e.getValue() <= Mandom.WINDOW) marked.add(e.getKey());
        if (!Mandom.rewind(p)) return false;
        HURT_BY.remove(p.getUUID());
        // Bombs still fizzing are put out the moment it starts winding back.
        defuseAll();
        // The Stand blew itself up in those seconds? That's undone too.
        c.later(Mandom.WIND_TICKS, () -> c.stand.endReform());
        // The whole sound in one: the switch, a clock ticking backwards over a tape-rewind squeal and a sinking
        // drone, a heartbeat, then the snap (timed to land on WIND_TICKS).
        Vec3 at = c.stand.position();
        sl.playSound(null, at.x, at.y, at.z, com.mobstands.registry.ModSounds.BITES_THE_DUST.get(), SoundSource.PLAYERS, 2.5F, 1.0F);
        // ...and the creeper in it: a deep hiss as the switch is pressed, a second, higher one as the rewind peaks,
        // and a real creeper blast on the snap.
        sl.playSound(null, at.x, at.y, at.z, SoundEvents.CREEPER_PRIMED, SoundSource.PLAYERS, 2.0F, 0.5F);
        c.later(Mandom.WIND_TICKS - 10, () -> {
            Vec3 s = c.stand.position();
            sl.playSound(null, s.x, s.y, s.z, SoundEvents.CREEPER_PRIMED, SoundSource.PLAYERS, 1.8F, 1.0F);
        });
        // Sparks spiralling in, faster and faster, as the clock winds back.
        int t = 0, gap = 5;
        while (t < Mandom.WIND_TICKS - 1) {
            final int k = t;
            c.later(k, () -> c.ring(ParticleTypes.ELECTRIC_SPARK, c.stand.position().add(0.0D, 0.8D, 0.0D), 1.0D + k * 0.08D, 14));
            t += gap;
            gap = Math.max(2, gap - 1);
        }
        c.later(Mandom.WIND_TICKS, () -> {
            Vec3 s = p.position();
            sl.playSound(null, s.x, s.y, s.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 1.2F, 0.7F);
            sl.sendParticles(ParticleTypes.FLASH, s.x, s.y + 1.0D, s.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
            sl.sendParticles(ParticleTypes.ELECTRIC_SPARK, s.x, s.y + 1.0D, s.z, 50, 0.2D, 0.3D, 0.2D, 0.6D);
        });
        c.later(Mandom.WIND_TICKS + 6, () -> {
            for (UUID u : marked) {
                if (sl.getEntity(u) instanceof LivingEntity le && le.isAlive() && le.distanceTo(p) < Mandom.RADIUS) {
                    c.explode(le.position().add(0.0D, le.getBbHeight() * 0.5D, 0.0D), 2.8D);
                }
            }
        });
        return true;
    }

    // ---- getting it ----

    /** A level 10 Creeper Stand (or its user) struck by lightning becomes a charged creeper: the Requiem. */
    public static void struck(ServerPlayer p) {
        StandData d = StandManager.data(p);
        if (d.getType() != StandType.CREEPER || d.getLevel() < StandData.MAX_LEVEL) return;
        ServerLevel sl = p.serverLevel();
        var old = StandManager.getStand(p);
        Vec3 at = old != null ? old.position() : p.position();
        StandManager.removeEntity(p);
        d.setType(StandType.CREEPER_REQUIEM);
        d.setSummoned(false);
        sl.sendParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y + 0.8D, at.z, 60, 0.6D, 0.8D, 0.6D, 0.2D);
        sl.playSound(null, at.x, at.y, at.z, SoundEvents.CREEPER_PRIMED, SoundSource.PLAYERS, 2.0F, 0.6F);
        sl.playSound(null, at.x, at.y, at.z, SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 1.5F, 0.7F);
        StandManager.summon(p);
        p.sendSystemMessage(Component.literal("The lightning charged your Creeper... REQUIEM: " + StandType.CREEPER_REQUIEM.standName() + "!")
                .withStyle(s -> s.withColor(0x7FD4FF).withBold(true)));
        StandManager.sync(p);
    }

    public static void clear() {
        PRIMED.clear();
        FUSING.clear();
        HURT_BY.clear();
    }
}
