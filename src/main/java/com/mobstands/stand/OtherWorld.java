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
 * a world next door - the same place, but nobody else is there. The rest of the mob crowd is simply gone. Settle it
 * one on one; when it dies (or after a minute) you slip back, and everyone else is right where they were.
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
        if (SESSIONS.containsKey(p.getUUID())) {
            p.displayClientMessage(Component.literal("You're already in another world.").withStyle(s -> s.withColor(0xB07CFF)), true);
            return false;
        }
        LivingEntity t = c.aim(20.0D);
        if (!(t instanceof Mob)) {
            p.displayClientMessage(Component.literal("Look at the mob you want to take with you.").withStyle(s -> s.withColor(0xB07CFF)), true);
            return false;
        }
        ServerLevel sl = c.level;
        Session s = new Session(p.getUUID(), t.getUUID(), sl, p.position(), sl.getGameTime());
        SESSIONS.put(p.getUUID(), s);
        int gone = sweep(s, p);
        // The crossing: a flag snaps, the world flickers, and everyone else is just... not here.
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ILLUSIONER_MIRROR_MOVE, SoundSource.PLAYERS, 1.5F, 0.6F);
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 1.5F, 0.5F);
        sl.sendParticles(ParticleTypes.REVERSE_PORTAL, p.getX(), p.getY() + 1.0D, p.getZ(), 60, 0.8D, 1.0D, 0.8D, 0.05D);
        sl.sendParticles(ParticleTypes.REVERSE_PORTAL, t.getX(), t.getY() + 1.0D, t.getZ(), 40, 0.5D, 0.8D, 0.5D, 0.05D);
        p.displayClientMessage(Component.literal("Another world: just you and it" + (gone > 0 ? " (" + gone + " left behind)" : "") + ".")
                .withStyle(st -> st.withColor(0xB07CFF).withItalic(true)), true);
        return true;
    }

    /** Everything else nearby goes to wait in the original world (saved exactly as it is). */
    private static int sweep(Session s, ServerPlayer p) {
        int n = 0;
        AABB box = new AABB(p.position(), p.position()).inflate(SWEEP, 256.0D, SWEEP);
        for (Mob m : s.level.getEntitiesOfClass(Mob.class, box, x -> !x.getUUID().equals(s.target) && x.isAlive()
                && !(x instanceof OwnableEntity o && p.getUUID().equals(o.getOwnerUUID())) && !x.isPassenger() && !x.isVehicle())) {
            CompoundTag tag = new CompoundTag();
            if (!m.saveAsPassenger(tag)) continue;
            s.elsewhere.add(tag);
            m.discard();
            n++;
        }
        return n;
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
            else if (t == null || !t.isAlive() || t.isRemoved()) why = "It's over. You slip back into your own world.";
            else if (now - s.started > MAX_TICKS) why = "The way back pulls you home.";
            else if (p.position().distanceTo(s.center) > RADIUS) why = "You wandered too far: you slip back into your own world.";
            if (why != null) {
                it.remove();
                restore(s, p, why);
                continue;
            }
            // Anything that wanders in from outside isn't in this world either.
            if (now % 10L == 0L) sweep(s, p);
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
        if (p == null) return;
        s.level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ILLUSIONER_MIRROR_MOVE, SoundSource.PLAYERS, 1.5F, 0.9F);
        s.level.sendParticles(ParticleTypes.PORTAL, p.getX(), p.getY() + 1.0D, p.getZ(), 60, 0.8D, 1.0D, 0.8D, 0.3D);
        if (!why.isEmpty()) p.displayClientMessage(Component.literal(why).withStyle(st -> st.withColor(0xB07CFF).withItalic(true)), true);
    }

    /** The server's stopping: everyone comes back before anything is saved. */
    public static void shutdown() {
        for (Session s : SESSIONS.values()) restore(s, null, "");
        SESSIONS.clear();
    }
}
