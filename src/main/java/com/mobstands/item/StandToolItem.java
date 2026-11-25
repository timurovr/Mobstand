package com.mobstands.item;

import com.mobstands.stand.StandTools;
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
 * A Stand you hold in your hand: Oh! Lonesome Llama's lasso, or Reef Boy's fishing rod. It only exists while it's out
 * (R with your Stand out) - it can't be dropped, stored or kept.
 */
public class StandToolItem extends Item {
    private final int kind;

    public StandToolItem(Properties props, int kind) {
        super(props);
        this.kind = kind;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player instanceof ServerPlayer sp) {
            if (kind == StandTools.LASSO) StandTools.lasso(sp);
            else StandTools.rod(sp, stack);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        if (kind == StandTools.LASSO) {
            tooltip.add(Component.literal("Right-click a creature: rope it (right-click again to yank)").withStyle(s -> s.withColor(0xD9B48A)));
            tooltip.add(Component.literal("Right-click a block: zip to it").withStyle(s -> s.withColor(0xD9B48A)));
        } else {
            tooltip.add(Component.literal("Right-click: cast - the line goes through walls").withStyle(s -> s.withColor(0x5FC9B8)));
            tooltip.add(Component.literal("Hooked: right-click to reel; or cast into water to fish").withStyle(s -> s.withColor(0x5FC9B8)));
        }
        tooltip.add(Component.literal("Shift + right-click: let go").withStyle(s -> s.withColor(0x8A8A8A)));
        tooltip.add(Component.literal("Your Stand - R to put it away").withStyle(s -> s.withColor(0x8A8A8A)));
    }
}
