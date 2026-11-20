package com.mobstands.network;

import com.mobstands.MobStands;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server -> client: your Barrage Clash (meter -1..1, + = you're winning; time left 1..0; the other Stand's colour). */
public record ClashPayload(boolean active, float meter, float timeLeft, int rivalColor) implements CustomPacketPayload {
    public static final Type<ClashPayload> TYPE = new Type<>(MobStands.id("clash"));
    public static final StreamCodec<ByteBuf, ClashPayload> CODEC = StreamCodec.of(
            (buf, p) -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                b.writeBoolean(p.active);
                b.writeFloat(p.meter);
                b.writeFloat(p.timeLeft);
                b.writeInt(p.rivalColor);
            },
            buf -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                return new ClashPayload(b.readBoolean(), b.readFloat(), b.readFloat(), b.readInt());
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ClashPayload p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.mobstands.client.ClashClient.onPayload(p.active, p.meter, p.timeLeft, p.rivalColor));
    }
}
