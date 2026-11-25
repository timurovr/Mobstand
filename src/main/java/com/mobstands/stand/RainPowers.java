package com.mobstands.stand;

import com.mobstands.network.RainTimePayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Dolphin "Catch the Rainbow": everything it does needs rain (or snow) falling on you - it stops raindrops in the
 * air and uses them. Rain Call makes 30 seconds of rain when the sky won't.
 */
public final class RainPowers {
    private RainPowers() {}

    public static final int RAIN_CALL_TICKS = 600;
    /** Frozen raindrops: pale blue specks. */
    public static final DustParticleOptions DROP = new DustParticleOptions(new Vector3f(0.72F, 0.86F, 1.0F), 0.7F);

    /** Rain (or snow) is falling on this spot, open to the sky. Works on both sides. */
    public static boolean wet(Entity e) {
        Level l = e.level();
        if (!l.isRaining()) return false;
        BlockPos pos = BlockPos.containing(e.getX(), e.getY() + e.getBbHeight(), e.getZ());
        return l.canSeeSky(pos) && l.getBiome(pos).value().getPrecipitationAt(pos) != Biome.Precipitation.NONE;
    }

    private static boolean needRain(AbilityContext c) {
        if (wet(c.owner)) return true;
        c.fail = "No rain.";
        return false;
    }

    // ------------------------------------------------------------------ Rain Call

    public static boolean rainCall(AbilityContext c) {
        if (c.level.dimension() != Level.OVERWORLD) {
            c.fail = "No sky to call rain from.";
            return false;
        }
        ServerLevel sl = c.level;
        int left = sl.isRaining() && sl.getLevelData() instanceof ServerLevelData d ? d.getRainTime() : 0;
        sl.setWeatherParameters(0, Math.max(RAIN_CALL_TICKS, left), true, sl.isThundering());
        c.sound(SoundEvents.DOLPHIN_PLAY, 1.2F, 0.8F);
        c.sound(SoundEvents.WEATHER_RAIN_ABOVE, 1.5F, 0.7F);
        c.sound(SoundEvents.TRIDENT_RIPTIDE_1, 1.0F, 0.8F);
        c.fx(ParticleTypes.SPLASH, c.owner.position().add(0.0D, 1.0D, 0.0D), 40, 1.0D, 0.3D);
        c.fx(ParticleTypes.CLOUD, c.owner.position().add(0.0D, 3.0D, 0.0D), 20, 1.5D, 0.05D);
        return true;
    }

    // ------------------------------------------------------------------ Rain Walk

    public static boolean rainWalk(AbilityContext c) {
        if (!c.data.rainWalk && !needRain(c)) return false;
        c.data.rainWalk = !c.data.rainWalk;
        c.sound(c.data.rainWalk ? SoundEvents.DOLPHIN_JUMP : SoundEvents.DOLPHIN_SPLASH, 1.0F, 1.2F);
        StandManager.sync(c.owner);
        return true;
    }

    /** Who's been caught in the air when the rain stopped (so the Slow Falling comes once). */
    private static final Set<UUID> SAVED = new HashSet<>();
    private static final Set<UUID> GRANTED_FLY = new HashSet<>();

    public static void playerTick(ServerPlayer p) {
        StandData d = StandManager.data(p);
        boolean dolphin = d.getType() == StandType.DOLPHIN;
        if (!dolphin && d.rainWalk) {
            d.rainWalk = false;
            StandManager.sync(p);
        }
        boolean wet = dolphin && wet(p);
        boolean airborne = !p.onGround() && p.level().noCollision(p, p.getBoundingBox().move(0.0D, -0.3D, 0.0D)) && !p.isInWater();
        // Up on the raindrops (no real ground under you): you're riding your Dolphin, surfing the rain.
        com.mobstands.entity.StandEntity stand = dolphin ? StandManager.getStand(p) : null;
        if (stand != null) {
            boolean onDrops = d.rainWalk && wet && !p.isInWater() && !p.isShiftKeyDown() && !p.isPassenger()
                    && p.level().noCollision(p, p.getBoundingBox().move(0.0D, -0.3D, 0.0D));
            stand.setCartRide(onDrops);
        }
        if (d.rainWalk && wet) {
            SAVED.remove(p.getUUID());
            p.fallDistance = 0.0F;
            // Your own game holds you up on the frozen drops; the server shouldn't call that flying.
            if (!p.getAbilities().mayfly) {
                p.getAbilities().mayfly = true;
                GRANTED_FLY.add(p.getUUID());
            }
            if (airborne && p.tickCount % 2 == 0) {
                // Everyone else sees the drops under you (your own game draws yours, right under your feet).
                for (ServerPlayer other : p.serverLevel().players()) {
                    if (other != p) p.serverLevel().sendParticles(other, DROP, false, p.getX(), p.getY() + 0.02D, p.getZ(), 3, 0.3D, 0.02D, 0.3D, 0.0D);
                }
            }
        } else {
            if (GRANTED_FLY.remove(p.getUUID()) && !p.isCreative() && !p.isSpectator()) p.getAbilities().mayfly = false;
            // The rain stopped under your feet: you float down instead of dropping.
            if (d.rainWalk && airborne && SAVED.add(p.getUUID())) {
                p.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 60, 0, false, true, true));
                p.fallDistance = 0.0F;
            }
        }
        // The rain timer on your screen.
        if (dolphin && p.tickCount % 10 == 0) {
            int ticks = 0;
            if (p.level().isRaining() && p.level().getLevelData() instanceof ServerLevelData sd) ticks = Math.max(1, sd.getRainTime());
            PacketDistributor.sendToPlayer(p, new RainTimePayload(ticks));
        }
    }

    // ------------------------------------------------------------------ Rain Blades

    private record Blades(ServerLevel level, UUID owner, Vec3 start, Vec3 dir, double length, long until, float damage) {}
    private static final List<Blades> BLADES = new ArrayList<>();
    private static final Map<UUID, Long> CUT_AT = new HashMap<>();

    public static boolean rainBlades(AbilityContext c) {
        if (!needRain(c)) return false;
        Vec3 dir = c.flatLook();
        Vec3 side = new Vec3(-dir.z, 0.0D, dir.x);
        // A wall of stopped raindrops across your path, 3 blocks ahead.
        Vec3 start = c.owner.position().add(dir.scale(3.0D)).subtract(side.scale(3.5D));
        BLADES.add(new Blades(c.level, c.owner.getUUID(), start, side, 7.0D, c.level.getGameTime() + 160L, c.dmg(3.0D)));
        c.sound(SoundEvents.GLASS_PLACE, 1.0F, 1.6F);
        c.sound(SoundEvents.PLAYER_ATTACK_SWEEP, 0.8F, 1.6F);
        return true;
    }

    // ------------------------------------------------------------------ Rain Cage

    private record Cage(ServerLevel level, UUID owner, UUID target, long until, float damage) {}
    private static final List<Cage> CAGES = new ArrayList<>();
    private static final Map<UUID, Vec3> CAGE_LAST = new HashMap<>();

    public static boolean rainCage(AbilityContext c) {
        if (!needRain(c)) return false;
        LivingEntity t = c.aim(24.0D);
        if (t == null) return false;
        CAGES.add(new Cage(c.level, c.owner.getUUID(), t.getUUID(), c.level.getGameTime() + 120L, c.dmg(2.5D)));
        CAGE_LAST.put(t.getUUID(), t.position());
        c.level.playSound(null, t.getX(), t.getY(), t.getZ(), SoundEvents.GLASS_PLACE, SoundSource.PLAYERS, 1.0F, 1.4F);
        c.level.playSound(null, t.getX(), t.getY(), t.getZ(), SoundEvents.DOLPHIN_ATTACK, SoundSource.PLAYERS, 0.8F, 1.2F);
        return true;
    }

    // ------------------------------------------------------------------ Rain Dash

    public static boolean rainDash(AbilityContext c) {
        if (!needRain(c)) return false;
        ServerPlayer p = c.owner;
        Vec3 look = c.look();
        Vec3 from = p.position();
        p.setDeltaMovement(look.scale(2.1D).add(0.0D, 0.15D, 0.0D));
        p.hurtMarked = true;
        p.fallDistance = 0.0F;
        // Cuts whatever it slips past.
        float dmg = c.dmg(4.0D);
        for (LivingEntity e : c.beam(p.getEyePosition(), look, 7.0D, 1.0D)) StandCombat.hurt(p, e, dmg);
        c.trail(ParticleTypes.SPLASH, from.add(0.0D, 1.0D, 0.0D), from.add(look.scale(6.0D)).add(0.0D, 1.0D, 0.0D), 0.4D);
        c.sound(SoundEvents.DOLPHIN_JUMP, 1.0F, 1.0F);
        c.sound(SoundEvents.TRIDENT_RIPTIDE_2, 0.8F, 1.3F);
        return true;
    }

    // ------------------------------------------------------------------ Raindrop Body (passive)

    /** In the rain, a hit can pass right through you: your body comes apart into raindrops. True = no damage. */
    public static boolean dodge(LivingEntity victim, DamageSource src) {
        if (!(victim instanceof ServerPlayer p) || src.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return false;
        StandData d = StandManager.data(p);
        if (d.getType() != StandType.DOLPHIN || !wet(p)) return false;
        double chance = 0.20D + 0.02D * Math.max(0, d.getLevel() - 1);
        if (p.getRandom().nextDouble() >= chance) return false;
        ServerLevel sl = p.serverLevel();
        sl.sendParticles(ParticleTypes.SPLASH, p.getX(), p.getY() + 1.0D, p.getZ(), 30, 0.3D, 0.6D, 0.3D, 0.1D);
        sl.sendParticles(DROP, p.getX(), p.getY() + 1.0D, p.getZ(), 20, 0.3D, 0.6D, 0.3D, 0.0D);
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.DOLPHIN_SPLASH, SoundSource.PLAYERS, 1.0F, 1.4F);
        return true;
    }

    // ------------------------------------------------------------------ tick

    public static void tick(ServerLevel sl) {
        long now = sl.getGameTime();
        Iterator<Blades> bi = BLADES.iterator();
        while (bi.hasNext()) {
            Blades b = bi.next();
            if (b.level != sl) continue;
            if (now >= b.until) {
                bi.remove();
                continue;
            }
            ServerPlayer owner = sl.getServer().getPlayerList().getPlayer(b.owner);
            Vec3 end = b.start.add(b.dir.scale(b.length));
            if (now % 3L == 0L) {
                for (double s = 0.0D; s <= b.length; s += 0.7D) {
                    Vec3 at = b.start.add(b.dir.scale(s));
                    sl.sendParticles(DROP, at.x, at.y + 0.3D + sl.random.nextDouble() * 2.2D, at.z, 1, 0.05D, 0.05D, 0.05D, 0.0D);
                }
            }
            if (owner == null) continue;
            AABB box = new AABB(b.start, end.add(0.0D, 2.5D, 0.0D)).inflate(0.4D);
            for (LivingEntity e : sl.getEntitiesOfClass(LivingEntity.class, box, x -> StandCombat.valid(owner, x))) {
                // Only what moves through it gets cut.
                if (e.getDeltaMovement().horizontalDistanceSqr() < 1.0E-4D && e.position().distanceToSqr(e.xo, e.yo, e.zo) < 1.0E-4D) continue;
                Long last = CUT_AT.get(e.getUUID());
                if (last != null && now - last < 10L) continue;
                CUT_AT.put(e.getUUID(), now);
                e.invulnerableTime = 0;
                StandCombat.hurt(owner, e, b.damage);
                sl.sendParticles(ParticleTypes.SPLASH, e.getX(), e.getY() + 1.0D, e.getZ(), 12, 0.3D, 0.4D, 0.3D, 0.1D);
                sl.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 0.7F, 1.7F);
            }
        }
        Iterator<Cage> ci = CAGES.iterator();
        while (ci.hasNext()) {
            Cage cg = ci.next();
            if (cg.level != sl) continue;
            Entity raw = sl.getEntity(cg.target);
            if (now >= cg.until || !(raw instanceof LivingEntity t) || !t.isAlive()) {
                ci.remove();
                CAGE_LAST.remove(cg.target);
                continue;
            }
            // The drops hang still around it.
            if (now % 2L == 0L) {
                double r = t.getBbWidth() * 0.5D + 0.7D;
                for (int i = 0; i < 10; i++) {
                    double a = Math.PI * 2.0D * i / 10.0D + now * 0.02D;
                    sl.sendParticles(DROP, t.getX() + Math.cos(a) * r, t.getY() + sl.random.nextDouble() * (t.getBbHeight() + 0.4D),
                            t.getZ() + Math.sin(a) * r, 1, 0.0D, 0.0D, 0.0D, 0.0D);
                }
            }
            Vec3 last = CAGE_LAST.getOrDefault(cg.target, t.position());
            CAGE_LAST.put(cg.target, t.position());
            if (t.position().distanceToSqr(last) > 0.0025D) {
                ServerPlayer owner = sl.getServer().getPlayerList().getPlayer(cg.owner);
                Long cut = CUT_AT.get(t.getUUID());
                if (owner != null && (cut == null || now - cut >= 6L)) {
                    CUT_AT.put(t.getUUID(), now);
                    t.invulnerableTime = 0;
                    StandCombat.hurt(owner, t, cg.damage);
                    t.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 20, 1, false, false, true));
                    sl.sendParticles(ParticleTypes.SPLASH, t.getX(), t.getY() + 1.0D, t.getZ(), 8, 0.3D, 0.4D, 0.3D, 0.1D);
                }
            }
        }
        if (CUT_AT.size() > 256) CUT_AT.clear();
    }

    public static void shutdown() {
        BLADES.clear();
        CAGES.clear();
        CAGE_LAST.clear();
        CUT_AT.clear();
        SAVED.clear();
        GRANTED_FLY.clear();
    }
}
