package com.mobstands.stand;

import com.mobstands.entity.StandEntity;
import com.mobstands.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Evoker;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Random encounters on the road. Stand users attract each other: out in the wild, far from home, an Illager war
 * party may pick up your trail (a crowd of Pillagers and Vindicators, always led by an Evoker with a Stand) - or a
 * lone Evoker Stand user comes after you by itself: tougher, with a boss bar, its Stand hitting much harder and
 * pulling a signature move every few seconds.
 * <p>
 * Only players who have a Stand get hunted, only in the Overworld, out under the open sky, and well away from their
 * bed or spawn. At least half an hour between encounters.
 */
public final class Encounters {
    private Encounters() {}

    /** First possible encounter after this much play, and the gap after each one (ticks). */
    public static final int FIRST_AFTER = 20 * 60 * 15, COOLDOWN = 20 * 60 * 30;
    /** Checked every 30 seconds once the cooldown is over: this chance each time (~1 encounter every 12 minutes of travel). */
    public static final float CHANCE = 0.04F;
    /** How far from your bed / spawn you have to be to count as "travelling". */
    public static final double FROM_HOME = 160.0D;
    /** On the way to a mansion Ancient Nose found: they come for you much more often. */
    public static final float MANSION_CHANCE = 0.15F;
    public static final int MANSION_COOLDOWN = 20 * 60 * 8;
    /** The solo Stand user: how often it's this instead of a war party. */
    public static final float SOLO_CHANCE = 0.4F;
    private static final String NEXT = "mobstands:encounter_next", TAG = "mobstands:encounter", BOSS = "mobstands:encounter_boss";
    private static final StandType[] BOSS_STANDS = {StandType.IRON_GOLEM, StandType.ENDERMAN, StandType.CREEPER, StandType.SPIDER};
    private static final String[] BOSS_NAMES = {"Grimald", "Vessk", "Ozric", "Malvo", "Corvane", "Haldrin", "Oswyn", "Brask"};

    private static final class Encounter {
        UUID player;
        ServerLevel level;
        final List<Mob> mobs = new ArrayList<>();
        Mob boss;
        StandType bossStand;
        String bossName;
        ServerBossEvent bar;
        long started, nextMove;
        int away;
        boolean solo;
    }

    private static final Map<UUID, Encounter> ACTIVE = new HashMap<>();

    public static boolean isBoss(LivingEntity e) {
        return e.getPersistentData().getBoolean(BOSS);
    }

    // ------------------------------------------------------------------ when

    public static void tick(MinecraftServer server) {
        long t = server.overworld().getGameTime();
        if (!ACTIVE.isEmpty()) tickActive(server);
        if (t % 600 != 0) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (ACTIVE.containsKey(p.getUUID())) continue;
            CompoundTag pd = p.getPersistentData();
            long now = p.serverLevel().getGameTime();
            if (!pd.contains(NEXT)) {
                pd.putLong(NEXT, now + FIRST_AFTER);
                continue;
            }
            // Heading to a mansion your Sniffer found: the road there is dangerous (no 15-minute grace either).
            boolean mansion = TimeEvoker.headingToMansion(p);
            if (mansion && pd.getLong(NEXT) > now + MANSION_COOLDOWN) pd.putLong(NEXT, now + MANSION_COOLDOWN / 2);
            if (now < pd.getLong(NEXT) || !canHappen(p) || p.getRandom().nextFloat() >= (mansion ? MANSION_CHANCE : CHANCE)) continue;
            start(p, p.getRandom().nextFloat() < SOLO_CHANCE);
        }
    }

    private static boolean canHappen(ServerPlayer p) {
        ServerLevel sl = p.serverLevel();
        if (sl.dimension() != Level.OVERWORLD || sl.getDifficulty() == Difficulty.PEACEFUL) return false;
        if (p.isCreative() || p.isSpectator() || p.isSleeping() || !p.isAlive()) return false;
        if (StandManager.data(p).getType() == null) return false; // Stand users attract each other
        BlockPos at = p.blockPosition();
        if (!sl.canSeeSky(at) || p.isInWater() || sl.isVillage(at)) return false;
        BlockPos home = p.getRespawnPosition() != null && p.getRespawnDimension() == Level.OVERWORLD ? p.getRespawnPosition() : sl.getSharedSpawnPos();
        return home.distSqr(at) > FROM_HOME * FROM_HOME;
    }

    // ------------------------------------------------------------------ starting

    /** Start one now (also /stand encounter). */
    public static boolean start(ServerPlayer p, boolean solo) {
        if (ACTIVE.containsKey(p.getUUID())) return false;
        ServerLevel sl = p.serverLevel();
        BlockPos spot = findSpot(sl, p);
        if (spot == null) return false;
        Encounter e = new Encounter();
        e.player = p.getUUID();
        e.level = sl;
        e.solo = solo;
        e.started = sl.getGameTime();
        e.nextMove = e.started + 100;
        if (solo) spawnSolo(sl, p, spot, e);
        else spawnParty(sl, p, spot, e);
        if (e.mobs.isEmpty()) return false;
        ACTIVE.put(p.getUUID(), e);
        p.getPersistentData().putLong(NEXT, sl.getGameTime() + (TimeEvoker.headingToMansion(p) ? MANSION_COOLDOWN : COOLDOWN));
        return true;
    }

    private static void spawnParty(ServerLevel sl, ServerPlayer p, BlockPos spot, Encounter e) {
        int pillagers = 4 + sl.random.nextInt(3), vindicators = 1 + sl.random.nextInt(2);
        // The Evoker always leads (and Evokers always have a Stand).
        Mob ev = spawn(sl, EntityType.EVOKER, spot, p, e);
        if (ev != null) e.boss = ev;
        for (int i = 0; i < pillagers; i++) spawn(sl, EntityType.PILLAGER, near(sl, spot), p, e);
        for (int i = 0; i < vindicators; i++) spawn(sl, EntityType.VINDICATOR, near(sl, spot), p, e);
        // No warning: like a vanilla patrol, they're just... there.
    }

    private static void spawnSolo(ServerLevel sl, ServerPlayer p, BlockPos spot, Encounter e) {
        StandType stand = BOSS_STANDS[sl.random.nextInt(BOSS_STANDS.length)];
        String name = BOSS_NAMES[sl.random.nextInt(BOSS_NAMES.length)];
        Evoker ev = EntityType.EVOKER.create(sl);
        if (ev == null) return;
        ev.moveTo(spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D, sl.random.nextFloat() * 360.0F, 0.0F);
        ev.finalizeSpawn(sl, sl.getCurrentDifficultyAt(spot), MobSpawnType.EVENT, null);
        // Its own Stand, chosen before it joins (so the normal Pillager roll doesn't pick one).
        CompoundTag pd = ev.getPersistentData();
        pd.putBoolean(StandManager.PILLAGER_ROLLED, true);
        pd.putString(StandManager.PILLAGER_STAND, stand.name());
        pd.putBoolean(TAG, true);
        pd.putBoolean(BOSS, true);
        var hp = ev.getAttribute(Attributes.MAX_HEALTH);
        if (hp != null) hp.setBaseValue(80.0D);
        ev.setHealth(80.0F);
        var arm = ev.getAttribute(Attributes.ARMOR);
        if (arm != null) arm.setBaseValue(6.0D);
        ev.setCustomName(Component.literal(name + ", " + stand.standName() + " user").withStyle(ChatFormatting.DARK_PURPLE));
        ev.setCustomNameVisible(true);
        ev.setPersistenceRequired();
        ev.setTarget(p);
        sl.addFreshEntity(ev);
        e.mobs.add(ev);
        e.boss = ev;
        e.bossStand = stand;
        e.bossName = name;
        e.bar = new ServerBossEvent(Component.literal(name + " - " + stand.standName()).withStyle(ChatFormatting.DARK_PURPLE),
                BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.NOTCHED_10);
        e.bar.addPlayer(p);
        // No warning either: you'll know when its Stand comes out.
    }

    private static Mob spawn(ServerLevel sl, EntityType<? extends Mob> type, BlockPos pos, ServerPlayer target, Encounter e) {
        Mob m = type.create(sl);
        if (m == null) return null;
        m.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, sl.random.nextFloat() * 360.0F, 0.0F);
        m.finalizeSpawn(sl, sl.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null);
        m.getPersistentData().putBoolean(TAG, true);
        m.setPersistenceRequired();
        m.setTarget(target);
        sl.addFreshEntity(m);
        e.mobs.add(m);
        return m;
    }

    /** Somewhere 20-30 blocks off, on dry open ground. */
    private static BlockPos findSpot(ServerLevel sl, ServerPlayer p) {
        for (int i = 0; i < 24; i++) {
            double ang = sl.random.nextDouble() * Math.PI * 2.0D;
            double d = 20.0D + sl.random.nextDouble() * 10.0D;
            int x = Mth.floor(p.getX() + Math.cos(ang) * d), z = Mth.floor(p.getZ() + Math.sin(ang) * d);
            if (!sl.hasChunkAt(new BlockPos(x, 0, z))) continue;
            int y = sl.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos pos = new BlockPos(x, y, z);
            if (Math.abs(y - p.getY()) > 12) continue;
            if (!sl.getFluidState(pos.below()).isEmpty() || !sl.getFluidState(pos).isEmpty()) continue;
            if (!sl.getBlockState(pos).isAir() || !sl.getBlockState(pos.above()).isAir()) continue;
            if (sl.getBlockState(pos.below()).getCollisionShape(sl, pos.below()).isEmpty()) continue;
            return pos;
        }
        return null;
    }

    private static BlockPos near(ServerLevel sl, BlockPos c) {
        for (int i = 0; i < 8; i++) {
            int x = c.getX() + sl.random.nextInt(7) - 3, z = c.getZ() + sl.random.nextInt(7) - 3;
            int y = sl.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (Math.abs(y - c.getY()) <= 3 && sl.getFluidState(new BlockPos(x, y - 1, z)).isEmpty()) return new BlockPos(x, y, z);
        }
        return c;
    }

    // ------------------------------------------------------------------ while it's on

    private static void tickActive(MinecraftServer server) {
        Iterator<Map.Entry<UUID, Encounter>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            Encounter e = it.next().getValue();
            ServerPlayer p = server.getPlayerList().getPlayer(e.player);
            e.mobs.removeIf(m -> !m.isAlive() || m.isRemoved());
            long now = e.level.getGameTime();
            if (e.mobs.isEmpty()) {
                end(e);
                it.remove();
                continue;
            }
            boolean gone = p == null || !p.isAlive() || p.level() != e.level;
            double nearest = Double.MAX_VALUE;
            if (!gone) for (Mob m : e.mobs) nearest = Math.min(nearest, m.distanceToSqr(p));
            if (gone || nearest > 96.0D * 96.0D) e.away++;
            else e.away = 0;
            // Got away (or died, or left): they lose your trail.
            if (e.away > 20 * 60 || now - e.started > 20 * 60 * 10) {
                for (Mob m : e.mobs) {
                    e.level.sendParticles(ParticleTypes.POOF, m.getX(), m.getY() + 1.0D, m.getZ(), 10, 0.3D, 0.5D, 0.3D, 0.02D);
                    m.discard();
                }
                end(e);
                it.remove();
                continue;
            }
            if (gone) continue;
            // Keep them on you.
            if (now % 20 == 0) {
                for (Mob m : e.mobs) {
                    if (m.getTarget() == null && m.distanceToSqr(p) < 64.0D * 64.0D && !p.isCreative() && !p.isSpectator()) m.setTarget(p);
                }
            }
            if (e.bar != null && e.boss != null) {
                e.bar.setProgress(Mth.clamp(e.boss.getHealth() / e.boss.getMaxHealth(), 0.0F, 1.0F));
                for (ServerPlayer o : e.level.players()) {
                    if (o.distanceToSqr(e.boss) < 48.0D * 48.0D) e.bar.addPlayer(o);
                    else e.bar.removePlayer(o);
                }
            }
            if (e.solo && e.boss != null && e.bossStand != null && now >= e.nextMove) signature(e, p, now);
        }
    }

    /** The solo Stand user's signature move, every 5-7 seconds when you're close enough. */
    private static void signature(Encounter e, ServerPlayer p, long now) {
        Mob b = e.boss;
        ServerLevel sl = e.level;
        if (b.distanceToSqr(p) > 14.0D * 14.0D || !b.hasLineOfSight(p) || p.isCreative() || p.isSpectator()) return;
        e.nextMove = now + 100 + sl.random.nextInt(40);
        StandEntity st = standOf(b);
        switch (e.bossStand) {
            case IRON_GOLEM -> { // Ground Slam: sends you flying
                say(p, e.bossName, "Iron Colossus!");
                if (st != null) { st.lunge(p.position().add(0.0D, 0.5D, 0.0D), 6); st.playAnim(0); }
                TickScheduler.later(6, () -> {
                    if (!b.isAlive() || !p.isAlive() || p.distanceToSqr(b) > 16.0D * 16.0D) return;
                    p.hurt(sl.damageSources().mobAttack(b), 6.0F);
                    Vec3 d = p.position().subtract(b.position()).multiply(1.0D, 0.0D, 1.0D).normalize();
                    p.setDeltaMovement(d.x * 0.8D, 1.0D, d.z * 0.8D);
                    p.hurtMarked = true;
                    sl.sendParticles(ParticleTypes.EXPLOSION, p.getX(), p.getY(), p.getZ(), 2, 0.3D, 0.1D, 0.3D, 0.0D);
                    sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.IRON_GOLEM_ATTACK, SoundSource.HOSTILE, 1.5F, 0.8F);
                });
            }
            case ENDERMAN -> { // Blink: right behind you, then a punch
                Vec3 look = p.getLookAngle().multiply(1.0D, 0.0D, 1.0D).normalize();
                Vec3 behind = p.position().subtract(look.scale(2.0D));
                BlockPos bp = BlockPos.containing(behind);
                if (!sl.getBlockState(bp).isAir() || !sl.getBlockState(bp.above()).isAir()) return;
                sl.sendParticles(ParticleTypes.PORTAL, b.getX(), b.getY() + 1.0D, b.getZ(), 30, 0.3D, 0.6D, 0.3D, 0.3D);
                b.teleportTo(behind.x, behind.y, behind.z);
                sl.playSound(null, behind.x, behind.y, behind.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.2F, 1.0F);
                say(p, e.bossName, "Behind you.");
                TickScheduler.later(8, () -> {
                    if (!b.isAlive() || !p.isAlive() || p.distanceToSqr(b) > 5.0D * 5.0D) return;
                    StandEntity s2 = standOf(b);
                    if (s2 != null) { s2.lunge(p.position().add(0.0D, 1.0D, 0.0D), 5); s2.playAnim(0); }
                    p.hurt(sl.damageSources().mobAttack(b), 5.0F);
                    p.knockback(0.8D, b.getX() - p.getX(), b.getZ() - p.getZ());
                });
            }
            case CREEPER -> { // Primer: you're the bomb now
                say(p, e.bossName, "I already touched you. You just didn't notice.");
                sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.CREEPER_PRIMED, SoundSource.HOSTILE, 1.5F, 1.0F);
                for (int i = 1; i <= 5; i++) TickScheduler.later(i * 5, () -> {
                    if (p.isAlive()) sl.sendParticles(ParticleTypes.HAPPY_VILLAGER, p.getX(), p.getY() + 1.0D, p.getZ(), 6, 0.3D, 0.5D, 0.3D, 0.0D);
                });
                TickScheduler.later(30, () -> {
                    if (!b.isAlive() || !p.isAlive()) return;
                    sl.explode(b, p.getX(), p.getY() + 0.5D, p.getZ(), 2.5F, Level.ExplosionInteraction.NONE);
                });
            }
            default -> { // SPIDER - Web Shot: stuck, poisoned
                say(p, e.bossName, "Hold still.");
                if (st != null) { st.lunge(p.position().add(0.0D, 1.0D, 0.0D), 6); st.playAnim(0); }
                BlockPos at = p.blockPosition();
                if (sl.getBlockState(at).isAir()) {
                    sl.setBlockAndUpdate(at, Blocks.COBWEB.defaultBlockState());
                    TickScheduler.later(100, () -> {
                        if (sl.getBlockState(at).is(Blocks.COBWEB)) sl.removeBlock(at, false);
                    });
                }
                p.addEffect(new MobEffectInstance(MobEffects.POISON, 80, 1), b);
                p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 80, 1), b);
                sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.SPIDER_AMBIENT, SoundSource.HOSTILE, 1.2F, 0.8F);
            }
        }
    }

    private static StandEntity standOf(Mob owner) {
        List<StandEntity> l = owner.level().getEntitiesOfClass(StandEntity.class, owner.getBoundingBox().inflate(16.0D),
                s -> s.isMobStand() && s.getMobOwner() == owner);
        return l.isEmpty() ? null : l.get(0);
    }

    private static void say(ServerPlayer p, String name, String line) {
        p.sendSystemMessage(Component.literal("<" + name + "> ").withStyle(ChatFormatting.DARK_PURPLE)
                .append(Component.literal(line).withStyle(ChatFormatting.WHITE)));
    }

    private static void end(Encounter e) {
        if (e.bar != null) e.bar.removeAllPlayers();
    }

    /** One of an encounter's Illagers died: the loot it carries. */
    public static void onDeath(Mob m, net.minecraft.world.damagesource.DamageSource src) {
        CompoundTag pd = m.getPersistentData();
        if (!pd.getBoolean(TAG) || !(src.getEntity() instanceof Player)) return;
        if (pd.getBoolean(BOSS)) {
            // The lone Stand user: always worth it.
            if (m.getRandom().nextFloat() < 0.5F) m.spawnAtLocation(new ItemStack(ModItems.ECHO_ARROW.get()));
            m.spawnAtLocation(new ItemStack(Items.EMERALD, 8 + m.getRandom().nextInt(9)));
            m.spawnAtLocation(new ItemStack(Items.EXPERIENCE_BOTTLE, 3 + m.getRandom().nextInt(4)));
            for (Encounter e : ACTIVE.values()) {
                if (e.boss == m && src.getEntity() instanceof ServerPlayer sp) {
                    say(sp, e.bossName, "...So this is... the gap between us...");
                }
            }
        } else if (m instanceof Evoker && m.getRandom().nextFloat() < 0.2F) {
            m.spawnAtLocation(new ItemStack(ModItems.ECHO_ARROW.get())); // (on top of the usual Evoker roll)
        }
    }

    public static void shutdown() {
        for (Encounter e : ACTIVE.values()) end(e);
        ACTIVE.clear();
    }

    public static void logout(ServerPlayer p) {
        Encounter e = ACTIVE.get(p.getUUID());
        if (e != null) e.away = 20 * 60; // they lose you next tick
    }
}
