package com.mobstands.stand;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * The signature tricks that make every Stand its own thing, not a weaker version of another:
 * Rattling Marksman's Bone Cage, Weaver of Webs' Silk Swing, Green Detonator's Sheer Heart Attack, Little Lullaby's
 * Lullaby, Muddy Fortune's Truffle Hunt, Iron Express's Cattle Car, Gallop Crown's Trample and Green Temperance's bounce.
 */
public final class Signatures {
    private Signatures() {}

    /** Things these tricks spawn only live for a little while: never saved into the world for good. */
    public static final String TEMP_TAG = "mobstands_signature_temp";
    /** A mob put to sleep by Lullaby (so it wakes up properly even after a reload). */
    public static final String SLEEP_TAG = "mobstands_lullaby";

    private static boolean boss(Entity e) {
        return e.getType().is(net.neoforged.neoforge.common.Tags.EntityTypes.BOSSES)
                || e instanceof net.minecraft.world.entity.monster.warden.Warden;
    }

    // ================================================================== Rattling Marksman: Bone Cage

    private record Cage(ServerLevel level, List<BlockPos> blocks) {}
    private static final List<Cage> CAGES = new ArrayList<>();

    public static boolean boneCage(AbilityContext c) {
        LivingEntity t = c.aim(30.0D);
        if (t == null) {
            c.fail = "Look at what you want to cage.";
            return false;
        }
        Vec3 from = c.shotOrigin();
        c.trail(ParticleTypes.WHITE_ASH, from, t.position().add(0.0D, t.getBbHeight() * 0.5D, 0.0D), 0.3D);
        c.trail(ParticleTypes.CRIT, from, t.position().add(0.0D, t.getBbHeight() * 0.5D, 0.0D), 0.8D);
        c.sound(SoundEvents.SKELETON_SHOOT, 1.0F, 0.7F);
        c.sound(SoundEvents.ARROW_SHOOT, 1.0F, 0.8F);
        ServerLevel sl = c.level;
        BlockPos base = t.blockPosition();
        // centre it in its block so the bars close around it, not through it
        if (t instanceof ServerPlayer tp) tp.connection.teleport(base.getX() + 0.5D, t.getY(), base.getZ() + 0.5D, tp.getYRot(), tp.getXRot());
        else t.teleportTo(base.getX() + 0.5D, t.getY(), base.getZ() + 0.5D);
        t.setDeltaMovement(Vec3.ZERO);
        int r = Math.max(1, (int) Math.ceil(t.getBbWidth() * 0.5D + 0.2D));
        int h = Math.max(1, (int) Math.ceil(t.getBbHeight()));
        List<BlockPos> placed = new ArrayList<>();
        BlockState bone = Blocks.BONE_BLOCK.defaultBlockState();
        for (int dy = 0; dy <= h; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    boolean wall = Math.abs(dx) == r || Math.abs(dz) == r;
                    if (dy < h && !wall) continue; // the inside stays open
                    BlockPos pos = base.offset(dx, dy, dz);
                    BlockState was = sl.getBlockState(pos);
                    if (!was.isAir() && !was.canBeReplaced()) continue;
                    sl.setBlock(pos, bone, 3);
                    placed.add(pos);
                }
            }
        }
        c.hurt(t, c.dmg(4.0D));
        sl.playSound(null, t.getX(), t.getY(), t.getZ(), SoundEvents.BONE_BLOCK_PLACE, SoundSource.PLAYERS, 1.5F, 0.7F);
        sl.playSound(null, t.getX(), t.getY(), t.getZ(), SoundEvents.SKELETON_AMBIENT, SoundSource.PLAYERS, 1.2F, 0.6F);
        Cage cage = new Cage(sl, placed);
        CAGES.add(cage);
        net.minecraft.world.entity.LivingEntity caged = t;
        TickScheduler.later(100, () -> {
            if (!CAGES.remove(cage)) return;
            crumble(cage);
            sl.playSound(null, caged.getX(), caged.getY(), caged.getZ(), SoundEvents.BONE_BLOCK_BREAK, SoundSource.PLAYERS, 1.5F, 0.8F);
        });
        return true;
    }

    private static void crumble(Cage cage) {
        for (BlockPos pos : cage.blocks()) {
            if (cage.level().getBlockState(pos).is(Blocks.BONE_BLOCK)) {
                cage.level().levelEvent(2001, pos, net.minecraft.world.level.block.Block.getId(Blocks.BONE_BLOCK.defaultBlockState()));
                cage.level().setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    // ================================================================== Weaver of Webs: Silk Swing

    private static final class Swing {
        final Vec3 anchor;
        int ticks;
        Swing(Vec3 anchor) { this.anchor = anchor; }
    }
    private static final Map<UUID, Swing> SWINGS = new HashMap<>();
    /** No fall damage until this game time (right after a swing). */
    private static final Map<UUID, Long> SOFT_LANDING = new HashMap<>();

    public static boolean silkSwing(AbilityContext c) {
        ServerPlayer p = c.owner;
        if (SWINGS.remove(p.getUUID()) != null) { // pressed again: let go
            SOFT_LANDING.put(p.getUUID(), c.level.getGameTime() + 60L);
            c.fail = "You let go of the web.";
            return false;
        }
        Vec3 eye = p.getEyePosition();
        BlockHitResult hit = c.level.clip(new ClipContext(eye, eye.add(c.look().scale(36.0D)), ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, p));
        if (hit.getType() == HitResult.Type.MISS) {
            c.fail = "Nothing in reach to stick a web line to.";
            return false;
        }
        SWINGS.put(p.getUUID(), new Swing(hit.getLocation()));
        c.trail(ParticleTypes.ITEM_COBWEB, eye, hit.getLocation(), 0.4D);
        c.sound(SoundEvents.SPIDER_AMBIENT, 1.0F, 1.4F);
        c.sound(SoundEvents.FISHING_BOBBER_THROW, 1.0F, 0.6F);
        return true;
    }

    private static void swingTick(MinecraftServer server) {
        if (SWINGS.isEmpty()) return;
        Iterator<Map.Entry<UUID, Swing>> it = SWINGS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Swing> en = it.next();
            ServerPlayer p = server.getPlayerList().getPlayer(en.getKey());
            Swing s = en.getValue();
            if (p == null || !p.isAlive() || TimeStop.held(p)) {
                it.remove();
                continue;
            }
            ServerLevel sl = p.serverLevel();
            Vec3 to = s.anchor.subtract(p.position().add(0.0D, 1.0D, 0.0D));
            double dist = to.length();
            if (dist < 1.6D || ++s.ticks > 50 || p.isShiftKeyDown()) {
                it.remove();
                SOFT_LANDING.put(p.getUUID(), sl.getGameTime() + 60L);
                if (dist < 1.6D) p.setDeltaMovement(p.getDeltaMovement().scale(0.3D).add(0.0D, 0.35D, 0.0D)); // a little hop at the top
                p.hurtMarked = true;
                continue;
            }
            double pull = Math.min(1.5D, 0.35D + dist * 0.07D);
            p.setDeltaMovement(p.getDeltaMovement().scale(0.55D).add(to.normalize().scale(pull)));
            p.hurtMarked = true;
            p.resetFallDistance();
            if (s.ticks % 2 == 0) {
                Vec3 hand = p.position().add(0.0D, 1.3D, 0.0D);
                Vec3 step = s.anchor.subtract(hand);
                int n = (int) Math.min(40, step.length() / 0.6D);
                for (int i = 0; i <= n; i++) {
                    Vec3 at = hand.add(step.scale(i / (double) Math.max(1, n)));
                    sl.sendParticles(ParticleTypes.ITEM_COBWEB, at.x, at.y, at.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
                }
            }
        }
    }

    /** Just let go of a web line: you land softly. */
    public static boolean softLanding(ServerPlayer p) {
        Long until = SOFT_LANDING.get(p.getUUID());
        if (until == null) return SWINGS.containsKey(p.getUUID());
        if (p.level().getGameTime() > until) {
            SOFT_LANDING.remove(p.getUUID());
            return false;
        }
        return true;
    }

    // ================================================================== Green Detonator: Sheer Heart Attack

    private static final class Hunter {
        final AbilityContext c;
        final long until;
        int blasts;
        Hunter(AbilityContext c, long until) { this.c = c; this.until = until; }
    }
    private static final Map<UUID, Hunter> HUNTERS = new HashMap<>();
    private static final int HUNTER_LIFE = 400, HUNTER_BLASTS = 3;

    public static boolean sheerHeartAttack(AbilityContext c) {
        for (Hunter h : HUNTERS.values()) {
            if (h.c.owner == c.owner) {
                c.fail = "Sheer Heart Attack is already out hunting.";
                return false;
            }
        }
        Vec3 at = c.owner.position().add(c.flatLook().scale(1.5D));
        if (!spawnHunter(c, at, new Hunter(c, c.level.getGameTime() + HUNTER_LIFE))) return false;
        c.sound(SoundEvents.CREEPER_PRIMED, 1.0F, 1.6F);
        c.owner.displayClientMessage(Component.literal("Sheer Heart Attack: it hunts the warmest thing nearby.")
                .withStyle(s -> s.withColor(0x4CFF4C).withItalic(true)), true);
        return true;
    }

    private static boolean spawnHunter(AbilityContext c, Vec3 at, Hunter h) {
        Creeper cr = EntityType.CREEPER.create(c.level);
        if (cr == null) return false;
        cr.moveTo(at.x, at.y, at.z, c.owner.getYRot(), 0.0F);
        cr.setInvulnerable(true);
        cr.setPersistenceRequired();
        cr.addTag(TEMP_TAG);
        cr.setCustomName(Component.literal("Sheer Heart Attack"));
        cr.setCustomNameVisible(false);
        var scale = cr.getAttribute(Attributes.SCALE);
        if (scale != null) scale.setBaseValue(0.45D);
        var speed = cr.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.setBaseValue(0.36D);
        HUNTERS.put(cr.getUUID(), h);
        c.level.addFreshEntity(cr);
        c.level.sendParticles(ParticleTypes.HAPPY_VILLAGER, at.x, at.y + 0.4D, at.z, 12, 0.3D, 0.3D, 0.3D, 0.05D);
        return true;
    }

    public static boolean isHunter(Entity e) {
        return e != null && HUNTERS.containsKey(e.getUUID());
    }

    /** It only ever goes after its user's enemies. True = don't let it lock onto this. */
    public static boolean blocksTarget(Entity mob, LivingEntity target) {
        if (target == null) return false;
        Hunter h = mob == null ? null : HUNTERS.get(mob.getUUID());
        if (h != null) return !StandCombat.valid(h.c.owner, target) || isHunter(target);
        if (SLEEPERS.containsKey(mob.getUUID())) return true;
        return false;
    }

    /** It went off: our blast (never breaks blocks, never hurts you), and it reforms to keep hunting. */
    public static boolean onExplode(Entity source) {
        Hunter h = source == null ? null : HUNTERS.remove(source.getUUID());
        if (h == null) return false;
        Vec3 at = source.position();
        h.c.blast(at.add(0.0D, 0.5D, 0.0D), 3.5D, 9.0D);
        h.blasts++;
        if (h.blasts < HUNTER_BLASTS && h.c.level.getGameTime() < h.until && h.c.owner.isAlive()) {
            TickScheduler.later(10, () -> {
                if (h.c.owner.isAlive() && !h.c.owner.isRemoved()) spawnHunter(h.c, at, h);
            });
        }
        return true;
    }

    private static void hunterTick(MinecraftServer server) {
        if (HUNTERS.isEmpty()) return;
        Iterator<Map.Entry<UUID, Hunter>> it = HUNTERS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Hunter> en = it.next();
            Hunter h = en.getValue();
            Entity e = h.c.level.getEntity(en.getKey());
            if (!(e instanceof Creeper cr) || !cr.isAlive()) {
                it.remove();
                continue;
            }
            if (h.c.level.getGameTime() > h.until || !h.c.owner.isAlive() || h.c.owner.hasDisconnected()
                    || h.c.owner.level() != cr.level()) {
                it.remove();
                h.c.level.sendParticles(ParticleTypes.POOF, cr.getX(), cr.getY() + 0.3D, cr.getZ(), 10, 0.2D, 0.2D, 0.2D, 0.02D);
                cr.discard();
                continue;
            }
            LivingEntity t = cr.getTarget();
            if (t == null || !t.isAlive() || t.distanceTo(cr) > 28.0F || !StandCombat.valid(h.c.owner, t) || cr.tickCount % 20 == 0) {
                // the warmest thing nearby: something burning first, then whatever's closest
                LivingEntity best = null;
                double bestScore = Double.MAX_VALUE;
                for (LivingEntity c : h.c.level.getEntitiesOfClass(LivingEntity.class, cr.getBoundingBox().inflate(24.0D),
                        x -> x != cr && !isHunter(x) && StandCombat.valid(h.c.owner, x))) {
                    double score = c.distanceToSqr(cr) * (c.isOnFire() || c.fireImmune() ? 0.25D : 1.0D);
                    if (score < bestScore) {
                        bestScore = score;
                        best = c;
                    }
                }
                if (best != t) cr.setTarget(best);
            }
            if (cr.tickCount % 6 == 0) h.c.level.sendParticles(ParticleTypes.SMOKE, cr.getX(), cr.getY() + 0.5D, cr.getZ(), 1, 0.05D, 0.05D, 0.05D, 0.0D);
        }
    }

    // ================================================================== Little Lullaby: Lullaby

    private record Sleep(ServerLevel level, long until, boolean setNoAi) {}
    private static final Map<UUID, Sleep> SLEEPERS = new HashMap<>();

    public static boolean lullaby(AbilityContext c) {
        List<LivingEntity> near = c.enemies(c.owner.position(), 12.0D);
        if (near.isEmpty()) {
            c.fail = "There's nobody nearby to sing to sleep.";
            return false;
        }
        long until = c.level.getGameTime() + 120L;
        for (LivingEntity e : near) {
            if (boss(e)) continue;
            if (e instanceof ServerPlayer pl) {
                pl.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 80, 3));
                pl.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 60, 0));
                pl.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN, 80, 2));
                pl.displayClientMessage(Component.literal("A lullaby... your eyes are so heavy...")
                        .withStyle(s -> s.withColor(0x6FE0FF).withItalic(true)), true);
            } else if (e instanceof Mob m) {
                boolean set = !m.isNoAi();
                m.setTarget(null);
                m.getNavigation().stop();
                if (set) m.setNoAi(true);
                m.addTag(SLEEP_TAG);
                SLEEPERS.put(m.getUUID(), new Sleep(c.level, until, set));
            }
        }
        c.sound(SoundEvents.ALLAY_AMBIENT_WITHOUT_ITEM, 1.5F, 0.6F);
        c.sound(SoundEvents.NOTE_BLOCK_CHIME.value(), 1.2F, 0.5F);
        TickScheduler.later(8, () -> c.sound(SoundEvents.NOTE_BLOCK_CHIME.value(), 1.2F, 0.63F));
        TickScheduler.later(16, () -> c.sound(SoundEvents.NOTE_BLOCK_CHIME.value(), 1.2F, 0.75F));
        c.ring(ParticleTypes.NOTE, c.owner.position().add(0.0D, 1.2D, 0.0D), 4.0D, 24);
        c.ring(ParticleTypes.NOTE, c.owner.position().add(0.0D, 1.2D, 0.0D), 8.0D, 40);
        return true;
    }

    private static void sleepTick(MinecraftServer server) {
        if (SLEEPERS.isEmpty()) return;
        Iterator<Map.Entry<UUID, Sleep>> it = SLEEPERS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Sleep> en = it.next();
            Sleep s = en.getValue();
            Entity e = s.level().getEntity(en.getKey());
            if (!(e instanceof Mob m) || !m.isAlive()) {
                it.remove();
                continue;
            }
            // time's up, or something hit it: it wakes with a start
            if (s.level().getGameTime() > s.until() || m.hurtTime > 0) {
                it.remove();
                wake(m, s.setNoAi());
                continue;
            }
            if (m.tickCount % 25 == 0) {
                s.level().sendParticles(ParticleTypes.NOTE, m.getX(), m.getY() + m.getBbHeight() + 0.4D, m.getZ(), 1, 0.2D, 0.1D, 0.2D, 0.5D);
            }
        }
    }

    private static void wake(Mob m, boolean setNoAi) {
        if (setNoAi) m.setNoAi(false);
        m.removeTag(SLEEP_TAG);
    }

    // ================================================================== Muddy Fortune: Truffle Hunt

    public static boolean truffleHunt(AbilityContext c) {
        if (c.stand == null) return false;
        ServerLevel sl = c.level;
        Vec3 at = c.stand.position();
        BlockState ground = sl.getBlockState(c.stand.blockPosition().below());
        if (ground.isAir()) {
            c.fail = "Your pig needs solid ground to dig in.";
            return false;
        }
        BlockParticleOption dirt = new BlockParticleOption(ParticleTypes.BLOCK, ground);
        c.sound(SoundEvents.PIG_AMBIENT, 1.2F, 1.0F);
        for (int i = 0; i < 6; i++) {
            TickScheduler.later(i * 5, () -> {
                sl.sendParticles(dirt, at.x, at.y + 0.1D, at.z, 10, 0.3D, 0.1D, 0.3D, 0.1D);
                sl.playSound(null, at.x, at.y, at.z, SoundEvents.ROOTED_DIRT_BREAK, SoundSource.PLAYERS, 0.9F, 0.8F + sl.random.nextFloat() * 0.3F);
            });
        }
        c.later(32, () -> {
            ItemStack loot = truffle(sl.random);
            ItemEntity item = new ItemEntity(sl, at.x, at.y + 0.4D, at.z, loot);
            item.setDeltaMovement(0.0D, 0.3D, 0.0D);
            item.setDefaultPickUpDelay();
            sl.addFreshEntity(item);
            sl.playSound(null, at.x, at.y, at.z, SoundEvents.PIG_AMBIENT, SoundSource.PLAYERS, 1.3F, 1.4F);
            sl.playSound(null, at.x, at.y, at.z, SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 1.0F, 0.6F);
            sl.sendParticles(ParticleTypes.HAPPY_VILLAGER, at.x, at.y + 0.6D, at.z, 12, 0.3D, 0.3D, 0.3D, 0.05D);
            boolean jackpot = loot.is(com.mobstands.registry.ModItems.ECHO_ARROW.get()) || loot.is(Items.DIAMOND)
                    || loot.is(Items.ENCHANTED_GOLDEN_APPLE) || loot.is(Items.NAME_TAG) || loot.is(Items.SADDLE);
            c.owner.displayClientMessage(Component.literal((jackpot ? "What luck! " : "Your pig dug up ") + loot.getCount() + "x "
                    + loot.getHoverName().getString() + (jackpot ? "!" : ".")).withStyle(s -> s.withColor(0xF0A0A0)), true);
        });
        return true;
    }

    private static ItemStack truffle(net.minecraft.util.RandomSource r) {
        double roll = r.nextDouble() * 100.0D;
        if (roll < 0.5D) return new ItemStack(Items.ENCHANTED_GOLDEN_APPLE);
        if (roll < 2.0D) return new ItemStack(com.mobstands.registry.ModItems.ECHO_ARROW.get());
        if (roll < 4.0D) return new ItemStack(r.nextBoolean() ? Items.NAME_TAG : Items.SADDLE);
        if (roll < 8.0D) return new ItemStack(Items.DIAMOND);
        if (roll < 13.0D) return new ItemStack(Items.GOLDEN_CARROT, 2 + r.nextInt(3));
        if (roll < 23.0D) return new ItemStack(Items.EMERALD, 1 + r.nextInt(3));
        if (roll < 35.0D) return new ItemStack(r.nextBoolean() ? Items.IRON_INGOT : Items.RAW_COPPER, 1 + r.nextInt(3));
        if (roll < 60.0D) return new ItemStack(Items.GOLD_NUGGET, 3 + r.nextInt(7));
        var food = new net.minecraft.world.item.Item[]{Items.CARROT, Items.POTATO, Items.BEETROOT, Items.BROWN_MUSHROOM};
        return new ItemStack(food[r.nextInt(food.length)], 2 + r.nextInt(3));
    }

    // ================================================================== Iron Express: Cattle Car

    private static final class Haul {
        final ServerLevel level;
        final UUID rider;
        final Vec3 dir;
        final long until;
        Haul(ServerLevel level, UUID rider, Vec3 dir, long until) {
            this.level = level;
            this.rider = rider;
            this.dir = dir;
            this.until = until;
        }
    }
    private static final Map<UUID, Haul> HAULS = new HashMap<>();

    public static boolean cattleCar(AbilityContext c) {
        LivingEntity t = c.aim(24.0D);
        if (t == null || boss(t) || t.isPassenger() && t.getVehicle() instanceof Minecart) {
            c.fail = "Look at what you want to haul away.";
            return false;
        }
        Minecart cart = EntityType.MINECART.create(c.level);
        if (cart == null) return false;
        cart.moveTo(t.getX(), t.getY(), t.getZ(), c.owner.getYRot(), 0.0F);
        cart.setInvulnerable(true);
        cart.addTag(TEMP_TAG);
        c.level.addFreshEntity(cart);
        t.stopRiding();
        t.startRiding(cart, true);
        c.hurt(t, c.dmg(6.0D));
        Vec3 dir = c.flatLook();
        cart.setDeltaMovement(dir.scale(0.8D).add(0.0D, 0.25D, 0.0D));
        HAULS.put(cart.getUUID(), new Haul(c.level, t.getUUID(), dir, c.level.getGameTime() + 120L));
        c.level.playSound(null, t.getX(), t.getY(), t.getZ(), SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.8F, 1.5F);
        c.level.playSound(null, t.getX(), t.getY(), t.getZ(), SoundEvents.MINECART_RIDING, SoundSource.PLAYERS, 1.2F, 0.9F);
        c.level.sendParticles(ParticleTypes.CRIT, t.getX(), t.getY() + 0.5D, t.getZ(), 15, 0.4D, 0.3D, 0.4D, 0.2D);
        return true;
    }

    private static void haulTick(MinecraftServer server) {
        if (HAULS.isEmpty()) return;
        Iterator<Map.Entry<UUID, Haul>> it = HAULS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Haul> en = it.next();
            Haul h = en.getValue();
            Entity cart = h.level.getEntity(en.getKey());
            Entity rider = h.level.getEntity(h.rider);
            if (cart == null || !cart.isAlive()) {
                it.remove();
                continue;
            }
            boolean done = h.level.getGameTime() > h.until || rider == null || !rider.isAlive();
            if (done) {
                it.remove();
                if (rider != null) rider.stopRiding();
                h.level.sendParticles(ParticleTypes.LARGE_SMOKE, cart.getX(), cart.getY() + 0.4D, cart.getZ(), 10, 0.3D, 0.2D, 0.3D, 0.02D);
                h.level.playSound(null, cart.getX(), cart.getY(), cart.getZ(), SoundEvents.ITEM_BREAK, SoundSource.PLAYERS, 1.0F, 0.6F);
                cart.discard();
                continue;
            }
            // no climbing out
            if (rider.getVehicle() != cart) rider.startRiding(cart, true);
            Vec3 v = cart.getDeltaMovement();
            cart.setDeltaMovement(h.dir.x * 0.6D, v.y, h.dir.z * 0.6D);
            if (cart.tickCount % 4 == 0) h.level.sendParticles(ParticleTypes.CRIT, cart.getX(), cart.getY() + 0.1D, cart.getZ(), 2, 0.3D, 0.0D, 0.3D, 0.05D);
        }
    }

    // ================================================================== Gallop Crown: Trample

    private static final Map<UUID, Long> TRAMPLED = new HashMap<>();

    /** Rushing: anything you run into gets trampled - knocked flying and hurt. */
    public static void trample(ServerPlayer p) {
        ServerLevel sl = p.serverLevel();
        long now = sl.getGameTime();
        Vec3 dir = p.getDeltaMovement().multiply(1.0D, 0.0D, 1.0D);
        if (dir.lengthSqr() < 1.0E-4D) dir = p.getLookAngle().multiply(1.0D, 0.0D, 1.0D);
        dir = dir.normalize();
        for (LivingEntity e : sl.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(0.7D, 0.2D, 0.7D),
                x -> StandCombat.valid(p, x))) {
            Long last = TRAMPLED.get(e.getUUID());
            if (last != null && now - last < 15L) continue;
            TRAMPLED.put(e.getUUID(), now);
            StandCombat.credit(p, e);
            e.hurt(sl.damageSources().playerAttack(p), (float) (6.0D * StandClass.power(StandType.HORSE)));
            e.setDeltaMovement(e.getDeltaMovement().add(dir.scale(1.3D)).add(0.0D, 0.45D, 0.0D));
            e.hurtMarked = true;
            sl.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.HORSE_LAND, SoundSource.PLAYERS, 1.2F, 0.8F);
            sl.sendParticles(ParticleTypes.CLOUD, e.getX(), e.getY() + 0.2D, e.getZ(), 8, 0.3D, 0.1D, 0.3D, 0.05D);
        }
        if (now % 200L == 0L) TRAMPLED.values().removeIf(t -> now - t > 40L);
    }

    // ================================================================== Green Temperance: Gelatinous Body bounce

    /** Landed with the goo out: no damage, and (unless you're sneaking) you bounce back up like off a slime block. */
    public static void bounce(ServerPlayer p, float distance) {
        if (p.isShiftKeyDown() || distance < 2.5F) return;
        double v = Math.sqrt(0.16D * Math.min(distance, 40.0F)) * 0.8D;
        TickScheduler.later(1, () -> {
            if (!p.isAlive()) return;
            Vec3 m = p.getDeltaMovement();
            p.setDeltaMovement(m.x, v, m.z);
            p.hurtMarked = true;
            p.resetFallDistance();
            ServerLevel sl = p.serverLevel();
            sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.SLIME_JUMP, SoundSource.PLAYERS, 1.0F, 0.9F);
            sl.sendParticles(ParticleTypes.ITEM_SLIME, p.getX(), p.getY() + 0.1D, p.getZ(), 14, 0.4D, 0.05D, 0.4D, 0.1D);
        });
    }

    // ================================================================== bookkeeping

    public static void tick(MinecraftServer server) {
        swingTick(server);
        hunterTick(server);
        sleepTick(server);
        haulTick(server);
    }

    /** Loaded back in from a save: whatever these tricks left behind is gone, and sleepers wake up. */
    public static boolean onLoad(Entity e) {
        if (e.getTags().contains(SLEEP_TAG) && !SLEEPERS.containsKey(e.getUUID()) && e instanceof Mob m) wake(m, true);
        return e.getTags().contains(TEMP_TAG) && !HUNTERS.containsKey(e.getUUID()) && !HAULS.containsKey(e.getUUID());
    }

    public static void logout(ServerPlayer p) {
        SWINGS.remove(p.getUUID());
        SOFT_LANDING.remove(p.getUUID());
    }

    public static void shutdown() {
        for (Cage cage : CAGES) crumble(cage);
        CAGES.clear();
        SWINGS.clear();
        SOFT_LANDING.clear();
        for (Map.Entry<UUID, Hunter> en : HUNTERS.entrySet()) {
            Entity e = en.getValue().c.level.getEntity(en.getKey());
            if (e != null) e.discard();
        }
        HUNTERS.clear();
        for (Map.Entry<UUID, Sleep> en : SLEEPERS.entrySet()) {
            if (en.getValue().level().getEntity(en.getKey()) instanceof Mob m) wake(m, en.getValue().setNoAi());
        }
        SLEEPERS.clear();
        for (Map.Entry<UUID, Haul> en : HAULS.entrySet()) {
            Entity rider = en.getValue().level.getEntity(en.getValue().rider);
            if (rider != null) rider.stopRiding();
            Entity cart = en.getValue().level.getEntity(en.getKey());
            if (cart != null) cart.discard();
        }
        HAULS.clear();
        TRAMPLED.clear();
    }
}
