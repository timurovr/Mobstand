package com.mobstands.stand;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ancient Nose's Hermit Purple side: thorny purple vines, and spirit photography. Like Joseph smashing a camera to
 * take a psychic photo, every reading costs a spyglass - your Stand crushes one in its vines and the image comes
 * out of the shards.
 */
public final class Hermit {
    private Hermit() {}

    private static final DustParticleOptions VINE = new DustParticleOptions(new Vector3f(0.55F, 0.22F, 0.78F), 1.1F);
    private static final DustParticleOptions THORN = new DustParticleOptions(new Vector3f(0.85F, 0.55F, 1.0F), 0.7F);

    // ------------------------------------------------------------------ the spyglass "camera"

    /** Crush a spyglass from your inventory (creative: free). False if you don't have one. */
    public static boolean sacrifice(ServerPlayer p) {
        if (!p.getAbilities().instabuild) {
            Inventory inv = p.getInventory();
            int slot = -1;
            if (p.getOffhandItem().is(Items.SPYGLASS)) slot = Inventory.SLOT_OFFHAND;
            else for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(Items.SPYGLASS)) { slot = i; break; }
            if (slot < 0) {
                p.displayClientMessage(Component.literal("Hermit Purple needs a camera: you have no spyglass to sacrifice.")
                        .withStyle(s -> s.withColor(0xB070E0)), true);
                return false;
            }
            inv.getItem(slot).shrink(1);
        }
        ServerLevel sl = p.serverLevel();
        Vec3 hand = p.getEyePosition().add(p.getLookAngle().scale(0.7D)).add(0.0D, -0.3D, 0.0D);
        sl.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, new ItemStack(Items.SPYGLASS)), hand.x, hand.y, hand.z, 14, 0.12D, 0.12D, 0.12D, 0.12D);
        Vec3 arm = p.position().add(0.0D, 1.0D, 0.0D);
        vine(sl, arm, hand.add(0.15D, 0.1D, 0.0D), 10, 0.6F, false);
        vine(sl, arm, hand.add(-0.15D, -0.05D, 0.1D), 10, 0.6F, false);
        sl.sendParticles(ParticleTypes.FLASH, hand.x, hand.y, hand.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 0.9F, 1.3F);
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, SoundSource.PLAYERS, 1.0F, 1.6F);
        return true;
    }

    // ------------------------------------------------------------------ Thorn Vine

    /** Look at a creature: the vines snare it and drag it to you. Look at a block: they catch it and haul you there. */
    public static boolean vine(AbilityContext c) {
        ServerPlayer p = c.owner;
        ServerLevel sl = c.level;
        Vec3 eye = p.getEyePosition();
        LivingEntity t = c.aim(16.0D);
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.WEEPING_VINES_PLACE, SoundSource.PLAYERS, 1.2F, 0.8F);
        if (t != null) {
            Vec3 at = t.position().add(0.0D, t.getBbHeight() * 0.5D, 0.0D);
            drawVine(sl, eye.add(0.0D, -0.4D, 0.0D), at, 6);
            Vec3 pull = eye.subtract(at);
            double d = pull.length();
            Vec3 v = pull.normalize().scale(Math.min(2.2D, 0.35D + d * 0.14D));
            t.setDeltaMovement(v.x, Math.max(0.35D, v.y + 0.25D), v.z);
            t.hurtMarked = true;
            t.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 50, 2), p);
            t.hurt(sl.damageSources().playerAttack(p), (float) c.dmg(2.0D)); // the thorns
            sl.sendParticles(THORN, at.x, at.y, at.z, 12, 0.3D, 0.4D, 0.3D, 0.0D);
            return true;
        }
        BlockHitResult hit = sl.clip(new ClipContext(eye, eye.add(c.look().scale(24.0D)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        if (hit.getType() != HitResult.Type.BLOCK) {
            c.fail = "Nothing for the vines to catch.";
            return false;
        }
        Vec3 at = hit.getLocation();
        drawVine(sl, eye.add(0.0D, -0.4D, 0.0D), at, 8);
        Vec3 pull = at.subtract(p.position());
        double d = pull.length();
        Vec3 v = pull.normalize().scale(Math.min(2.4D, 0.6D + d * 0.11D));
        p.setDeltaMovement(v.x, v.y + 0.45D, v.z);
        p.hurtMarked = true;
        p.fallDistance = 0.0F;
        p.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 30, 0, false, false), p);
        return true;
    }

    /** A real vine from a to b (drawn by everyone nearby), staying out for a few ticks. */
    private static void drawVine(ServerLevel sl, Vec3 a, Vec3 b, int ticks) {
        vine(sl, a, b, ticks + 6, 1.0F, false);
    }

    static void vine(ServerLevel sl, Vec3 a, Vec3 b, int life, float thick, boolean ground) {
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayersNear(sl, null, a.x, a.y, a.z, 96.0D,
                new com.mobstands.network.VinePayload(a, b, life, thick, ground));
    }

    // ------------------------------------------------------------------ Vine Sense

    /** The vines spread out across the ground: every creature within 32 blocks glows for 10 seconds, even through walls. */
    public static boolean sense(AbilityContext c) {
        ServerPlayer p = c.owner;
        ServerLevel sl = c.level;
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.WEEPING_VINES_PLACE, SoundSource.PLAYERS, 1.5F, 0.6F);
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.SCULK_CLICKING, SoundSource.PLAYERS, 1.0F, 0.8F);
        // vines creeping out across the ground in every direction
        for (int k = 0; k < 14; k++) {
            double a = k * Math.PI * 2.0D / 14.0D + p.getRandom().nextDouble() * 0.3D;
            double len = 12.0D + p.getRandom().nextDouble() * 10.0D;
            Vec3 from = p.position().add(Math.cos(a) * 0.6D, 0.0D, Math.sin(a) * 0.6D);
            Vec3 to = p.position().add(Math.cos(a) * len, 0.0D, Math.sin(a) * len);
            vine(sl, from, to, 40, 0.8F, true);
        }
        int n = 0;
        for (LivingEntity e : sl.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(32.0D), x -> x != p && x.isAlive())) {
            e.addEffect(new MobEffectInstance(MobEffects.GLOWING, 200, 0, false, false), p);
            n++;
        }
        p.displayClientMessage(Component.literal("Hermit Purple feels " + n + (n == 1 ? " creature" : " creatures") + " around you.")
                .withStyle(s -> s.withColor(0xB070E0)), true);
        return true;
    }

    // ------------------------------------------------------------------ Spirit Photograph

    private record Lead(ServerLevel level, Vec3 target, long until) {}
    private static final Map<UUID, Lead> LEADS = new HashMap<>();

    /**
     * Crush a spyglass and the shards show you where something is. What it looks for is whatever is in your other
     * hand: a block (the nearest one of it within 48 blocks), a spawn egg (the nearest of that creature), any other
     * item (the nearest one lying on the ground) - or nothing, and it shows you the nearest other player
     * (or, if there's no one, the nearest Stand-using Illager). You get the photo, and the vines point the way.
     */
    public static boolean photograph(AbilityContext c) {
        ServerPlayer p = c.owner;
        ServerLevel sl = c.level;
        ItemStack subject = p.getOffhandItem();
        if (subject.is(Items.SPYGLASS)) subject = ItemStack.EMPTY; // (that's the camera, not the subject)
        if (!sacrifice(p)) return false;
        String what;
        Vec3 found;
        if (subject.getItem() instanceof BlockItem bi) {
            Block b = bi.getBlock();
            what = b.getName().getString();
            BlockPos bp = nearestBlock(sl, p.blockPosition(), b, 48);
            found = bp == null ? null : Vec3.atCenterOf(bp);
        } else if (subject.getItem() instanceof SpawnEggItem egg) {
            EntityType<?> type = egg.getType(subject);
            what = type.getDescription().getString();
            found = nearestEntity(sl, p, e -> e.getType() == type, 256.0D);
        } else if (!subject.isEmpty()) {
            ItemStack want = subject;
            what = want.getHoverName().getString() + " (on the ground)";
            found = nearestEntity(sl, p, e -> e instanceof ItemEntity ie && ie.getItem().is(want.getItem()), 128.0D);
        } else {
            found = nearestEntity(sl, p, e -> e instanceof Player o && o != p && !o.isSpectator(), 100000.0D);
            what = "another player";
            if (found == null) {
                found = nearestEntity(sl, p, e -> e.getPersistentData().contains(StandManager.PILLAGER_STAND), 512.0D);
                what = "a Stand user";
            }
        }
        if (found == null) {
            give(p, photo("A blurry photograph", List.of("Purple vines... and nothing else.", "Hermit Purple found no " + what + " nearby.")));
            p.displayClientMessage(Component.literal("The photo shows nothing. No " + what + " nearby.").withStyle(s -> s.withColor(0x9A8AA8)), false);
            return true;
        }
        BlockPos at = BlockPos.containing(found);
        int dist = (int) Math.round(p.position().distanceTo(found));
        String dir = direction(p, found);
        String height = found.y > p.getY() + 4 ? " (above you)" : found.y < p.getY() - 4 ? " (below you)" : "";
        String where = dist + " blocks " + dir + height + " - " + at.getX() + ", " + at.getY() + ", " + at.getZ();
        give(p, photo("Spirit Photograph: " + what, List.of(where, "Taken by Hermit Purple.")));
        p.displayClientMessage(Component.literal("The photo shows " + what + ": " + where + ". Follow the vines.")
                .withStyle(s -> s.withColor(0xB070E0)), false);
        LEADS.put(p.getUUID(), new Lead(sl, found, Long.MAX_VALUE));
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new com.mobstands.network.VineLeadPayload(true, found));
        SHOWN.put(p.getUUID(), true);
        return true;
    }

    private static ItemStack photo(String title, List<String> lines) {
        ItemStack s = new ItemStack(Items.PAPER);
        s.set(DataComponents.CUSTOM_NAME, Component.literal(title).withStyle(st -> st.withColor(0xB070E0).withItalic(false)));
        List<Component> lore = new ArrayList<>();
        for (String l : lines) lore.add(Component.literal(l).withStyle(ChatFormatting.GRAY));
        s.set(DataComponents.LORE, new ItemLore(lore));
        return s;
    }

    private static void give(ServerPlayer p, ItemStack s) {
        if (!p.getInventory().add(s)) p.drop(s, false);
    }

    private static BlockPos nearestBlock(ServerLevel sl, BlockPos c, Block b, int r) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        int cx0 = (c.getX() - r) >> 4, cx1 = (c.getX() + r) >> 4, cz0 = (c.getZ() - r) >> 4, cz1 = (c.getZ() + r) >> 4;
        int y0 = Math.max(sl.getMinBuildHeight(), c.getY() - r), y1 = Math.min(sl.getMaxBuildHeight() - 1, c.getY() + r);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                if (!sl.hasChunk(cx, cz)) continue;
                LevelChunk ch = sl.getChunk(cx, cz);
                LevelChunkSection[] secs = ch.getSections();
                for (int si = 0; si < secs.length; si++) {
                    LevelChunkSection sec = secs[si];
                    if (sec == null || sec.hasOnlyAir() || !sec.maybeHas(st -> st.is(b))) continue;
                    int baseY = sl.getSectionYFromSectionIndex(si) << 4;
                    if (baseY + 15 < y0 || baseY > y1) continue;
                    for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                        if (!sec.getBlockState(x, y, z).is(b)) continue;
                        m.set((cx << 4) + x, baseY + y, (cz << 4) + z);
                        double d = m.distSqr(c);
                        if (d < bestD && d <= (double) r * r * 3) { bestD = d; best = m.immutable(); }
                    }
                }
            }
        }
        return best;
    }

    private static Vec3 nearestEntity(ServerLevel sl, ServerPlayer p, java.util.function.Predicate<Entity> ok, double range) {
        Entity best = null;
        double bestD = range * range;
        for (Entity e : sl.getAllEntities()) {
            if (e == null || !e.isAlive() || !ok.test(e)) continue;
            double d = e.distanceToSqr(p);
            if (d < bestD) { bestD = d; best = e; }
        }
        return best == null ? null : best.position().add(0.0D, best.getBbHeight() * 0.5D, 0.0D);
    }

    private static String direction(ServerPlayer p, Vec3 at) {
        double dx = at.x - p.getX(), dz = at.z - p.getZ();
        String[] dirs = {"south", "south-west", "west", "north-west", "north", "north-east", "east", "south-east"};
        double ang = Math.toDegrees(Math.atan2(-dx, dz));
        return dirs[(int) Math.floorMod(Math.round(ang / 45.0D), 8)];
    }

    /** Whether the ground vine is showing for each player (hidden while they're in another dimension). */
    private static final Map<UUID, Boolean> SHOWN = new HashMap<>();

    /** The vine on the ground points you toward what the photo showed - until you get there. */
    public static void tick(ServerPlayer p) {
        if (LEADS.isEmpty() || p.tickCount % 10 != 0) return;
        Lead l = LEADS.get(p.getUUID());
        if (l == null) return;
        boolean here = p.serverLevel() == l.level();
        boolean shown = SHOWN.getOrDefault(p.getUUID(), false);
        if (here != shown) {
            SHOWN.put(p.getUUID(), here);
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new com.mobstands.network.VineLeadPayload(here, l.target()));
        }
        if (!here) return;
        Vec3 to = l.target().subtract(p.position());
        if (new Vec3(to.x, 0.0D, to.z).length() < 4.0D && Math.abs(to.y) < 8.0D) {
            p.displayClientMessage(Component.literal("It's right here.").withStyle(s -> s.withColor(0xB070E0)), true);
            LEADS.remove(p.getUUID());
            SHOWN.remove(p.getUUID());
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new com.mobstands.network.VineLeadPayload(false, Vec3.ZERO));
        }
    }

    /** Logging back in: the ground vine shows again. */
    public static void login(ServerPlayer p) {
        SHOWN.remove(p.getUUID());
    }
}
