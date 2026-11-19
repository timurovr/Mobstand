package com.mobstands.network;

import com.mobstands.MobStands;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client -> server: the structure picked from Sniff Out ("" = none of these). */
public record SniffPickPayload(String id) implements CustomPacketPayload {
    public static final Type<SniffPickPayload> TYPE = new Type<>(MobStands.id("sniff_pick"));
    public static final StreamCodec<ByteBuf, SniffPickPayload> CODEC =
            StreamCodec.composite(ByteBufCodecs.STRING_UTF8, SniffPickPayload::id, SniffPickPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SniffPickPayload p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer sp) com.mobstands.stand.Sniff.pick(sp, p.id());
        });
    }
}
