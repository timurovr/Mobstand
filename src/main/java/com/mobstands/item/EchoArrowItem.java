package com.mobstands.item;

import com.mobstands.stand.StandData;
import com.mobstands.stand.StandManager;
import com.mobstands.stand.StandType;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * The only way to get a Stand. Right click: awakens a random Stand (every Stand equally likely) if you have none,
 * otherwise rerolls your Stand into a different one (level and XP reset).
 */
public class EchoArrowItem extends Item {
    public EchoArrowItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide || !(player instanceof ServerPlayer sp)) {
            return InteractionResultHolder.success(stack);
        }
        StandData d = StandManager.data(sp);
        // Sneak + use: give your Stand up. It fades out of you for good (the arrow isn't used up).
        if (sp.isShiftKeyDown()) {
            if (d.getType() == null) return InteractionResultHolder.fail(stack);
            net.minecraft.server.level.ServerLevel sl = sp.serverLevel();
            var st = StandManager.getStand(sp);
            net.minecraft.world.phys.Vec3 at = st != null ? st.position() : sp.position();
            sl.sendParticles(net.minecraft.core.particles.ParticleTypes.POOF, at.x, at.y + 1.0D, at.z, 30, 0.4D, 0.7D, 0.4D, 0.03D);
            sl.sendParticles(net.minecraft.core.particles.ParticleTypes.SCULK_SOUL, at.x, at.y + 1.0D, at.z, 12, 0.3D, 0.6D, 0.3D, 0.02D);
            sl.playSound(null, sp.getX(), sp.getY(), sp.getZ(), net.minecraft.sounds.SoundEvents.SOUL_ESCAPE.value(),
                    net.minecraft.sounds.SoundSource.PLAYERS, 1.5F, 0.7F);
            sl.playSound(null, sp.getX(), sp.getY(), sp.getZ(), net.minecraft.sounds.SoundEvents.BEACON_DEACTIVATE,
                    net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 0.8F);
            StandManager.removeEntity(sp);
            d.setType(null);
            d.resetProgress();
            d.setSummoned(false);
            StandManager.sync(sp);
            sp.getCooldowns().addCooldown(this, 20);
            return InteractionResultHolder.success(stack);
        }
        // An Iron Golem Stand user, in a raid, at death's door: the arrow may awaken its Requiem (or reject you).
        if (com.mobstands.stand.Ger.tryAwaken(sp)) {
            if (!sp.getAbilities().instabuild) stack.shrink(1);
            sp.getCooldowns().addCooldown(this, 20);
            return InteractionResultHolder.success(stack);
        }
        StandType type = StandManager.roll(sp, d.getType());
        StandManager.assign(sp, type);
        announce(sp, type);
        if (!sp.getAbilities().instabuild) stack.shrink(1);
        sp.getCooldowns().addCooldown(this, 20);
        return InteractionResultHolder.success(stack);
    }

    private static void announce(ServerPlayer sp, StandType type) {
        ServerLevel level = (ServerLevel) sp.level();
        level.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.TRIDENT_HIT, SoundSource.PLAYERS, 0.8F, 0.7F);
        level.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 1.0F, 0.6F);
        sp.sendSystemMessage(Component.literal("Your Stand awakens: " + type.standName() + " (" + type.mobName() + ", "
                + type.kindLabel() + ")").withStyle(s -> s.withColor(type.color())));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("Awakens a Stand").withStyle(s -> s.withColor(0xAAAAAA)));
    }
}
