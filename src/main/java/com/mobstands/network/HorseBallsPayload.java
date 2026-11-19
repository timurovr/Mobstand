package com.mobstands.network;

import com.mobstands.MobStands;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server -> client: Steel Balls spinning under a horse (balls = 1 or 2; 0 = they're gone). */
public record HorseBallsPayload(int horseId, int balls, int ticks) implements CustomPacketPayload {
    public static final Type<HorseBallsPayload> TYPE = new Type<>(MobStands.id("horse_balls"));
    public static final StreamCodec<ByteBuf, HorseBallsPayload> CODEC = StreamCodec.of(
            (buf, p) -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                b.writeVarInt(p.horseId);
                b.writeVarInt(p.balls);
                b.writeVarInt(p.ticks);
            },
            buf -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                return new HorseBallsPayload(b.readVarInt(), b.readVarInt(), b.readVarInt());
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(HorseBallsPayload p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.mobstands.client.HorseBalls.set(p));
    }
}
