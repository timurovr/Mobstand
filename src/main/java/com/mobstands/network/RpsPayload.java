package com.mobstands.network;

import com.mobstands.MobStands;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server -> a Boy II Man victim: rock-paper-scissors time (your wins and losses so far, its taunt, ticks to pick). */
public record RpsPayload(int wins, int losses, String taunt, int timeout) implements CustomPacketPayload {
    public static final Type<RpsPayload> TYPE = new Type<>(MobStands.id("rps"));
    public static final StreamCodec<ByteBuf, RpsPayload> CODEC = StreamCodec.of(
            (buf, p) -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                b.writeVarInt(p.wins);
                b.writeVarInt(p.losses);
                b.writeUtf(p.taunt);
                b.writeVarInt(p.timeout);
            },
            buf -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                return new RpsPayload(b.readVarInt(), b.readVarInt(), b.readUtf(), b.readVarInt());
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RpsPayload p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.mobstands.client.RpsScreen.open(p.wins, p.losses, p.taunt, p.timeout));
    }
}
