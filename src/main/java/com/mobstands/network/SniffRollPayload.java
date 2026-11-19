package com.mobstands.network;

import com.mobstands.MobStands;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/** Server -> client: Sniff Out rolled (three options, every structure here for the reels, or the search). */
public record SniffRollPayload(List<String> options, List<String> pool, boolean search, int misses) implements CustomPacketPayload {
    public static final Type<SniffRollPayload> TYPE = new Type<>(MobStands.id("sniff_roll"));
    public static final StreamCodec<ByteBuf, SniffRollPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), SniffRollPayload::options,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), SniffRollPayload::pool,
            ByteBufCodecs.BOOL, SniffRollPayload::search,
            ByteBufCodecs.VAR_INT, SniffRollPayload::misses,
            SniffRollPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SniffRollPayload p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.mobstands.client.SniffScreen.open(p));
    }
}
