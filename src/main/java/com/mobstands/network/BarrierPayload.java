package com.mobstands.network;

import com.mobstands.MobStands;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server -> client: GER's Golden Barrier went up here (the clients draw its curtain of light). */
public record BarrierPayload(double x, double y, double z, float radius, int ticks, long seed) implements CustomPacketPayload {
    public static final Type<BarrierPayload> TYPE = new Type<>(MobStands.id("golden_barrier"));
    public static final StreamCodec<ByteBuf, BarrierPayload> CODEC = StreamCodec.of(
            (buf, p) -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                b.writeDouble(p.x);
                b.writeDouble(p.y);
                b.writeDouble(p.z);
                b.writeFloat(p.radius);
                b.writeVarInt(p.ticks);
                b.writeLong(p.seed);
            },
            buf -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                return new BarrierPayload(b.readDouble(), b.readDouble(), b.readDouble(), b.readFloat(), b.readVarInt(), b.readLong());
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BarrierPayload p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.mobstands.client.LoveTrainFx.add(p));
    }
}
