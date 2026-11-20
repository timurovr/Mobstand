package com.mobstands.network;

import com.mobstands.MobStands;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server -> clients: a mob with Volatility is swelling up like a creeper (fuse 0..30). */
public record FusePayload(int entityId, int fuse) implements CustomPacketPayload {
    public static final Type<FusePayload> TYPE = new Type<>(MobStands.id("fuse"));
    public static final StreamCodec<ByteBuf, FusePayload> CODEC = StreamCodec.of(
            (buf, p) -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                b.writeVarInt(p.entityId);
                b.writeByte(p.fuse);
            },
            buf -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                return new FusePayload(b.readVarInt(), b.readByte());
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(FusePayload p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.mobstands.client.FuseClient.onFuse(p.entityId, p.fuse));
    }
}
