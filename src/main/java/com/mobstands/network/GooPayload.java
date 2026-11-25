package com.mobstands.network;

import com.mobstands.MobStands;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server -> players: this creature is being eaten by Yellow Masquerade's goo for {@code ticks} more (0 = it's off). */
public record GooPayload(int entityId, int ticks) implements CustomPacketPayload {
    public static final Type<GooPayload> TYPE = new Type<>(MobStands.id("goo"));
    public static final StreamCodec<ByteBuf, GooPayload> CODEC = StreamCodec.of(
            (buf, p) -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                b.writeVarInt(p.entityId);
                b.writeVarInt(p.ticks);
            },
            buf -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                return new GooPayload(b.readVarInt(), b.readVarInt());
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(GooPayload p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.mobstands.client.GooFx.eaten(p.entityId, p.ticks));
    }
}
