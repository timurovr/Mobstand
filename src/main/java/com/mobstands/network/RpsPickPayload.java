package com.mobstands.network;

import com.mobstands.MobStands;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client -> server: your hand against Boy II Man (0 rock, 1 paper, 2 scissors, -1 = closed it without picking). */
public record RpsPickPayload(int choice) implements CustomPacketPayload {
    public static final Type<RpsPickPayload> TYPE = new Type<>(MobStands.id("rps_pick"));
    public static final StreamCodec<ByteBuf, RpsPickPayload> CODEC = StreamCodec.of(
            (buf, p) -> new FriendlyByteBuf(buf).writeByte(p.choice),
            buf -> new RpsPickPayload(new FriendlyByteBuf(buf).readByte()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RpsPickPayload p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer sp) com.mobstands.stand.Curses.pick(sp, p.choice);
        });
    }
}
