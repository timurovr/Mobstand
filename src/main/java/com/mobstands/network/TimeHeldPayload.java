package com.mobstands.network;

import com.mobstands.MobStands;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server -> a player: you're caught in someone's stopped time (true) / time moves again (false). */
public record TimeHeldPayload(boolean held) implements CustomPacketPayload {
    public static final Type<TimeHeldPayload> TYPE = new Type<>(MobStands.id("time_held"));
    public static final StreamCodec<ByteBuf, TimeHeldPayload> CODEC = ByteBufCodecs.BOOL.map(TimeHeldPayload::new, TimeHeldPayload::held);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(TimeHeldPayload p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.mobstands.client.TimeHeld.set(p.held));
    }
}
