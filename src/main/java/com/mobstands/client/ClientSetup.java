package com.mobstands.client;

import com.mobstands.MobStands;
import com.mobstands.registry.ModEntities;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

public final class ClientSetup {
    private ClientSetup() {}

    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.STAND.get(), StandRenderer::new);
        event.registerEntityRenderer(ModEntities.STAND_PROJECTILE.get(), NoopRenderer::new);
        event.registerEntityRenderer(ModEntities.SPIN_ITEM.get(), SpinItemRenderer::new);
        event.registerEntityRenderer(ModEntities.HEART_ATTACK.get(), HeartAttackRenderer::new);
        event.registerEntityRenderer(ModEntities.NULL_BUBBLE.get(), NullBubbleRenderer::new);
        event.registerEntityRenderer(ModEntities.NUGGET_SHOT.get(), NuggetShotRenderer::new);
        event.registerEntityRenderer(ModEntities.ZIPPER.get(), ZipperRenderer::new);
        event.registerEntityRenderer(ModEntities.CURSE.get(), CurseRenderer::new);
        event.registerEntityRenderer(ModEntities.TRAIN_CAR.get(), TrainCarRenderer::new);
    }

    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(StandKeys.SUMMON);
        event.register(StandKeys.BASIC);
        event.register(StandKeys.ABILITY1);
        event.register(StandKeys.ABILITY2);
        event.register(StandKeys.ULTIMATE);
        event.register(StandKeys.ABILITY4);
        event.register(StandKeys.ABILITY5);
        event.register(StandKeys.INFO);
        event.register(StandKeys.POSE);
    }

    public static void registerParticles(net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(com.mobstands.registry.ModParticles.HAMON_SPARK.get(), HamonSparkParticle.Provider::new);
    }

    public static void registerGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(MobStands.id("rush_fx"), RushFx::render);
        event.registerAboveAll(MobStands.id("spin_cue"), SpinInput::render);
        event.registerAboveAll(MobStands.id("stand_hud"), StandHud::render);
        event.registerAboveAll(MobStands.id("curse_hud"), CurseHud::render);
        event.registerAboveAll(MobStands.id("stand_tool_slot"), StandToolSlot::render);
        event.registerAboveAll(MobStands.id("pilot_heat"), PilotClient::render);
        event.registerAboveAll(MobStands.id("rain_timer"), RainClient::render);
    }
}
