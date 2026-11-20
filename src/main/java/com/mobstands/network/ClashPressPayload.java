package com.mobstands.network;

import com.mobstands.MobStands;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client -> server: one press of the Barrage key during a clash. */
public record ClashPressPayload() implements CustomPacketPayload {
    public static final Type<ClashPressPayload> TYPE = new Type<>(MobStands.id("clash_press"));
    public static final StreamCodec<ByteBuf, ClashPressPayload> CODEC = StreamCodec.unit(new ClashPressPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ClashPressPayload p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer sp) com.mobstands.stand.Clash.press(sp);
        });
    }
}
