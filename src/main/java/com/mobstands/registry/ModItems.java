package com.mobstands.registry;

import com.mobstands.MobStands;
import com.mobstands.item.EchoArrowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MobStands.MODID);


    public static final DeferredItem<EchoArrowItem> ECHO_ARROW = ITEMS.register("echo_arrow",
            () -> new EchoArrowItem(new Item.Properties().stacksTo(16).rarity(Rarity.UNCOMMON)));

    /** Gyro's Steel Ball: only a Spin master can spin it. Comes back after a throw and hits much harder. */
    public static final DeferredItem<com.mobstands.item.SteelBallItem> STEEL_BALL = ITEMS.register("steel_ball",
            () -> new com.mobstands.item.SteelBallItem(new Item.Properties().stacksTo(2).rarity(Rarity.EPIC)));

    /** Spin Scroll: read it to learn the Spin (and the Steel Ball recipe). */
    public static final DeferredItem<com.mobstands.item.SpinScrollItem> SPIN_SCROLL = ITEMS.register("spin_scroll",
            () -> new com.mobstands.item.SpinScrollItem(new Item.Properties().stacksTo(1).rarity(Rarity.RARE)));

    /** Stand tools: only exist in your hand while your Stand's out (see StandTools). */
    public static final DeferredItem<com.mobstands.item.StandToolItem> LASSO = ITEMS.register("lasso",
            () -> new com.mobstands.item.StandToolItem(new Item.Properties().stacksTo(1).rarity(Rarity.RARE), com.mobstands.stand.StandTools.LASSO));
    public static final DeferredItem<com.mobstands.item.StandToolItem> REEF_ROD = ITEMS.register("reef_rod",
            () -> new com.mobstands.item.StandToolItem(new Item.Properties().stacksTo(1).rarity(Rarity.RARE), com.mobstands.stand.StandTools.ROD));

    private ModItems() {}
}
