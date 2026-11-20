package com.mobstands.network;

import com.mobstands.MobStands;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server -> a Dolphin user: how many ticks the rain has left (0 = not raining). */
public record RainTimePayload(int ticks) implements CustomPacketPayload {
    public static final Type<RainTimePayload> TYPE = new Type<>(MobStands.id("rain_time"));
    public static final StreamCodec<ByteBuf, RainTimePayload> CODEC = StreamCodec.of(
            (buf, p) -> new FriendlyByteBuf(buf).writeVarInt(p.ticks),
            buf -> new RainTimePayload(new FriendlyByteBuf(buf).readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RainTimePayload p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.mobstands.client.RainClient.onTime(p.ticks));
    }
}
