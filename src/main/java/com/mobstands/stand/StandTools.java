package com.mobstands.stand;

import com.mobstands.entity.StandEntity;
import com.mobstands.network.RopePayload;
import com.mobstands.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Stands that are tools in your hand (Mountain Tim's lasso, Beach Boy's fishing rod). With the Stand out, R takes the
 * tool out of the extra slot beside your hotbar and into your hand (whatever you were holding waits in its place) -
 * R again, switching hotbar slots, or pressing Q puts it back. It only ever exists in your hand: it can't be dropped,
 * stored or kept.
 */
public final class StandTools {
    private StandTools() {}

    public static final int LASSO = 0, ROD = 1;
    private static final String STASH = "mobstands:tool_stash";

    /** Which tool a Stand has (null: none). */
    public static Item toolFor(StandType t) {
        if (t == StandType.LLAMA) return ModItems.LASSO.get();
        if (t == StandType.GUARDIAN) return ModItems.REEF_ROD.get();
        return null;
    }

    public static boolean isTool(ItemStack s) {
        return s.is(ModItems.LASSO.get()) || s.is(ModItems.REEF_ROD.get());
    }

    private record Out(int slot, ItemStack stash) {}

    private static final Map<UUID, Out> OUT = new HashMap<>();

    // ------------------------------------------------------------------ out / away

    /** R: take the tool out (or put it back). */
    public static void toggle(ServerPlayer p) {
        if (OUT.containsKey(p.getUUID())) {
            away(p);
            return;
        }
        StandData d = StandManager.data(p);
        Item tool = toolFor(d.getType());
        if (tool == null || !d.isSummoned() || p.isSpectator()) return;
        if (Curses.suppressed(p) || TimeStop.held(p)) return;
        Inventory inv = p.getInventory();
        int slot = inv.selected;
        ItemStack stash = inv.getItem(slot).copy();
        inv.setItem(slot, new ItemStack(tool));
        OUT.put(p.getUUID(), new Out(slot, stash));
        // (kept on you too, so nothing's lost if the game closes while it's out)
        CompoundTag t = new CompoundTag();
        t.putInt("slot", slot);
        if (!stash.isEmpty()) t.put("item", stash.save(p.registryAccess()));
        p.getPersistentData().put(STASH, t);
        p.serverLevel().playSound(null, p.getX(), p.getY(), p.getZ(), tool == ModItems.LASSO.get() ? SoundEvents.LEASH_KNOT_PLACE : SoundEvents.FISHING_BOBBER_RETRIEVE,
                SoundSource.PLAYERS, 0.8F, 1.2F);
    }

    /** Put the tool back in its slot (and what you were holding back in your hand). */
    public static void away(ServerPlayer p) {
        Out o = OUT.remove(p.getUUID());
        Inventory inv = p.getInventory();
        // the tool goes, wherever it ended up
        for (int i = 0; i < inv.getContainerSize(); i++) if (isTool(inv.getItem(i))) inv.setItem(i, ItemStack.EMPTY);
        if (isTool(p.containerMenu.getCarried())) p.containerMenu.setCarried(ItemStack.EMPTY);
        p.getPersistentData().remove(STASH);
        release(p);
        if (o == null) return;
        if (!o.stash().isEmpty()) {
            if (inv.getItem(o.slot()).isEmpty()) inv.setItem(o.slot(), o.stash());
            else if (!inv.add(o.stash())) p.drop(o.stash(), false);
        }
        p.containerMenu.broadcastChanges();
    }

    public static boolean out(ServerPlayer p) {
        return !OUT.isEmpty() && OUT.containsKey(p.getUUID());
    }

    /** Logging in: a tool left out when the game closed is put away, and what it replaced is given back. */
    public static void login(ServerPlayer p) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) if (isTool(inv.getItem(i))) inv.setItem(i, ItemStack.EMPTY);
        CompoundTag t = p.getPersistentData().getCompound(STASH);
        if (t.isEmpty()) return;
        p.getPersistentData().remove(STASH);
        if (t.contains("item", Tag.TAG_COMPOUND)) {
            ItemStack s = ItemStack.parseOptional(p.registryAccess(), t.getCompound("item"));
            int slot = t.getInt("slot");
            if (!s.isEmpty()) {
                if (inv.getItem(slot).isEmpty()) inv.setItem(slot, s);
                else if (!inv.add(s)) p.drop(s, false);
            }
        }
    }

    /** A container was closed: no tool gets left inside it. */
    public static void scrub(Container c) {
        for (int i = 0; i < c.getContainerSize(); i++) if (isTool(c.getItem(i))) c.setItem(i, ItemStack.EMPTY);
    }

    // ------------------------------------------------------------------ ropes and lines

    private static final class Rope {
        int kind;
        LivingEntity target;
        long until;
        float length;
        boolean fishing;
        long biteAt;
        Vec3 water;
    }

    private static final Map<UUID, Rope> ROPES = new HashMap<>();

    private static void show(ServerPlayer p, Rope r) {
        Vec3 at = r.water != null ? r.water : Vec3.ZERO;
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(p, new RopePayload(p.getId(), r.target != null ? r.target.getId() : -1,
                at.x, at.y, at.z, r.kind, true));
    }

    private static void flash(ServerPlayer p, Vec3 at, int kind, int ticks) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(p, new RopePayload(p.getId(), -1, at.x, at.y, at.z, kind, true));
        TickScheduler.later(ticks, () -> {
            if (!ROPES.containsKey(p.getUUID())) PacketDistributor.sendToPlayersTrackingEntityAndSelf(p, new RopePayload(p.getId(), -1, 0, 0, 0, kind, false));
        });
    }

    public static void release(ServerPlayer p) {
        Rope r = ROPES.remove(p.getUUID());
        if (r != null) PacketDistributor.sendToPlayersTrackingEntityAndSelf(p, new RopePayload(p.getId(), -1, 0, 0, 0, r.kind, false));
    }

    public static LivingEntity roped(ServerPlayer p) {
        Rope r = ROPES.get(p.getUUID());
        return r != null && r.target != null && r.target.isAlive() ? r.target : null;
    }

    // ------------------------------------------------------------------ the lasso

    /** Right-click with the lasso. */
    public static void lasso(ServerPlayer p) {
        ServerLevel sl = p.serverLevel();
        Rope r = ROPES.get(p.getUUID());
        if (p.isShiftKeyDown() && r != null) {
            release(p);
            sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.LEASH_KNOT_BREAK, SoundSource.PLAYERS, 0.8F, 1.2F);
            return;
        }
        LivingEntity t = r != null ? r.target : null;
        if (t != null && t.isAlive()) {
            // yank it toward you
            Vec3 pull = p.position().subtract(t.position()).normalize();
            t.setDeltaMovement(pull.x * 1.5D, 0.45D, pull.z * 1.5D);
            t.hurtMarked = true;
            t.invulnerableTime = 0;
            StandCombat.hurt(p, t, 2.0F);
            sl.playSound(null, t.getX(), t.getY(), t.getZ(), SoundEvents.LEASH_KNOT_PLACE, SoundSource.PLAYERS, 1.0F, 0.7F);
            p.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
            return;
        }
        Vec3 eye = p.getEyePosition(), end = eye.add(p.getLookAngle().scale(20.0D));
        EntityHitResult eh = ProjectileUtil.getEntityHitResult(sl, p, eye, end, p.getBoundingBox().expandTowards(p.getLookAngle().scale(20.0D)).inflate(1.0D),
                e -> e instanceof LivingEntity && e.isAlive() && !(e instanceof StandEntity) && !e.isSpectator(), 0.3F);
        BlockHitResult bh = sl.clip(new ClipContext(eye, p.getEyePosition().add(p.getLookAngle().scale(24.0D)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        p.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.FISHING_BOBBER_THROW, SoundSource.PLAYERS, 1.0F, 0.6F);
        if (eh != null && eh.getEntity() instanceof LivingEntity le && (bh.getType() == HitResult.Type.MISS || eh.getLocation().distanceToSqr(eye) < bh.getLocation().distanceToSqr(eye))) {
            Rope nr = new Rope();
            nr.kind = LASSO;
            nr.target = le;
            nr.length = (float) Math.max(3.0D, Math.min(8.0D, le.distanceTo(p)));
            nr.until = sl.getGameTime() + 20 * 20;
            ROPES.put(p.getUUID(), nr);
            show(p, nr);
            sl.playSound(null, le.getX(), le.getY(), le.getZ(), SoundEvents.LEASH_KNOT_PLACE, SoundSource.PLAYERS, 1.2F, 0.9F);
            if (le instanceof Mob m) m.getNavigation().stop();
            return;
        }
        if (bh.getType() == HitResult.Type.BLOCK) zip(p, bh.getLocation());
    }

    /** Lasso a block and haul yourself to it. */
    public static void zip(ServerPlayer p, Vec3 to) {
        Vec3 pull = to.subtract(p.position());
        double d = pull.length();
        Vec3 v = pull.normalize().scale(Math.min(2.4D, 0.6D + d * 0.11D));
        p.setDeltaMovement(v.x, v.y + 0.45D, v.z);
        p.hurtMarked = true;
        p.fallDistance = 0.0F;
        p.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 30, 0, false, false));
        flash(p, to, LASSO, 10);
        p.serverLevel().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.LEASH_KNOT_PLACE, SoundSource.PLAYERS, 1.0F, 0.8F);
    }

    // ------------------------------------------------------------------ the rod

    /** Right-click with Reef Boy's rod. */
    public static void rod(ServerPlayer p, ItemStack rodStack) {
        ServerLevel sl = p.serverLevel();
        Rope r = ROPES.get(p.getUUID());
        p.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
        if (p.isShiftKeyDown() && r != null) {
            release(p);
            sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.FISHING_BOBBER_RETRIEVE, SoundSource.PLAYERS, 1.0F, 1.0F);
            return;
        }
        if (r != null && r.target != null && r.target.isAlive()) {
            // reel it in: the hook tears at it
            LivingEntity t = r.target;
            Vec3 pull = p.position().subtract(t.position());
            Vec3 v = pull.normalize().scale(Math.min(1.4D, 0.4D + pull.length() * 0.08D));
            t.setDeltaMovement(v.x, Math.max(0.25D, v.y + 0.2D), v.z);
            t.hurtMarked = true;
            t.invulnerableTime = 0;
            StandCombat.hurt(p, t, 3.0F);
            sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.FISHING_BOBBER_RETRIEVE, SoundSource.PLAYERS, 1.0F, 0.7F);
            if (t.distanceTo(p) < 3.0D) {
                t.invulnerableTime = 0;
                StandCombat.hurt(p, t, 4.0F);
                sl.sendParticles(ParticleTypes.CRIT, t.getX(), t.getY() + t.getBbHeight() * 0.6D, t.getZ(), 12, 0.3D, 0.3D, 0.3D, 0.3D);
                release(p);
            }
            return;
        }
        if (r != null && r.water != null) {
            release(p); // pulled the line out of the water early
            return;
        }
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.FISHING_BOBBER_THROW, SoundSource.PLAYERS, 1.0F, 0.5F);
        // The line goes straight through walls: the first creature along your look, up to 32 blocks.
        Vec3 eye = p.getEyePosition(), dir = p.getLookAngle(), end = eye.add(dir.scale(32.0D));
        EntityHitResult eh = ProjectileUtil.getEntityHitResult(sl, p, eye, end, p.getBoundingBox().expandTowards(dir.scale(32.0D)).inflate(1.5D),
                e -> e instanceof LivingEntity && e.isAlive() && !(e instanceof StandEntity) && !e.isSpectator(), 1.0F);
        if (eh != null && eh.getEntity() instanceof LivingEntity le) {
            Rope nr = new Rope();
            nr.kind = ROD;
            nr.target = le;
            nr.length = 32.0F;
            nr.until = sl.getGameTime() + 20 * 20;
            ROPES.put(p.getUUID(), nr);
            show(p, nr);
            sl.playSound(null, le.getX(), le.getY(), le.getZ(), SoundEvents.FISHING_BOBBER_SPLASH, SoundSource.PLAYERS, 0.8F, 1.4F);
            p.displayClientMessage(Component.literal("Hooked: " + le.getName().getString() + ". Right-click to reel, Shift + right-click to let go.")
                    .withStyle(s -> s.withColor(0x5FC9B8)), true);
            return;
        }
        // No creature: fish. Cast into water and something bites in a few seconds.
        BlockHitResult wh = sl.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.SOURCE_ONLY, p));
        if (wh.getType() == HitResult.Type.BLOCK && sl.getFluidState(wh.getBlockPos()).is(net.minecraft.tags.FluidTags.WATER)) {
            Rope nr = new Rope();
            nr.kind = ROD;
            nr.fishing = true;
            nr.water = wh.getLocation();
            nr.biteAt = sl.getGameTime() + 40 + sl.random.nextInt(60);
            nr.until = nr.biteAt + 40;
            ROPES.put(p.getUUID(), nr);
            show(p, nr);
            sl.playSound(null, nr.water.x, nr.water.y, nr.water.z, SoundEvents.FISHING_BOBBER_SPLASH, SoundSource.PLAYERS, 0.6F, 1.0F);
            return;
        }
        flash(p, wh.getType() == HitResult.Type.MISS ? end : wh.getLocation(), ROD, 8);
    }

    private static void bite(ServerPlayer p, Rope r) {
        ServerLevel sl = p.serverLevel();
        var table = sl.getServer().reloadableRegistries().getLootTable(BuiltInLootTables.FISHING);
        LootParams params = new LootParams.Builder(sl)
                .withParameter(LootContextParams.ORIGIN, r.water)
                .withParameter(LootContextParams.TOOL, new ItemStack(net.minecraft.world.item.Items.FISHING_ROD))
                .withParameter(LootContextParams.THIS_ENTITY, p)
                .withLuck(p.getLuck())
                .create(LootContextParamSets.FISHING);
        for (ItemStack s : table.getRandomItems(params)) {
            if (!p.getInventory().add(s)) p.drop(s, false);
        }
        p.giveExperiencePoints(1 + sl.random.nextInt(6));
        sl.sendParticles(ParticleTypes.FISHING, r.water.x, r.water.y, r.water.z, 12, 0.2D, 0.0D, 0.2D, 0.1D);
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.FISHING_BOBBER_RETRIEVE, SoundSource.PLAYERS, 1.0F, 1.0F);
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.6F, 1.2F);
    }

    // ------------------------------------------------------------------ every tick

    public static void tick(MinecraftServer server) {
        if (!OUT.isEmpty()) {
            Iterator<Map.Entry<UUID, Out>> it = OUT.entrySet().iterator();
            java.util.List<ServerPlayer> putAway = new java.util.ArrayList<>();
            while (it.hasNext()) {
                Map.Entry<UUID, Out> en = it.next();
                ServerPlayer p = server.getPlayerList().getPlayer(en.getKey());
                if (p == null) {
                    it.remove();
                    continue;
                }
                StandData d = StandManager.data(p);
                Item tool = toolFor(d.getType());
                Inventory inv = p.getInventory();
                boolean ok = tool != null && d.isSummoned() && p.isAlive() && inv.selected == en.getValue().slot()
                        && inv.getItem(inv.selected).is(tool);
                if (!ok) putAway.add(p);
            }
            for (ServerPlayer p : putAway) away(p);
        }
        if (!ROPES.isEmpty()) tickRopes(server);
    }

    private static void tickRopes(MinecraftServer server) {
        Iterator<Map.Entry<UUID, Rope>> it = ROPES.entrySet().iterator();
        java.util.List<ServerPlayer> drop = new java.util.ArrayList<>();
        while (it.hasNext()) {
            Map.Entry<UUID, Rope> en = it.next();
            ServerPlayer p = server.getPlayerList().getPlayer(en.getKey());
            Rope r = en.getValue();
            if (p == null) {
                it.remove();
                continue;
            }
            ServerLevel sl = p.serverLevel();
            long now = sl.getGameTime();
            if (now > r.until || !p.isAlive()) {
                drop.add(p);
                continue;
            }
            if (r.fishing) {
                if (now == r.biteAt) {
                    sl.playSound(null, r.water.x, r.water.y, r.water.z, SoundEvents.FISHING_BOBBER_SPLASH, SoundSource.PLAYERS, 1.0F, 1.2F);
                    bite(p, r);
                    drop.add(p);
                }
                continue;
            }
            LivingEntity t = r.target;
            if (t == null || !t.isAlive() || t.level() != p.level() || t.distanceTo(p) > 48.0D) {
                drop.add(p);
                continue;
            }
            double dist = t.distanceTo(p);
            if (r.kind == LASSO && dist > r.length) {
                // the rope pulls it along behind you
                Vec3 pull = p.position().subtract(t.position()).normalize().scale(Math.min(0.6D, (dist - r.length) * 0.25D));
                t.setDeltaMovement(t.getDeltaMovement().scale(0.5D).add(pull.x, Math.max(0.0D, pull.y) + 0.04D, pull.z));
                t.hurtMarked = true;
            }
            if (r.kind == ROD && now % 20 == 0) {
                // the hook digs in, and you feel its heart beating down the line
                t.invulnerableTime = 0;
                StandCombat.hurt(p, t, 1.0F);
                p.playNotifySound(SoundEvents.WARDEN_HEARTBEAT, SoundSource.PLAYERS, 0.8F, 1.3F);
            }
        }
        for (ServerPlayer p : drop) release(p);
    }

    public static void logout(ServerPlayer p) {
        away(p);
    }

    public static void shutdown() {
        OUT.clear();
        ROPES.clear();
    }

    // ------------------------------------------------------------------ Oh! Lonesome Llama abilities

    /** Spit: a fast glob at whatever you're looking at. */
    public static boolean spit(AbilityContext c) {
        LivingEntity t = c.aim(20.0D);
        Vec3 from = c.stand.position().add(0.0D, 1.6D, 0.0D);
        Vec3 to = t != null ? t.position().add(0.0D, t.getBbHeight() * 0.6D, 0.0D) : c.owner.getEyePosition().add(c.look().scale(16.0D));
        Vec3 d = to.subtract(from);
        for (int i = 0; i <= 10; i++) {
            Vec3 at = from.add(d.scale(i / 10.0D));
            c.level.sendParticles(ParticleTypes.SPIT, at.x, at.y, at.z, 1, 0.02D, 0.02D, 0.02D, 0.0D);
        }
        c.sound(SoundEvents.LLAMA_SPIT, 1.0F, 1.0F);
        c.stand.playAnim(0);
        if (t != null) {
            t.invulnerableTime = 0;
            c.hurt(t, c.dmg(4.0D));
            c.effect(t, MobEffects.MOVEMENT_SLOWDOWN, 30, 0);
        }
        return true;
    }

    /** Lone Rider: ride the rope to whatever's on the end of it and kick it - or lasso a block and zip there. */
    public static boolean ride(AbilityContext c) {
        LivingEntity t = roped(c.owner);
        if (t == null) {
            BlockHitResult bh = c.level.clip(new ClipContext(c.owner.getEyePosition(), c.owner.getEyePosition().add(c.look().scale(28.0D)),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, c.owner));
            if (bh.getType() != HitResult.Type.BLOCK) {
                c.fail = "Rope something first, or look at a block.";
                return false;
            }
            zip(c.owner, bh.getLocation());
            return true;
        }
        zip(c.owner, t.position().add(0.0D, t.getBbHeight() * 0.5D, 0.0D));
        c.later(8, () -> {
            if (!t.isAlive() || t.distanceTo(c.owner) > 4.0D) return;
            t.invulnerableTime = 0;
            c.hurt(t, c.dmg(5.0D));
            c.push(t, t.position().subtract(c.owner.position()), 1.2D, 0.3D);
            c.sound(SoundEvents.LLAMA_ANGRY, 1.0F, 1.0F);
        });
        return true;
    }

    /** Oh! Lonesome Me: the body on the end of your rope comes apart along it. */
    public static boolean lonesome(AbilityContext c) {
        LivingEntity t = roped(c.owner);
        if (t == null) {
            c.fail = "Lasso something first.";
            return false;
        }
        Vec3 a = c.owner.position().add(0.0D, 1.0D, 0.0D), b = t.position().add(0.0D, t.getBbHeight() * 0.5D, 0.0D);
        for (int i = 0; i <= 16; i++) {
            Vec3 at = a.add(b.subtract(a).scale(i / 16.0D));
            c.level.sendParticles(ParticleTypes.CRIT, at.x, at.y, at.z, 2, 0.05D, 0.05D, 0.05D, 0.05D);
        }
        c.sound(SoundEvents.LEASH_KNOT_BREAK, 1.2F, 0.6F);
        t.invulnerableTime = 0;
        c.hurt(t, c.dmg(9.0D));
        c.effect(t, MobEffects.WEAKNESS, 120, 1);
        c.effect(t, MobEffects.MOVEMENT_SLOWDOWN, 120, 2);
        c.level.sendParticles(ParticleTypes.DAMAGE_INDICATOR, b.x, b.y, b.z, 12, 0.3D, 0.4D, 0.3D, 0.2D);
        release(c.owner);
        return true;
    }

    /** Stampede: a herd of phantom llamas charges out ahead of you. */
    public static boolean stampede(AbilityContext c) {
        Vec3 dir = c.look().multiply(1.0D, 0.0D, 1.0D).normalize();
        Vec3 side = new Vec3(-dir.z, 0.0D, dir.x);
        Vec3 o = c.owner.position();
        c.sound(SoundEvents.LLAMA_ANGRY, 1.5F, 0.8F);
        java.util.Set<Entity> hit = new java.util.HashSet<>();
        for (int k = 0; k < 14; k++) {
            final int kk = k;
            c.later(k, () -> {
                Vec3 at = o.add(dir.scale(1.5D + kk));
                c.level.sendParticles(ParticleTypes.CLOUD, at.x, at.y + 0.3D, at.z, 8, 1.2D, 0.2D, 1.2D, 0.02D);
                c.level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK,
                        c.level.getBlockState(BlockPos.containing(at).below())), at.x, at.y + 0.1D, at.z, 10, 1.2D, 0.1D, 1.2D, 0.1D);
                if (kk % 3 == 0) c.level.playSound(null, at.x, at.y, at.z, SoundEvents.LLAMA_STEP, SoundSource.PLAYERS, 1.2F, 0.8F);
                for (LivingEntity e : c.level.getEntitiesOfClass(LivingEntity.class, new AABB(at, at).inflate(1.6D, 1.5D, 1.6D),
                        x -> x != c.owner && x.isAlive() && StandCombat.valid(c.owner, x))) {
                    if (!hit.add(e)) continue;
                    e.invulnerableTime = 0;
                    c.hurt(e, c.dmg(6.0D));
                    e.setDeltaMovement(dir.x * 1.3D, 0.6D, dir.z * 1.3D);
                    e.hurtMarked = true;
                }
            });
        }
        return true;
    }

    // ------------------------------------------------------------------ Reef Boy abilities

    /** Guardian Laser: a beam charges for a moment, then burns whatever you're looking at. */
    public static boolean laser(AbilityContext c) {
        LivingEntity t = c.aim(16.0D);
        if (t == null) {
            c.fail = "Nothing to aim at.";
            return false;
        }
        c.sound(SoundEvents.GUARDIAN_ATTACK, 1.0F, 1.2F);
        for (int k = 0; k < 12; k += 2) {
            final int kk = k;
            c.later(k, () -> {
                if (!t.isAlive()) return;
                Vec3 a = c.stand.position().add(0.0D, 0.6D, 0.0D), b = t.position().add(0.0D, t.getBbHeight() * 0.5D, 0.0D);
                for (int i = 0; i <= 12; i++) {
                    Vec3 at = a.add(b.subtract(a).scale(i / 12.0D));
                    c.level.sendParticles(kk >= 10 ? ParticleTypes.END_ROD : ParticleTypes.BUBBLE, at.x, at.y, at.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
                }
            });
        }
        c.later(12, () -> {
            if (!t.isAlive()) return;
            t.invulnerableTime = 0;
            t.hurt(c.level.damageSources().indirectMagic(c.owner, c.owner), (float) c.dmg(5.0D));
            StandCombat.credit(c.owner, t);
        });
        return true;
    }

    /** Heartbeat: everything with a pulse within 32 blocks glows for 8 seconds, even through walls. */
    public static boolean heartbeat(AbilityContext c) {
        int n = 0;
        for (LivingEntity e : c.level.getEntitiesOfClass(LivingEntity.class, c.owner.getBoundingBox().inflate(32.0D), x -> x != c.owner && x.isAlive())) {
            e.addEffect(new MobEffectInstance(MobEffects.GLOWING, 160, 0, false, false), c.owner);
            n++;
        }
        c.owner.playNotifySound(SoundEvents.WARDEN_HEARTBEAT, SoundSource.PLAYERS, 1.0F, 1.0F);
        int fn = n;
        c.owner.displayClientMessage(Component.literal(fn + (fn == 1 ? " heartbeat" : " heartbeats") + " down the line.").withStyle(s -> s.withColor(0x5FC9B8)), true);
        return true;
    }

    /** Deep Reel: whatever's on your hook is torn all the way to you and slammed down. */
    public static boolean deepReel(AbilityContext c) {
        LivingEntity t = roped(c.owner);
        if (t == null) {
            c.fail = "Hook something first.";
            return false;
        }
        Vec3 to = c.owner.position().add(c.look().multiply(1.0D, 0.0D, 1.0D).normalize().scale(1.5D));
        t.teleportTo(to.x, to.y, to.z);
        t.invulnerableTime = 0;
        c.hurt(t, c.dmg(7.0D));
        t.setDeltaMovement(0.0D, -0.5D, 0.0D);
        t.hurtMarked = true;
        c.level.sendParticles(ParticleTypes.SPLASH, to.x, to.y + 0.5D, to.z, 40, 0.5D, 0.3D, 0.5D, 0.2D);
        c.sound(SoundEvents.FISHING_BOBBER_RETRIEVE, 1.2F, 0.6F);
        c.sound(SoundEvents.PLAYER_ATTACK_CRIT, 1.0F, 0.8F);
        release(c.owner);
        return true;
    }

    /** Elder Curse: everyone around you is weighed down by the deep - slow, weak, and too tired to mine. */
    public static boolean curse(AbilityContext c) {
        for (LivingEntity e : c.level.getEntitiesOfClass(LivingEntity.class, c.owner.getBoundingBox().inflate(16.0D),
                x -> x != c.owner && x.isAlive() && StandCombat.valid(c.owner, x))) {
            e.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN, 200, 2), c.owner);
            e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 200, 1), c.owner);
            e.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 200, 0), c.owner);
            if (e instanceof ServerPlayer sp) {
                sp.connection.send(new net.minecraft.network.protocol.game.ClientboundGameEventPacket(
                        net.minecraft.network.protocol.game.ClientboundGameEventPacket.GUARDIAN_ELDER_EFFECT, 1.0F));
            }
        }
        c.sound(SoundEvents.ELDER_GUARDIAN_CURSE, 1.2F, 1.0F);
        return true;
    }
}
