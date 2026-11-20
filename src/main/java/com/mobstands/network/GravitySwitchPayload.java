package com.mobstands.network;

import com.mobstands.MobStands;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client -> server: with Gravity Shift on, you walked into a wall and your gravity turned onto it. */
public record GravitySwitchPayload(int dir) implements CustomPacketPayload {
    public static final Type<GravitySwitchPayload> TYPE = new Type<>(MobStands.id("gravity_switch"));
    public static final StreamCodec<ByteBuf, GravitySwitchPayload> CODEC = StreamCodec.of(
            (buf, p) -> new FriendlyByteBuf(buf).writeByte(p.dir),
            buf -> new GravitySwitchPayload(new FriendlyByteBuf(buf).readByte()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(GravitySwitchPayload p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer sp) com.mobstands.stand.GravityFlip.requestSwitch(sp, p.dir);
        });
    }
}
