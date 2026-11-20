package com.mobstands.item;

import com.mobstands.stand.StandManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * A Spin Scroll: the Zeppeli family's teachings. Read it (right-click) to learn the Spin - and how to forge a Steel
 * Ball (the recipe appears in your recipe book). The Spin is a technique for people without a Stand.
 */
public class SpinScrollItem extends Item {
    public SpinScrollItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player instanceof ServerPlayer sp) {
            var d = StandManager.data(sp);
            if (d.getType() != null) {
                sp.displayClientMessage(Component.literal("You can't learn the Spin while you have a Stand.").withStyle(s -> s.withColor(0xE8C25A)), true);
                return InteractionResultHolder.fail(stack);
            }
            if (d.spinLearned) {
                sp.displayClientMessage(Component.literal("You already know the Spin.").withStyle(s -> s.withColor(0xE8C25A)), true);
                return InteractionResultHolder.fail(stack);
            }
            StandManager.learnSpin(sp);
            if (!sp.getAbilities().instabuild) stack.shrink(1);
        }
        player.swing(hand, true);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("Right-click to learn the Spin").withStyle(s -> s.withColor(0xE8C25A)));
        tooltip.add(Component.literal("Also teaches you to craft a Steel Ball").withStyle(s -> s.withColor(0x8A8A8A)));
        tooltip.add(Component.literal("Only for those without a Stand").withStyle(s -> s.withColor(0x8A8A8A)));
    }
}
