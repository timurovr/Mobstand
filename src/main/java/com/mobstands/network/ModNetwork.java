package com.mobstands.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class ModNetwork {
    private ModNetwork() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(StandActionPayload.TYPE, StandActionPayload.CODEC, StandActionPayload::handle);
        registrar.playToClient(SyncStandPayload.TYPE, SyncStandPayload.CODEC, SyncStandPayload::handle);
        registrar.playToClient(GerCutscenePayload.TYPE, GerCutscenePayload.CODEC, GerCutscenePayload::handle);
        registrar.playToClient(BarrierPayload.TYPE, BarrierPayload.CODEC, BarrierPayload::handle);
        registrar.playToClient(HorseBallsPayload.TYPE, HorseBallsPayload.CODEC, HorseBallsPayload::handle);
        registrar.playToClient(SniffRollPayload.TYPE, SniffRollPayload.CODEC, SniffRollPayload::handle);
        registrar.playToClient(PilotHeatPayload.TYPE, PilotHeatPayload.CODEC, PilotHeatPayload::handle);
        registrar.playToClient(PossessPayload.TYPE, PossessPayload.CODEC, PossessPayload::handle);
        registrar.playToServer(PossessInputPayload.TYPE, PossessInputPayload.CODEC, PossessInputPayload::handle);
        registrar.playToServer(PilotMovePayload.TYPE, PilotMovePayload.CODEC, PilotMovePayload::handle);
        registrar.playToClient(GravityPayload.TYPE, GravityPayload.CODEC, GravityPayload::handle);
        registrar.playToClient(RainTimePayload.TYPE, RainTimePayload.CODEC, RainTimePayload::handle);
        registrar.playToClient(PropMenuPayload.TYPE, PropMenuPayload.CODEC, PropMenuPayload::handle);
        registrar.playToServer(PropPickPayload.TYPE, PropPickPayload.CODEC, PropPickPayload::handle);
        registrar.playToClient(FusePayload.TYPE, FusePayload.CODEC, FusePayload::handle);
        registrar.playToClient(ClashPayload.TYPE, ClashPayload.CODEC, ClashPayload::handle);
        registrar.playToServer(ClashPressPayload.TYPE, ClashPressPayload.CODEC, ClashPressPayload::handle);
        registrar.playToServer(GravityPickPayload.TYPE, GravityPickPayload.CODEC, GravityPickPayload::handle);
        registrar.playToServer(GravitySwitchPayload.TYPE, GravitySwitchPayload.CODEC, GravitySwitchPayload::handle);
        registrar.playToServer(SniffPickPayload.TYPE, SniffPickPayload.CODEC, SniffPickPayload::handle);
        registrar.playToClient(HorseLaunchPayload.TYPE, HorseLaunchPayload.CODEC, HorseLaunchPayload::handle);
    }
}
