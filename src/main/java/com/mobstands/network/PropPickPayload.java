package com.mobstands.network;

import com.mobstands.MobStands;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client -> server: what you took in the Property Swap menu (0 = its own property, 1.. = Sight, Friction...; -1 = closed). */
public record PropPickPayload(int choice) implements CustomPacketPayload {
    public static final Type<PropPickPayload> TYPE = new Type<>(MobStands.id("prop_pick"));
    public static final StreamCodec<ByteBuf, PropPickPayload> CODEC = StreamCodec.of(
            (buf, p) -> new FriendlyByteBuf(buf).writeByte(p.choice),
            buf -> new PropPickPayload(new FriendlyByteBuf(buf).readByte()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PropPickPayload p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer sp) com.mobstands.stand.PropertySwap.pick(sp, p.choice);
        });
    }
}
