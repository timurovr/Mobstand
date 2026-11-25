package com.mobstands.stand;

import com.mobstands.entity.StandEntity;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * The Pig line is one Stand in four Acts, Echoes style:
 *   ACT 1 Muddy Fortune (Pig) -> ACT 2 Thunderborn Swine (Zombified Piglin) -> ACT 3 Gilded Swine (Piglin)
 *   -> ACT 4 Pigstep (its Requiem).
 * Each Act keeps its own level. Once unlocked you can switch between Acts any time (Shift + summon key).
 *
 *   1 -> 2: your Pig Stand (or you) is struck by lightning.
 *   2 -> 3: Act 2 at level 10. Get struck by lightning holding a Totem of Undying - the totem takes the bolt and
 *           restores the rotten body. Then eat an Enchanted Golden Apple to restore its soul: a brand new Stand.
 *   3 -> 4: Piglin's Requiem trial (unchanged).
 */
public final class PigActs {
    private PigActs() {}

    /** Moves to another Act: the one you leave keeps its level; a newly unlocked Act starts at level 1. */
    public static void moveTo(ServerPlayer p, StandType to, boolean fresh) {
        StandData d = StandManager.data(p);
        StandType from = d.getType();
        if (from != null && from.pigAct() > 0) {
            d.actLevel[from.pigAct()] = d.getLevel();
            d.actXp[from.pigAct()] = d.getXp();
        }
        boolean wasOut = d.isSummoned();
        StandManager.removeEntity(p);
        d.setType(to);
        if (fresh || d.actLevel[to.pigAct()] <= 0) {
            d.setLevel(1);
            d.setXp(0);
        } else {
            d.setLevel(d.actLevel[to.pigAct()]);
            d.setXp(d.actXp[to.pigAct()]);
        }
        d.setSummoned(false);
        if (wasOut || fresh) StandManager.summon(p);
        StandManager.sync(p);
    }

    /** Shift + summon key: the next unlocked Act (wrapping back to ACT 1). */
    public static void switchAct(ServerPlayer p) {
        StandData d = StandManager.data(p);
        StandType t = d.getType();
        if (t == null || t.pigAct() == 0) return;
        if (d.pigActMax <= 1) {
            p.displayClientMessage(Component.literal("Your Stand has no other Acts yet."), true);
            return;
        }
        int next = t.pigAct() % d.pigActMax + 1;
        StandType to = StandType.pigActType(next);
        StandEntity old = StandManager.getStand(p);
        Vec3 at = old != null ? old.position() : p.position();
        moveTo(p, to, false);
        ServerLevel sl = p.serverLevel();
        sl.sendParticles(ParticleTypes.WAX_ON, at.x, at.y + 0.8D, at.z, 25, 0.4D, 0.5D, 0.4D, 0.05D);
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ARMOR_EQUIP_GOLD.value(), SoundSource.PLAYERS, 1.0F, 0.6F + next * 0.15F);
        p.displayClientMessage(Component.literal(to.actLabel() + ": " + to.standName()).withStyle(s -> s.withColor(to.color()).withBold(true)), true);
    }

    private static void title(ServerPlayer p, StandType to) {
        int col = to.color();
        p.connection.send(new ClientboundSetTitlesAnimationPacket(10, 60, 20));
        p.connection.send(new ClientboundSetTitleTextPacket(Component.literal(to.actLabel()).withStyle(s -> s.withColor(col).withBold(true))));
        p.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(to.standName()).withStyle(s -> s.withColor(col).withItalic(true))));
    }

    /** ACT 1 -> 2: the Pig is struck by lightning. */
    public static void pigStruck(ServerPlayer p) {
        StandData d = StandManager.data(p);
        if (d.getType() != StandType.PIG || d.pigActMax >= 2) return;
        StandEntity old = StandManager.getStand(p);
        Vec3 at = old != null ? old.position() : p.position();
        ServerLevel level = p.serverLevel();
        moveTo(p, StandType.ZOMBIFIED_PIGLIN, true);
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y + 0.6D, at.z, 30, 0.4D, 0.5D, 0.4D, 0.05D);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.PIG_DEATH, SoundSource.PLAYERS, 1.0F, 0.8F);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.ZOMBIFIED_PIGLIN_ANGRY, SoundSource.PLAYERS, 1.2F, 1.0F);
        title(p, StandType.ZOMBIFIED_PIGLIN);
        p.sendSystemMessage(Component.literal("The lightning killed your Pig... and something rotten got back up. "
                + "ACT 2 unlocked. Shift + summon key switches Acts.").withStyle(s -> s.withColor(0x8DBE6A)));
    }

    /**
     * ACT 2 -> 3, step 1: struck by lightning at level 10 holding a Totem of Undying. The totem takes the bolt
     * (you're unhurt) and restores your Stand's body. Returns true if the lightning should be cancelled.
     */
    public static boolean totemStruck(ServerPlayer p) {
        StandData d = StandManager.data(p);
        if (d.getType() != StandType.ZOMBIFIED_PIGLIN || d.pigActMax >= 3) return false;
        InteractionHand hand = p.getMainHandItem().is(Items.TOTEM_OF_UNDYING) ? InteractionHand.MAIN_HAND
                : p.getOffhandItem().is(Items.TOTEM_OF_UNDYING) ? InteractionHand.OFF_HAND : null;
        if (hand == null) return false;
        if (d.getLevel() < StandData.MAX_LEVEL) {
            p.displayClientMessage(Component.literal("Your Stand isn't strong enough yet (ACT 2 needs level 10)."), true);
            return false;
        }
        if (d.bodyRestored) return true; // already done: the totem just keeps you safe
        ItemStack totem = p.getItemInHand(hand);
        totem.shrink(1);
        p.level().broadcastEntityEvent(p, (byte) 35); // the totem animation
        p.clearFire();
        d.bodyRestored = true;
        StandManager.sync(p);
        p.serverLevel().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 1.0F, 0.8F);
        p.sendSystemMessage(Component.literal("The Totem of Undying took the lightning... and your Stand's rotten body is whole again. "
                + "But its soul is still lost. Eat an Enchanted Golden Apple to bring it back.").withStyle(s -> s.withColor(0xF2C94C).withItalic(true)));
        return true;
    }

    /** ACT 2 -> 3, step 2: an Enchanted Golden Apple restores its soul - a brand new Stand. */
    public static void ateApple(ServerPlayer p, ItemStack food) {
        if (!food.is(Items.ENCHANTED_GOLDEN_APPLE)) return;
        StandData d = StandManager.data(p);
        if (d.getType() != StandType.ZOMBIFIED_PIGLIN || !d.bodyRestored || d.pigActMax >= 3) return;
        d.bodyRestored = false;
        StandEntity old = StandManager.getStand(p);
        Vec3 at = old != null ? old.position() : p.position();
        moveTo(p, StandType.PIGLIN, true);
        ServerLevel sl = p.serverLevel();
        sl.sendParticles(ParticleTypes.WAX_ON, at.x, at.y + 0.8D, at.z, 60, 0.5D, 0.8D, 0.5D, 0.1D);
        sl.sendParticles(ParticleTypes.END_ROD, at.x, at.y + 0.8D, at.z, 30, 0.4D, 0.8D, 0.4D, 0.08D);
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ZOMBIE_VILLAGER_CURE, SoundSource.PLAYERS, 1.0F, 1.0F);
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PIGLIN_CELEBRATE, SoundSource.PLAYERS, 1.2F, 1.0F);
        title(p, StandType.PIGLIN);
        p.sendSystemMessage(Component.literal("Body and soul, restored. Your Stand is reborn, golden and new: ACT 3 unlocked.")
                .withStyle(s -> s.withColor(0xF2C94C)));
    }
}
