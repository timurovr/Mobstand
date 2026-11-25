package com.mobstands.network;

import com.mobstands.MobStands;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server -> the Pigstep user: you just crossed into another world ({@code enter}) or back into your own. */
public record AnotherWorldPayload(boolean enter) implements CustomPacketPayload {
    public static final Type<AnotherWorldPayload> TYPE = new Type<>(MobStands.id("another_world"));
    public static final StreamCodec<ByteBuf, AnotherWorldPayload> CODEC = StreamCodec.of(
            (buf, p) -> new FriendlyByteBuf(buf).writeBoolean(p.enter),
            buf -> new AnotherWorldPayload(new FriendlyByteBuf(buf).readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(AnotherWorldPayload p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.mobstands.client.AnotherWorldFx.cross(p.enter));
    }
}
