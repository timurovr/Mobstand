package com.mobstands.stand;

import com.mobstands.entity.StandEntity;
import com.mobstands.mixin.MobGoalsAccessor;
import com.mobstands.network.DinoPayload;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;

/**
 * Scary Monsters (Diego Brando), server side.
 * <ul>
 *   <li><b>Infection</b>: whatever its user hurts while the Stand is out starts turning into a dinosaur. It keeps its own
 *       body (so its own size), and after a while it's a dinosaur in your herd. Only the user infects - the herd never
 *       spreads it. If it dies before it finishes turning (or you kill it outright), it isn't over: it gets back up where
 *       it fell and keeps turning.</li>
 *   <li><b>The herd</b>: dinosaurs follow you, and only see what moves - they hunt monsters (and whatever fought you)
 *       as long as it keeps moving, and lose it the moment it stands still.</li>
 *   <li><b>Abilities</b>: Stampede, Living Wall, Call the Herd.</li>
 *   <li><b>The form</b>: its user is a Ravager - regeneration, speed, a high jump, no fall damage, and Steel Balls
 *       thrown at it get dodged.</li>
 * </ul>
 * State lives on the mob itself ({@link #TAG} in its persistent data), so infections and herds survive a reload.
 */
public final class ScaryMonsters {
    private ScaryMonsters() {}

    /** Persistent data on an infected mob: owner (UUID), start (game time), dur (ticks). */
    public static final String TAG = "mobstands_dino";

    /** Ticks a creature takes to turn: 10 s, down to 5.5 s at level 10. */
    public static int infectTicks(int level) {
        return 200 - 10 * (Math.max(1, level) - 1);
    }

    /** How many dinosaurs you can keep: 5, up to 14 at level 10. Past that, the oldest wanders off. */
    public static int herdCap(int level) {
        return 4 + Math.max(1, level);
    }

    /** Every infected (or turned) mob on the server. */
    private static final Map<UUID, Mob> TRACKED = new HashMap<>();
    /** Mobs whose AI has already been swapped for a dinosaur's. */
    private static final Set<UUID> CONVERTED = new HashSet<>();
    /** Stampede: owner -> [target UUID, until]. */
    private static final Map<UUID, Object[]> STAMPEDE = new HashMap<>();
    /** Owners with Living Wall up. */
    private static final Set<UUID> WALL = new HashSet<>();
    /** Steel Ball dodge: player -> game time the next dodge is ready / the current one ends. */
    private static final Map<UUID, long[]> DODGE = new HashMap<>();

    record Dino(UUID owner, long start, int dur) {
        boolean done(long now) { return now >= start + dur; }
    }

    // ================================================================== who's who

    public static boolean active(Player p) {
        StandData d = StandManager.data(p);
        return d.getType() == StandType.SCARY_MONSTERS && d.isSummoned();
    }

    /** Fully turned into the Ravager (the change has finished). */
    public static boolean transformed(ServerPlayer p) {
        if (!active(p)) return false;
        StandEntity s = StandManager.getStand(p);
        return s != null && s.morph(0.0F) >= 1.0F;
    }

    static Dino read(Entity e) {
        if (!(e instanceof Mob m) || !m.getPersistentData().contains(TAG)) return null;
        CompoundTag t = m.getPersistentData().getCompound(TAG);
        if (!t.hasUUID("owner")) return null;
        return new Dino(t.getUUID("owner"), t.getLong("start"), t.getInt("dur"));
    }

    private static void write(Mob m, Dino d) {
        CompoundTag t = new CompoundTag();
        t.putUUID("owner", d.owner());
        t.putLong("start", d.start());
        t.putInt("dur", d.dur());
        m.getPersistentData().put(TAG, t);
    }

    /** A finished dinosaur in this player's herd (your own abilities and hits pass it by). */
    public static boolean inHerdOf(Player p, Entity e) {
        if (TRACKED.isEmpty() || !(e instanceof Mob m) || !CONVERTED.contains(m.getUUID())) return false;
        Dino d = read(m);
        return d != null && d.owner().equals(p.getUUID());
    }

    private static boolean excluded(LivingEntity e) {
        return !(e instanceof Mob)
                || e.getType().is(net.neoforged.neoforge.common.Tags.EntityTypes.BOSSES)
                || e instanceof net.minecraft.world.entity.monster.warden.Warden;
    }

    /** This owner's finished dinosaurs, oldest first. */
    public static List<Mob> herd(UUID owner) {
        List<Mob> out = new ArrayList<>();
        for (Mob m : TRACKED.values()) {
            if (m.isRemoved() || !m.isAlive() || !CONVERTED.contains(m.getUUID())) continue;
            Dino d = read(m);
            if (d != null && d.owner().equals(owner)) out.add(m);
        }
        out.sort(Comparator.comparingLong(m -> read(m).start()));
        return out;
    }

    // ================================================================== infection

    /** Any damage: if the Stand's user dealt it, the victim starts turning. (Fires before a killing blow's death.) */
    public static void onHurt(LivingEntity victim, DamageSource src) {
        if (victim.level().isClientSide || !(src.getEntity() instanceof ServerPlayer p) || !active(p)) return;
        if (excluded(victim) || read(victim) != null) return;
        Mob m = (Mob) victim;
        ServerLevel sl = (ServerLevel) m.level();
        infect(m, new Dino(p.getUUID(), sl.getGameTime(), infectTicks(StandManager.data(p).getLevel())));
        sl.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, m.getX(), m.getY() + m.getBbHeight() * 0.6D, m.getZ(), 12, 0.3D, 0.3D, 0.3D, 0.0D);
        sl.playSound(null, m.getX(), m.getY(), m.getZ(), SoundEvents.ZOMBIE_INFECT, SoundSource.PLAYERS, 0.8F, 0.7F);
    }

    private static void infect(Mob m, Dino d) {
        write(m, d);
        m.setPersistenceRequired();
        TRACKED.put(m.getUUID(), m);
        sync(m, d);
    }

    /**
     * An infected creature that dies before it finished turning (or was killed by the very hit that infected it)
     * comes back: a moment later it rises where it fell - the same creature - and goes on turning from the start.
     * Its drops still fall as usual. A finished dinosaur that dies stays dead.
     */
    public static void onDeath(LivingEntity victim) {
        if (!(victim instanceof Mob m) || !(m.level() instanceof ServerLevel sl)) return;
        Dino d = read(m);
        if (d == null) return;
        TRACKED.remove(m.getUUID());
        boolean wasDino = CONVERTED.remove(m.getUUID());
        if (wasDino) return;
        EntityType<?> type = m.getType();
        CompoundTag body = m.saveWithoutId(new CompoundTag());
        for (String k : new String[]{"UUID", "Health", "DeathTime", "HurtTime", "HurtByTimestamp", "Fire", "Motion",
                "active_effects", "Brain", "Passengers", "Leash", "HandItems", "ArmorItems"}) {
            body.remove(k);
        }
        Vec3 at = m.position();
        float yaw = m.getYRot();
        int dur = d.dur();
        UUID owner = d.owner();
        TickScheduler.later(25, () -> {
            Entity n = type.create(sl);
            if (!(n instanceof Mob nm)) return;
            nm.load(body);
            nm.setUUID(UUID.randomUUID());
            nm.setHealth(nm.getMaxHealth());
            nm.moveTo(at.x, at.y, at.z, yaw, 0.0F);
            nm.setDeltaMovement(Vec3.ZERO);
            Dino nd = new Dino(owner, sl.getGameTime(), dur);
            write(nm, nd);
            nm.setPersistenceRequired();
            if (!sl.addFreshEntity(nm)) return;
            TRACKED.put(nm.getUUID(), nm); // (watchers get the infection when they start tracking it)
            sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.BONE_BLOCK.defaultBlockState()),
                    at.x, at.y + 0.5D, at.z, 30, 0.4D, 0.4D, 0.4D, 0.15D);
            sl.sendParticles(ParticleTypes.SOUL, at.x, at.y + 0.3D, at.z, 10, 0.3D, 0.2D, 0.3D, 0.03D);
            sl.playSound(null, at.x, at.y, at.z, SoundEvents.ZOMBIE_VILLAGER_CURE, SoundSource.HOSTILE, 0.7F, 0.6F);
        });
    }

    /** After a reload: infected mobs pick up where they left off. */
    public static void onJoin(Entity e) {
        if (!(e instanceof Mob m) || m.level().isClientSide) return;
        Dino d = read(m);
        if (d == null) return;
        TRACKED.put(m.getUUID(), m);
        if (d.done(m.level().getGameTime())) convert(m, d, false);
    }

    public static void onStartTracking(ServerPlayer viewer, Entity e) {
        Dino d = read(e);
        if (d != null) PacketDistributor.sendToPlayer(viewer, new DinoPayload(e.getId(), d.start(), d.dur(), d.owner()));
    }

    private static void sync(Mob m, Dino d) {
        PacketDistributor.sendToPlayersTrackingEntity(m, new DinoPayload(m.getId(), d.start(), d.dur(), d.owner()));
    }

    // ================================================================== turning, and going back

    private static void convert(Mob m, Dino d, boolean loud) {
        if (!CONVERTED.add(m.getUUID())) return;
        MobGoalsAccessor g = (MobGoalsAccessor) m;
        g.mobstands$goals().removeAllGoals(x -> true);
        g.mobstands$targets().removeAllGoals(x -> true);
        m.setTarget(null);
        g.mobstands$goals().addGoal(0, new FloatGoal(m));
        g.mobstands$goals().addGoal(1, new BiteGoal(m));
        g.mobstands$goals().addGoal(2, new FollowGoal(m));
        g.mobstands$goals().addGoal(3, new RandomLookAroundGoal(m));
        g.mobstands$targets().addGoal(1, new HuntGoal(m));
        m.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
        if (!loud || !(m.level() instanceof ServerLevel sl)) return;
        sl.sendParticles(ParticleTypes.POOF, m.getX(), m.getY() + m.getBbHeight() * 0.5D, m.getZ(), 15, 0.4D, 0.4D, 0.4D, 0.03D);
        sl.playSound(null, m.getX(), m.getY(), m.getZ(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 0.8F,
                1.6F - Math.min(0.8F, m.getBbHeight() * 0.3F)); // small dinosaurs roar higher
        if (sl.getServer().getPlayerList().getPlayer(d.owner()) instanceof ServerPlayer p) {
            List<Mob> herd = herd(p.getUUID());
            int cap = herdCap(StandManager.data(p).getLevel());
            while (herd.size() > cap) revert(herd.remove(0)); // the oldest wanders off
            p.displayClientMessage(Component.literal("Scary Monsters: a " + m.getType().getDescription().getString()
                    + " joined your herd (" + herd.size() + "/" + cap + ")").withStyle(s -> s.withColor(0xC8A15A)), true);
        }
    }

    /** It's itself again: same creature, its own AI back (rebuilt from its saved body). */
    public static void revert(Mob m) {
        TRACKED.remove(m.getUUID());
        CONVERTED.remove(m.getUUID());
        if (!(m.level() instanceof ServerLevel sl) || m.isRemoved()) return;
        PacketDistributor.sendToPlayersTrackingEntity(m, new DinoPayload(m.getId(), 0L, 0, new UUID(0L, 0L)));
        m.getPersistentData().remove(TAG);
        CompoundTag body = m.saveWithoutId(new CompoundTag());
        body.remove("UUID");
        Entity n = m.getType().create(sl);
        if (!(n instanceof Mob nm)) return;
        nm.load(body);
        nm.setUUID(UUID.randomUUID());
        m.discard();
        sl.addFreshEntity(nm);
        sl.sendParticles(ParticleTypes.CLOUD, nm.getX(), nm.getY() + nm.getBbHeight() * 0.5D, nm.getZ(), 10, 0.3D, 0.3D, 0.3D, 0.02D);
    }

    // ================================================================== every tick

    public static void tick(MinecraftServer server) {
        if (!TRACKED.isEmpty()) {
            Iterator<Map.Entry<UUID, Mob>> it = TRACKED.entrySet().iterator();
            List<Mob> toConvert = new ArrayList<>(), toRevert = new ArrayList<>();
            while (it.hasNext()) {
                Mob m = it.next().getValue();
                if (m.isRemoved()) {
                    if (m.getRemovalReason() != null && m.getRemovalReason().shouldSave()) continue; // just unloaded
                    it.remove();
                    CONVERTED.remove(m.getUUID());
                    continue;
                }
                Dino d = read(m);
                if (d == null) {
                    it.remove();
                    continue;
                }
                long now = m.level().getGameTime();
                if (!d.done(now)) {
                    float prog = (now - d.start()) / (float) d.dur();
                    if (now % 20 == 0) m.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 30, prog > 0.5F ? 2 : 0, true, false));
                    if (now % 5 == 0 && m.level() instanceof ServerLevel sl) {
                        sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.MUD.defaultBlockState()),
                                m.getX(), m.getY() + m.getBbHeight() * 0.5D, m.getZ(), 2, m.getBbWidth() * 0.4D, m.getBbHeight() * 0.3D,
                                m.getBbWidth() * 0.4D, 0.0D);
                    }
                    continue;
                }
                if (!CONVERTED.contains(m.getUUID())) toConvert.add(m);
                ServerPlayer owner = server.getPlayerList().getPlayer(d.owner());
                // Its user gave up the Stand: the herd is itself again.
                if (owner != null && StandManager.data(owner).getType() != StandType.SCARY_MONSTERS) toRevert.add(m);
            }
            for (Mob m : toConvert) convert(m, read(m), true);
            for (Mob m : toRevert) revert(m);
        }
        STAMPEDE.values().removeIf(v -> server.overworld().getGameTime() > (long) v[1]);
        if (server.getTickCount() % 20 == 0) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (!transformed(p)) {
                    WALL.remove(p.getUUID());
                    continue;
                }
                // The dinosaur's body: it heals fast, runs fast, jumps high.
                p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 60, 1, true, false, true));
                p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 60, 1, true, false, true));
                p.addEffect(new MobEffectInstance(MobEffects.JUMP, 60, 2, true, false, true));
            }
        }
    }

    public static void shutdown() {
        TRACKED.clear();
        CONVERTED.clear();
        STAMPEDE.clear();
        WALL.clear();
        DODGE.clear();
    }

    // ================================================================== the form

    /** A dinosaur lands on its feet. */
    public static boolean noFall(Entity e) {
        return e instanceof ServerPlayer p && transformed(p);
    }

    /**
     * A Steel Ball is about to hit {@code target}: a transformed user sees it coming (dinosaurs see what moves) and
     * sidesteps it. True = it missed. One dodge every 2 seconds.
     */
    public static boolean dodge(LivingEntity target, Entity ball) {
        if (!(target instanceof ServerPlayer p) || !transformed(p)) return false;
        long now = p.level().getGameTime();
        long[] st = DODGE.computeIfAbsent(p.getUUID(), u -> new long[]{0L, 0L});
        if (now < st[1]) return true;   // mid-dodge: still out of its way
        if (now < st[0]) return false;  // just dodged one
        st[0] = now + 40;
        st[1] = now + 8;
        Vec3 v = ball.getDeltaMovement();
        Vec3 side = new Vec3(-v.z, 0.0D, v.x);
        side = side.lengthSqr() < 1.0E-6D ? new Vec3(1.0D, 0.0D, 0.0D) : side.normalize();
        if (p.getRandom().nextBoolean()) side = side.scale(-1.0D);
        p.setDeltaMovement(side.scale(1.1D).add(0.0D, 0.3D, 0.0D));
        p.hurtMarked = true;
        if (p.level() instanceof ServerLevel sl) {
            sl.sendParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + 0.5D, p.getZ(), 8, 0.3D, 0.2D, 0.3D, 0.02D);
            sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.RAVAGER_STEP, SoundSource.PLAYERS, 1.0F, 1.4F);
        }
        return true;
    }

    /** Living Wall: part of every hit on you is taken by the nearest dinosaur instead. Returns what's left for you. */
    public static float wall(ServerPlayer p, DamageSource src, float amount) {
        if (!WALL.contains(p.getUUID()) || amount <= 0.0F) return amount;
        Mob best = null;
        double bestD = 25.0D;
        for (Mob m : herd(p.getUUID())) {
            double d = m.distanceToSqr(p);
            if (d < bestD) {
                bestD = d;
                best = m;
            }
        }
        if (best == null) return amount;
        best.invulnerableTime = 0;
        best.hurt(p.damageSources().generic(), amount * 0.7F);
        if (p.level() instanceof ServerLevel sl) {
            sl.sendParticles(ParticleTypes.CRIT, best.getX(), best.getY() + best.getBbHeight() * 0.6D, best.getZ(), 8, 0.2D, 0.2D, 0.2D, 0.2D);
        }
        return amount * 0.3F;
    }

    // ================================================================== abilities

    /** Z - Stampede: the whole herd charges the creature you're looking at. */
    public static boolean stampede(AbilityContext c) {
        List<Mob> herd = herd(c.owner.getUUID());
        if (herd.isEmpty()) {
            c.fail = "You have no dinosaurs yet. Hurt something to infect it.";
            return false;
        }
        LivingEntity t = c.aim(32.0D);
        if (t == null) {
            c.fail = "Look at what the herd should run down.";
            return false;
        }
        STAMPEDE.put(c.owner.getUUID(), new Object[]{t.getUUID(), c.level.getGameTime() + 200L});
        for (Mob m : herd) {
            m.setTarget(t);
            m.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 200, 1, true, false));
        }
        c.level.playSound(null, c.owner.getX(), c.owner.getY(), c.owner.getZ(), SoundEvents.RAVAGER_ROAR, SoundSource.PLAYERS, 1.2F, 0.9F);
        return true;
    }

    /** X - Living Wall (toggle): the herd closes in around you and takes the hits meant for you. */
    public static boolean livingWall(AbilityContext c) {
        UUID u = c.owner.getUUID();
        boolean on = WALL.add(u);
        if (!on) WALL.remove(u);
        c.owner.displayClientMessage(Component.literal(on ? "Living Wall: the herd closes in around you." : "Living Wall: the herd breaks formation.")
                .withStyle(s -> s.withColor(0xC8A15A)), true);
        c.level.playSound(null, c.owner.getX(), c.owner.getY(), c.owner.getZ(), on ? SoundEvents.RAVAGER_STUNNED : SoundEvents.RAVAGER_AMBIENT,
                SoundSource.PLAYERS, 1.0F, 1.0F);
        return true;
    }

    /** C - Call the Herd: every dinosaur comes back to you at once and roars, throwing back whatever is around it. */
    public static boolean callHerd(AbilityContext c) {
        List<Mob> herd = herd(c.owner.getUUID());
        if (herd.isEmpty()) {
            c.fail = "You have no dinosaurs yet. Hurt something to infect it.";
            return false;
        }
        int i = 0;
        for (Mob m : herd) {
            if (m.level() != c.level) continue;
            double a = i++ * (Math.PI * 2.0D / herd.size());
            if (m.distanceToSqr(c.owner) > 36.0D) {
                m.teleportTo(c.owner.getX() + Math.cos(a) * 2.5D, c.owner.getY(), c.owner.getZ() + Math.sin(a) * 2.5D);
            }
            m.setTarget(null);
            for (LivingEntity e : c.enemies(m.position(), 3.0D)) {
                if (inHerdOf(c.owner, e)) continue;
                c.push(e, e.position().subtract(m.position()), 1.4D, 0.4D);
            }
            c.level.sendParticles(ParticleTypes.POOF, m.getX(), m.getY() + 0.5D, m.getZ(), 6, 0.3D, 0.2D, 0.3D, 0.05D);
        }
        STAMPEDE.remove(c.owner.getUUID());
        c.level.playSound(null, c.owner.getX(), c.owner.getY(), c.owner.getZ(), SoundEvents.RAVAGER_ROAR, SoundSource.PLAYERS, 1.4F, 0.8F);
        return true;
    }

    // ================================================================== dinosaur AI

    private static ServerPlayer ownerOf(Mob m) {
        Dino d = read(m);
        return d == null || m.level().getServer() == null ? null : m.level().getServer().getPlayerList().getPlayer(d.owner());
    }

    /** Dinosaurs only see what moves. */
    static boolean moving(Entity e) {
        double dx = e.getX() - e.xo, dy = e.getY() - e.yo, dz = e.getZ() - e.zo;
        return dx * dx + dy * dy + dz * dz > 1.0E-4D;
    }

    private static boolean prey(Mob m, ServerPlayer owner, LivingEntity e) {
        if (e == m || e == owner || !e.isAlive()) return false;
        if (owner != null && (!StandCombat.valid(owner, e) || inHerdOf(owner, e))) return false;
        if (e instanceof Player pl && (pl.isCreative() || pl.isSpectator())) return false;
        return true;
    }

    /** Picks what to hunt: a Stampede target, else whatever moving thing is after you (or you're after). */
    static final class HuntGoal extends Goal {
        private final Mob m;
        private int still;

        HuntGoal(Mob m) {
            this.m = m;
            setFlags(EnumSet.of(Flag.TARGET));
        }

        @Override
        public boolean canUse() {
            return m.tickCount % 10 == 0;
        }

        @Override
        public boolean canContinueToUse() {
            return false;
        }

        @Override
        public void start() {
            ServerPlayer owner = ownerOf(m);
            if (owner == null) {
                m.setTarget(null);
                return;
            }
            Object[] st = STAMPEDE.get(owner.getUUID());
            if (st != null && ((ServerLevel) m.level()).getEntity((UUID) st[0]) instanceof LivingEntity t && t.isAlive()) {
                m.setTarget(t);
                return;
            }
            LivingEntity cur = m.getTarget();
            if (cur != null) {
                // It stopped moving: it's gone, as far as a dinosaur can tell.
                still = moving(cur) ? 0 : still + 10;
                if (!cur.isAlive() || still >= 30 || !prey(m, owner, cur) || cur.distanceToSqr(m) > 32.0D * 32.0D) {
                    m.setTarget(null);
                    still = 0;
                }
                return;
            }
            boolean wall = WALL.contains(owner.getUUID());
            LivingEntity best = null;
            double bestD = Double.MAX_VALUE;
            for (LivingEntity e : m.level().getEntitiesOfClass(LivingEntity.class, m.getBoundingBox().inflate(16.0D),
                    x -> prey(m, owner, x) && moving(x))) {
                boolean hostile = e instanceof Enemy || e == owner.getLastHurtByMob() || e == owner.getLastHurtMob()
                        || (e instanceof Mob em && em.getTarget() == owner);
                if (!hostile) continue;
                if (wall && e.distanceToSqr(owner) > 25.0D) continue; // holding the wall: only what comes close
                double d = e.distanceToSqr(m);
                if (d < bestD) {
                    bestD = d;
                    best = e;
                }
            }
            if (best != null) m.setTarget(best);
        }
    }

    /** Runs its target down and bites. Bigger dinosaurs bite harder. */
    static final class BiteGoal extends Goal {
        private final Mob m;
        private int cooldown, repath;

        BiteGoal(Mob m) {
            this.m = m;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            LivingEntity t = m.getTarget();
            return t != null && t.isAlive();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void stop() {
            m.getNavigation().stop();
        }

        @Override
        public void tick() {
            LivingEntity t = m.getTarget();
            if (t == null) return;
            m.getLookControl().setLookAt(t, 30.0F, 30.0F);
            if (--repath <= 0) {
                repath = 5;
                m.getNavigation().moveTo(t, 1.3D);
            }
            if (cooldown > 0) cooldown--;
            double reach = m.getBbWidth() * 0.5D + t.getBbWidth() * 0.5D + 0.9D;
            if (cooldown == 0 && m.distanceToSqr(t) <= reach * reach) {
                cooldown = 20;
                float dmg = 3.0F + Math.min(6.0F, m.getBbHeight() * 2.5F);
                m.swing(InteractionHand.MAIN_HAND);
                t.hurt(m.damageSources().mobAttack(m), dmg);
                Vec3 push = t.position().subtract(m.position()).multiply(1.0D, 0.0D, 1.0D);
                if (push.lengthSqr() > 1.0E-4D) t.knockback(0.4D, -push.x, -push.z);
                m.level().playSound(null, m.getX(), m.getY(), m.getZ(), SoundEvents.RAVAGER_ATTACK, SoundSource.HOSTILE, 0.7F,
                        1.6F - Math.min(0.8F, m.getBbHeight() * 0.3F));
            }
        }
    }

    /** Stays with its user: close behind, or in a ring around them while Living Wall is up. */
    static final class FollowGoal extends Goal {
        private final Mob m;
        private int repath;

        FollowGoal(Mob m) {
            this.m = m;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            return m.getTarget() == null && ownerOf(m) != null;
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            ServerPlayer o = ownerOf(m);
            if (o == null || o.level() != m.level()) return;
            double d = m.distanceToSqr(o);
            if (d > 24.0D * 24.0D) { // fell too far behind: it catches up
                m.teleportTo(o.getX() + (m.getRandom().nextDouble() - 0.5D) * 4.0D, o.getY(), o.getZ() + (m.getRandom().nextDouble() - 0.5D) * 4.0D);
                return;
            }
            if (--repath > 0) return;
            repath = 8;
            if (WALL.contains(o.getUUID())) {
                List<Mob> herd = herd(o.getUUID());
                int i = Math.max(0, herd.indexOf(m));
                double a = i * (Math.PI * 2.0D / Math.max(1, herd.size())) + o.level().getGameTime() * 0.01D;
                m.getNavigation().moveTo(o.getX() + Math.cos(a) * 2.2D, o.getY(), o.getZ() + Math.sin(a) * 2.2D, 1.4D);
            } else if (d > 16.0D) {
                m.getNavigation().moveTo(o, 1.25D);
            } else {
                m.getNavigation().stop();
            }
        }
    }
}
