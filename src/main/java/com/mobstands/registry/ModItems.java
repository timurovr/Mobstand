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

    private ModItems() {}
}
