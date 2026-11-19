package com.mobstands.network;

import com.mobstands.MobStands;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server -> client: Soul Control started (the mob's id) or ended (-1). */
public record PossessPayload(int mobId) implements CustomPacketPayload {
    public static final Type<PossessPayload> TYPE = new Type<>(MobStands.id("possess"));
    public static final StreamCodec<ByteBuf, PossessPayload> CODEC =
            StreamCodec.composite(ByteBufCodecs.VAR_INT, PossessPayload::mobId, PossessPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PossessPayload p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.mobstands.client.PossessClient.set(p.mobId()));
    }
}
