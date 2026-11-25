package com.mobstands.network;

import com.mobstands.MobStands;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server -> a player caught in someone's Bites the Dust: play the whole rewind on your screen too. */
public record RewoundPayload() implements CustomPacketPayload {
    public static final Type<RewoundPayload> TYPE = new Type<>(MobStands.id("rewound"));
    public static final StreamCodec<ByteBuf, RewoundPayload> CODEC = StreamCodec.unit(new RewoundPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RewoundPayload p, IPayloadContext ctx) {
        ctx.enqueueWork(com.mobstands.client.TimeStopFx::rewound);
    }
}
