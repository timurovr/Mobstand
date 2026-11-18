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

/** Gyro's Steel Ball. Right-click it once to learn the Spin technique; a Spin master can spin it, and it comes back. */
public class SteelBallItem extends Item {
    public SteelBallItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        boolean learned = level.isClientSide ? com.mobstands.network.ClientStandCache.get().spinLearned
                : StandManager.data((ServerPlayer) player).spinLearned;
        if (learned) return InteractionResultHolder.pass(stack);
        if (player instanceof ServerPlayer sp) StandManager.learnSpin(sp);
        player.swing(hand, true);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("Right-click to learn the Spin").withStyle(s -> s.withColor(0xE8C25A)));
        tooltip.add(Component.literal("Only a Spin master can spin it").withStyle(s -> s.withColor(0x8A8A8A)));
        tooltip.add(Component.literal("Spinning on horseback: right-click to put the Spin in its legs").withStyle(s -> s.withColor(0x8A8A8A)));
    }
}
