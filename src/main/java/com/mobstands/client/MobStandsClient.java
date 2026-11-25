package com.mobstands.client;

import com.mobstands.MobStands;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

/** Client-only entry point (never loaded on a dedicated server). */
@Mod(value = MobStands.MODID, dist = Dist.CLIENT)
public class MobStandsClient {
    public MobStandsClient(IEventBus modBus, ModContainer container) {
        modBus.addListener(ClientSetup::registerRenderers);
        modBus.addListener(ClientSetup::registerKeys);
        modBus.addListener(ClientSetup::registerGuiLayers);
        modBus.addListener(ClientSetup::registerParticles);
        NeoForge.EVENT_BUS.register(ClientEvents.class);
        NeoForge.EVENT_BUS.register(HeartStopHud.class);
        NeoForge.EVENT_BUS.register(ZipperFx.class);
        NeoForge.EVENT_BUS.register(TimeHeld.class);
        NeoForge.EVENT_BUS.register(GooFx.class);
        NeoForge.EVENT_BUS.register(RavagerForm.class);
        NeoForge.EVENT_BUS.register(DinoClient.class);
        NeoForge.EVENT_BUS.register(IllusionFx.class);
        NeoForge.EVENT_BUS.register(CartRide.class);
        NeoForge.EVENT_BUS.register(GerCutscene.class);
        NeoForge.EVENT_BUS.register(LoveTrainFx.class);
        NeoForge.EVENT_BUS.register(HorseBalls.class);
        NeoForge.EVENT_BUS.register(GravityClient.class);
        NeoForge.EVENT_BUS.register(GravityFlick.class);
        NeoForge.EVENT_BUS.register(RainClient.class);
        NeoForge.EVENT_BUS.register(FuseClient.class);
        NeoForge.EVENT_BUS.register(ClashClient.class);
    }
}
