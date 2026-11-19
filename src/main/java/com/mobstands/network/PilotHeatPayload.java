package com.mobstands.network;

import com.mobstands.MobStands;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server -> pilot: how much the Phantom's gun has left before it overheats (0..100), and whether it has. */
public record PilotHeatPayload(int left, boolean overheated) implements CustomPacketPayload {
    public static final Type<PilotHeatPayload> TYPE = new Type<>(MobStands.id("pilot_heat"));
    public static final StreamCodec<ByteBuf, PilotHeatPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, PilotHeatPayload::left,
            ByteBufCodecs.BOOL, PilotHeatPayload::overheated,
            PilotHeatPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PilotHeatPayload p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.mobstands.client.PilotClient.setHeat(p.left(), p.overheated()));
    }
}
