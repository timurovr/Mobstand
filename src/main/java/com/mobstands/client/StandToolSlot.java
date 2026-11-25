package com.mobstands.client;

import com.mobstands.network.ClientStandCache;
import com.mobstands.stand.StandData;
import com.mobstands.stand.StandTools;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** The extra slot beside your hotbar holding your Stand's tool (lit up while it's in your hand). */
public final class StandToolSlot {
    private StandToolSlot() {}

    private static final ResourceLocation SLOT_LEFT = ResourceLocation.withDefaultNamespace("hud/hotbar_offhand_left");
    private static final ResourceLocation SLOT_RIGHT = ResourceLocation.withDefaultNamespace("hud/hotbar_offhand_right");
    private static final ResourceLocation SELECTED = ResourceLocation.withDefaultNamespace("hud/hotbar_selection");

    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || mc.player.isSpectator()) return;
        StandData d = ClientStandCache.get();
        Item tool = StandTools.toolFor(d.getType());
        if (tool == null || !d.isSummoned()) return;
        int w = g.guiWidth(), h = g.guiHeight();
        // on the other side of the hotbar from the off-hand slot
        boolean offLeft = mc.player.getMainArm() == HumanoidArm.RIGHT;
        int x = offLeft ? w / 2 + 91 + 1 : w / 2 - 91 - 29 - 1;
        if (offLeft && !mc.player.getOffhandItem().isEmpty()) x += 0; // (off hand is on the left: this side's free)
        int y = h - 23;
        g.blitSprite(offLeft ? SLOT_RIGHT : SLOT_LEFT, x, y, 29, 24);
        boolean out = mc.player.getMainHandItem().is(tool);
        int ix = x + (offLeft ? 10 : 3), iy = y + 4;
        g.renderItem(new ItemStack(tool), ix, iy);
        if (out) g.blitSprite(SELECTED, ix - 4, iy - 4, 24, 23);
        g.drawString(mc.font, KeyLabels.of(StandKeys.SUMMON), ix + 12, iy + 9, 0xFFDDDDDD, true);
    }
}
