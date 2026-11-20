package com.mobstands.stand;

import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.ShriekParticleOption;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Warden Requiem - Wonder of U. Nobody wants to come after you: most creatures simply leave you alone, a few panic
 * and run. The ones that do pursue you build up calamity, and the world turns on them - they trip, stalactites fall,
 * they catch fire, lightning finds them - until it kills them. Bosses aren't afraid of you, and nothing happens to them.
 */
public final class Calamity {
    private Calamity() {}

    public static final double IGNORE_CHANCE = 0.70D, PANIC_CHANCE = 0.10D;
    /** Calamity points between accidents (players build at half speed). */
    private static final double STEP = 60.0D;

    private record Fear(int decision, long until) {}   // 0 ignore, 1 panic, 2 pursue
    private static final Map<UUID, Fear> FEAR = new HashMap<>();
    private static final Map<UUID, Double> CAL = new HashMap<>();
    private static final Map<UUID, Double> NEXT = new HashMap<>();
    private static final Map<UUID, UUID> CAL_USER = new HashMap<>();
    private static final Map<UUID, Long> PANIC = new HashMap<>();
    private static final Map<UUID, UUID> PANIC_FROM = new HashMap<>();
    private static final Map<UUID, Long> MARKED = new HashMap<>();
    private static final Map<UUID, UUID> MARKED_BY = new HashMap<>();
    /** Players who hit a Warden Requiem user: they're "after" them for a while. */
    private static final Map<UUID, Long> ENGAGED = new HashMap<>();
    private static final Map<UUID, UUID> ENGAGED_WITH = new HashMap<>();
    private static final Map<UUID, Long> MISDIRECT = new HashMap<>();
    private static final Map<UUID, Long> HIDDEN = new HashMap<>();
    private static final Map<UUID, Long> RAIN = new HashMap<>();

    // ------------------------------------------------------------------ who

    public static boolean isUser(Entity e) {
        return e instanceof ServerPlayer p && StandManager.data(p).getType() == StandType.WARDEN_REQUIEM;
    }

    /** They know they can beat you. */
    public static boolean boss(Entity e) {
        if (e instanceof net.minecraft.world.entity.boss.wither.WitherBoss
                || e instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon
                || e instanceof net.minecraft.world.entity.monster.warden.Warden
                || e instanceof net.minecraft.world.entity.monster.ElderGuardian) return true;
        return e instanceof net.minecraft.world.entity.raid.Raider r && (r.isCaptain() || r.isPatrolLeader());
    }

    private static void drop(Mob m) {
        m.setTarget(null);
        m.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
    }

    private static long now(Entity e) {
        return e.level().getGameTime();
    }

    // ------------------------------------------------------------------ fear

    /** A mob is about to go after someone. True = it doesn't (cancel). */
    public static boolean onChangeTarget(LivingEntity mob, LivingEntity target) {
        if (!(mob instanceof Mob m) || target == null || !isUser(target) || boss(mob)) return false;
        long now = now(mob);
        Long hid = HIDDEN.get(target.getUUID());
        if (hid != null && now < hid) return true;
        return decide(m, (ServerPlayer) target, now) != 2;
    }

    private static int decide(Mob m, ServerPlayer user, long now) {
        Long panic = PANIC.get(m.getUUID());
        if (panic != null && now < panic) return 1;
        Fear f = FEAR.get(m.getUUID());
        if (f != null && now < f.until) return f.decision;
        double r = m.getRandom().nextDouble();
        int d = r < IGNORE_CHANCE ? 0 : r < IGNORE_CHANCE + PANIC_CHANCE ? 1 : 2;
        // It thinks again every few seconds.
        FEAR.put(m.getUUID(), new Fear(d, now + 100L + m.getRandom().nextInt(60)));
        if (d == 1) {
            PANIC.put(m.getUUID(), now + 200L);
            PANIC_FROM.put(m.getUUID(), user.getUUID());
            m.level().playSound(null, m.getX(), m.getY(), m.getZ(), SoundEvents.WARDEN_NEARBY_CLOSE, SoundSource.HOSTILE, 0.4F, 1.6F);
        }
        return d;
    }

    // ------------------------------------------------------------------ calamity

    private static void add(LivingEntity e, ServerPlayer user, double points) {
        if (boss(e) || e == user || (e instanceof Player p && (p.isCreative() || p.isSpectator()))) return;
        CAL.merge(e.getUUID(), points, Double::sum);
        NEXT.putIfAbsent(e.getUUID(), STEP);
        CAL_USER.put(e.getUUID(), user.getUUID());
    }

    /** Someone hurt a Warden Requiem user: the universe takes note. */
    public static void onUserHurt(ServerPlayer user, Entity attacker) {
        if (!(attacker instanceof LivingEntity a) || a == user || boss(a) || NullBubble.popping()) return;
        if (a instanceof Player) {
            add(a, user, 40.0D);
            ENGAGED.put(a.getUUID(), now(a) + 300L);
            ENGAGED_WITH.put(a.getUUID(), user.getUUID());
        } else {
            add(a, user, 80.0D);
        }
    }

    /** The Warden Requiem user struck someone: hiding ends. */
    public static void onUserAttacks(ServerPlayer user) {
        HIDDEN.remove(user.getUUID());
    }

    /** One accident, worse the more calamity it has built. */
    private static void accident(ServerLevel sl, LivingEntity e, double points, ServerPlayer user) {
        boolean player = e instanceof Player;
        if (user != null && !player) StandCombat.credit(user, e);
        int tier = (int) Math.min(4, points / 120.0D);
        if (player) tier = Math.min(tier, 3);
        Vec3 head = e.position().add(0.0D, e.getBbHeight(), 0.0D);
        switch (tier) {
            case 0 -> {
                // It trips.
                e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 30, 3, false, false));
                e.setDeltaMovement(e.getDeltaMovement().add((sl.random.nextDouble() - 0.5D) * 0.4D, 0.1D, (sl.random.nextDouble() - 0.5D) * 0.4D));
                e.hurtMarked = true;
                e.hurt(sl.damageSources().fall(), 1.0F);
                sl.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.GENERIC_SMALL_FALL, SoundSource.HOSTILE, 1.0F, 0.9F);
            }
            case 1 -> stalactite(sl, e, head, player ? 3.0F : 5.0F);
            case 2 -> {
                if (e.fireImmune()) {
                    stalactite(sl, e, head, player ? 3.0F : 6.0F);
                } else {
                    e.igniteForTicks(player ? 40 : 100);
                    sl.sendParticles(ParticleTypes.FLAME, e.getX(), e.getY() + 0.5D, e.getZ(), 12, 0.3D, 0.4D, 0.3D, 0.02D);
                    sl.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.FIRECHARGE_USE, SoundSource.HOSTILE, 0.8F, 1.2F);
                }
            }
            case 3 -> {
                if (!player && sl.isRainingAt(e.blockPosition().above((int) Math.ceil(e.getBbHeight())))) {
                    LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(sl);
                    if (bolt != null) {
                        bolt.moveTo(e.getX(), e.getY(), e.getZ());
                        sl.addFreshEntity(bolt);
                    }
                } else {
                    stalactite(sl, e, head, player ? 4.0F : 8.0F);
                }
            }
            default -> stalactite(sl, e, head, 12.0F); // it doesn't walk away from this one
        }
    }

    /** How close a monster can come before calamity starts building on it (faster and faster the closer). */
    public static final double CLOSE_RANGE = 16.0D;

    /** It got too close: the last accident, the one nothing survives. A creeper's fuse goes out first. */
    /** Already on their way out (the dripstone is falling): don't drop a second one. */
    private static final Map<UUID, Long> DOOMED = new HashMap<>();

    private static void doom(ServerLevel sl, Mob m, ServerPlayer user) {
        Long d = DOOMED.get(m.getUUID());
        if (d != null && sl.getGameTime() < d) return;
        DOOMED.put(m.getUUID(), sl.getGameTime() + 60L);
        if (DOOMED.size() > 512) DOOMED.clear();
        if (m instanceof net.minecraft.world.entity.monster.Creeper cr) cr.setSwellDir(-1);
        StandCombat.credit(user, m);
        Vec3 head = m.position().add(0.0D, m.getBbHeight(), 0.0D);
        float lethal = m.getMaxHealth() * 4.0F + 20.0F;
        int pick = sl.random.nextInt(3);
        if (pick == 0 && sl.isRainingAt(m.blockPosition().above((int) Math.ceil(m.getBbHeight())))) {
            LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(sl);
            if (bolt != null) {
                bolt.moveTo(m.getX(), m.getY(), m.getZ());
                bolt.setVisualOnly(true);
                sl.addFreshEntity(bolt);
            }
            m.invulnerableTime = 0;
            m.hurt(sl.damageSources().lightningBolt(), lethal);
        } else if (pick == 1 && !m.fireImmune()) {
            // It trips, cracks its head on the ground and goes up in flames.
            sl.sendParticles(ParticleTypes.FLAME, m.getX(), m.getY() + 0.5D, m.getZ(), 20, 0.3D, 0.4D, 0.3D, 0.04D);
            sl.playSound(null, m.getX(), m.getY(), m.getZ(), SoundEvents.GENERIC_BIG_FALL, SoundSource.HOSTILE, 1.0F, 0.8F);
            m.igniteForTicks(100);
            m.invulnerableTime = 0;
            m.hurt(sl.damageSources().fall(), lethal);
        } else {
            // Frozen in place under it, it looks up as the dripstone comes down.
            m.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 10, false, false));
            m.getNavigation().stop();
            stalactite(sl, m, lethal, true);
            return;
        }
        if (m.isAlive()) { // something soaked it up (armor, resistance...): it still doesn't make it
            m.invulnerableTime = 0;
            m.hurt(sl.damageSources().fallingStalactite(null), 1.0E6F);
        }
    }

    private static void stalactite(ServerLevel sl, LivingEntity e, Vec3 head, float dmg) {
        stalactite(sl, e, dmg, false);
    }

    /**
     * A real pointed dripstone breaks loose a few blocks above it and falls (following it, so it lands): it shatters
     * on its head. {@code finish}: nothing survives it.
     */
    private static void stalactite(ServerLevel sl, LivingEntity e, float dmg, boolean finish) {
        net.minecraft.world.level.block.state.BlockState tip = Blocks.POINTED_DRIPSTONE.defaultBlockState()
                .setValue(net.minecraft.world.level.block.PointedDripstoneBlock.TIP_DIRECTION, net.minecraft.core.Direction.DOWN);
        net.minecraft.core.BlockPos top = net.minecraft.core.BlockPos.containing(e.getX(), e.getY() + e.getBbHeight() + 0.2D, e.getZ());
        net.minecraft.core.BlockPos from = null;
        for (int up = 0; up <= 4; up++) {
            net.minecraft.core.BlockPos bp = top.above(up);
            if (!sl.getBlockState(bp).isAir()) break; // never touches a real block
            from = bp;
        }
        if (from == null) { // no room above: it just comes down out of nowhere
            impact(sl, e, dmg, finish);
            return;
        }
        net.minecraft.world.entity.item.FallingBlockEntity fb = net.minecraft.world.entity.item.FallingBlockEntity.fall(sl, from, tip);
        fb.disableDrop();
        fb.dropItem = false;
        sl.playSound(null, from.getX() + 0.5D, from.getY() + 0.5D, from.getZ() + 0.5D, SoundEvents.POINTED_DRIPSTONE_DRIP_LAVA, SoundSource.BLOCKS, 1.0F, 0.6F);
        sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.DRIPSTONE_BLOCK.defaultBlockState()),
                from.getX() + 0.5D, from.getY() + 0.9D, from.getZ() + 0.5D, 8, 0.2D, 0.05D, 0.2D, 0.02D);
        followFall(sl, fb, e, dmg, finish, 0);
    }

    private static void followFall(ServerLevel sl, net.minecraft.world.entity.item.FallingBlockEntity fb, LivingEntity e, float dmg, boolean finish, int t) {
        TickScheduler.later(1, () -> {
            if (fb.isRemoved()) { // it hit something on the way
                if (e.isAlive()) impact(sl, e, dmg, finish);
                return;
            }
            if (!e.isAlive() || e.level() != sl || t > 60) {
                fb.discard();
                return;
            }
            fb.setPos(e.getX(), fb.getY(), e.getZ()); // it's coming for it
            if (fb.getY() <= e.getY() + e.getBbHeight() + 0.1D) {
                fb.discard();
                impact(sl, e, dmg, finish);
                return;
            }
            followFall(sl, fb, e, dmg, finish, t + 1);
        });
    }

    private static void impact(ServerLevel sl, LivingEntity e, float dmg, boolean finish) {
        Vec3 head = e.position().add(0.0D, e.getBbHeight(), 0.0D);
        sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.POINTED_DRIPSTONE.defaultBlockState()),
                head.x, head.y + 0.3D, head.z, 24, 0.25D, 0.3D, 0.25D, 0.1D);
        sl.playSound(null, head.x, head.y, head.z, SoundEvents.POINTED_DRIPSTONE_LAND, SoundSource.BLOCKS, 1.2F, 1.0F);
        e.invulnerableTime = 0;
        e.hurt(sl.damageSources().fallingStalactite(null), dmg);
        if (finish && e.isAlive()) {
            e.invulnerableTime = 0;
            e.hurt(sl.damageSources().fallingStalactite(null), 1.0E6F);
        }
    }

    // ------------------------------------------------------------------ abilities

    public static boolean pursuitMark(AbilityContext c) {
        LivingEntity t = c.aim(32.0D);
        if (t == null) return false;
        if (boss(t)) {
            c.fail = "It isn't afraid of you.";
            return false;
        }
        add(t, c.owner, 0.0D);
        CAL.put(t.getUUID(), 480.0D);
        c.level.playSound(null, t.getX(), t.getY(), t.getZ(), SoundEvents.WARDEN_HEARTBEAT, SoundSource.PLAYERS, 2.0F, 0.6F);
        c.level.sendParticles(ParticleTypes.SCULK_SOUL, t.getX(), t.getY() + 1.0D, t.getZ(), 12, 0.3D, 0.5D, 0.3D, 0.02D);
        // A quick chain of misfortune, each worse than the last.
        for (int i = 0; i < 5; i++) {
            double pts = i * 120.0D;
            c.later(10 + i * 10, () -> {
                if (t.isAlive()) accident(c.level, t, pts, c.owner);
            });
        }
        return true;
    }

    public static boolean calamityRain(AbilityContext c) {
        if (!RainPowers.wet(c.owner)) {
            c.fail = "No rain.";
            return false;
        }
        RAIN.put(c.owner.getUUID(), c.level.getGameTime() + 200L);
        c.sound(SoundEvents.WARDEN_SONIC_CHARGE, 1.0F, 0.6F);
        c.sound(SoundEvents.WEATHER_RAIN_ABOVE, 1.5F, 0.6F);
        return true;
    }

    public static boolean shriekerToll(AbilityContext c) {
        ServerPlayer p = c.owner;
        long until = c.level.getGameTime() + 400L;
        for (int i = 0; i < 4; i++) {
            c.level.sendParticles(new ShriekParticleOption(i * 5), p.getX(), p.getY() + 0.5D, p.getZ(), 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
        c.sound(SoundEvents.SCULK_SHRIEKER_SHRIEK, 2.0F, 0.8F);
        int n = 0;
        for (LivingEntity e : c.level.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(24.0D),
                x -> x != p && x.isAlive() && !boss(x) && (x instanceof net.minecraft.world.entity.monster.Enemy
                        || (x instanceof Player pl && StandCombat.valid(p, pl))))) {
            MARKED.put(e.getUUID(), until);
            MARKED_BY.put(e.getUUID(), p.getUUID());
            n++;
        }
        return n > 0 || true;
    }

    public static boolean misdirection(AbilityContext c) {
        MISDIRECT.put(c.owner.getUUID(), c.level.getGameTime() + 120L);
        c.sound(SoundEvents.WARDEN_TENDRIL_CLICKS, 1.5F, 0.8F);
        c.fx(ParticleTypes.SCULK_SOUL, c.owner.position().add(0.0D, 1.0D, 0.0D), 10, 0.5D, 0.02D);
        return true;
    }

    public static boolean unseenPresence(AbilityContext c) {
        ServerPlayer p = c.owner;
        HIDDEN.put(p.getUUID(), c.level.getGameTime() + 300L);
        for (Mob m : c.level.getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(48.0D), m -> m.getTarget() == p && !boss(m))) drop(m);
        c.sound(SoundEvents.AMBIENT_CAVE.value(), 1.0F, 0.6F);
        c.fx(ParticleTypes.SCULK_SOUL, p.position().add(0.0D, 1.0D, 0.0D), 16, 0.4D, 0.02D);
        return true;
    }

    /** Misdirection: something shot at you misses - and misfortune finds the shooter. True = cancel the hit. */
    /** Shots turned back at whoever fired them: projectile -> {shooter, when it gives up}. */
    private record Return(UUID shooter, long until) {}
    private static final Map<UUID, Return> RETURNING = new HashMap<>();
    private static final Map<UUID, ServerLevel> RETURN_LEVEL = new HashMap<>();

    public static boolean onProjectileHit(Projectile pr, Entity hit) {
        if (!(hit instanceof ServerPlayer user) || !isUser(user)) return false;
        if (RETURNING.containsKey(pr.getUUID())) return true; // already on its way back: it passes through you
        // Whatever is shot at you swerves just short of you, turns round and flies all the way back to its shooter.
        if (pr.getOwner() instanceof LivingEntity shooter && shooter != user && !boss(shooter) && shooter.isAlive()) {
            ServerLevel sl = user.serverLevel();
            RETURNING.put(pr.getUUID(), new Return(shooter.getUUID(), now(user) + 120L));
            RETURN_LEVEL.put(pr.getUUID(), sl);
            StandCombat.credit(user, shooter);
            add(shooter, user, 20.0D);
            pr.setDeltaMovement(pr.getDeltaMovement().scale(-0.15D)); // it stalls in the air and starts to turn
            pr.hurtMarked = true;
            sl.sendParticles(ParticleTypes.SCULK_SOUL, pr.getX(), pr.getY(), pr.getZ(), 4, 0.1D, 0.1D, 0.1D, 0.02D);
            sl.playSound(null, pr.getX(), pr.getY(), pr.getZ(), SoundEvents.WARDEN_TENDRIL_CLICKS, SoundSource.HOSTILE, 1.0F, 1.4F);
            return true;
        }
        Long until = MISDIRECT.get(user.getUUID());
        if (until == null || now(user) >= until) return false;
        ServerLevel sl = user.serverLevel();
        sl.sendParticles(ParticleTypes.SCULK_SOUL, pr.getX(), pr.getY(), pr.getZ(), 4, 0.1D, 0.1D, 0.1D, 0.02D);
        pr.setDeltaMovement(pr.getDeltaMovement().yRot((float) ((sl.random.nextBoolean() ? 1 : -1) * Math.PI / 3.0D)));
        if (pr.getOwner() instanceof LivingEntity shooter && !boss(shooter) && shooter != user) {
            StandCombat.credit(user, shooter);
            stalactite(sl, shooter, shooter.position().add(0.0D, shooter.getBbHeight(), 0.0D), shooter instanceof Player ? 3.0F : 5.0F);
        }
        return true;
    }

    // ------------------------------------------------------------------ tick

    /** Turned-back shots curve round and home in on their shooter (flying, not teleporting). */
    private static void steerReturning() {
        if (RETURNING.isEmpty()) return;
        Iterator<Map.Entry<UUID, Return>> it = RETURNING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Return> en = it.next();
            ServerLevel sl = RETURN_LEVEL.get(en.getKey());
            Entity pe = sl == null ? null : sl.getEntity(en.getKey());
            Entity se = sl == null ? null : sl.getEntity(en.getValue().shooter);
            if (!(pe instanceof Projectile pr) || pr.isRemoved() || !(se instanceof LivingEntity shooter) || !shooter.isAlive()
                    || sl.getGameTime() > en.getValue().until
                    || (pr instanceof net.minecraft.world.entity.projectile.AbstractArrow ar && ar.onGround())) {
                it.remove();
                RETURN_LEVEL.remove(en.getKey());
                continue;
            }
            Vec3 aim = shooter.position().add(0.0D, shooter.getBbHeight() * 0.6D, 0.0D).subtract(pr.position());
            if (aim.lengthSqr() < 1.0E-4D) continue;
            double speed = Math.max(1.3D, Math.min(2.2D, pr.getDeltaMovement().length() + 0.12D));
            Vec3 v = pr.getDeltaMovement().scale(0.55D).add(aim.normalize().scale(speed * 0.45D));
            v = v.normalize().scale(Math.min(speed, v.length() + 0.15D));
            pr.setDeltaMovement(v);
            pr.setNoGravity(true);
            double h = v.horizontalDistance();
            pr.setYRot((float) (Math.atan2(v.x, v.z) * 57.2957763671875D));
            pr.setXRot((float) (Math.atan2(v.y, h) * 57.2957763671875D));
            pr.hurtMarked = true;
            if (sl.getGameTime() % 2L == 0L) sl.sendParticles(ParticleTypes.SCULK_SOUL, pr.getX(), pr.getY(), pr.getZ(), 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }

    /**
     * Something struck a Wonder of Deep user up close: the blow turns on the one who threw it. True = the user takes
     * nothing (it all went back to them).
     */
    private static boolean reflecting;

    public static boolean reflect(ServerPlayer user, net.minecraft.world.damagesource.DamageSource src, float amount) {
        if (reflecting || src.is(net.minecraft.world.damagesource.DamageTypes.THORNS)) return false; // a returned hit isn't returned again
        Entity atk = src.getEntity();
        if (!(atk instanceof LivingEntity a) || a == user || boss(a) || !a.isAlive()) return false;
        if (src.getDirectEntity() != atk) return false; // shots are handled on the way in
        ServerLevel sl = user.serverLevel();
        StandCombat.credit(user, a);
        a.invulnerableTime = 0;
        reflecting = true;
        try {
            a.hurt(sl.damageSources().thorns(user), amount);
        } finally {
            reflecting = false;
        }
        Vec3 m = a.position().add(0.0D, a.getBbHeight() * 0.6D, 0.0D);
        sl.sendParticles(ParticleTypes.SCULK_SOUL, m.x, m.y, m.z, 8, 0.25D, 0.3D, 0.25D, 0.03D);
        sl.playSound(null, m.x, m.y, m.z, SoundEvents.WARDEN_ATTACK_IMPACT, SoundSource.HOSTILE, 0.8F, 1.3F);
        return true;
    }

    public static void tick(MinecraftServer server) {
        steerReturning();
        Set<UUID> building = new HashSet<>();
        for (ServerPlayer u : server.getPlayerList().getPlayers()) {
            if (!isUser(u) || !u.isAlive()) continue;
            ServerLevel sl = u.serverLevel();
            long now = sl.getGameTime();
            boolean hidden = HIDDEN.containsKey(u.getUUID()) && now < HIDDEN.get(u.getUUID());
            for (Mob m : sl.getEntitiesOfClass(Mob.class, u.getBoundingBox().inflate(32.0D), x -> x.isAlive() && !boss(x))) {
                boolean after = m.getTarget() == u
                        || (m.getBrain().checkMemory(MemoryModuleType.ATTACK_TARGET, net.minecraft.world.entity.ai.memory.MemoryStatus.REGISTERED)
                        && m.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).map(t -> t == u).orElse(false));
                // Monsters don't get a choice: they come - and the closer they get, the harder the world turns on them.
                // Nothing evil ever reaches you.
                if (m instanceof net.minecraft.world.entity.monster.Enemy && !hidden) {
                    boolean fusing = m instanceof net.minecraft.world.entity.monster.Creeper cr && cr.getSwellDir() > 0;
                    double dist = m.distanceTo(u);
                    if ((after || fusing) && dist < CLOSE_RANGE) {
                        double k = 1.0D - dist / CLOSE_RANGE;          // 0 far ... 1 on top of you
                        add(m, u, 1.0D + k * k * 14.0D);
                        building.add(m.getUUID());
                        double doomAt = m instanceof net.minecraft.world.entity.monster.Creeper ? 5.0D : 3.0D;
                        if (dist < doomAt + m.getBbWidth() * 0.5D) doom(sl, m, u);
                    }
                    continue;
                }
                if (after) {
                    int d = hidden ? 0 : decide(m, u, now);
                    if (d != 2) {
                        drop(m);
                    } else {
                        add(m, u, 1.0D);
                        building.add(m.getUUID());
                    }
                }
                Long mk = MARKED.get(m.getUUID());
                if (mk != null && now < mk && u.getUUID().equals(MARKED_BY.get(m.getUUID())) && m.distanceToSqr(u) < 24.0D * 24.0D) {
                    add(m, u, 1.0D);
                    building.add(m.getUUID());
                }
                // Running scared.
                Long pan = PANIC.get(m.getUUID());
                if (pan != null && now < pan && u.getUUID().equals(PANIC_FROM.get(m.getUUID()))) {
                    if (now % 20L == 0L) {
                        Vec3 away = m.position().subtract(u.position()).multiply(1.0D, 0.0D, 1.0D);
                        away = away.lengthSqr() < 1.0E-3D ? new Vec3(1.0D, 0.0D, 0.0D) : away.normalize();
                        Vec3 to = m.position().add(away.scale(12.0D));
                        m.getNavigation().moveTo(to.x, to.y, to.z, 1.4D);
                    }
                    if (now % 6L == 0L) sl.sendParticles(ParticleTypes.SPLASH, m.getX(), m.getEyeY() + 0.3D, m.getZ(), 2, 0.15D, 0.05D, 0.15D, 0.0D);
                }
            }
            // Players who came after you: half as fast, and only while they're near.
            for (ServerPlayer o : sl.players()) {
                if (o == u || !o.isAlive()) continue;
                Long eng = ENGAGED.get(o.getUUID());
                Long mk = MARKED.get(o.getUUID());
                boolean engaged = eng != null && now < eng && u.getUUID().equals(ENGAGED_WITH.get(o.getUUID()));
                boolean marked = mk != null && now < mk && u.getUUID().equals(MARKED_BY.get(o.getUUID()));
                if ((engaged || marked) && o.distanceToSqr(u) < 24.0D * 24.0D) {
                    add(o, u, 0.5D);
                    building.add(o.getUUID());
                }
            }
            // Calamity Rain: the rain itself turns on your enemies.
            Long rain = RAIN.get(u.getUUID());
            if (rain != null && now < rain && now % 5L == 0L && RainPowers.wet(u)) {
                for (LivingEntity e : sl.getEntitiesOfClass(LivingEntity.class, u.getBoundingBox().inflate(12.0D),
                        x -> StandCombat.valid(u, x) && !boss(x) && RainPowers.wet(x))) {
                    e.invulnerableTime = 0;
                    StandCombat.hurt(u, e, 1.5F);
                    sl.sendParticles(ParticleTypes.FALLING_WATER, e.getX(), e.getY() + e.getBbHeight() + 0.5D, e.getZ(), 6, 0.3D, 0.2D, 0.3D, 0.0D);
                }
            }
        }

        // Each creature's calamity: accidents as it builds, and it fades once they stop.
        Iterator<Map.Entry<UUID, Double>> it = CAL.entrySet().iterator();
        List<Runnable> later = new ArrayList<>();
        while (it.hasNext()) {
            Map.Entry<UUID, Double> en = it.next();
            UUID id = en.getKey();
            ServerPlayer user = server.getPlayerList().getPlayer(CAL_USER.getOrDefault(id, id));
            Entity raw = null;
            if (user != null) raw = user.serverLevel().getEntity(id);
            if (!(raw instanceof LivingEntity e) || !e.isAlive() || user == null) {
                it.remove();
                NEXT.remove(id);
                CAL_USER.remove(id);
                continue;
            }
            double pts = en.getValue();
            if (!building.contains(id)) {
                pts -= 0.3D;
                if (pts <= 0.0D) {
                    it.remove();
                    NEXT.remove(id);
                    CAL_USER.remove(id);
                    continue;
                }
                en.setValue(pts);
            }
            double next = NEXT.getOrDefault(id, STEP);
            if (pts >= next) {
                double p = pts;
                NEXT.put(id, next + (e instanceof Player ? STEP * 1.5D : STEP));
                later.add(() -> accident(user.serverLevel(), e, p, user));
            }
        }
        later.forEach(Runnable::run);
        if (FEAR.size() > 2048) FEAR.clear();
    }

    public static void onDeath(LivingEntity e) {
        UUID id = e.getUUID();
        CAL.remove(id);
        NEXT.remove(id);
        CAL_USER.remove(id);
        FEAR.remove(id);
        PANIC.remove(id);
        MARKED.remove(id);
    }

    public static void shutdown() {
        FEAR.clear();
        CAL.clear();
        NEXT.clear();
        CAL_USER.clear();
        PANIC.clear();
        PANIC_FROM.clear();
        MARKED.clear();
        MARKED_BY.clear();
        ENGAGED.clear();
        ENGAGED_WITH.clear();
        MISDIRECT.clear();
        HIDDEN.clear();
        RAIN.clear();
        RETURNING.clear();
        RETURN_LEVEL.clear();
        DOOMED.clear();
    }
}
