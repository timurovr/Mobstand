package com.mobstands;

import com.mobstands.config.StandConfig;
import com.mobstands.event.StandEvents;
import com.mobstands.network.ModNetwork;
import com.mobstands.registry.ModAttachments;
import com.mobstands.registry.ModEntities;
import com.mobstands.registry.ModItems;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import org.slf4j.Logger;

/** Common (client + server) entry point. */
@Mod(MobStands.MODID)
public class MobStands {
    public static final String MODID = "mobstands";
    public static final Logger LOGGER = LogUtils.getLogger();

    public MobStands(IEventBus modBus, ModContainer container) {
        ModEntities.ENTITIES.register(modBus);
        ModItems.ITEMS.register(modBus);
        ModAttachments.ATTACHMENT_TYPES.register(modBus);
        com.mobstands.registry.ModSounds.SOUNDS.register(modBus);
        com.mobstands.registry.ModParticles.PARTICLES.register(modBus);

        modBus.addListener(ModNetwork::register);
        modBus.addListener(MobStands::addCreativeItems);

        container.registerConfig(ModConfig.Type.COMMON, StandConfig.SPEC);

        NeoForge.EVENT_BUS.register(StandEvents.class);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }

    private static void addCreativeItems(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.COMBAT) {
            event.accept(ModItems.ECHO_ARROW.get());
            event.accept(ModItems.STEEL_BALL.get());
            event.accept(ModItems.SPIN_SCROLL.get());
        }
    }
}
