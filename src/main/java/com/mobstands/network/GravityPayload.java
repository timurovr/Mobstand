package com.mobstands.network;

import com.mobstands.MobStands;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server -> clients: C-Moon turned this creature's gravity (dir = Direction 3D id, -1 = back to normal). */
public record GravityPayload(int entityId, int dir) implements CustomPacketPayload {
    public static final Type<GravityPayload> TYPE = new Type<>(MobStands.id("gravity"));
    public static final StreamCodec<ByteBuf, GravityPayload> CODEC = StreamCodec.of(
            (buf, p) -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                b.writeVarInt(p.entityId);
                b.writeByte(p.dir);
            },
            buf -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                return new GravityPayload(b.readVarInt(), b.readByte());
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(GravityPayload p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.mobstands.client.GravityClient.onPayload(p.entityId, p.dir));
    }
}
