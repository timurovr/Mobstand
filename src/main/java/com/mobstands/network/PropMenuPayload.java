package com.mobstands.network;

import com.mobstands.MobStands;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server -> a Pigstep user: open the Property Swap menu on this creature (its own property's name and colour). */
public record PropMenuPayload(int entityId, String own, int color) implements CustomPacketPayload {
    public static final Type<PropMenuPayload> TYPE = new Type<>(MobStands.id("prop_menu"));
    public static final StreamCodec<ByteBuf, PropMenuPayload> CODEC = StreamCodec.of(
            (buf, p) -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                b.writeVarInt(p.entityId);
                b.writeUtf(p.own);
                b.writeInt(p.color);
            },
            buf -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                return new PropMenuPayload(b.readVarInt(), b.readUtf(), b.readInt());
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PropMenuPayload p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.mobstands.client.PropMenuScreen.open(p.entityId, p.own, p.color));
    }
}
