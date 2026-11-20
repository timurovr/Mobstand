package com.mobstands.network;

import com.mobstands.MobStands;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client -> server: the arrow picked in C-Moon's Gravity Punch wheel (0 sky, 1 left, 2 right, 3 back at you, 4 away). */
public record GravityPickPayload(int choice) implements CustomPacketPayload {
    public static final Type<GravityPickPayload> TYPE = new Type<>(MobStands.id("gravity_pick"));
    public static final StreamCodec<ByteBuf, GravityPickPayload> CODEC = StreamCodec.of(
            (buf, p) -> new FriendlyByteBuf(buf).writeByte(p.choice),
            buf -> new GravityPickPayload(new FriendlyByteBuf(buf).readByte()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(GravityPickPayload p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            if (p.choice >= 10) com.mobstands.stand.GravityFlip.turnSelf(sp, p.choice - 10);
            else com.mobstands.stand.GravityFlip.arm(sp, p.choice);
        });
    }
}
