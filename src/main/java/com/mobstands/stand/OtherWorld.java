package com.mobstands.stand;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Pigstep: Another World - Dirty Deeds Done Dirt Cheap style. You and the mob you're looking at slip into
 * a world next door - the same place, but nobody else is there. Settle it one on one; when it dies (or after a
 * minute, or you press it again) you slip back, and everyone else is right where they were.
 *
 * Done per player, not by deleting anything: you stop seeing every other mob and player, and they stop seeing you
 * and your mob (see TrackedEntityMixin). Nothing can hurt across the gap - a hit, an arrow or a blast from one world
 * never lands in the other - and nothing outside can keep chasing you.
 */
public final class OtherWorld {
    private OtherWorld() {}

    public static final double RADIUS = 48.0D;
    /** How far out the world is emptied: as far as you could possibly see. */
    public static final double SWEEP = 192.0D;
    public static final int MAX_TICKS = 1200; // one minute

    private static final class Session {
        final UUID owner, target;
        final ServerLevel level;
        final Vec3 center;
        final long started;
        final List<CompoundTag> elsewhere = new ArrayList<>();

        Session(UUID owner, UUID target, ServerLevel level, Vec3 center, long started) {
            this.owner = owner;
            this.target = target;
            this.level = level;
            this.center = center;
            this.started = started;
        }
    }

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    public static boolean use(AbilityContext c) {
        ServerPlayer p = c.owner;
        if (SESSIONS.containsKey(p.getUUID()) || sessionOf(p) != null) {
            p.displayClientMessage(Component.literal("You're already in another world.").withStyle(s -> s.withColor(0xB07CFF)), true);
            return false;
        }
        LivingEntity t = c.aim(20.0D);
        if (t instanceof ServerPlayer tp && (tp.isSpectator() || tp == p)) t = null;
        if (t != null && sessionOf(t) != null) {
            p.displayClientMessage(Component.literal("They're already in another world.").withStyle(s -> s.withColor(0xB07CFF)), true);
            return false;
        }
        if (!(t instanceof Mob) && !(t instanceof ServerPlayer)) {
            p.displayClientMessage(Component.literal("Look at the mob or player you want to take with you.").withStyle(s -> s.withColor(0xB07CFF)), true);
            return false;
        }
        ServerLevel sl = c.level;
        Session s = new Session(p.getUUID(), t.getUUID(), sl, p.position(), sl.getGameTime());
        SESSIONS.put(p.getUUID(), s);
        // The two of you drop out of everyone else's world (and they out of yours).
        refresh(sl);
        if (t instanceof Mob m) m.setTarget(p); // it's you and it now
        // The crossing: a flag snaps, the world flickers, and everyone else is just... not here.
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ILLUSIONER_MIRROR_MOVE, SoundSource.PLAYERS, 1.5F, 0.6F);
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 1.5F, 0.5F);
        sl.sendParticles(ParticleTypes.REVERSE_PORTAL, p.getX(), p.getY() + 1.0D, p.getZ(), 60, 0.8D, 1.0D, 0.8D, 0.05D);
        sl.sendParticles(ParticleTypes.REVERSE_PORTAL, t.getX(), t.getY() + 1.0D, t.getZ(), 40, 0.5D, 0.8D, 0.5D, 0.05D);
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new com.mobstands.network.AnotherWorldPayload(true));
        if (t instanceof ServerPlayer tp) {
            // they get pulled through too
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(tp, new com.mobstands.network.AnotherWorldPayload(true));
            tp.displayClientMessage(Component.literal("Another world: just you and " + p.getName().getString() + ".")
                    .withStyle(st -> st.withColor(0xB07CFF).withItalic(true)), true);
        }
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENDER_EYE_DEATH, SoundSource.PLAYERS, 1.2F, 0.6F);
        p.displayClientMessage(Component.literal("Another world: just you and it.")
                .withStyle(st -> st.withColor(0xB07CFF).withItalic(true)), true);
        return true;
    }

    public static void tick(MinecraftServer server) {
        if (SESSIONS.isEmpty()) return;
        Iterator<Session> it = SESSIONS.values().iterator();
        while (it.hasNext()) {
            Session s = it.next();
            ServerPlayer p = server.getPlayerList().getPlayer(s.owner);
            Entity t = s.level.getEntity(s.target);
            long now = s.level.getGameTime();
            String why = null;
            if (p == null || !p.isAlive() || p.level() != s.level) why = "";
            else if (t == null || !t.isAlive() || t.isRemoved() || (t instanceof ServerPlayer tp && tp.hasDisconnected())) why = "It's over. You slip back into your own world.";
            else if (now - s.started > MAX_TICKS) why = "The way back pulls you home.";
            else if (p.position().distanceTo(s.center) > RADIUS) why = "You wandered too far: you slip back into your own world.";
            if (why != null) {
                it.remove();
                restore(s, p, why);
                continue;
            }
            // Nothing from the real world can keep after you two, and your mob only has eyes for you.
            keepApart(s, p, t);
            if (now % 4L == 0L) {
                s.level.sendParticles(p, ParticleTypes.REVERSE_PORTAL, true, p.getX(), p.getY() + 1.0D, p.getZ(), 1, 6.0D, 2.0D, 6.0D, 0.0D);
            }
        }
    }

    private static void restore(Session s, ServerPlayer p, String why) {
        for (CompoundTag tag : s.elsewhere) {
            Entity back = EntityType.loadEntityRecursive(tag, s.level, x -> x);
            if (back != null && s.level.getEntity(back.getUUID()) == null) s.level.addFreshEntity(back);
        }
        s.elsewhere.clear();
        refresh(s.level); // everyone can see each other again
        ServerPlayer other = s.level.getServer().getPlayerList().getPlayer(s.target);
        if (other != null) {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(other, new com.mobstands.network.AnotherWorldPayload(false));
            if (other.level() != s.level) refresh((ServerLevel) other.level());
            if (!why.isEmpty() && other.isAlive()) other.displayClientMessage(Component.literal("You slip back into your own world.")
                    .withStyle(st -> st.withColor(0xB07CFF).withItalic(true)), true);
        }
        if (p == null) return;
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new com.mobstands.network.AnotherWorldPayload(false));
        s.level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ILLUSIONER_MIRROR_MOVE, SoundSource.PLAYERS, 1.5F, 0.9F);
        s.level.sendParticles(ParticleTypes.PORTAL, p.getX(), p.getY() + 1.0D, p.getZ(), 60, 0.8D, 1.0D, 0.8D, 0.3D);
        if (!why.isEmpty()) p.displayClientMessage(Component.literal(why).withStyle(st -> st.withColor(0xB07CFF).withItalic(true)), true);
    }

    // ------------------------------------------------------------------ who's in which world

    /** The other world this entity is in (null = the real one): its owner, its mob, the owner's Stand, their shots. */
    private static Session sessionOf(Entity e) {
        if (e == null || SESSIONS.isEmpty()) return null;
        UUID id = e.getUUID();
        for (Session s : SESSIONS.values()) {
            if (id.equals(s.owner) || id.equals(s.target)) return s;
            if (e instanceof com.mobstands.entity.StandEntity st
                    && st.getOwnerUUID().map(o -> o.equals(s.owner) || o.equals(s.target)).orElse(false)) return s;
        }
        if (e instanceof net.minecraft.world.entity.projectile.Projectile pr && pr.getOwner() != null && pr.getOwner() != e) {
            return sessionOf(pr.getOwner());
        }
        return null;
    }

    /** Can't {@code viewer} see {@code e}? (They're in different worlds.) Read by TrackedEntityMixin. */
    public static boolean hiddenFrom(Entity e, ServerPlayer viewer) {
        if (SESSIONS.isEmpty() || e == viewer) return false;
        return sessionOf(e) != sessionOf(viewer);
    }

    /** Are these two in different worlds? (Then nothing between them lands.) */
    public static boolean separated(Entity a, Entity b) {
        if (SESSIONS.isEmpty() || a == null || b == null || a == b) return false;
        return sessionOf(a) != sessionOf(b);
    }

    /** Re-check who sees whom, for everyone in this level, right now. */
    private static void refresh(ServerLevel sl) {
        for (ServerPlayer pl : sl.players()) sl.getChunkSource().move(pl);
    }

    private static void keepApart(Session s, ServerPlayer p, Entity target) {
        if (target instanceof Mob m && m.getTarget() != p) m.setTarget(p);
        AABB box = new AABB(s.center, s.center).inflate(SWEEP, 64.0D, SWEEP);
        for (Mob m : s.level.getEntitiesOfClass(Mob.class, box, x -> true)) {
            if (m.getTarget() != null && separated(m, m.getTarget())) m.setTarget(null);
            // mobs that think with a brain (piglins, hoglins, wardens...) keep their target somewhere else
            var brain = m.getBrain();
            if (brain.hasMemoryValue(net.minecraft.world.entity.ai.memory.MemoryModuleType.ATTACK_TARGET)) {
                LivingEntity bt = brain.getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.ATTACK_TARGET).orElse(null);
                if (bt != null && separated(m, bt)) brain.eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.ATTACK_TARGET);
            }
            if (m instanceof net.minecraft.world.entity.monster.warden.Warden w) {
                for (ServerPlayer pl : s.level.players()) if (separated(w, pl)) w.getAngerManagement().clearAnger(pl);
            }
            // a creeper hissing at someone who isn't in its world anymore calms back down
            if (m instanceof net.minecraft.world.entity.monster.Creeper cr && !cr.isIgnited() && cr.getSwellDir() > 0
                    && (cr.getTarget() == null || separated(cr, cr.getTarget()))) cr.setSwellDir(-1);
        }
    }

    /** Are you in another world right now? */
    public static boolean inside(ServerPlayer p) {
        return SESSIONS.containsKey(p.getUUID());
    }

    /** Press Another World again: you come straight back, whenever you like. */
    public static void leave(ServerPlayer p) {
        Session s = SESSIONS.remove(p.getUUID());
        if (s != null) restore(s, p, "You step back into your own world.");
    }

    /** The server's stopping: everyone comes back before anything is saved. */
    public static void shutdown() {
        for (Session s : SESSIONS.values()) restore(s, null, "");
        SESSIONS.clear();
    }
}
