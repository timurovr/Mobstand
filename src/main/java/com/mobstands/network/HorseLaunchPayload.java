package com.mobstands.network;

import com.mobstands.MobStands;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server -> the rider: the sky toss launches your horse. Your own game moves the horse you ride, so the launch is
 * applied there too - otherwise a running horse could swallow it. (push = forward push, up = upward speed)
 */
public record HorseLaunchPayload(int horseId, float pushX, float pushZ, float up) implements CustomPacketPayload {
    public static final Type<HorseLaunchPayload> TYPE = new Type<>(MobStands.id("horse_launch"));
    public static final StreamCodec<ByteBuf, HorseLaunchPayload> CODEC = StreamCodec.of(
            (buf, p) -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                b.writeVarInt(p.horseId);
                b.writeFloat(p.pushX);
                b.writeFloat(p.pushZ);
                b.writeFloat(p.up);
            },
            buf -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                return new HorseLaunchPayload(b.readVarInt(), b.readFloat(), b.readFloat(), b.readFloat());
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(HorseLaunchPayload p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.mobstands.client.HorseBalls.launch(p));
    }
}
