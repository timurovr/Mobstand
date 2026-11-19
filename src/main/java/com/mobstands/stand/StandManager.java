package com.mobstands.stand;

import com.mobstands.entity.StandEntity;
import com.mobstands.network.SyncStandPayload;
import com.mobstands.registry.ModAttachments;
import com.mobstands.registry.ModEntities;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/** Server-side authority for everything Stand related: summoning, cooldowns, XP, persistence upkeep. */
public final class StandManager {
    private StandManager() {}

    public static StandData data(Player player) {
        return player.getData(ModAttachments.STAND_DATA);
    }

    public static void sync(ServerPlayer p) {
        PacketDistributor.sendToPlayer(p, new SyncStandPayload(data(p).save()));
    }

    // ------------------------------------------------------------------ obtaining

    /** Has this player reached the Nether / the End yet? (their "We Need to Go Deeper" / "The End?" advancements) */
    public static boolean reached(ServerPlayer p, StandType.Pool pool) {
        if (pool == StandType.Pool.OVERWORLD) return true;
        String path = pool == StandType.Pool.NETHER ? "nether/root" : "end/root";
        var adv = p.server.getAdvancements().get(net.minecraft.resources.ResourceLocation.withDefaultNamespace(path));
        return adv != null && p.getAdvancements().getOrStartProgress(adv).isDone();
    }

    /** A random Stand for this player: only from the parts of the world they've reached. */
    public static StandType roll(ServerPlayer p, StandType exclude) {
        RandomSource rng = p.getRandom();
        java.util.function.Predicate<StandType> ok = t -> t != exclude && t.rollable() && reached(p, t.pool());
        int total = 0;
        for (StandType t : StandType.values()) {
            if (ok.test(t)) total += t.rarity().weight();
        }
        if (total <= 0) return StandType.SLIME;
        int r = rng.nextInt(total);
        for (StandType t : StandType.values()) {
            if (!ok.test(t)) continue;
            r -= t.rarity().weight();
            if (r < 0) return t;
        }
        return StandType.SLIME;
    }

    /** Gives the player a Stand (resets level/XP) and summons it. */
    public static void assign(ServerPlayer p, StandType type) {
        StandData d = data(p);
        removeEntity(p);
        d.setType(type);
        d.resetProgress();
        d.allayCount = 1;
        d.allayDupes.clear();
        d.allayHolds.clear();
        d.allayRoam = false;
        d.setSummoned(false);
        summon(p);
    }

    /**
     * Pig Stand struck by lightning (you or the pig): it becomes a Zombified Piglin Stand, or a Piglin Stand if
     * you have Fire Resistance at that moment. Level and XP carry over.
     */
    public static void pigStruck(ServerPlayer p) {
        StandData d = data(p);
        if (d.getType() != StandType.PIG) return;
        boolean fireproof = p.hasEffect(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE);
        StandType to = fireproof ? StandType.PIGLIN : StandType.ZOMBIFIED_PIGLIN;
        StandEntity old = getStand(p);
        net.minecraft.world.phys.Vec3 at = old != null ? old.position() : p.position();
        ServerLevel level = (ServerLevel) p.level();
        removeEntity(p);
        d.setType(to);
        d.setSummoned(false);
        level.sendParticles(fireproof ? ParticleTypes.FLAME : ParticleTypes.ELECTRIC_SPARK, at.x, at.y + 0.6D, at.z, 30, 0.4D, 0.5D, 0.4D, 0.05D);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.PIG_DEATH, SoundSource.PLAYERS, 1.0F, 0.8F);
        level.playSound(null, at.x, at.y, at.z, fireproof ? SoundEvents.PIGLIN_CELEBRATE : SoundEvents.ZOMBIFIED_PIGLIN_ANGRY,
                SoundSource.PLAYERS, 1.2F, 1.0F);
        summon(p);
        p.displayClientMessage(net.minecraft.network.chat.Component.literal("Your Pig Stand became " + to.standName() + "!")
                .withStyle(fireproof ? net.minecraft.ChatFormatting.GOLD : net.minecraft.ChatFormatting.DARK_GREEN), true);
        sync(p);
    }

    // ------------------------------------------------------------------ summon / dismiss

    public static StandEntity getStand(ServerPlayer p) {
        StandData d = data(p);
        if (d.activeStand == null) return null;
        Entity e = ((ServerLevel) p.level()).getEntity(d.activeStand);
        return e instanceof StandEntity s && s.isAlive() ? s : null;
    }

    /** How long after summoning a punch still counts as "summon and instantly punch" (ticks). */
    private static final int SUMMON_PUNCH_WINDOW = 20;

    public static void summon(ServerPlayer p) {
        summon(p, true);
    }

    // ---- Pillager Stand users ----
    public static final String PILLAGER_ROLLED = "mobstands:rolled", PILLAGER_STAND = "mobstands:stand";
    /** Chance a newly spawned Pillager is a Stand user. */
    public static final float PILLAGER_STAND_CHANCE = 0.12F;
    /** Chance a Pillager Stand user drops a Stand Arrow when you kill it. */
    public static final float PILLAGER_ARROW_DROP = 0.25F;
    /** Chance an Evoker drops a Stand Arrow when you kill it. */
    public static final float EVOKER_ARROW_DROP = 0.15F;
    private static final StandType[] PILLAGER_STANDS = {StandType.IRON_GOLEM, StandType.ENDERMAN, StandType.CREEPER, StandType.SPIDER}; // Overworld Stands only

    /** A Pillager entered the world: the first time, it may roll a Stand; if it has one, its Stand appears beside it. */
    public static void pillagerJoin(net.minecraft.world.entity.Mob pil) {
        net.minecraft.nbt.CompoundTag pd = pil.getPersistentData();
        if (!pd.getBoolean(PILLAGER_ROLLED)) {
            pd.putBoolean(PILLAGER_ROLLED, true);
            // Evokers are always Stand users; Pillagers and Vindicators only sometimes.
            if (pil instanceof net.minecraft.world.entity.monster.Evoker || pil.getRandom().nextFloat() < PILLAGER_STAND_CHANCE) {
                pd.putString(PILLAGER_STAND, PILLAGER_STANDS[pil.getRandom().nextInt(PILLAGER_STANDS.length)].name());
            }
        }
        if (!pd.contains(PILLAGER_STAND)) return;
        StandType type;
        try {
            type = StandType.valueOf(pd.getString(PILLAGER_STAND));
        } catch (IllegalArgumentException e) {
            return;
        }
        TickScheduler.later(2, () -> spawnMobStand(pil, type));
    }

    private static void spawnMobStand(net.minecraft.world.entity.LivingEntity owner, StandType type) {
        if (!owner.isAlive() || owner.isRemoved() || !(owner.level() instanceof ServerLevel level)) return;
        // Already has one (e.g. it just changed chunks): don't double up.
        if (!level.getEntitiesOfClass(StandEntity.class, owner.getBoundingBox().inflate(16.0D),
                s -> s.isMobStand() && s.getMobOwner() == owner).isEmpty()) return;
        StandEntity s = new StandEntity(ModEntities.STAND.get(), level);
        s.setMobOwner(owner);
        s.setStandType(type);
        s.setSide(owner.getRandom().nextBoolean() ? 1 : -1);
        s.moveTo(owner.getX(), owner.getY() + 0.15D, owner.getZ(), owner.getYRot(), 0.0F);
        level.addFreshEntity(s);
    }

    /** A Pillager Stand user killed by a player sometimes drops a Stand Arrow. */
    public static void pillagerDied(net.minecraft.world.entity.Mob pil, net.minecraft.world.damagesource.DamageSource src) {
        if (!(src.getEntity() instanceof ServerPlayer)) return;
        // Only Evokers and Stand-user Vindicators drop it (Pillagers respawn at outposts forever, so they never do).
        float chance = pil instanceof net.minecraft.world.entity.monster.Evoker ? EVOKER_ARROW_DROP
                : pil instanceof net.minecraft.world.entity.monster.Vindicator && pil.getPersistentData().contains(PILLAGER_STAND) ? PILLAGER_ARROW_DROP
                : 0.0F;
        if (pil.getRandom().nextFloat() < chance) {
            pil.spawnAtLocation(new net.minecraft.world.item.ItemStack(com.mobstands.registry.ModItems.ECHO_ARROW.get()));
        }
    }

    /** @param announce false for silent re-spawns (after teleports, dimension changes, relogging). */
    private static void summon(ServerPlayer p, boolean announce) {
        StandData d = data(p);
        if (d.getType() == null || !p.isAlive()) return;
        d.setSummoned(true);
        if (getStand(p) == null) {
            ServerLevel level = (ServerLevel) p.level();
            StandEntity s = new StandEntity(ModEntities.STAND.get(), level);
            s.setOwner(p);
            s.setStandType(d.getType());
            s.setSide(p.getRandom().nextBoolean() ? 1 : -1); // appears behind a random shoulder
            Vec3 home = StandEntity.homePosition(p, d.getType(), 0, s.side());
            s.moveTo(p.getX(), p.getY() + 0.15D, p.getZ(), p.getYRot(), 0.0F);
            d.activeStand = s.getUUID(); // set BEFORE adding so the Stand validates itself on its first tick
            level.addFreshEntity(s);
            if (announce) {
                level.playSound(null, p.getX(), p.getY(), p.getZ(), (d.getType().menacing()
                        ? com.mobstands.registry.ModSounds.STAND_SUMMON_MENACING
                        : com.mobstands.registry.ModSounds.STAND_SUMMON).get(), SoundSource.PLAYERS, 1.0F, 1.0F);
                d.summonPunchUntil = level.getGameTime() + SUMMON_PUNCH_WINDOW;
                mobVoice(p, d.getType());
                s.setPosing(true); // you strike your pose as it appears, until you move
                d.poseAnchor = p.position();
            }
        }
        sync(p);
    }

    // ------------------------------------------------------------------ mob voices

    /** Chance a calm summon also plays the mob's own sound. */
    private static final float VOICE_CHANCE = 0.35F;
    /** At or below this health (3 hearts) the Stand appears angry. */
    private static final float LOW_HEALTH = 6.0F;

    /**
     * Sometimes the Stand makes its mob's own sound as it appears (sculk clicks, Enderman phrases...). Summon it
     * while you're on 3 hearts or less and it always reacts angrily instead (the Iron Golem has its own rage rule).
     */
    private static void mobVoice(ServerPlayer p, StandType type) {
        boolean low = p.getHealth() <= LOW_HEALTH && type != StandType.IRON_GOLEM;
        if (!low && p.getRandom().nextFloat() >= VOICE_CHANCE) return;
        TickScheduler.later(8, () -> {
            StandEntity s = getStand(p);
            if (s == null || !p.isAlive()) return;
            ServerLevel level = (ServerLevel) p.level();
            if (low) {
                switch (type) {
                    case WARDEN -> { voice(level, s, SoundEvents.WARDEN_ROAR, 1.6F, 1.0F); voice(level, s, SoundEvents.WARDEN_ANGRY, 1.2F, 1.0F); }
                    case ENDERMAN, ENDER_WORLD -> voice(level, s, SoundEvents.ENDERMAN_SCREAM, 1.5F, 1.0F);
                    case CREEPER -> voice(level, s, SoundEvents.CREEPER_PRIMED, 1.6F, 0.7F);
                    case BLAZE -> { voice(level, s, SoundEvents.BLAZE_HURT, 1.4F, 0.8F); voice(level, s, SoundEvents.BLAZE_SHOOT, 1.0F, 0.8F); }
                    case PHANTOM -> { voice(level, s, SoundEvents.PHANTOM_SWOOP, 1.5F, 0.8F); voice(level, s, SoundEvents.PHANTOM_BITE, 1.2F, 0.8F); }
                    case SKELETON -> voice(level, s, SoundEvents.SKELETON_HURT, 1.4F, 0.7F);
                    case SPIDER -> voice(level, s, SoundEvents.SPIDER_HURT, 1.4F, 0.7F);
                    case SLIME -> voice(level, s, SoundEvents.SLIME_ATTACK, 1.4F, 0.7F);
                    case SQUID -> voice(level, s, SoundEvents.SQUID_HURT, 1.4F, 0.6F);
                    case HORSE -> voice(level, s, SoundEvents.HORSE_ANGRY, 1.4F, 0.9F);
                    case PIG -> voice(level, s, SoundEvents.PIG_HURT, 1.4F, 0.9F);
                    case ALLAY -> voice(level, s, SoundEvents.ALLAY_HURT, 1.2F, 1.0F);
                    case PIGLIN -> voice(level, s, SoundEvents.PIGLIN_ANGRY, 1.4F, 1.0F);
                    case ZOMBIFIED_PIGLIN -> voice(level, s, SoundEvents.ZOMBIFIED_PIGLIN_ANGRY, 1.4F, 1.0F);
                    default -> { }
                }
            } else {
                switch (type) {
                    case WARDEN -> { voice(level, s, SoundEvents.WARDEN_TENDRIL_CLICKS, 1.2F, 1.0F); voice(level, s, SoundEvents.SCULK_CLICKING, 1.0F, 1.0F); }
                    case ENDERMAN, ENDER_WORLD -> voice(level, s, SoundEvents.ENDERMAN_AMBIENT, 1.0F, 1.0F);
                    case IRON_GOLEM -> voice(level, s, SoundEvents.IRON_GOLEM_REPAIR, 1.0F, 0.9F);
                    case BLAZE -> voice(level, s, SoundEvents.BLAZE_AMBIENT, 1.0F, 1.0F);
                    case PHANTOM -> voice(level, s, SoundEvents.PHANTOM_AMBIENT, 1.0F, 1.0F);
                    case SKELETON -> voice(level, s, SoundEvents.SKELETON_AMBIENT, 1.0F, 1.0F);
                    case SPIDER -> voice(level, s, SoundEvents.SPIDER_AMBIENT, 1.0F, 1.0F);
                    case CREEPER -> voice(level, s, SoundEvents.CREEPER_PRIMED, 0.5F, 1.2F);
                    case SLIME -> voice(level, s, SoundEvents.SLIME_SQUISH, 1.0F, 1.0F);
                    case SQUID -> voice(level, s, SoundEvents.SQUID_AMBIENT, 1.0F, 1.0F);
                    case AXOLOTL -> voice(level, s, SoundEvents.AXOLOTL_IDLE_AIR, 1.0F, 1.0F);
                    case HORSE -> voice(level, s, SoundEvents.HORSE_AMBIENT, 1.0F, 1.0F);
                    case PIG -> voice(level, s, SoundEvents.PIG_AMBIENT, 1.0F, 1.0F);
                    case ALLAY -> voice(level, s, SoundEvents.ALLAY_AMBIENT_WITHOUT_ITEM, 1.0F, 1.0F);
                    case PIGLIN -> voice(level, s, SoundEvents.PIGLIN_AMBIENT, 1.0F, 1.0F);
                    case ZOMBIFIED_PIGLIN -> voice(level, s, SoundEvents.ZOMBIFIED_PIGLIN_AMBIENT, 1.0F, 1.0F);
                }
            }
        });
    }

    private static void voice(ServerLevel level, StandEntity s, net.minecraft.sounds.SoundEvent sound, float volume, float pitch) {
        level.playSound(null, s.getX(), s.getY() + 1.0D, s.getZ(), sound, SoundSource.PLAYERS, volume, pitch);
    }

    /**
     * Axolotl Stand: a hit that would kill you is taken by your Axolotl instead. It appears (if it wasn't out),
     * dives in front of you toward the attacker and plays dead for 20 seconds, then revives. Returns true if
     * the hit was taken (the caller cancels it).
     */
    public static boolean axolotlSave(ServerPlayer p, net.minecraft.world.damagesource.DamageSource source, float amount) {
        StandData d = data(p);
        if (d.getType() != StandType.AXOLOTL || !p.isAlive()) return false;
        if (source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)) return false; // /kill, void
        if (amount < p.getHealth() + p.getAbsorptionAmount()) return false; // not lethal
        long now = p.level().getGameTime();
        if (d.cooldownEnd(0) > now) return false; // still playing dead from last time
        if (!d.isSummoned() || getStand(p) == null) summon(p, false);
        StandEntity s = getStand(p);
        if (s == null) return false;

        Vec3 from = source.getSourcePosition() != null ? source.getSourcePosition()
                : source.getEntity() != null ? source.getEntity().position() : p.position().add(p.getLookAngle());
        Vec3 dir = new Vec3(from.x - p.getX(), 0.0D, from.z - p.getZ());
        dir = dir.lengthSqr() < 1.0E-4D ? p.getLookAngle().multiply(1.0D, 0.0D, 1.0D).normalize() : dir.normalize();
        s.playDead(p.position().add(dir.scale(0.9D)), now + StandAbilities.AXOLOTL_DEAD_TICKS, dir);

        d.setCooldownEnd(0, now + StandAbilities.AXOLOTL_DEAD_TICKS);
        p.invulnerableTime = 20; // the same blow can't land twice
        p.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.REGENERATION, 100, 0));
        ServerLevel level = (ServerLevel) p.level();
        level.playSound(null, s.getX(), s.getY(), s.getZ(), SoundEvents.AXOLOTL_HURT, SoundSource.PLAYERS, 1.2F, 1.0F);
        level.playSound(null, s.getX(), s.getY(), s.getZ(), SoundEvents.AXOLOTL_DEATH, SoundSource.PLAYERS, 1.0F, 1.0F);
        // Legendary: the save lands like a miracle - a pink-and-gold burst, hearts and a chime.
        level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 1.4F, 1.2F);
        level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BUBBLE_COLUMN_UPWARDS_INSIDE, SoundSource.PLAYERS, 1.0F, 1.0F);
        level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, p.getX(), p.getY() + 1.0D, p.getZ(), 10, 0.3D, 0.5D, 0.3D, 0.15D);
        level.sendParticles(ParticleTypes.HEART, s.getX(), s.getY() + 0.5D, s.getZ(), 1, 0.2D, 0.1D, 0.2D, 0.0D);
        sync(p);
        return true;
    }

    /** Hits this close together count as "in a row", and how many it takes to enrage the Iron Golem. */
    private static final int RAGE_WINDOW = 60;
    private static final int RAGE_HITS = 3;
    private static final int RAGE_COOLDOWN = 120;

    /** Iron Golem Stand: take 3 hits in a row while it's out and it lets out an aggressive clang. */
    public static void onOwnerHurt(ServerPlayer p) {
        StandData d = data(p);
        if (d.getType() != StandType.IRON_GOLEM || !d.isSummoned()) return;
        StandEntity s = getStand(p);
        if (s == null) return;
        long now = p.level().getGameTime();
        if (now - d.hurtBurstStart > RAGE_WINDOW) {
            d.hurtBurstStart = now;
            d.hurtBurstHits = 0;
        }
        d.hurtBurstHits++;
        if (d.hurtBurstHits < RAGE_HITS || now < d.golemRageReady) return;
        d.hurtBurstHits = 0;
        d.golemRageReady = now + RAGE_COOLDOWN;
        ServerLevel level = (ServerLevel) p.level();
        voice(level, s, SoundEvents.IRON_GOLEM_ATTACK, 1.6F, 0.75F);
        voice(level, s, SoundEvents.IRON_GOLEM_DAMAGE, 1.3F, 0.8F);
        TickScheduler.later(5, () -> voice(level, s, SoundEvents.IRON_GOLEM_ATTACK, 1.4F, 0.65F));
    }

    public static void dismiss(ServerPlayer p) {
        StandData d = data(p);
        StandEntity s = getStand(p);
        if (s != null) {
            // Drift away and fade out; the entity removes itself when the fade finishes.
            s.startDismiss(p);
            ServerLevel level = (ServerLevel) p.level();
            level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 0.7F, 0.9F);
            level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ALLAY_ITEM_TAKEN, SoundSource.PLAYERS, 0.3F, 0.5F);
        }
        d.activeStand = null;
        d.setSummoned(false);
        sync(p);
    }

    /** Hits this close together (ticks) count as one frantic burst. */
    private static final int PANIC_WINDOW = 30;
    /** Hits in one burst needed before the Stand jumps in. */
    private static final int PANIC_HITS = 3;
    /** How long the Stand keeps fighting along after your last hit (ticks). */
    private static final int PANIC_HOLD = 80;

    /**
     * Called whenever the player hits a mob with anything. Spam hits (e.g. swinging a pickaxe at a creeper that
     * sneaked up on you) and your Stand appears on its own and strikes the same target with every hit you land.
     */
    public static void onPlayerAttack(ServerPlayer p) {
        StandData d = data(p);
        if (d.getType() == null || !p.isAlive() || !com.mobstands.config.StandConfig.PANIC_SUMMON.get()) return;
        long now = p.level().getGameTime();
        if (now - d.attackBurstStart > PANIC_WINDOW) {
            d.attackBurstStart = now;
            d.attackBurstHits = 0;
        }
        d.attackBurstHits++;
        d.attackBurstStart = Math.max(d.attackBurstStart, now - PANIC_WINDOW / 2); // keep the burst alive while spamming

        boolean panicking = now <= d.panicUntil;
        if (!panicking && d.attackBurstHits >= PANIC_HITS) {
            panicking = true;
            if (!d.isSummoned() || getStand(p) == null) summon(p); // appears with its summon sound
        }
        if (!panicking) return;
        d.panicUntil = now + PANIC_HOLD;
        // (Your Stand no longer throws its own punches along with your hits: it only attacks when you use it.)
    }

    /** J: strike (or drop) your Stand's signature pose. You stand normally otherwise. */
    public static void togglePose(ServerPlayer p) {
        StandEntity s = getStand(p);
        if (s == null || !data(p).isSummoned()) return;
        s.setPosing(!s.isPosing());
        data(p).poseAnchor = p.position();
    }

    // ------------------------------------------------------------------ Pig: distracted monsters

    private record Distraction(java.util.UUID owner, long until) {}
    private static final java.util.Map<java.util.UUID, Distraction> DISTRACTED = new java.util.HashMap<>();

    /** Oink: this monster drops its target and goes over to the owner's pig until {@code until}. */
    public static void distract(ServerPlayer owner, net.minecraft.world.entity.Mob m, long until) {
        DISTRACTED.put(m.getUUID(), new Distraction(owner.getUUID(), until));
        m.setTarget(null);
        m.getNavigation().stop();
        ((ServerLevel) m.level()).sendParticles(ParticleTypes.HEART, m.getX(), m.getEyeY() + 0.5D, m.getZ(), 2, 0.2D, 0.1D, 0.2D, 0.0D);
    }

    /** The Pig reached its spot: a loud oink, and each monster near it has a 30% chance to be lured. */
    public static void pigOink(ServerPlayer owner, StandEntity pig) {
        ServerLevel sl = (ServerLevel) pig.level();
        sl.playSound(null, pig.getX(), pig.getY(), pig.getZ(), SoundEvents.PIG_AMBIENT, SoundSource.PLAYERS, 2.0F, 0.8F);
        sl.sendParticles(ParticleTypes.NOTE, pig.getX(), pig.getY() + 1.1D, pig.getZ(), 3, 0.3D, 0.1D, 0.3D, 0.5D);
        long until = sl.getGameTime() + StandAbilities.OINK_TICKS;
        for (net.minecraft.world.entity.Mob m : sl.getEntitiesOfClass(net.minecraft.world.entity.Mob.class,
                pig.getBoundingBox().inflate(10.0D), x -> x instanceof net.minecraft.world.entity.monster.Enemy && x.isAlive())) {
            if (owner.getRandom().nextFloat() < StandAbilities.OINK_CHANCE) distract(owner, m, until);
        }
    }

    /** Roam toggle: your Allay flutters around freely (hittable to duplicate) or stands by your side. */
    public static void allayRoamToggle(ServerPlayer p) {
        StandData d = data(p);
        d.allayRoam = !d.allayRoam;
        p.displayClientMessage(Component.literal(d.allayRoam ? "Your Allay is roaming (beat it up to make it duplicate)" : "Your Allay is back by your side"), true);
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), d.allayRoam ? SoundEvents.ALLAY_AMBIENT_WITH_ITEM : SoundEvents.ALLAY_AMBIENT_WITHOUT_ITEM,
                SoundSource.PLAYERS, 1.0F, 1.0F);
    }

    /** Allay Hold: send a free Allay to grab {@code target} (or add one more to an enemy already held: +4 s). */
    public static void allayHold(ServerPlayer p, net.minecraft.world.entity.LivingEntity target) {
        StandData d = data(p);
        int free = d.allayCount - d.heldAllays();
        if (free <= 0) {
            p.displayClientMessage(Component.literal("All your Allays are busy."), true);
            return;
        }
        // Duplicates go first; your own Allay only leaves your side when it's the last one free.
        int dupesHeld = 0;
        boolean mainHeld = false;
        for (StandData.AllayHold h : d.allayHolds) {
            if (h.usesMain) mainHeld = true;
            dupesHeld += h.allays - (h.usesMain ? 1 : 0);
        }
        boolean useMain = !mainHeld && dupesHeld >= d.allayCount - 1;
        long now = p.level().getGameTime();
        StandData.AllayHold hold = null;
        for (StandData.AllayHold h : d.allayHolds) if (h.entityId == target.getId()) hold = h;
        if (hold == null) {
            addXp(p, 3); // a new enemy grabbed
            hold = new StandData.AllayHold();
            hold.entityId = target.getId();
            hold.baseY = target.getY();
            hold.anchor = target.position();
            hold.end = now;
            d.allayHolds.add(hold);
        }
        hold.allays++;
        // More Allays pulling: lifted higher (3.5 blocks with one, up to 8 with four).
        hold.anchor = new Vec3(hold.anchor.x, hold.baseY + 2.0D + 1.5D * hold.allays, hold.anchor.z);
        hold.end = Math.max(hold.end, now) + StandAbilities.ALLAY_HOLD_TICKS;
        if (useMain) hold.usesMain = true;
        ServerLevel sl = (ServerLevel) p.level();
        sl.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.ALLAY_ITEM_TAKEN, SoundSource.PLAYERS, 1.0F, 1.0F);
        sl.sendParticles(ParticleTypes.CLOUD, target.getX(), target.getY() + 0.1D, target.getZ(), 8, 0.3D, 0.05D, 0.3D, 0.03D);
    }

    /**
     * Hold X: your Allays let go. Looking at one of the enemies they're holding: just that one drops.
     * Otherwise: they all drop.
     */
    public static void allayRelease(ServerPlayer p) {
        StandData d = data(p);
        if (d.getType() != StandType.ALLAY || d.allayHolds.isEmpty()) return;
        ServerLevel sl = (ServerLevel) p.level();
        // Which held enemy (if any) you're looking at.
        Vec3 eye = p.getEyePosition();
        Vec3 end = eye.add(p.getLookAngle().scale(32.0D));
        StandData.AllayHold aimed = null;
        double best = Double.MAX_VALUE;
        for (StandData.AllayHold h : d.allayHolds) {
            net.minecraft.world.entity.Entity e = sl.getEntity(h.entityId);
            if (e == null) continue;
            java.util.Optional<Vec3> hit = e.getBoundingBox().inflate(0.4D).clip(eye, end);
            if (hit.isPresent() && hit.get().distanceToSqr(eye) < best) {
                best = hit.get().distanceToSqr(eye);
                aimed = h;
            }
        }
        java.util.List<StandData.AllayHold> drop = aimed != null ? java.util.List.of(aimed) : new java.util.ArrayList<>(d.allayHolds);
        for (StandData.AllayHold h : drop) {
            net.minecraft.world.entity.Entity e = sl.getEntity(h.entityId);
            if (e != null) {
                sl.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.ALLAY_ITEM_GIVEN, SoundSource.PLAYERS, 0.9F, 0.9F);
                sl.sendParticles(ParticleTypes.CLOUD, e.getX(), e.getY() + e.getBbHeight() + 0.3D, e.getZ(), 5, 0.2D, 0.1D, 0.2D, 0.02D);
            }
            d.allayHolds.remove(h);
        }
        p.displayClientMessage(Component.literal(aimed != null ? "Dropped it." : "Your Allays let go."), true);
        StandEntity s = getStand(p);
        if (s != null) s.syncAllays(d);
    }

    /** Ticks in water before duplicates start refilling. */
    public static final int ALLAY_WATER_DELAY = 60;

    /** Duplicates fading after 3 minutes, and enemies held in the air. */
    private static void allayTick(ServerPlayer p, StandData d, long now) {
        ServerLevel sl = (ServerLevel) p.level();
        int before = d.allayDupes.size();
        d.allayDupes.removeIf(t -> now >= t);
        if (d.allayDupes.size() != before) {
            p.displayClientMessage(Component.literal("A duplicate Allay drifted away."), true);
            sync(p);
            sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ALLAY_THROW, SoundSource.PLAYERS, 0.8F, 1.2F);
        }
        d.allayCount = 1 + d.allayDupes.size();
        // Allays love water: after 3 seconds in water, your duplicates' timers start filling back up (up to full).
        if (p.isInWater()) d.allayWaterTicks++;
        else d.allayWaterTicks = 0;
        if (d.allayWaterTicks >= ALLAY_WATER_DELAY && !d.allayDupes.isEmpty()) {
            long full = now + StandAbilities.ALLAY_DUPE_TICKS;
            for (int i = 0; i < d.allayDupes.size(); i++) d.allayDupes.set(i, Math.min(full, d.allayDupes.get(i) + 3L));
            if (now % 10L == 0L) {
                sl.sendParticles(ParticleTypes.BUBBLE, p.getX(), p.getY() + 1.0D, p.getZ(), 3, 0.4D, 0.4D, 0.4D, 0.02D);
                sync(p); // keep the timer bars up to date
            }
            if (d.allayWaterTicks == ALLAY_WATER_DELAY) {
                sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ALLAY_AMBIENT_WITH_ITEM, SoundSource.PLAYERS, 1.0F, 1.2F);
            }
        }
        StandEntity s = getStand(p);
        java.util.Iterator<StandData.AllayHold> it = d.allayHolds.iterator();
        int budget = d.allayCount;
        while (it.hasNext()) {
            StandData.AllayHold h = it.next();
            net.minecraft.world.entity.Entity e = sl.getEntity(h.entityId);
            if (s == null || !(e instanceof net.minecraft.world.entity.LivingEntity le) || !le.isAlive() || now >= h.end || budget <= 0) {
                if (e != null) sl.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.ALLAY_ITEM_GIVEN, SoundSource.PLAYERS, 0.8F, 1.0F);
                it.remove(); // let go: it drops
                continue;
            }
            h.allays = Math.min(h.allays, budget); // lost duplicates also let go
            h.anchor = new Vec3(h.anchor.x, h.baseY + 2.0D + 1.5D * h.allays, h.anchor.z);
            budget -= h.allays;
            Vec3 pull = h.anchor.subtract(le.position());
            le.setDeltaMovement(pull.scale(0.25D));
            le.hurtMarked = true;
            le.resetFallDistance();
            if (now % 10L == 0L) sl.sendParticles(ParticleTypes.NOTE, le.getX(), le.getY() + le.getBbHeight() + 0.6D, le.getZ(), 1, 0.2D, 0.1D, 0.2D, 0.5D);
        }
        if (s != null) s.syncAllays(d);
    }

    /**
     * You punched a duplicate Allay. Five quick hits "kill" it: below 4 Allays it comes back with a twin (one more
     * Allay); with 4 already, it just vanishes.
     */
    public static void allayDupeHit(ServerPlayer p, int idx) {
        StandData d = data(p);
        if (d.getType() != StandType.ALLAY || idx < 0 || idx >= d.allayDupes.size()) return;
        long now = p.level().getGameTime();
        long[] h = d.dupeHits.computeIfAbsent(idx, k -> new long[]{0L, 0L});
        if (now - h[1] < 6L) return; // one hit per swing
        if (now - h[1] > 100L) h[0] = 0L;
        h[1] = now;
        h[0]++;
        if (h[0] < com.mobstands.entity.StandEntity.ALLAY_HITS_TO_KILL) return;
        d.dupeHits.clear();
        ServerLevel sl = (ServerLevel) p.level();
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ALLAY_DEATH, SoundSource.PLAYERS, 1.0F, 1.0F);
        if (d.allayCount < StandAbilities.MAX_ALLAYS) {
            d.allayDupes.add(now + StandAbilities.ALLAY_DUPE_TICKS);
            d.allayDupes.set(idx, now + StandAbilities.ALLAY_DUPE_TICKS); // the twins are fresh
            d.allayCount = 1 + d.allayDupes.size();
            p.displayClientMessage(Component.literal("Your Allay duplicated! (" + d.allayCount + "/" + StandAbilities.MAX_ALLAYS + ")"), true);
            TickScheduler.later(8, () -> sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ALLAY_ITEM_GIVEN, SoundSource.PLAYERS, 1.0F, 1.2F));
        } else {
            d.allayDupes.remove(idx);
            d.allayCount = 1 + d.allayDupes.size();
            p.displayClientMessage(Component.literal("A duplicate Allay vanished. (" + d.allayCount + "/" + StandAbilities.MAX_ALLAYS + ")"), true);
        }
        StandEntity s = getStand(p);
        if (s != null) s.syncAllays(d);
        sync(p);
    }

    /** Allay Stand hit: it "dies" in a puff and comes back as one more Allay (up to 4). */
    public static void allayKilled(ServerPlayer p, StandEntity s) {
        StandData d = data(p);
        ServerLevel sl = (ServerLevel) s.level();
        sl.sendParticles(ParticleTypes.POOF, s.getX(), s.getY() + 0.4D, s.getZ(), 10, 0.2D, 0.2D, 0.2D, 0.02D);
        sl.playSound(null, s.getX(), s.getY(), s.getZ(), SoundEvents.ALLAY_DEATH, SoundSource.PLAYERS, 1.0F, 1.0F);
        if (d.allayCount >= StandAbilities.MAX_ALLAYS) {
            p.displayClientMessage(Component.literal("You already have " + StandAbilities.MAX_ALLAYS + " Allays."), true);
        } else {
            d.allayDupes.add(sl.getGameTime() + StandAbilities.ALLAY_DUPE_TICKS);
            d.allayCount = 1 + d.allayDupes.size();
            p.displayClientMessage(Component.literal("Your Allay duplicated! (" + d.allayCount + "/" + StandAbilities.MAX_ALLAYS + ")"), true);
            TickScheduler.later(8, () -> sl.playSound(null, s.getX(), s.getY(), s.getZ(), SoundEvents.ALLAY_ITEM_GIVEN, SoundSource.PLAYERS, 1.0F, 1.2F));
            sync(p);
        }
        s.reform(8); // gone for a moment, then back (with its new twin)
    }

    /** Allay Lift (server side): no fall damage while carried; landing ends it. The motion itself is client-side. */
    private static void liftTick(ServerPlayer p, StandData d, long now) {
        StandEntity s = getStand(p);
        if (s == null || !d.isSummoned()) {
            d.liftUntil = 0L;
            return;
        }
        p.resetFallDistance();
        if (now - d.liftStart > StandAbilities.LIFT_RISE_TICKS + 4 && (p.onGround() || p.isInWater())) {
            d.liftUntil = 0L; // touched down
            s.stopLift();
        }
        if (now % 16L == 0L) ((ServerLevel) p.level()).playSound(null, s.getX(), s.getY(), s.getZ(), SoundEvents.PHANTOM_FLAP, SoundSource.PLAYERS, 0.35F, 1.7F);
    }

    // ------------------------------------------------------------------ Spin

    /** Chance one Q tap sets the held item spinning: 8% for a rookie (Lv 1) up to 92% for a master (max level). */
    public static float spinChance(int level) {
        return 0.08F + 0.84F * (Math.max(1, level) - 1) / (float) (StandData.SPIN_MAX_LEVEL - 1);
    }

    /**
     * Q pressed while holding an item (Spin users only; the client hands the key to us instead of dropping).
     * Spinning: stop it. Otherwise roll the skill check: success sets it spinning; failure drops the item like
     * vanilla, and very rarely it flies off and hits whatever you're facing.
     */
    public static void spinPress(ServerPlayer p, boolean fullStack) {
        StandData d = data(p);
        if (!d.spinLearned) {
            dropSelected(p, fullStack);
            return;
        }
        net.minecraft.world.item.ItemStack held = p.getMainHandItem();
        if (held.isEmpty()) return;
        ServerLevel sl = (ServerLevel) p.level();
        long now = sl.getGameTime();
        if (d.spinning) {
            d.spinning = false;
            sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.5F, 0.6F);
            sync(p);
            return;
        }
        if (mayNotSpin(p, d, held)) return;
        // Spamming builds a little rhythm: a small bonus for quick consecutive taps.
        d.spinStreak = now - d.spinLastPress <= 10L ? d.spinStreak + 1 : 1;
        d.spinLastPress = now;
        float chance = spinChance(d.spinLevel) + Math.min(0.12F, (d.spinStreak - 1) * 0.03F);
        if (p.getRandom().nextFloat() < chance) {
            d.spinning = true;
            d.spinStart = now;
            d.spinArrows = 0;
            d.spinItem = held.getItem();
            d.spinSlot = p.getInventory().selected;
            d.spinStreak = 0;
            sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.TRIDENT_RIPTIDE_1.value(), SoundSource.PLAYERS, 0.5F, 1.8F);
            sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.8F, 1.5F);
            Vec3 hand = p.getEyePosition().add(p.getLookAngle().scale(0.6D)).add(0.0D, -0.4D, 0.0D);
            sl.sendParticles(ParticleTypes.ENCHANTED_HIT, hand.x, hand.y, hand.z, 8, 0.15D, 0.15D, 0.15D, 0.1D);
            p.displayClientMessage(Component.literal("Spin!").withStyle(net.minecraft.ChatFormatting.GOLD), true);
            addSpinXp(p, SPIN_SUCCESS_XP);
            sync(p);
            return;
        }
        spinFumble(p, false);
    }

    /**
     * Missed the timing: the item slips out of your hand (and you still learn a little from it).
     * Very rarely the slip turns into a wild throw that actually hits something.
     */
    public static void spinFumble(ServerPlayer p, boolean offHand) {
        StandData d = data(p);
        if (!d.spinLearned) return;
        ServerLevel sl = (ServerLevel) p.level();
        net.minecraft.world.item.ItemStack removed = offHand ? p.getOffhandItem().split(1) : p.getInventory().removeFromSelected(false);
        if (removed.isEmpty()) return;
        net.minecraft.world.entity.item.ItemEntity dropped = p.drop(removed, false, true);
        p.swing(offHand ? net.minecraft.world.InteractionHand.OFF_HAND : net.minecraft.world.InteractionHand.MAIN_HAND, true);
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.6F, 0.5F);
        addSpinXp(p, 1); // practice makes perfect
        // Very rarely the fumble turns into a wild throw that actually hits something.
        if (dropped != null && p.getRandom().nextFloat() < 0.03F + 0.002F * d.spinLevel) {
            Vec3 look = p.getLookAngle();
            dropped.setDeltaMovement(look.scale(1.1D).add(0.0D, 0.1D, 0.0D));
            net.minecraft.world.entity.LivingEntity hit = null;
            double best = 0.0D;
            for (net.minecraft.world.entity.LivingEntity e : sl.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                    p.getBoundingBox().inflate(6.0D), x -> x != p && x.isAlive() && StandCombat.valid(p, x))) {
                Vec3 to = e.getEyePosition().subtract(p.getEyePosition());
                double dist = to.length();
                if (dist > 6.0D) continue;
                double dot = to.normalize().dot(look);
                if (dot > 0.93D && dot > best) {
                    best = dot;
                    hit = e;
                }
            }
            if (hit != null) {
                hit.invulnerableTime = 0;
                StandCombat.hurt(p, hit, 1.5F + d.spinLevel * 0.15F);
                sl.sendParticles(ParticleTypes.CRIT, hit.getX(), hit.getEyeY(), hit.getZ(), 6, 0.2D, 0.2D, 0.2D, 0.1D);
                sl.playSound(null, hit.getX(), hit.getY(), hit.getZ(), SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.PLAYERS, 0.8F, 1.3F);
            }
        }
    }

    /**
     * Drop from the selected slot. (Not ServerPlayer#drop(boolean): that one assumes the client already removed the
     * item itself - which it didn't here, since Spin took over the Q key - so the client would keep a phantom item.)
     */
    private static boolean dropSelected(ServerPlayer p, boolean fullStack) {
        net.minecraft.world.item.ItemStack removed = p.getInventory().removeFromSelected(fullStack);
        if (removed.isEmpty()) return false;
        p.drop(removed, false, true);
        return true;
    }

    /** Spin user tapped Q (no wind-up): a plain drop, like vanilla. */
    public static void spinDrop(ServerPlayer p, boolean fullStack) {
        if (p.getMainHandItem().isEmpty()) return;
        if (dropSelected(p, fullStack)) p.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
    }

    /**
     * Hold-Q wind-up + spammed the cue: the item starts spinning. Guaranteed for now (everyone is a master);
     * later {@link #spinChance} can decide it by skill.
     */
    /** A Spin master: max level. */
    public static boolean spinMaster(StandData d) {
        return d.spinLearned && d.spinLevel >= StandData.SPIN_MAX_LEVEL;
    }

    /** Weapons (swords, axes, tridents, maces). */
    public static boolean isWeapon(net.minecraft.world.item.ItemStack s) {
        return s.is(net.minecraft.tags.ItemTags.SWORDS) || s.is(net.minecraft.tags.ItemTags.AXES)
                || s.getItem() instanceof net.minecraft.world.item.TridentItem || s.getItem() instanceof net.minecraft.world.item.MaceItem;
    }

    /** What comes back after a right-click throw: Steel Balls always, and a master's weapons. */
    public static boolean spinReturns(ServerPlayer p, net.minecraft.world.item.ItemStack s) {
        return s.is(com.mobstands.registry.ModItems.STEEL_BALL.get()) || (spinMaster(data(p)) && isWeapon(s));
    }

    /**
     * Shift while winding up a Steel Ball: a second Steel Ball from your inventory (or the same stack) goes into your
     * off hand by itself, and both start spinning together.
     */
    public static void spinDualSteel(ServerPlayer p) {
        StandData d = data(p);
        net.minecraft.world.item.Item steel = com.mobstands.registry.ModItems.STEEL_BALL.get();
        if (!d.spinLearned) return;
        if (!p.getMainHandItem().is(steel) && p.getOffhandItem().is(steel)) {
            // The ball's in your off hand: bring a second one into your main hand first.
            if (mayNotSpin(p, d, p.getOffhandItem())) return;
            net.minecraft.world.item.ItemStack off = p.getOffhandItem();
            net.minecraft.world.item.ItemStack second = net.minecraft.world.item.ItemStack.EMPTY;
            if (off.getCount() >= 2) {
                second = off.split(1);
            } else {
                net.minecraft.world.entity.player.Inventory inv = p.getInventory();
                for (int i = 0; i < inv.items.size(); i++) {
                    net.minecraft.world.item.ItemStack s = inv.items.get(i);
                    if (i != inv.selected && s.is(steel)) {
                        second = s.split(1);
                        break;
                    }
                }
            }
            if (second.isEmpty()) {
                spinOffHand(p, 15); // only the one: spin it alone
                return;
            }
            net.minecraft.world.item.ItemStack mainOld = p.getMainHandItem();
            if (!mainOld.isEmpty()) {
                net.minecraft.world.item.ItemStack moved = mainOld.copy();
                p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
                if (!p.getInventory().add(moved)) p.drop(moved, false);
            }
            p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, second);
            spinStart(p);
            spinOffHand(p, 15);
            return;
        }
        net.minecraft.world.item.ItemStack main = p.getMainHandItem();
        if (!main.is(steel)) return;
        if (mayNotSpin(p, d, main)) return;
        net.minecraft.world.item.ItemStack off = p.getOffhandItem();
        if (!off.is(steel)) {
            // Find the second ball: the rest of the main-hand stack, or anywhere else in the inventory.
            net.minecraft.world.item.ItemStack second = net.minecraft.world.item.ItemStack.EMPTY;
            if (main.getCount() >= 2) {
                second = main.split(1);
            } else {
                net.minecraft.world.entity.player.Inventory inv = p.getInventory();
                for (int i = 0; i < inv.items.size(); i++) {
                    if (i == inv.selected) continue;
                    net.minecraft.world.item.ItemStack s = inv.items.get(i);
                    if (s.is(steel)) {
                        second = s.split(1);
                        break;
                    }
                }
            }
            if (second.isEmpty()) {
                spinStart(p); // only the one: spin it alone
                return;
            }
            // Whatever was in the off hand goes back into the inventory.
            if (!off.isEmpty()) {
                net.minecraft.world.item.ItemStack moved = off.copy();
                p.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, net.minecraft.world.item.ItemStack.EMPTY);
                if (!p.getInventory().add(moved)) p.drop(moved, false);
            }
            p.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, second);
        }
        spinStart(p);
        spinOffHand(p, 15);
    }

    /** Steel Balls can only be spun by a master of the Spin. */
    private static boolean mayNotSpin(ServerPlayer p, StandData d, net.minecraft.world.item.ItemStack s) {
        if (!s.is(com.mobstands.registry.ModItems.STEEL_BALL.get()) || spinMaster(d)) return false;
        p.displayClientMessage(Component.literal("Only a master of the Spin can spin a Steel Ball. (Lv " + StandData.SPIN_MAX_LEVEL + ")")
                .withStyle(net.minecraft.ChatFormatting.GOLD), true);
        return true;
    }

    public static void spinStart(ServerPlayer p) {
        StandData d = data(p);
        net.minecraft.world.item.ItemStack held = p.getMainHandItem();
        if (!d.spinLearned || held.isEmpty() || d.spinning) return;
        if (mayNotSpin(p, d, held)) return;
        ServerLevel sl = (ServerLevel) p.level();
        d.spinning = true;
        d.spinStart = sl.getGameTime();
        d.spinArrows = 0;
        d.spinItem = held.getItem();
        d.spinSlot = p.getInventory().selected;
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), com.mobstands.registry.ModSounds.SPIN_HAMON.get(), SoundSource.PLAYERS, 1.0F, 1.0F);
        Vec3 hand = p.getEyePosition().add(p.getLookAngle().scale(0.6D)).add(0.0D, -0.4D, 0.0D);
        hamonBurst(sl, hand, 8, 0.15D);
        addSpinXp(p, SPIN_SUCCESS_XP);
        sync(p);
    }

    /**
     * Spin controls for the off hand (Shift + Q, or Q with an empty main hand):
     * 13 = drop the spinning item to the floor, 14 = stop its spin, 15 = start spinning it, 16 = drop one (no spin).
     */
    public static void spinOffHand(ServerPlayer p, int action) {
        StandData d = data(p);
        net.minecraft.world.item.ItemStack off = p.getOffhandItem();
        if (!d.spinLearned || off.isEmpty()) return;
        ServerLevel sl = (ServerLevel) p.level();
        long now = sl.getGameTime();
        switch (action) {
            case 15 -> {
                if (d.offSpinning || mayNotSpin(p, d, off)) return;
                d.offSpinning = true;
                d.offSpinStart = now;
                d.offSpinItem = off.getItem();
                sl.playSound(null, p.getX(), p.getY(), p.getZ(), com.mobstands.registry.ModSounds.SPIN_HAMON.get(), SoundSource.PLAYERS, 1.0F, 1.0F);
                Vec3 look = p.getLookAngle();
                boolean rightSide = p.getMainArm() != net.minecraft.world.entity.HumanoidArm.RIGHT; // the off hand's side
                Vec3 side = new Vec3(-look.z, 0.0D, look.x).normalize().scale(rightSide ? 0.4D : -0.4D);
                hamonBurst(sl, p.getEyePosition().add(look.scale(0.6D)).add(side).add(0.0D, -0.4D, 0.0D), 8, 0.15D);
                addSpinXp(p, SPIN_SUCCESS_XP);
            }
            case 14 -> {
                if (!d.offSpinning) return;
                d.offSpinning = false;
                sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.5F, 0.6F);
            }
            case 13 -> {
                if (!d.offSpinning) return;
                float power = (0.35F + 0.65F * spinSpeed(d.offSpinStart, now, spinTicks(d.spinLevel))) * spinMult(d.spinLevel);
                net.minecraft.world.item.ItemStack one = off.split(1);
                d.offSpinning = false;
                com.mobstands.entity.SpinItemEntity si = new com.mobstands.entity.SpinItemEntity(com.mobstands.registry.ModEntities.SPIN_ITEM.get(), sl);
                si.setup(p, one, power);
                si.setPos(p.getX(), p.getEyeY() - 0.3D, p.getZ());
                Vec3 look = p.getLookAngle();
                si.setDeltaMovement(look.x * 0.3D, look.y * 0.3D + 0.1D, look.z * 0.3D);
                sl.addFreshEntity(si);
                p.swing(net.minecraft.world.InteractionHand.OFF_HAND, true);
            }
            case 16 -> {
                net.minecraft.world.item.ItemStack one = off.split(1);
                p.drop(one, false, true);
                p.swing(net.minecraft.world.InteractionHand.OFF_HAND, true);
            }
            default -> { }
        }
        sync(p);
    }

    // Thrown spinning items in flight, and mobs sent flying by them.
    /** A spinning item dropped on the floor: it keeps spinning there and blasts anyone who comes close. */
    private static final class SpinThrow {
        final java.util.UUID owner;
        final long thrownAt;
        final float power;
        final java.util.Map<java.util.UUID, Long> lastHit = new java.util.HashMap<>();
        SpinThrow(java.util.UUID owner, long thrownAt, float power) {
            this.owner = owner;
            this.thrownAt = thrownAt;
            this.power = power;
        }
    }
    /** How long a dropped item keeps spinning on the floor (ticks). */
    public static final int FLOOR_SPIN_TICKS = 600;
    /** Item data keys: the client reads these to keep the dropped item spinning. */
    public static final String SPIN_TAG = "mobstands_spin", SPIN_POWER_TAG = "mobstands_spin_power";
    private record SpinFling(java.util.UUID owner, long until) {}
    private static final java.util.Map<java.util.UUID, SpinThrow> SPIN_THROWS = new java.util.HashMap<>();
    private static final java.util.Map<java.util.UUID, SpinFling> SPIN_FLUNG = new java.util.HashMap<>();
    /** Half-size of the area a spinning item on the floor affects (an 8x8 block square). */
    public static final double SPIN_RADIUS = 4.0D;

    /**
     * Tap Q while spinning: the spinning item drops to the floor like a normal item, but keeps spinning there for
     * 30 seconds (it can't be picked up until it stops) and blasts anyone who comes within its 8x8 area.
     */
    public static void spinThrow(ServerPlayer p) {
        StandData d = data(p);
        net.minecraft.world.item.ItemStack held = p.getMainHandItem();
        if (!d.spinLearned || !d.spinning || held.isEmpty()) return;
        long now = p.level().getGameTime();
        float power = (0.35F + 0.65F * spinSpeed(d, now)) * spinMult(d.spinLevel);
        net.minecraft.world.item.ItemStack one = held.split(1);
        d.spinning = false;
        sync(p);
        com.mobstands.entity.SpinItemEntity si = new com.mobstands.entity.SpinItemEntity(com.mobstands.registry.ModEntities.SPIN_ITEM.get(), p.level());
        si.setup(p, one, power);
        // Dropped like a normal item (same spot and toss as pressing Q).
        si.setPos(p.getX(), p.getEyeY() - 0.3D, p.getZ());
        Vec3 look = p.getLookAngle();
        si.setDeltaMovement(look.x * 0.3D, look.y * 0.3D + 0.1D, look.z * 0.3D);
        p.level().addFreshEntity(si);
        p.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
    }

    /**
     * Right-click throw: the spinning item flies out (further the longer you wound up). Whoever it hits gets
     * spun around 180 degrees and loses track of things for a moment - and a mob with a bow, crossbow or any
     * projectile ends up shooting itself for a few seconds. Then it flies back to you.
     */
    public static void spinThrowFar(ServerPlayer p, int charge) {
        StandData d = data(p);
        if (!d.spinLearned) return;
        if (HorseSpin.tryApply(p, d)) return; // riding: the Spin goes into the horse's legs instead
        long now = p.level().getGameTime();
        boolean main = d.spinning && !p.getMainHandItem().isEmpty()
                && !(p.getMainHandItem().getItem() instanceof net.minecraft.world.item.ProjectileWeaponItem);
        boolean off = d.offSpinning && !p.getOffhandItem().isEmpty();
        if (!main && !off) return;
        // Both hands spinning: both fly out at once, a little apart.
        boolean both = main && off;
        if (both && p.getXRot() > 55.0F) {
            spinRocket(p, d, charge, now); // thrown down at your own feet: you rocket up
        }
        if (main) {
            float power = (0.35F + 0.65F * spinSpeed(d, now)) * spinMult(d.spinLevel);
            d.spinning = false;
            launchSpin(p, p.getMainHandItem().split(1), power, charge, false, both);
        }
        if (off) {
            float power = (0.35F + 0.65F * spinSpeed(d.offSpinStart, now, spinTicks(d.spinLevel))) * spinMult(d.spinLevel);
            d.offSpinning = false;
            launchSpin(p, p.getOffhandItem().split(1), power, charge, true, both);
        }
        sync(p);
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.TRIDENT_THROW.value(), SoundSource.PLAYERS, 0.8F, both ? 1.3F : 1.5F);
    }

    /**
     * Two spinning items hurled down at your own feet: the double spin kicks off the ground and rockets you up
     * (higher the more you wound up). No fall damage until you land again.
     */
    private static void spinRocket(ServerPlayer p, StandData d, int charge, long now) {
        ServerLevel sl = (ServerLevel) p.level();
        double up = (0.85D + 0.65D * (charge / 40.0D)) * (0.7D + 0.3D * spinSkill(d.spinLevel)); // rookies barely hop
        Vec3 v = p.getDeltaMovement();
        p.setDeltaMovement(v.x * 1.2D, up, v.z * 1.2D);
        p.hurtMarked = true;
        p.resetFallDistance();
        d.spinRocketAt = now;
        Vec3 c = p.position();
        sl.playSound(null, c.x, c.y, c.z, com.mobstands.registry.ModSounds.SPIN_HAMON.get(), SoundSource.PLAYERS, 1.2F, 0.85F);
        sl.playSound(null, c.x, c.y, c.z, SoundEvents.TRIDENT_RIPTIDE_3.value(), SoundSource.PLAYERS, 1.0F, 1.2F);
        for (int i = 0; i < 20; i++) {
            double a = i * (Math.PI * 2.0D / 20.0D);
            sl.sendParticles(ParticleTypes.CLOUD, c.x + Math.cos(a) * 0.6D, c.y + 0.1D, c.z + Math.sin(a) * 0.6D, 0,
                    Math.cos(a) * 0.3D, 0.02D, Math.sin(a) * 0.3D, 1.0D);
        }
        sl.sendParticles(ParticleTypes.SWEEP_ATTACK, c.x, c.y + 0.2D, c.z, 2, 0.4D, 0.0D, 0.4D, 0.0D);
        hamonBurst(sl, c.add(0.0D, 0.2D, 0.0D), 16, 0.5D);
    }

    /** After a spin rocket: no fall damage until you're back on the ground. */
    private static void rocketTick(ServerPlayer p, StandData d, long now) {
        if (now - d.spinRocketAt > 200L) return;
        if (p.onGround() && now - d.spinRocketAt > 5L) {
            d.spinRocketAt = -1000L;
            return;
        }
        p.resetFallDistance();
        if (now % 2L == 0L) hamonBurst((ServerLevel) p.level(), p.position(), 1, 0.2D);
    }

    /** The player right-clicked one of their thrown balls from a distance: the one under the crosshair comes back. */
    public static void recallBall(ServerPlayer p) {
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getLookAngle();
        com.mobstands.entity.SpinItemEntity best = null;
        double bestAng = 0.06D; // about 3.5 degrees off the crosshair, at most
        for (com.mobstands.entity.SpinItemEntity si : p.serverLevel().getEntitiesOfClass(com.mobstands.entity.SpinItemEntity.class,
                p.getBoundingBox().inflate(RECALL_RANGE), x -> x.ownedBy(p))) {
            Vec3 to = si.position().add(0.0D, si.getBbHeight() * 0.5D, 0.0D).subtract(eye);
            double d = to.length();
            if (d < 1.0E-3D || d > RECALL_RANGE) continue;
            double ang = Math.acos(Math.max(-1.0D, Math.min(1.0D, to.scale(1.0D / d).dot(look))));
            // Closer balls are bigger on screen: allow a bit more slack up close.
            if (ang < Math.max(bestAng, 0.45D / d) && (best == null || ang < bestAng)) {
                best = si;
                bestAng = ang;
            }
        }
        // A Steel Ball you dropped on the ground (a plain item) counts too.
        net.minecraft.world.entity.item.ItemEntity bestItem = null;
        double itemAng = Double.MAX_VALUE;
        for (net.minecraft.world.entity.item.ItemEntity ie : p.serverLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                p.getBoundingBox().inflate(RECALL_RANGE), x -> x.getItem().is(com.mobstands.registry.ModItems.STEEL_BALL.get())
                        && (x.getOwner() == null || x.getOwner() == p))) {
            Vec3 to = ie.position().add(0.0D, 0.15D, 0.0D).subtract(eye);
            double d = to.length();
            if (d < 1.0E-3D || d > RECALL_RANGE) continue;
            double ang = Math.acos(Math.max(-1.0D, Math.min(1.0D, to.scale(1.0D / d).dot(look))));
            // Whichever is closest to the crosshair wins: a thrown ball or this dropped one.
            if (ang < Math.max(0.06D, 0.45D / d) && ang < itemAng && (best == null || ang < bestAng)) {
                bestItem = ie;
                itemAng = ang;
            }
        }
        if (bestItem != null) {
            Vec3 at = bestItem.position();
            com.mobstands.entity.SpinItemEntity.recallItem(p, bestItem);
            p.serverLevel().playSound(null, at.x, at.y, at.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0F, 1.8F);
            hamonBurst(p.serverLevel(), at, 8, 0.2D);
            return;
        }
        if (best != null && best.recall()) {
            p.serverLevel().playSound(null, best.getX(), best.getY(), best.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0F, 1.8F);
            hamonBurst(p.serverLevel(), best.position(), 8, 0.2D);
        }
    }

    public static final double RECALL_RANGE = 64.0D;

    /** Aerosmith: the piloted Phantom fires a blue bolt where you're looking (a few per second). */
    private static final java.util.Map<java.util.UUID, Long> LAST_BOLT = new java.util.HashMap<>();
    /**
     * Aerosmith's gun overheats: 70 shots' worth of room. Each shot uses one; stop firing for a moment and it cools
     * back up. Empty it and it overheats - locked until it has cooled all the way.
     */
    public static final int PILOT_AMMO = 70;
    private static final java.util.Map<java.util.UUID, Integer> AMMO = new java.util.HashMap<>();
    private static final java.util.Set<java.util.UUID> OVERHEATED = new java.util.HashSet<>();
    public static final String AERO_TAG = "mobstands_aero";

    /** Taking off with a cool gun. */
    public static void pilotReload(ServerPlayer p) {
        AMMO.put(p.getUUID(), PILOT_AMMO);
        OVERHEATED.remove(p.getUUID());
        sendHeat(p);
    }

    private static void sendHeat(ServerPlayer p) {
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new com.mobstands.network.PilotHeatPayload(
                AMMO.getOrDefault(p.getUUID(), PILOT_AMMO), OVERHEATED.contains(p.getUUID())));
    }

    /** Every tick while piloting: the gun cools down once you've stopped firing for a moment. */
    public static void pilotCool(ServerPlayer p) {
        int ammo = AMMO.getOrDefault(p.getUUID(), PILOT_AMMO);
        Long last = LAST_BOLT.get(p.getUUID());
        long now = p.level().getGameTime();
        boolean hot = OVERHEATED.contains(p.getUUID());
        if (ammo >= PILOT_AMMO || (!hot && last != null && now - last < 15L)) return;
        ammo = Math.min(PILOT_AMMO, ammo + (hot ? 1 : 2)); // an overheated gun cools slower
        AMMO.put(p.getUUID(), ammo);
        if (hot && ammo >= PILOT_AMMO) {
            OVERHEATED.remove(p.getUUID());
            p.serverLevel().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.4F, 1.8F);
        }
        sendHeat(p);
    }

    public static void pilotShoot(ServerPlayer p) {
        com.mobstands.entity.StandEntity s = getStand(p);
        if (s == null || !s.isPiloting()) return;
        long now = p.level().getGameTime();
        Long last = LAST_BOLT.get(p.getUUID());
        if (last != null && now - last < 3L && now >= last) return;
        int ammo = AMMO.getOrDefault(p.getUUID(), PILOT_AMMO);
        if (OVERHEATED.contains(p.getUUID())) {
            if (now % 10L == 0L) p.serverLevel().playSound(null, s.getX(), s.getY(), s.getZ(), SoundEvents.DISPENSER_FAIL, SoundSource.PLAYERS, 0.6F, 1.6F);
            return;
        }
        ammo--;
        AMMO.put(p.getUUID(), ammo);
        if (ammo <= 0) {
            OVERHEATED.add(p.getUUID());
            p.displayClientMessage(net.minecraft.network.chat.Component.literal("Overheated!").withStyle(st -> st.withColor(0xFF5533).withBold(true)), true);
            p.serverLevel().playSound(null, s.getX(), s.getY(), s.getZ(), SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.8F, 0.8F);
        }
        sendHeat(p);
        LAST_BOLT.put(p.getUUID(), now);
        // Aim where the Phantom is facing (your game sends its heading with every move). Your body isn't the camera
        // while you fly, so the server never hears where you're looking.
        Vec3 dir = Vec3.directionFromRotation(s.getXRot(), s.getYRot());
        Vec3 from = s.position().add(0.0D, 0.3D, 0.0D).add(dir.scale(1.2D));
        // Real arrows with the Turtle Master tipped-arrow look (its coloured swirl), but no potion effect at all.
        net.minecraft.world.item.ItemStack look = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TIPPED_ARROW);
        int color = net.minecraft.world.item.alchemy.PotionContents.getColor(net.minecraft.world.item.alchemy.Potions.TURTLE_MASTER);
        look.set(net.minecraft.core.component.DataComponents.POTION_CONTENTS,
                new net.minecraft.world.item.alchemy.PotionContents(java.util.Optional.empty(), java.util.Optional.of(color), java.util.List.of()));
        net.minecraft.world.entity.projectile.Arrow arrow = new net.minecraft.world.entity.projectile.Arrow(p.serverLevel(), from.x, from.y, from.z, look, null);
        arrow.setOwner(p);
        arrow.pickup = net.minecraft.world.entity.projectile.AbstractArrow.Pickup.CREATIVE_ONLY;
        arrow.setBaseDamage(0.15D); // a nick each - half a heart - but a hundred of them, and each one lands
        arrow.addTag(AERO_TAG);
        arrow.shoot(dir.x, dir.y, dir.z, 3.2F, 1.5F);
        p.serverLevel().addFreshEntity(arrow);
        p.serverLevel().playSound(null, from.x, from.y, from.z, SoundEvents.ARROW_SHOOT, SoundSource.PLAYERS, 0.6F, 1.6F + p.getRandom().nextFloat() * 0.3F);
    }

    /** Aerosmith: bring the Phantom back. */
    public static void pilotLand(ServerPlayer p) {
        com.mobstands.entity.StandEntity s = getStand(p);
        if (s == null || !s.isPiloting()) return;
        s.setPiloting(false);
        p.serverLevel().playSound(null, s.getX(), s.getY(), s.getZ(), SoundEvents.PHANTOM_SWOOP, SoundSource.PLAYERS, 1.0F, 1.2F);
    }

    /** Aerosmith: where the pilot's game says the Phantom is. */
    public static void pilotMove(ServerPlayer p, double x, double y, double z, float yRot, float xRot) {
        com.mobstands.entity.StandEntity s = getStand(p);
        if (s == null || !s.isPiloting()) return;
        if (p.distanceToSqr(x, y, z) > (com.mobstands.entity.StandEntity.PILOT_RANGE + 40.0D) * (com.mobstands.entity.StandEntity.PILOT_RANGE + 40.0D)) return;
        s.setPos(x, y, z);
        s.setYRot(yRot);
        s.setXRot(xRot);
    }

    private static void launchSpin(ServerPlayer p, net.minecraft.world.item.ItemStack one, float power, int charge, boolean offHand, boolean spread) {
        com.mobstands.entity.SpinItemEntity si = new com.mobstands.entity.SpinItemEntity(com.mobstands.registry.ModEntities.SPIN_ITEM.get(), p.level());
        si.setup(p, one, power);
        si.setOffHand(offHand);
        si.setReturns(spinReturns(p, one));
        Vec3 look = p.getLookAngle();
        // Which side this hand is on (right hand = right side for a right-handed player).
        boolean rightSide = (p.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT) != offHand;
        Vec3 right = new Vec3(-look.z, 0.0D, look.x).normalize().scale(rightSide ? 1.0D : -1.0D);
        Vec3 start = new Vec3(p.getX(), p.getEyeY() - 0.2D, p.getZ()).add(right.scale(0.3D));
        si.setPos(start.x, start.y, start.z);
        double speed = 0.8D + 1.7D * (charge / 40.0D);
        // One Steel Ball flies a bit slower and not as far; two thrown together keep the full speed and range.
        if (one.is(com.mobstands.registry.ModItems.STEEL_BALL.get()) && !spread) speed = 0.6D + 1.15D * (charge / 40.0D);
        Vec3 dir = spread ? look.add(right.scale(0.06D)).normalize() : look; // two at once fan out slightly
        si.setDeltaMovement(dir.scale(speed).add(p.getDeltaMovement().multiply(1.0D, 0.0D, 1.0D)));
        si.markThrown();
        // Sky toss: a Steel Ball thrown upward can be steered with your look.
        if (p.getXRot() < -35.0F && one.is(com.mobstands.registry.ModItems.STEEL_BALL.get())) si.setSteerable();
        // Wall Sense: a Steel Ball thrown while sneaking sticks to the wall it hits and listens for vibrations.
        // Sneaking and looking up: both - steer it like a missile, and it sticks wherever you fly it into.
        if (p.isShiftKeyDown() && !p.isPassenger() && one.is(com.mobstands.registry.ModItems.STEEL_BALL.get())) si.setWallSense();
        p.level().addFreshEntity(si);
        p.swing(offHand ? net.minecraft.world.InteractionHand.OFF_HAND : net.minecraft.world.InteractionHand.MAIN_HAND, true);
    }

    // ------------------------------------------------------------------ Spin: sword ground slash

    /** Ticks between ground slashes, how far the slash runs and how many steps it takes to get there. */
    public static final int SLASH_COOLDOWN = 25, SLASH_STEPS = 11;
    public static final double SLASH_STEP = 0.8D;

    /** A spinning sword (main hand) is in your hand. */
    public static boolean spinningSword(StandData d, net.minecraft.world.item.ItemStack main) {
        return d.spinLearned && d.spinning && main.getItem() instanceof net.minecraft.world.item.SwordItem;
    }

    /**
     * Strike the ground with a spinning sword: it rolls forward in your hand and sends a cutting wave racing along
     * the ground in front of you - a chain of golden crescent slashes, curving left and right like a spinning
     * blade's track - cutting everyone standing in its path.
     */
    public static void spinSwordSlash(ServerPlayer p) {
        StandData d = data(p);
        ServerLevel sl = (ServerLevel) p.level();
        long now = sl.getGameTime();
        if (!spinningSword(d, p.getMainHandItem()) || now - d.spinSlashAt < SLASH_COOLDOWN) return;
        d.spinSlashAt = now;
        float power = (0.35F + 0.65F * spinSpeed(d, now)) * spinMult(d.spinLevel);
        d.spinning = false; // the slash uses up the spin
        sync(p);
        int lv = d.spinLevel;
        Vec3 dir = p.getLookAngle().multiply(1.0D, 0.0D, 1.0D);
        dir = dir.lengthSqr() < 1.0E-4D ? Vec3.directionFromRotation(0.0F, p.getYRot()) : dir.normalize();
        Vec3 side = new Vec3(-dir.z, 0.0D, dir.x);
        Vec3 origin = p.position();
        p.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.2F, 0.7F);
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), com.mobstands.registry.ModSounds.SPIN_HAMON.get(), SoundSource.PLAYERS, 0.9F, 1.2F);
        java.util.Set<java.util.UUID> cut = new java.util.HashSet<>();
        final Vec3 fdir = dir;
        int steps = Math.round(5 + (SLASH_STEPS - 5) * spinSkill(d.spinLevel)); // a rookie's slash reaches half as far
        for (int i = 0; i < steps; i++) {
            final int step = i;
            TickScheduler.later(i, () -> {
                if (!p.isAlive()) return;
                Vec3 at = origin.add(fdir.scale(1.2D + step * SLASH_STEP));
                // Follow the ground.
                net.minecraft.world.phys.BlockHitResult ground = sl.clip(new net.minecraft.world.level.ClipContext(at.add(0.0D, 2.0D, 0.0D),
                        at.add(0.0D, -4.0D, 0.0D), net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, p));
                double gy = ground.getType() == net.minecraft.world.phys.HitResult.Type.MISS ? at.y : ground.getLocation().y;
                Vec3 c = new Vec3(at.x, gy + 0.05D, at.z);
                slashCrescent(sl, c, fdir, side, step);
                // The ground itself gets cut.
                net.minecraft.core.BlockPos below = net.minecraft.core.BlockPos.containing(c.x, gy - 0.5D, c.z);
                net.minecraft.world.level.block.state.BlockState bs = sl.getBlockState(below);
                if (!bs.isAir()) {
                    sl.sendParticles(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, bs), c.x, c.y + 0.1D, c.z, 6, 0.2D, 0.05D, 0.2D, 0.1D);
                }
                if (step % 2 == 0) sl.playSound(null, c.x, c.y, c.z, SoundEvents.TRIDENT_RIPTIDE_2.value(), SoundSource.PLAYERS, 0.35F, 1.6F + step * 0.03F);
                // Cut whoever stands on this stretch.
                for (net.minecraft.world.entity.LivingEntity e : sl.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                        new net.minecraft.world.phys.AABB(c.x - 0.7D, c.y - 0.5D, c.z - 0.7D, c.x + 0.7D, c.y + 3.0D, c.z + 0.7D),
                        x -> x != p && x.isAlive() && !cut.contains(x.getUUID()) && StandCombat.valid(p, x))) {
                    cut.add(e.getUUID());
                    e.invulnerableTime = 0;
                    StandCombat.hurt(p, e, (2.5F + lv * 0.15F) * power);
                    e.setDeltaMovement(e.getDeltaMovement().add(fdir.scale(0.35D)).add(0.0D, 0.3D, 0.0D));
                    e.hurtMarked = true;
                    hamonBurst(sl, e.position().add(0.0D, e.getBbHeight() * 0.5D, 0.0D), 10, e.getBbWidth() * 0.5D);
                    sl.sendParticles(ParticleTypes.SWEEP_ATTACK, e.getX(), e.getY() + e.getBbHeight() * 0.5D, e.getZ(), 1, 0.0D, 0.0D, 0.0D, 0.0D);
                    sl.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1.0F, 1.2F);
                }
            });
        }
    }

    /**
     * One link of the slash's track: a golden crescent across the path, bowing forward and back in turn so the
     * whole trail reads as a spinning blade's zig-zag, with a bright spark at its heart.
     */
    private static void slashCrescent(ServerLevel sl, Vec3 c, Vec3 dir, Vec3 side, int step) {
        net.minecraft.core.particles.DustParticleOptions gold = new net.minecraft.core.particles.DustParticleOptions(new org.joml.Vector3f(1.0F, 0.84F, 0.25F), 1.3F);
        net.minecraft.core.particles.DustParticleOptions pale = new net.minecraft.core.particles.DustParticleOptions(new org.joml.Vector3f(1.0F, 0.97F, 0.75F), 0.9F);
        // A tall, thin crescent standing up out of the ground, bowing forward and back in turn.
        double bow = (step % 2 == 0 ? 1.0D : -1.0D) * 0.4D;
        double height = 2.0D + Math.min(step, 6) * 0.1D; // the cut grows taller as it runs
        for (int j = 0; j <= 12; j++) {
            double u = j / 12.0D;                // 0 at the ground, 1 at the tip
            double v = u * 2.0D - 1.0D;
            Vec3 pt = c.add(0.0D, u * height, 0.0D).add(dir.scale(bow * (1.0D - v * v)));
            sl.sendParticles(Math.abs(v) <= 0.35D ? pale : gold, pt.x, pt.y, pt.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
        sl.sendParticles(ParticleTypes.SWEEP_ATTACK, c.x, c.y + height * 0.5D, c.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        if (step % 2 == 0) hamonBurst(sl, c.add(0.0D, 0.3D, 0.0D), 2, 0.2D);
    }

    /** F (swap hands): a spinning item keeps spinning in whichever hand it ends up in. */
    public static void spinSwapHands(ServerPlayer p) {
        StandData d = data(p);
        if (!d.spinLearned || (!d.spinning && !d.offSpinning)) return;
        boolean mainWas = d.spinning, offWas = d.offSpinning;
        long mainStart = d.spinStart, offStart = d.offSpinStart;
        net.minecraft.world.item.Item mainItem = d.spinItem, offItem = d.offSpinItem;
        d.offSpinning = mainWas;
        d.offSpinStart = mainStart;
        d.offSpinItem = mainItem;
        d.spinning = offWas;
        d.spinStart = offStart;
        d.spinItem = offItem;
        d.spinSlot = p.getInventory().selected;
        sync(p);
    }

    // Mobs hit by a thrown spin: spun round, confused, and their own projectiles turn back on them.
    private static final java.util.Map<java.util.UUID, long[]> SPIN_TURNED = new java.util.HashMap<>();
    private static final java.util.Map<java.util.UUID, java.util.UUID> SPIN_TURNED_BY = new java.util.HashMap<>();
    /** How long the hit mob is confused (can't target anyone), and how long its shots turn back on it. */
    public static final int TURN_CONFUSE_TICKS = 50, TURN_REVERSE_TICKS = 100;

    /** The flying spinning item hit someone. */
    public static void spinThrowHit(ServerPlayer p, net.minecraft.world.entity.LivingEntity e, float power, boolean steel) {
        ServerLevel sl = (ServerLevel) p.level();
        long now = sl.getGameTime();
        e.invulnerableTime = 0;
        if (steel) {
            // A Steel Ball's spin gets into the flesh: their muscles twist - slowed and weakened for a while.
            power *= 1.25F;
            e.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN, 80, 1));
            e.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.WEAKNESS, 80, 0));
            sl.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.35F, 1.9F);
        }
        StandCombat.hurt(p, e, (2.0F + data(p).spinLevel * 0.1F) * power);
        // Caught by the spin: they whirl round and round on the spot, ending up facing the wrong way.
        twirl(e, now);
        if (e instanceof net.minecraft.world.entity.Mob m) {
            m.setTarget(null);
            m.getNavigation().stop();
        }
        SPIN_TURNED.put(e.getUUID(), new long[]{now + TURN_CONFUSE_TICKS, now + TURN_REVERSE_TICKS});
        SPIN_TURNED_BY.put(e.getUUID(), p.getUUID());
        // A Steel Ball into an archer (skeleton, pillager, anyone with a bow or crossbow): it spins, and when it
        // comes out of it, it turns its weapon on another mob nearby instead of you.
        if (steel && e instanceof net.minecraft.world.entity.Mob m && (e instanceof net.minecraft.world.entity.monster.RangedAttackMob
                || e instanceof net.minecraft.world.entity.monster.CrossbowAttackMob)) {
            MISFIRE.put(e.getUUID(), new Misfire(now + MISFIRE_TICKS, p.getUUID()));
            m.setLastHurtByMob(null); // it doesn't hold the hit against you
        }
        hamonBurst(sl, e.position().add(0.0D, e.getBbHeight() * 0.5D, 0.0D), 16, e.getBbWidth() * 0.5D);
        sl.playSound(null, e.getX(), e.getY(), e.getZ(), com.mobstands.registry.ModSounds.SPIN_HAMON.get(), SoundSource.PLAYERS, 1.0F, 1.0F);
        sl.sendParticles(ParticleTypes.CRIT, e.getX(), e.getEyeY(), e.getZ(), 8, 0.3D, 0.2D, 0.3D, 0.1D);
    }

    // ---- Steel Ball misfire: a spun archer shoots another mob ----
    private record Misfire(long until, java.util.UUID by) {}
    private static final java.util.Map<java.util.UUID, Misfire> MISFIRE = new java.util.HashMap<>();
    /** Archer UUID -> the mob it's been turned on. */
    private static final java.util.Map<java.util.UUID, java.util.UUID> MISFIRE_AT = new java.util.HashMap<>();
    public static final int MISFIRE_TICKS = 200;
    /** Chance a mob shot by a misfiring archer turns on that archer. */
    public static final float MISFIRE_RETALIATE = 0.4F;

    private static boolean misfiring(net.minecraft.world.entity.Entity e) {
        Misfire mf = MISFIRE.get(e.getUUID());
        return mf != null && e.level().getGameTime() < mf.until();
    }

    /** While misfiring, it only locks onto the mob it was turned on. */
    public static boolean misfireBlocksTarget(net.minecraft.world.entity.Entity e, net.minecraft.world.entity.LivingEntity newTarget) {
        if (!misfiring(e)) return false;
        java.util.UUID at = MISFIRE_AT.get(e.getUUID());
        return at == null || newTarget == null || !at.equals(newTarget.getUUID());
    }

    /** Server tick: once a spun archer stops whirling, it picks another mob nearby and shoots at it. */
    private static void misfireTick(ServerLevel sl) {
        if (MISFIRE.isEmpty()) return;
        long now = sl.getGameTime();
        java.util.Iterator<java.util.Map.Entry<java.util.UUID, Misfire>> it = MISFIRE.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<java.util.UUID, Misfire> en = it.next();
            net.minecraft.world.entity.Entity ent = sl.getEntity(en.getKey());
            if (ent == null) continue;
            if (!(ent instanceof net.minecraft.world.entity.Mob m) || !m.isAlive() || now >= en.getValue().until()) {
                if (ent instanceof net.minecraft.world.entity.Mob mm && mm.isAlive()) mm.setTarget(null);
                it.remove();
                MISFIRE_AT.remove(en.getKey());
                continue;
            }
            if (TWIRLS.containsKey(m.getUUID())) continue; // still whirling
            net.minecraft.world.entity.Entity cur = MISFIRE_AT.containsKey(m.getUUID()) ? sl.getEntity(MISFIRE_AT.get(m.getUUID())) : null;
            if (!(cur instanceof net.minecraft.world.entity.LivingEntity le) || !le.isAlive()) {
                // Someone else to shoot: the nearest other mob around it (monsters first).
                net.minecraft.world.entity.LivingEntity pick = null;
                double best = Double.MAX_VALUE;
                for (net.minecraft.world.entity.Mob o : sl.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, m.getBoundingBox().inflate(16.0D),
                        x -> x != m && x.isAlive())) {
                    double d = o.distanceToSqr(m) * (o instanceof net.minecraft.world.entity.monster.Enemy ? 1.0D : 2.5D);
                    if (d < best) {
                        best = d;
                        pick = o;
                    }
                }
                if (pick == null) {
                    // Nobody else around: it just stays dazed.
                    continue;
                }
                MISFIRE_AT.put(m.getUUID(), pick.getUUID());
                cur = pick;
                sl.sendParticles(ParticleTypes.ANGRY_VILLAGER, m.getX(), m.getEyeY() + 0.4D, m.getZ(), 1, 0.0D, 0.0D, 0.0D, 0.0D);
            }
            if (m.getTarget() != cur) m.setTarget((net.minecraft.world.entity.LivingEntity) cur);
        }
    }

    /** A mob was hurt: if a misfiring archer did it, 40% of the time it turns on that archer. */
    public static void misfireHurt(net.minecraft.world.entity.LivingEntity victim, net.minecraft.world.damagesource.DamageSource src) {
        if (!(src.getEntity() instanceof net.minecraft.world.entity.Mob shooter) || !misfiring(shooter)) return;
        if (!(victim instanceof net.minecraft.world.entity.Mob vm) || vm == shooter) return;
        if (vm.getRandom().nextFloat() < MISFIRE_RETALIATE) {
            vm.setTarget(shooter);
            ((ServerLevel) vm.level()).sendParticles(ParticleTypes.ANGRY_VILLAGER, vm.getX(), vm.getEyeY() + 0.4D, vm.getZ(), 1, 0.0D, 0.0D, 0.0D, 0.0D);
        } else {
            vm.setLastHurtByMob(null); // shrugs it off
        }
    }

    // Mobs whirling from a spin hit: {end time, final yaw}.
    private static final java.util.Map<java.util.UUID, float[]> TWIRLS = new java.util.HashMap<>();
    private static final java.util.Map<java.util.UUID, Long> TWIRL_END = new java.util.HashMap<>();
    /** How long a mob whirls after a spin hit (ticks), and how fast (degrees per tick). */
    public static final int TWIRL_TICKS = 60;
    private static final float TWIRL_SPEED = 47.0F;

    /** Starts a mob spinning on the spot; it ends up facing the opposite way. */
    public static void twirl(net.minecraft.world.entity.LivingEntity e, long now) {
        TWIRLS.put(e.getUUID(), new float[]{e.getYRot() + 180.0F});
        TWIRL_END.put(e.getUUID(), now + TWIRL_TICKS);
        if (e instanceof net.minecraft.world.entity.Mob m) {
            m.setTarget(null);
            m.getNavigation().stop();
        }
    }

    /** Server tick: keep the whirling mobs whirling. */
    public static void twirlTick(ServerLevel sl) {
        misfireTick(sl);
        if (TWIRLS.isEmpty()) return;
        long now = sl.getGameTime();
        java.util.Iterator<java.util.Map.Entry<java.util.UUID, float[]>> it = TWIRLS.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<java.util.UUID, float[]> en = it.next();
            net.minecraft.world.entity.Entity ent = sl.getEntity(en.getKey());
            if (ent == null) continue; // maybe in another dimension
            if (!(ent instanceof net.minecraft.world.entity.LivingEntity e) || !e.isAlive()) {
                it.remove();
                TWIRL_END.remove(en.getKey());
                continue;
            }
            long end = TWIRL_END.getOrDefault(en.getKey(), 0L);
            float yaw;
            if (now >= end) {
                yaw = en.getValue()[0]; // stops, facing the wrong way
                it.remove();
                TWIRL_END.remove(en.getKey());
            } else {
                float left = (end - now) / (float) TWIRL_TICKS; // slows down as it ends
                yaw = e.getYRot() + TWIRL_SPEED * (0.35F + 0.65F * left);
                if (e instanceof net.minecraft.world.entity.Mob m) m.getNavigation().stop();
                if (now % 3L == 0L) sl.sendParticles(ParticleTypes.CRIT, e.getX(), e.getY() + e.getBbHeight() * 0.6D, e.getZ(), 1, 0.3D, 0.2D, 0.3D, 0.0D);
            }
            e.setYRot(yaw);
            e.setYHeadRot(yaw);
            e.setYBodyRot(yaw);
        }
    }

    /** Confused after a spin hit: may not lock onto anyone. */
    public static boolean isSpinConfused(net.minecraft.world.entity.Entity e) {
        long[] t = SPIN_TURNED.get(e.getUUID());
        if (t == null) return false;
        long now = e.level().getGameTime();
        if (now >= t[1]) {
            SPIN_TURNED.remove(e.getUUID());
            SPIN_TURNED_BY.remove(e.getUUID());
            return false;
        }
        return now < t[0];
    }

    /**
     * A projectile just appeared: if it was shot by a mob still under the spin's effect, it turns right back at
     * the shooter (like Gyro turning the thief's own gun on him).
     */
    public static void reverseSpinShot(net.minecraft.world.entity.projectile.Projectile proj) {
        if (!(proj.getOwner() instanceof net.minecraft.world.entity.LivingEntity shooter) || shooter instanceof net.minecraft.world.entity.player.Player) return;
        long[] t = SPIN_TURNED.get(shooter.getUUID());
        if (t == null || shooter.level().getGameTime() >= t[1]) return;
        if (misfiring(shooter)) return; // a Steel Ball's misfire: it's shooting someone else, not itself
        ServerLevel sl = (ServerLevel) proj.level();
        java.util.UUID by = SPIN_TURNED_BY.get(shooter.getUUID());
        net.minecraft.world.entity.player.Player player = by == null ? null : sl.getPlayerByUUID(by);
        if (player != null) proj.setOwner(player); // credited to you, and allowed to hit the shooter
        else proj.setOwner(null);
        // The shot leaves the bow, then swings round and comes straight back into the shooter: it's moved just out
        // in front of them (a projectile can't hit what it starts inside of) and sent back at their chest.
        Vec3 v = proj.getDeltaMovement();
        Vec3 out = v.lengthSqr() < 1.0E-4D ? shooter.getLookAngle() : v.normalize();
        Vec3 chest = shooter.position().add(0.0D, shooter.getBbHeight() * 0.55D, 0.0D);
        Vec3 from = chest.add(out.scale(shooter.getBbWidth() * 0.5D + 1.4D));
        proj.setPos(from.x, from.y, from.z);
        proj.setDeltaMovement(chest.subtract(from).normalize().scale(Math.max(0.9D, v.length())));
        sl.sendParticles(ParticleTypes.CRIT, proj.getX(), proj.getY(), proj.getZ(), 6, 0.1D, 0.1D, 0.1D, 0.1D);
        hamonBurst(sl, proj.position(), 4, 0.1D);
    }

    // ------------------------------------------------------------------ Spin: Steel Ball bounce fields

    private static final class SpinField {
        final java.util.UUID owner;
        final Vec3 pos;
        final float power;
        final long until;
        final java.util.Set<java.util.UUID> caught = new java.util.HashSet<>();
        SpinField(java.util.UUID owner, Vec3 pos, float power, long until) {
            this.owner = owner;
            this.pos = pos;
            this.power = power;
            this.until = until;
        }
    }
    private static final java.util.List<SpinField> SPIN_FIELDS = new java.util.ArrayList<>();
    /** How long a bounce field lingers (ticks). */
    public static final int SPIN_FIELD_TICKS = 60;

    public static void addSpinField(ServerPlayer p, Vec3 pos, float power, net.minecraft.world.entity.LivingEntity alreadyHit) {
        ServerLevel sl = (ServerLevel) p.level();
        SpinField f = new SpinField(p.getUUID(), pos, power, sl.getGameTime() + SPIN_FIELD_TICKS);
        if (alreadyHit != null) f.caught.add(alreadyHit.getUUID());
        SPIN_FIELDS.add(f);
        sl.playSound(null, pos.x, pos.y, pos.z, SoundEvents.TRIDENT_RIPTIDE_2.value(), SoundSource.PLAYERS, 0.8F, 1.4F);
        hamonBurst(sl, pos, 10, 0.4D);
    }

    private static void spinFieldTick(ServerPlayer p) {
        if (SPIN_FIELDS.isEmpty()) return;
        ServerLevel sl = (ServerLevel) p.level();
        long now = sl.getGameTime();
        net.minecraft.core.particles.DustParticleOptions gold = new net.minecraft.core.particles.DustParticleOptions(new org.joml.Vector3f(1.0F, 0.85F, 0.3F), 1.2F);
        java.util.Iterator<SpinField> it = SPIN_FIELDS.iterator();
        while (it.hasNext()) {
            SpinField f = it.next();
            if (!f.owner.equals(p.getUUID())) continue;
            if (now >= f.until) {
                it.remove();
                continue;
            }
            // A swirling golden ring on the spot, so you can see the spin is still there.
            if (now % 3L == 0L) {
                double t = now * 0.35D;
                for (int i = 0; i < 6; i++) {
                    double a = t + i * (Math.PI / 3.0D);
                    double r = 0.8D + 0.25D * Math.sin(t + i);
                    sl.sendParticles(gold, f.pos.x + Math.cos(a) * r, f.pos.y + 0.1D, f.pos.z + Math.sin(a) * r, 1, 0.0D, 0.0D, 0.0D, 0.0D);
                }
            }
            for (net.minecraft.world.entity.LivingEntity e : sl.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                    com.mobstands.entity.SpinItemEntity.area(f.pos),
                    x -> x != p && x.isAlive() && !f.caught.contains(x.getUUID()) && StandCombat.valid(p, x))) {
                f.caught.add(e.getUUID()); // once each, ever
                spinAffect(p, sl, f.pos, e, f.power, now);
            }
        }
    }

    // ------------------------------------------------------------------ Spin: orbiting arrows

    private static final class Orbit {
        final java.util.UUID owner;
        int age;
        double angle;
        Orbit(java.util.UUID owner, double angle) {
            this.owner = owner;
            this.angle = angle;
        }
    }
    private static final java.util.Map<java.util.UUID, Orbit> ORBITS = new java.util.HashMap<>();
    /** Arrows a single spin can send into orbit before it's used up. */
    public static final int SPIN_ARROWS = 3;

    /** Arrows a spinning bow keeps in orbit: 1 for a rookie, 2 from Lv 6, all 3 for a master. */
    public static int spinArrowsFor(int level) {
        return 1 + (int) (2.0F * spinSkill(level) + 0.001F);
    }
    public static final double ORBIT_RADIUS = 2.2D;
    /** Orbit speed (radians per tick): starts slow, then speeds up fast; past the limit the arrow vanishes. */
    private static final double ORBIT_START = 0.05D, ORBIT_GROWTH = 0.018D, ORBIT_MAX = 2.0D; // ~10 s before it tears apart

    /**
     * An arrow shot from a spinning bow or crossbow: instead of flying off it circles around you, slowly at first and
     * then faster and faster (and the faster it goes the harder it hits - arrows do damage by speed), piercing
     * whoever it passes through. Once it's going too fast it vanishes. Three arrows per spin.
     */
    public static boolean orbitSpinArrow(net.minecraft.world.entity.projectile.AbstractArrow arrow) {
        if (!(arrow.getOwner() instanceof ServerPlayer p)) return false;
        StandData d = data(p);
        if (!d.spinLearned || !d.spinning
                || !(p.getMainHandItem().getItem() instanceof net.minecraft.world.item.ProjectileWeaponItem)) return false;
        Vec3 off = arrow.position().subtract(p.position());
        ORBITS.put(arrow.getUUID(), new Orbit(p.getUUID(), Math.atan2(off.z, off.x)));
        arrow.setNoGravity(true);
        setPierce(arrow, (byte) 8); // passes through people so it keeps circling
        arrow.pickup = net.minecraft.world.entity.projectile.AbstractArrow.Pickup.CREATIVE_ONLY;
        ServerLevel sl = (ServerLevel) p.level();
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), com.mobstands.registry.ModSounds.SPIN_HAMON.get(), SoundSource.PLAYERS, 0.6F, 1.3F);
        if (++d.spinArrows >= spinArrowsFor(d.spinLevel)) {
            d.spinning = false; // the spin is used up
            p.displayClientMessage(Component.literal("The spin is spent."), true);
        }
        sync(p);
        return true;
    }

    private static java.lang.reflect.Method pierceSetter;

    /** AbstractArrow#setPierceLevel is private: set it reflectively (Mojang names at runtime). Harmless if it fails. */
    private static void setPierce(net.minecraft.world.entity.projectile.AbstractArrow arrow, byte level) {
        try {
            if (pierceSetter == null) {
                pierceSetter = net.minecraft.world.entity.projectile.AbstractArrow.class.getDeclaredMethod("setPierceLevel", byte.class);
                pierceSetter.setAccessible(true);
            }
            pierceSetter.invoke(arrow, level);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // no piercing: the arrow just stops at the first person it hits
        }
    }

    private static void orbitTick(ServerPlayer p) {
        if (ORBITS.isEmpty()) return;
        ServerLevel sl = (ServerLevel) p.level();
        java.util.Iterator<java.util.Map.Entry<java.util.UUID, Orbit>> it = ORBITS.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<java.util.UUID, Orbit> en = it.next();
            Orbit o = en.getValue();
            if (!o.owner.equals(p.getUUID())) continue;
            net.minecraft.world.entity.Entity e = sl.getEntity(en.getKey());
            if (!(e instanceof net.minecraft.world.entity.projectile.AbstractArrow arrow) || !arrow.isAlive() || !p.isAlive()) {
                it.remove();
                continue;
            }
            if (arrow.onGround() || arrow.getDeltaMovement().lengthSqr() < 1.0E-6D && o.age > 2) {
                it.remove(); // stuck in a block: orbit over
                continue;
            }
            o.age++;
            double w = ORBIT_START * Math.exp(o.age * ORBIT_GROWTH);
            if (w >= ORBIT_MAX) {
                // Too fast: it tears itself apart.
                sl.sendParticles(ParticleTypes.CRIT, arrow.getX(), arrow.getY(), arrow.getZ(), 8, 0.2D, 0.2D, 0.2D, 0.2D);
                hamonBurst(sl, arrow.position(), 5, 0.2D);
                sl.playSound(null, arrow.getX(), arrow.getY(), arrow.getZ(), SoundEvents.ARROW_HIT, SoundSource.PLAYERS, 0.8F, 1.6F);
                arrow.discard();
                it.remove();
                continue;
            }
            o.angle += w;
            Vec3 c = p.position().add(0.0D, 1.1D + Math.sin(o.age * 0.15D) * 0.15D, 0.0D);
            Vec3 next = c.add(Math.cos(o.angle) * ORBIT_RADIUS, 0.0D, Math.sin(o.angle) * ORBIT_RADIUS);
            // Steer it: its own movement this tick carries it to the next spot on the circle (and checks for hits on the
            // way); arrows deal damage by speed, so the faster it orbits the harder it hits.
            arrow.setDeltaMovement(next.subtract(arrow.position()));
            arrow.hasImpulse = true;
            arrow.hurtMarked = true;
            if (o.age % 3 == 0) sl.sendParticles(ParticleTypes.ENCHANTED_HIT, arrow.getX(), arrow.getY(), arrow.getZ(), 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }

    /** The thrown item came back into your hand: it's still spinning (no wind-up needed). */
    public static void resumeSpin(ServerPlayer p, boolean offHand) {
        StandData d = data(p);
        if (offHand) {
            if (!d.spinLearned || p.getOffhandItem().isEmpty()) return;
            d.offSpinning = true;
            d.offSpinStart = p.level().getGameTime();
            d.offSpinItem = p.getOffhandItem().getItem();
            sync(p);
            return;
        }
        if (!d.spinLearned || p.getMainHandItem().isEmpty()) return;
        d.spinning = true;
        d.spinStart = p.level().getGameTime();
        d.spinArrows = 0;
        d.spinItem = p.getMainHandItem().getItem();
        d.spinSlot = p.getInventory().selected;
        sync(p);
    }

    /** Takes the spin marker off an item (when it's picked up or stops spinning) so it stacks normally again. */
    public static void clearSpinTag(net.minecraft.world.item.ItemStack stack) {
        net.minecraft.world.item.component.CustomData cd = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if (cd == null || !cd.contains(SPIN_TAG)) return;
        net.minecraft.world.item.component.CustomData.update(net.minecraft.core.component.DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.remove(SPIN_TAG);
            tag.remove(SPIN_POWER_TAG);
        });
    }

    private static void spinThrowTick(ServerPlayer p, long now) {
        ServerLevel sl = (ServerLevel) p.level();
        if (!SPIN_FLUNG.isEmpty()) {
            java.util.Iterator<java.util.Map.Entry<java.util.UUID, SpinFling>> it = SPIN_FLUNG.entrySet().iterator();
            while (it.hasNext()) {
                java.util.Map.Entry<java.util.UUID, SpinFling> en = it.next();
                if (!en.getValue().owner().equals(p.getUUID())) continue;
                net.minecraft.world.entity.Entity e = sl.getEntity(en.getKey());
                if (!(e instanceof net.minecraft.world.entity.LivingEntity le) || !le.isAlive() || now > en.getValue().until()) {
                    it.remove();
                    continue;
                }
                // Slammed into a wall while flying: it hurts.
                if (le.horizontalCollision && le.getDeltaMovement().horizontalDistance() > 0.15D) {
                    it.remove();
                    le.invulnerableTime = 0;
                    StandCombat.hurt(p, le, 3.0F + data(p).spinLevel * 0.15F);
                    hamonBurst(sl, le.position().add(0.0D, le.getBbHeight() * 0.5D, 0.0D), 18, le.getBbWidth() * 0.5D);
                    sl.playSound(null, le.getX(), le.getY(), le.getZ(), com.mobstands.registry.ModSounds.SPIN_HAMON.get(), SoundSource.PLAYERS, 1.0F, 0.9F);
                    sl.sendParticles(ParticleTypes.EXPLOSION, le.getX(), le.getY() + le.getBbHeight() * 0.5D, le.getZ(), 1, 0.0D, 0.0D, 0.0D, 0.0D);
                    sl.sendParticles(ParticleTypes.CRIT, le.getX(), le.getY() + le.getBbHeight() * 0.5D, le.getZ(), 10, 0.3D, 0.3D, 0.3D, 0.2D);
                    sl.playSound(null, le.getX(), le.getY(), le.getZ(), SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1.0F, 0.8F);
                    sl.playSound(null, le.getX(), le.getY(), le.getZ(), SoundEvents.STONE_BREAK, SoundSource.PLAYERS, 1.0F, 0.7F);
                }
            }
        }
    }

    /**
     * Someone got caught by a spinning item on the floor: either launched straight up (a full hit) or scattered
     * out to the side - and slamming into a block on the way hurts a lot more.
     */
    public static void spinAffect(ServerPlayer p, ServerLevel sl, Vec3 c, net.minecraft.world.entity.LivingEntity e, float power, long now) {
        spinAffect(p, sl, c, e, power, now, false);
    }

    /**
     * {@code gentle}: the dropped floor spin - barely hurts (half a heart to a heart), a low hop or a short shove,
     * no wall-slam damage, so it spins and tosses mobs around without killing them.
     */
    public static void spinAffect(ServerPlayer p, ServerLevel sl, Vec3 c, net.minecraft.world.entity.LivingEntity e, float power, long now,
                                  boolean gentle) {
        if (gentle) {
            e.invulnerableTime = 0;
            twirl(e, now);
            hamonBurst(sl, e.position().add(0.0D, e.getBbHeight() * 0.5D, 0.0D), 10, e.getBbWidth() * 0.5D);
            sl.playSound(null, e.getX(), e.getY(), e.getZ(), com.mobstands.registry.ModSounds.SPIN_HAMON.get(), SoundSource.PLAYERS, 0.8F,
                    0.95F + p.getRandom().nextFloat() * 0.1F);
            if (p.getRandom().nextBoolean()) {
                StandCombat.hurt(p, e, 2.0F); // 1 heart
                e.setDeltaMovement(e.getDeltaMovement().x * 0.2D, 0.45D, e.getDeltaMovement().z * 0.2D); // a hop, too low to hurt on landing
            } else {
                StandCombat.hurt(p, e, 1.0F); // half a heart
                Vec3 out = e.position().subtract(c).multiply(1.0D, 0.0D, 1.0D);
                out = out.lengthSqr() < 1.0E-4D ? new Vec3(1.0D, 0.0D, 0.0D) : out.normalize();
                e.setDeltaMovement(out.scale(0.6D).add(new Vec3(-out.z, 0.0D, out.x).scale(0.25D)).add(0.0D, 0.25D, 0.0D));
            }
            e.hurtMarked = true;
            return;
        }
        int lv = data(p).spinLevel;
        e.invulnerableTime = 0;
        twirl(e, now); // whirls round as it's thrown
        hamonBurst(sl, e.position().add(0.0D, e.getBbHeight() * 0.5D, 0.0D), 14, e.getBbWidth() * 0.5D);
        sl.playSound(null, e.getX(), e.getY(), e.getZ(), com.mobstands.registry.ModSounds.SPIN_HAMON.get(), SoundSource.PLAYERS, 0.9F,
                0.95F + p.getRandom().nextFloat() * 0.1F);
        if (p.getRandom().nextBoolean()) {
            // Launched straight up, full hit.
            StandCombat.hurt(p, e, 3.0F); // the floor spin barely hurts: 1.5 hearts - it's about the spin and the flight
            e.setDeltaMovement(e.getDeltaMovement().x * 0.2D, 0.5D + 0.55D * power, e.getDeltaMovement().z * 0.2D);
        } else {
            // Scattered outward with the spin.
            StandCombat.hurt(p, e, 2.0F); // 1 heart
            Vec3 out = e.position().subtract(c).multiply(1.0D, 0.0D, 1.0D);
            out = out.lengthSqr() < 1.0E-4D ? new Vec3(1.0D, 0.0D, 0.0D) : out.normalize();
            Vec3 swirl = new Vec3(-out.z, 0.0D, out.x).scale(0.5D); // a bit of spin to the throw
            e.setDeltaMovement(out.scale(0.7D + 1.0D * power).add(swirl).add(0.0D, 0.35D, 0.0D));
            SPIN_FLUNG.put(e.getUUID(), new SpinFling(p.getUUID(), now + 30L));
        }
        e.hurtMarked = true;
    }

    /** Golden pixel stars bursting out from a point. */
    public static void hamonBurst(ServerLevel sl, Vec3 at, int count, double spread) {
        for (int i = 0; i < count; i++) {
            double vx = (sl.random.nextDouble() - 0.5D) * 0.5D, vy = (sl.random.nextDouble() - 0.3D) * 0.4D, vz = (sl.random.nextDouble() - 0.5D) * 0.5D;
            sl.sendParticles(com.mobstands.registry.ModParticles.HAMON_SPARK.get(),
                    at.x + (sl.random.nextDouble() - 0.5D) * spread * 2.0D, at.y + (sl.random.nextDouble() - 0.5D) * spread * 2.0D,
                    at.z + (sl.random.nextDouble() - 0.5D) * spread * 2.0D, 0, vx, vy, vz, 1.0D);
        }
    }

    /** The spin stops when you switch slots or the item changes (or you stop being a Spin user). */
    /** How long a spin lasts: it starts violent and slowly winds down to a full stop (ticks). */
    public static final int SPIN_TICKS = 1200; // 1 minute (for a master)
    /** Spin XP for landing a spin (hitting the timing); a slip still gives 1. */
    public static final int SPIN_SUCCESS_XP = 12;

    /** 0 for a rookie (Spin Lv 1) up to 1 for a master (Lv 10): every Spin stat scales with this. */
    public static float spinSkill(int level) {
        return Math.max(0.0F, Math.min(1.0F, (level - 1) / (float) (StandData.SPIN_MAX_LEVEL - 1)));
    }

    /** How hard your spins hit and fly: half strength for a rookie, full for a master. */
    public static float spinMult(int level) {
        return 0.5F + 0.5F * spinSkill(level);
    }

    /** How long a spin keeps going: 18 s for a rookie, the full minute for a master. */
    public static int spinTicks(int level) {
        return Math.round(SPIN_TICKS * (0.3F + 0.7F * spinSkill(level)));
    }

    /** 1 right after the spin starts, easing down to 0 as it stops. */
    public static float spinSpeed(StandData d, long now) {
        return spinSpeed(d.spinStart, now, spinTicks(d.spinLevel));
    }

    public static float spinSpeed(long start, long now) {
        return spinSpeed(start, now, SPIN_TICKS);
    }

    public static float spinSpeed(long start, long now, int total) {
        float k = 1.0F - Math.max(0.0F, Math.min(1.0F, (now - start) / (float) total));
        return k * k;
    }

    /** The off-hand spin stops if that item changes, or when it winds down. */
    private static void offSpinTick(ServerPlayer p, StandData d) {
        net.minecraft.world.item.ItemStack held = p.getOffhandItem();
        long now = p.level().getGameTime();
        if (!d.spinLearned || held.isEmpty() || held.getItem() != d.offSpinItem || now - d.offSpinStart >= spinTicks(d.spinLevel)) {
            d.offSpinning = false;
            sync(p);
        }
    }

    private static void spinTick(ServerPlayer p, StandData d) {
        net.minecraft.world.item.ItemStack held = p.getMainHandItem();
        long now = p.level().getGameTime();
        if (!d.spinLearned || held.isEmpty() || held.getItem() != d.spinItem || p.getInventory().selected != d.spinSlot) {
            d.spinning = false;
            sync(p);
            return;
        }
        if (now - d.spinStart >= spinTicks(d.spinLevel)) {
            // Wound all the way down.
            d.spinning = false;
            p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.4F, 0.5F);
            sync(p);
            return;
        }
        // The whirr stays at full speed the whole spin, just like the ball itself.
        if (p.tickCount % 4 == 0) {
            p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.TRIDENT_RIPTIDE_1.value(), SoundSource.PLAYERS, 0.2F, 2.0F);
        }
    }

    // ------------------------------------------------------------------ Horse Rush

    private static final net.minecraft.resources.ResourceLocation RUSH_STEP = com.mobstands.MobStands.id("horse_rush_step");
    private static final int RUSH_CHARGE_TICKS = 20;

    /** Triple-tapped sprint: charge up the Rush, or stop it if you're already running. */
    public static void horseRushToggle(ServerPlayer p) {
        StandData d = data(p);
        if (d.getType() != StandType.HORSE) return;
        if (d.rushing || d.rushChargeUntil != 0L) {
            endRush(p, d, true);
            return;
        }
        if (p.getFoodData().getFoodLevel() <= 0 && !p.isCreative()) {
            p.displayClientMessage(Component.literal("You're too hungry to run."), true);
            return;
        }
        long now = p.level().getGameTime();
        if (!d.isSummoned() || getStand(p) == null) summon(p); // the horse comes out to help you run
        d.rushChargeUntil = now + RUSH_CHARGE_TICKS;
        ServerLevel sl = (ServerLevel) p.level();
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.HORSE_BREATHE, SoundSource.PLAYERS, 1.2F, 0.8F);
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.HORSE_STEP, SoundSource.PLAYERS, 1.0F, 0.8F);
    }

    private static void startRush(ServerPlayer p, StandData d) {
        d.rushChargeUntil = 0L;
        d.rushing = true;
        d.rushTicks = 0;
        d.rushLastX = p.getX();
        d.rushLastZ = p.getZ();
        net.minecraft.world.entity.ai.attributes.AttributeInstance step = p.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.STEP_HEIGHT);
        if (step != null) step.addOrUpdateTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(RUSH_STEP, 0.45D,
                net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE)); // walks up full blocks like slabs
        ServerLevel sl = (ServerLevel) p.level();
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.HORSE_ANGRY, SoundSource.PLAYERS, 1.3F, 1.0F);
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.HORSE_GALLOP, SoundSource.PLAYERS, 1.2F, 1.0F);
        sl.sendParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + 0.1D, p.getZ(), 14, 0.4D, 0.05D, 0.4D, 0.08D);
        p.displayClientMessage(Component.literal("Rush!").withStyle(net.minecraft.ChatFormatting.GOLD), true);
    }

    private static void endRush(ServerPlayer p, StandData d, boolean announce) {
        boolean was = d.rushing;
        d.rushing = false;
        d.rushChargeUntil = 0L;
        d.rushTicks = 0;
        net.minecraft.world.entity.ai.attributes.AttributeInstance step = p.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.STEP_HEIGHT);
        if (step != null) step.removeModifier(RUSH_STEP);
        if (was) {
            p.removeEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED); // back to the normal Horse Speed I next second
            if (announce) p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.HORSE_BREATHE, SoundSource.PLAYERS, 1.0F, 1.0F);
        }
    }

    /**
     * Charging: a second of snorting and pawing (clouds at your feet). Rushing: Speed V and full-block step-up for
     * as long as you have food; the longer you run, the faster it eats your hunger.
     */
    private static void rushTick(ServerPlayer p, StandData d, long now) {
        ServerLevel sl = (ServerLevel) p.level();
        if (d.rushChargeUntil != 0L) {
            if (now % 4L == 0L) sl.sendParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + 0.05D, p.getZ(), 2, 0.25D, 0.0D, 0.25D, 0.02D);
            if (now % 7L == 0L) sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.HORSE_STEP, SoundSource.PLAYERS, 0.8F, 0.7F);
            if (now >= d.rushChargeUntil) startRush(p, d);
            return;
        }
        if (!d.rushing) return;
        if (!p.isAlive()) {
            endRush(p, d, false);
            return;
        }
        if (p.getFoodData().getFoodLevel() <= 0 && !p.isCreative()) {
            endRush(p, d, true);
            p.displayClientMessage(Component.literal("You're out of breath (too hungry)."), true);
            return;
        }
        if (now % 5L == 0L) {
            // Speed VII, and faster the more hurt you are (up to Speed XI near death) so you can flee danger.
            float hurt = 1.0F - Math.max(0.0F, Math.min(1.0F, p.getHealth() / p.getMaxHealth()));
            int amp = 6 + Math.round(hurt * 4.0F);
            p.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED, 20, amp, true, false, true));
        }
        double dx = p.getX() - d.rushLastX, dz = p.getZ() - d.rushLastZ;
        d.rushLastX = p.getX();
        d.rushLastZ = p.getZ();
        boolean running = dx * dx + dz * dz > 0.01D;
        if (running) {
            d.rushTicks++;
            // Hunger: a steady drain that keeps growing the longer you run (on top of normal sprinting).
            p.causeFoodExhaustion(0.03F + Math.min(0.07F, d.rushTicks / 4000.0F));
            if (p.onGround() && now % 5L == 0L) {
                sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.HORSE_GALLOP, SoundSource.PLAYERS, 0.5F, 1.1F);
                sl.sendParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + 0.05D, p.getZ(), 1, 0.2D, 0.0D, 0.2D, 0.01D);
                // Kicking up the ground you run on, like Sandman across the desert.
                var under = sl.getBlockState(p.blockPosition().below());
                if (!under.isAir()) sl.sendParticles(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, under),
                        p.getX(), p.getY() + 0.1D, p.getZ(), 4, 0.25D, 0.05D, 0.25D, 0.15D);
            }
        }
    }

    /** A distracted monster may not pick a new target. */
    public static boolean isDistracted(net.minecraft.world.entity.Entity e) {
        Distraction dd = DISTRACTED.get(e.getUUID());
        if (dd == null) return false;
        if (e.level().getGameTime() >= dd.until()) {
            DISTRACTED.remove(e.getUUID());
            return false;
        }
        return true;
    }

    private static void distractTick(ServerPlayer p, long now) {
        if (DISTRACTED.isEmpty()) return;
        StandEntity pig = getStand(p);
        ServerLevel sl = (ServerLevel) p.level();
        java.util.Iterator<java.util.Map.Entry<java.util.UUID, Distraction>> it = DISTRACTED.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<java.util.UUID, Distraction> en = it.next();
            if (!en.getValue().owner().equals(p.getUUID())) continue;
            net.minecraft.world.entity.Entity e = sl.getEntity(en.getKey());
            if (!(e instanceof net.minecraft.world.entity.Mob m) || !m.isAlive() || now >= en.getValue().until()
                    || pig == null || data(p).getType() != StandType.PIG) {
                it.remove();
                continue;
            }
            m.getLookControl().setLookAt(pig.getX(), pig.getY() + 0.5D, pig.getZ());
            if (now % 5L == 0L) {
                if (m.distanceToSqr(pig) > 2.5D) m.getNavigation().moveTo(pig.getX(), pig.getY(), pig.getZ(), 1.0D);
                else m.getNavigation().stop();
            }
        }
    }

    /** Moving (walking, jumping, being knocked away) breaks the pose; only J brings it back. */
    private static void poseTick(ServerPlayer p, StandData d) {
        StandEntity s = getStand(p);
        if (s == null || !s.isPosing()) return;
        if (d.poseAnchor == null) d.poseAnchor = p.position();
        Vec3 off = p.position().subtract(d.poseAnchor);
        if (off.x * off.x + off.z * off.z > 0.04D || Math.abs(off.y) > 0.3D) s.setPosing(false);
    }

    public static void toggle(ServerPlayer p) {
        StandData d = data(p);
        if (d.getType() == null) {
            p.displayClientMessage(Component.literal("You have no Stand. Use an Echoing Arrow to awaken one."), true);
            return;
        }
        long now = p.level().getGameTime();
        if (now - d.lastToggle < 10L) return; // anti-spam / prevents duplicate spawns
        d.lastToggle = now;
        if (d.isSummoned() && getStand(p) != null) dismiss(p);
        else summon(p);
    }

    /** Removes the Stand entity but keeps the "summoned" flag (used on logout, death, dimension change). */
    public static void removeEntity(ServerPlayer p) {
        StandData d = data(p);
        StandEntity s = getStand(p);
        if (s != null) s.discard();
        d.activeStand = null;
    }

    // ------------------------------------------------------------------ abilities

    /** @param slot 0 = basic, 1 = Z, 2 = X, 3 = C */
    public static void use(ServerPlayer p, int slot) {
        if (slot < 0 || slot >= StandData.SLOTS) return;
        // Piloting the Phantom: no punching or other moves - only the key that lands it.
        StandEntity flying = getStand(p);
        if (flying != null && flying.isPiloting() && slot != 2) return;
        StandData d = data(p);
        StandType type = d.getType();
        if (type == null) {
            p.displayClientMessage(Component.literal("You have no Stand. Use an Echoing Arrow to awaken one."), true);
            return;
        }
        if (!d.isSummoned()) {
            p.displayClientMessage(Component.literal("Summon your Stand first."), true);
            return;
        }
        StandEntity stand = getStand(p);
        if (stand == null) return;
        if (stand.isHidden()) {
            p.displayClientMessage(Component.literal("Your Stand is reforming..."), true);
            return;
        }
        Ability ability = type.ability(slot);
        if (ability.isEmpty() || (slot == 0 && type == StandType.AXOLOTL)) return; // nothing on that key
        StandEntity posed = getStand(p);
        if (posed != null && posed.isPosing()) posed.setPosing(false); // attacking breaks the pose
        if (d.getLevel() < ability.unlockLevel()) {
            p.displayClientMessage(Component.literal(ability.name() + " unlocks at Stand level " + ability.unlockLevel()), true);
            return;
        }
        long now = p.level().getGameTime();
        if (slot == 0 && type.meleeBasic() && d.punchStart >= 0L) {
            long off = now - d.punchStart;
            if (off < d.punchHitAt) {
                // Second click before the punch lands: right-left double with the full two-punch sound.
                if (!d.punchDouble) {
                    // Double-click: becomes the charged special, timed to the full combo sound.
                    d.punchDouble = true;
                    stand.setComboHit2(StandAbilities.CHARGED_HIT_2);
                    stand.playAnim(StandEntity.ANIM_CHARGED_COMBO);
                    StandAbilities.chargedCombo(new AbilityContext(p, stand, (ServerLevel) p.level(), type, d));
                    d.setCooldownEnd(0, now + StandAbilities.CHARGED_HIT_2 + StandAbilities.COMBO_RECOVER);
                    addXp(p, 2);
                }
                return;
            }
        }
        if (slot == 0 && type.meleeBasic() && now <= d.followUpUntil) {
            // Second click after the first punch landed: left follow-up right away, cooldown starts after it.
            StandAbilities.followUp(new AbilityContext(p, stand, (ServerLevel) p.level(), type, d));
            d.setCooldownEnd(0, now + ability.cooldownTicks(d.getLevel()));
            addXp(p, 1);
            return;
        }
        if (slot == 0 && type == StandType.SKELETON && now >= d.doubleShotOpen && now <= d.doubleShotClose) {
            // Hit the timing: bonus arrow, ignores the basic-attack cooldown.
            StandAbilities.doubleShot(new AbilityContext(p, stand, (ServerLevel) p.level(), type, d));
            addXp(p, 2);
            return;
        }
        if (d.cooldownEnd(slot) > now) {
            if (slot == 0 && type == StandType.SKELETON) {
                d.doubleShotFumbled = true; // clicked too early: no bonus arrow this time
                d.doubleShotClose = 0L;
            }
            return;
        }

        AbilityContext ctx = new AbilityContext(p, stand, (ServerLevel) p.level(), type, d);
        if (slot == 0 && type.meleeBasic() && type != StandType.ALLAY && now <= d.summonPunchUntil) { // (the Allay always does its own rush)
            // Summoned and hit straight away: the Stand charges, then punches (punch 2 needs a second click).
            d.summonPunchUntil = 0L;
            StandAbilities.comboOpen(ctx);
            d.setCooldownEnd(0, now + StandAbilities.COMBO_HIT_1 + StandAbilities.DOUBLE_GAP + 6);
            stand.playAnim(StandEntity.ANIM_SUMMON_COMBO);
            addXp(p, 2);
            return;
        }
        if (!ability.action().run(ctx)) {
            p.displayClientMessage(Component.literal("No valid target."), true);
            return;
        }
        d.setCooldownEnd(slot, now + ability.cooldownTicks(d.getLevel()));
        stand.playAnim(slot);
        if (slot == 0) d.summonPunchUntil = 0L;
        // Allay Hold / Roam toggle give XP only for real use (a new enemy grabbed), not for spamming the key.
        if (!(type == StandType.ALLAY && slot >= 2)) addXp(p, slot == 0 ? 1 : slot == 3 ? 8 : 3);
    }

    // ------------------------------------------------------------------ progression

    // ---- Minecart Stand: Rail Rider ----
    private static final net.minecraft.resources.ResourceLocation CART_STEP = com.mobstands.MobStands.id("cart_step");

    public static void cartToggle(ServerPlayer p) {
        StandData d = data(p);
        if (d.cartRiding) {
            cartEnd(p, d);
            return;
        }
        StandEntity s = getStand(p);
        if ((d.getType() == null || !d.getType().isCart()) || s == null) return;
        d.cartRiding = true;
        s.setCartRide(true);
        net.minecraft.world.entity.ai.attributes.AttributeInstance step = p.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.STEP_HEIGHT);
        if (step != null) step.addOrUpdateTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(CART_STEP, 0.45D,
                net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE));
        ServerLevel sl = (ServerLevel) p.level();
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.MINECART_INSIDE, SoundSource.PLAYERS, 0.7F, 1.2F);
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.CHAIN_PLACE, SoundSource.PLAYERS, 0.8F, 1.0F);
    }

    /** Powered Rails: a steady, long boost. Nitro: a short, much faster blast. */
    public static final int POWERED_TICKS = 240, NITRO_TICKS = 60;

    public static boolean cartBoost(ServerPlayer p, int kind) {
        StandData d = data(p);
        StandEntity s = getStand(p);
        if (!d.cartRiding || s == null) {
            p.displayClientMessage(Component.literal("Hop in your Minecart first."), true);
            return false;
        }
        ServerLevel sl = (ServerLevel) p.level();
        int end = (int) sl.getGameTime() + (kind == 2 ? NITRO_TICKS : POWERED_TICKS);
        s.setCartBoost(kind, end);
        if (kind == 2) {
            sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.PLAYERS, 1.2F, 0.8F);
            sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 1.0F, 0.7F);
            sl.sendParticles(ParticleTypes.FLAME, p.getX(), p.getY() + 0.3D, p.getZ(), 30, 0.4D, 0.2D, 0.4D, 0.08D);
            sl.sendParticles(ParticleTypes.LARGE_SMOKE, p.getX(), p.getY() + 0.3D, p.getZ(), 12, 0.3D, 0.2D, 0.3D, 0.02D);
        } else {
            sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.COMPARATOR_CLICK, SoundSource.PLAYERS, 1.0F, 1.4F);
            sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 0.8F, 1.8F);
            sl.sendParticles(net.minecraft.core.particles.DustParticleOptions.REDSTONE, p.getX(), p.getY() + 0.2D, p.getZ(), 25, 0.5D, 0.1D, 0.5D, 0.0D);
        }
        return true;
    }

    /**
     * Running mobs over: anything in front of the moving cart gets hit, harder the faster you're going, and is
     * thrown off ahead of you (up and over the cart at Nitro speeds).
     */
    private static void cartRam(ServerPlayer p, StandData d, long now) {
        StandData.Vec3Holder last = d.cartLast;
        double vx = last.set ? p.getX() - last.x : 0.0D, vz = last.set ? p.getZ() - last.z : 0.0D;
        last.x = p.getX();
        last.y = p.getY();
        last.z = p.getZ();
        last.set = true;
        double speed = Math.sqrt(vx * vx + vz * vz);
        if (speed < 0.18D || speed > 3.0D) return; // too slow to hurt (or a teleport)
        Vec3 dir = new Vec3(vx / speed, 0.0D, vz / speed);
        net.minecraft.world.phys.AABB box = p.getBoundingBox().inflate(0.35D, 0.0D, 0.35D).move(dir.scale(0.6D)).expandTowards(vx, 0.0D, vz);
        ServerLevel sl = (ServerLevel) p.level();
        d.cartHits.values().removeIf(t -> t < now);
        for (net.minecraft.world.entity.LivingEntity e : sl.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, box,
                x -> x != p && x.isAlive() && !(x instanceof net.minecraft.world.entity.decoration.ArmorStand) && StandCombat.valid(p, x))) {
            if (d.cartHits.containsKey(e.getUUID())) continue;
            d.cartHits.put(e.getUUID(), now + 12L);
            // ~2.5 hearts at full normal speed, ~3.5 with Powered Rails, ~5 with Nitro.
            float dmg = (float) (speed * 9.0D);
            e.invulnerableTime = 0;
            StandCombat.hurt(p, e, dmg);
            double up = 0.25D + speed * 0.45D;
            e.setDeltaMovement(dir.x * speed * 1.6D, up, dir.z * speed * 1.6D);
            e.hurtMarked = true;
            sl.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.PLAYERS, 1.0F, 0.8F);
            sl.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.MINECART_INSIDE, SoundSource.PLAYERS, 0.6F, 0.6F + (float) speed * 0.4F);
            sl.sendParticles(ParticleTypes.CRIT, e.getX(), e.getY() + e.getBbHeight() * 0.5D, e.getZ(), 10, 0.3D, 0.3D, 0.3D, 0.3D);
            if (speed > 0.8D) sl.sendParticles(ParticleTypes.EXPLOSION, e.getX(), e.getY() + e.getBbHeight() * 0.5D, e.getZ(), 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }

    public static void cartEnd(ServerPlayer p, StandData d) {
        if (!d.cartRiding) return;
        d.cartRiding = false;
        StandEntity s = getStand(p);
        if (s != null) {
            s.setCartRide(false);
            s.setCartBoost(0, 0);
        }
        net.minecraft.world.entity.ai.attributes.AttributeInstance step = p.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.STEP_HEIGHT);
        if (step != null) step.removeModifier(CART_STEP);
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.CHAIN_BREAK, SoundSource.PLAYERS, 0.6F, 1.2F);
    }

    /** Spin practice: the technique has its own level, separate from your Stand's. */
    public static void addSpinXp(ServerPlayer p, int amount) {
        StandData d = data(p);
        if (!d.spinLearned) return;
        int before = d.spinLevel;
        int gained = d.addSpinXp(amount);
        if (gained > 0) {
            p.sendSystemMessage(Component.literal("Your Spin reached level " + d.spinLevel + "!").withStyle(s -> s.withColor(0xE8C25A)));
            if (d.spinLevel >= StandData.SPIN_MAX_LEVEL) {
                p.sendSystemMessage(Component.literal("  You have mastered the Spin: Steel Balls and thrown weapons now come back to you. Toss a spinning Steel Ball up into the sky, then look where you want it to go."));
            }
            ServerLevel level = (ServerLevel) p.level();
            level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8F, 1.5F);
            hamonBurst(level, p.position().add(0.0D, 1.0D, 0.0D), 20, 0.6D);
        }
        sync(p);
    }

    /** Learning the Spin from a Steel Ball. */
    public static boolean learnSpin(ServerPlayer p) {
        StandData d = data(p);
        if (d.spinLearned) return false;
        d.spinLearned = true;
        d.spinLevel = 1;
        d.spinXp = 0;
        sync(p);
        ServerLevel level = (ServerLevel) p.level();
        level.playSound(null, p.getX(), p.getY(), p.getZ(), com.mobstands.registry.ModSounds.SPIN_HAMON.get(), SoundSource.PLAYERS, 1.0F, 1.0F);
        level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8F, 1.3F);
        hamonBurst(level, p.position().add(0.0D, 1.0D, 0.0D), 40, 0.8D);
        p.sendSystemMessage(Component.literal("You learned the Spin!").withStyle(s -> s.withColor(0xE8C25A).withBold(true)));
        p.sendSystemMessage(Component.literal("  Hold Q while holding any item, then tap Q fast when the cue appears. Practice to get better: master it to spin Steel Balls."));
        return true;
    }

    public static void addXp(ServerPlayer p, int amount) {
        StandData d = data(p);
        StandType type = d.getType();
        if (type == null || type == StandType.HORSE || type.requiem()) return; // the Horse and Requiems don't level up
        int before = d.getLevel();
        int gained = d.addXp(amount);
        if (gained > 0) {
            int now = d.getLevel();
            p.sendSystemMessage(Component.literal("Your " + type.standName() + " reached level " + now + "!")
                    .withStyle(s -> s.withColor(type.color())));
            for (int lvl = before + 1; lvl <= now; lvl++) {
                for (int slot = 1; slot < StandData.SLOTS; slot++) {
                    if (type.ability(slot).unlockLevel() == lvl) {
                        p.sendSystemMessage(Component.literal("  Unlocked: " + type.ability(slot).name()));
                    }
                }
                if (type.passive().level() == lvl) {
                    p.sendSystemMessage(Component.literal("  Passive unlocked: " + type.passive().name()));
                }
            }
            ServerLevel level = (ServerLevel) p.level();
            level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8F, 1.2F);
            level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, p.getX(), p.getY() + 1.0D, p.getZ(), 30, 0.5D, 0.8D, 0.5D, 0.3D);
        }
        sync(p);
    }

    // ------------------------------------------------------------------ upkeep

    /** Called every tick for each server player. */
    public static void tick(ServerPlayer p) {
        StandData d = data(p);
        long now = p.level().getGameTime();
        // The Spin is a technique, not a Stand: it works with any Stand, or none at all.
        if (d.spinning) spinTick(p, d);
        if (d.offSpinning) offSpinTick(p, d);
        rocketTick(p, d, now);
        orbitTick(p);
        spinFieldTick(p);
        spinThrowTick(p, now);
        if (d.cartRiding && ((d.getType() == null || !d.getType().isCart()) || !d.isSummoned() || getStand(p) == null || p.isPassenger() || p.isSpectator())) {
            cartEnd(p, d);
        }
        if (d.cartRiding) {
            p.resetFallDistance(); // the cart takes the bumps
            cartRam(p, d, now);
        } else {
            d.cartLast.set = false;
        }
        if (d.getType() == null) return;
        if (d.getType().requiem()) {
            StandEntity rs = getStand(p);
            if (rs != null) rs.setAura(!d.auraOff);
        }
        if (d.getType().requiem() && d.getLevel() < StandData.MAX_LEVEL) {
            // A Requiem is already complete: every ability unlocked, at full strength.
            d.setLevel(StandData.MAX_LEVEL);
            d.setXp(0);
            sync(p);
        }
        if (d.climbUntil > now) climbTick(p);
        if (d.getType() == StandType.SQUID) rainTick(p, d, now);
        poseTick(p, d);
        distractTick(p, now);
        if (d.getType() == StandType.ALLAY && d.liftUntil > now) liftTick(p, d, now);
        if (d.getType() == StandType.ALLAY) allayTick(p, d, now);
        if (d.getType() == StandType.PHANTOM || d.getType() == StandType.SKELETON) sunBurnTick(p, d, now);
        if (d.getType() == StandType.HORSE && now % 20L == 3L) {
            // Horse Stand: always faster and springier, whether its head is out or not.
            p.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED, 80, 0, true, false, true));
            p.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.JUMP, 80, 0, true, false, true));
        }
        if (d.getType() == StandType.HORSE) {
            rushTick(p, d, now);
        } else if (d.rushing || d.rushChargeUntil != 0L) {
            endRush(p, d, false);
        }
        if (p.tickCount % 20 != 7) return;

        if (d.isSummoned()) {
            // Self-healing: respawn the Stand after teleports, chunk unloads, dimension changes, respawns...
            StandEntity s = getStand(p);
            if (s == null && p.isAlive()) summon(p, false);
            StandPassives.tick(p, d);
        } else if (d.activeStand != null) {
            removeEntity(p);
        }
    }

    /** Ticks an enemy must stand in the rain before it starts hurting (3 seconds). */
    private static final int RAIN_DELAY = 60;

    /**
     * Squid Stand "November Rain": rain falls in a circle around you while active. Anyone who stays in it for 3
     * seconds starts taking damage that grows the longer they stay (leaving resets them). During Downpour the
     * rain pins every projectile inside to the ground (fireballs are put out) and slows enemies.
     */
    /** True if nothing solid is between the top of this entity and the squid's rain above it. */
    private static boolean rainReaches(ServerLevel sl, net.minecraft.world.entity.Entity e, double rainY) {
        Vec3 top = new Vec3(e.getX(), e.getY() + e.getBbHeight(), e.getZ());
        if (top.y >= rainY) return true;
        net.minecraft.world.phys.BlockHitResult hit = sl.clip(new net.minecraft.world.level.ClipContext(top, new Vec3(top.x, rainY, top.z),
                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, e));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS;
    }

    private static void rainTick(ServerPlayer p, StandData d, long now) {
        StandEntity s = getStand(p);
        boolean raining = s != null && d.isSummoned() && !s.isDismissing() && (d.rainOn || now < d.rainUntil);
        if (raining && d.rainOn) {
            // Keeping the rain going is tiring: hunger drains a bit faster (about half a drumstick every 13 s).
            p.causeFoodExhaustion(0.015F);
            if (p.getFoodData().getFoodLevel() <= 0 && !p.isCreative()) {
                d.rainOn = false;
                p.displayClientMessage(Component.literal("You're too hungry to keep the rain going."), true);
            }
        }
        int level = !raining ? 0 : now < d.delugeUntil ? 3 : now < d.surgeUntil ? 2 : 1;
        if (s != null) s.setRainLevel(level);
        if (!raining) {
            d.rainExposure.clear();
            return;
        }
        ServerLevel sl = (ServerLevel) p.level();
        double r = StandAbilities.RAIN_RADIUS;
        // The rain falls straight down out of the squid, as far as it goes: anyone under it is hit, at any height,
        // unless a roof is in the way.
        Vec3 c = new Vec3(s.getX(), s.getY() + 0.3D, s.getZ());
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(c.x - r, c.y - 32.0D, c.z - r, c.x + r, c.y + 0.5D, c.z + r);
        AbilityContext ctx = new AbilityContext(p, s, sl, StandType.SQUID, d);
        java.util.Set<java.util.UUID> inside = new java.util.HashSet<>();
        for (net.minecraft.world.entity.LivingEntity e : sl.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, box,
                x -> StandCombat.valid(p, x) && (x.getX() - c.x) * (x.getX() - c.x) + (x.getZ() - c.z) * (x.getZ() - c.z) <= r * r
                        && rainReaches(sl, x, c.y))) {
            inside.add(e.getUUID());
            int exposure = d.rainExposure.merge(e.getUUID(), 1, Integer::sum);
            // November Rain: plain rain, starts hurting after 3 seconds. Downpour: hammering rain, almost instant and rapid.
            boolean storm = level >= 2;
            if (e instanceof net.minecraft.world.entity.monster.EnderMan && exposure % 10 == 0) {
                // Water hurts Endermen: any rain from the Squid burns them like real rain does (even the harmless Downpour).
                e.invulnerableTime = 0;
                e.hurt(sl.damageSources().drown(), storm ? 2.0F : 1.0F);
                sl.sendParticles(ParticleTypes.PORTAL, e.getX(), e.getY() + e.getBbHeight() * 0.6D, e.getZ(), 10, 0.3D, 0.6D, 0.3D, 0.3D);
            }
            if (storm) {
                // Downpour doesn't hurt: the rain hammers down so hard it pins them to the spot.
                net.minecraft.world.phys.Vec3 v = e.getDeltaMovement();
                e.setDeltaMovement(0.0D, Math.min(v.y, -0.35D), 0.0D);
                e.hurtMarked = true;
                if (e instanceof net.minecraft.world.entity.Mob mob) mob.getNavigation().stop();
                e.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN, 10, 6, false, false));
                e.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.JUMP, 10, 128, false, false)); // can't jump out
                if (exposure % 3 == 0) sl.sendParticles(ParticleTypes.SPLASH, e.getX(), e.getY() + e.getBbHeight(), e.getZ(), 6, e.getBbWidth() * 0.4D, 0.05D, e.getBbWidth() * 0.4D, 0.1D);
                if (exposure % 12 == 1) sl.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.GENERIC_SPLASH, SoundSource.PLAYERS, 0.5F, 0.8F);
                continue;
            }
            int delay = storm ? 8 : RAIN_DELAY;
            // November Rain: tiny hits, but relentless (every 3 ticks). Downpour: heavier hits.
            int interval = storm ? 4 : 3;
            if (exposure >= delay && (exposure - delay) % interval == 0) {
                e.invulnerableTime = 0;
                double dmg = storm ? 2.0D + (exposure - delay) / 25.0D : 0.4D + (exposure - delay) / 200.0D; // grows the longer they stay
                StandCombat.hurt(p, e, ctx.dmg(dmg));
                e.setDeltaMovement(e.getDeltaMovement().multiply(storm ? 0.6D : 0.85D, 1.0D, storm ? 0.6D : 0.85D).add(0.0D, storm ? -0.25D : -0.08D, 0.0D));
                e.hurtMarked = true;
                if (storm || (exposure - delay) % 9 == 0) {
                    sl.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.GENERIC_SPLASH, SoundSource.PLAYERS, 0.4F, 1.4F);
                }
            }
        }
        d.rainExposure.keySet().retainAll(inside); // stepping out of the rain resets the 3 seconds

        if (level >= 2) {
            // Torrential rain: every projectile not fired by you is beaten down into the ground.
            for (net.minecraft.world.entity.projectile.Projectile proj : sl.getEntitiesOfClass(
                    net.minecraft.world.entity.projectile.Projectile.class, box, x -> x.getOwner() != p)) {
                if (proj instanceof net.minecraft.world.entity.projectile.AbstractHurtingProjectile) {
                    sl.sendParticles(ParticleTypes.SMOKE, proj.getX(), proj.getY(), proj.getZ(), 6, 0.1D, 0.1D, 0.1D, 0.01D);
                    sl.playSound(null, proj.getX(), proj.getY(), proj.getZ(), SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.6F, 1.2F);
                    proj.discard();
                    continue;
                }
                Vec3 v = proj.getDeltaMovement();
                proj.setDeltaMovement(v.x * 0.15D, Math.min(v.y, -1.0D), v.z * 0.15D);
                proj.hurtMarked = true;
            }
        }
        if (now % 20L == 0L) {
            sl.playSound(null, c.x, c.y, c.z, level >= 2 ? SoundEvents.WEATHER_RAIN_ABOVE : SoundEvents.WEATHER_RAIN,
                    SoundSource.PLAYERS, level >= 2 ? 1.0F : 0.6F, level >= 2 ? 0.7F : 1.0F);
        }
    }

    /**
     * The Phantom and Skeleton Stands burn in sunlight like the real mobs, unless you wear something on your head
     * (like a skeleton with a helmet). Only the Stand is on fire; you quietly lose a bit of health from it
     * (half a heart every 2 seconds, less than burning yourself), with no hurt sound.
     */
    private static void sunBurnTick(ServerPlayer p, StandData d, long now) {
        StandEntity s = getStand(p);
        if (s == null || !d.isSummoned() || s.isDismissing()) return;
        ServerLevel sl = (ServerLevel) p.level();
        boolean hat = !p.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).isEmpty();
        if (hat) {
            if (s.isOnFire()) s.clearFire();
            return;
        }
        if (now % 10L == 0L) {
            net.minecraft.core.BlockPos at = net.minecraft.core.BlockPos.containing(s.getX(), s.getY() + 0.5D, s.getZ());
            if (sl.isDay() && sl.canSeeSky(at) && !s.isInWaterRainOrBubble() && !sl.isRainingAt(at)) s.igniteForSeconds(2.0F);
        }
        if (s.isOnFire() && now % 40L == 0L && !p.hasEffect(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE)
                && !p.isCreative() && !p.isSpectator()) {
            if (p.getHealth() > 1.0F) {
                p.setHealth(p.getHealth() - 1.0F); // silent: no hurt sound, no knock
            } else {
                p.hurt(p.damageSources().onFire(), 1.0F); // the last half heart can still finish you
            }
        }
    }

    private static void climbTick(ServerPlayer p) {
        if (p.horizontalCollision && !p.isShiftKeyDown() && !p.onGround() && !p.isInWater()) {
            Vec3 m = p.getDeltaMovement();
            p.setDeltaMovement(m.x, 0.22D, m.z);
            p.fallDistance = 0.0F;
            p.hurtMarked = true;
        }
    }
}
