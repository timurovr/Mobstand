package com.mobstands.network;

import com.mobstands.MobStands;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client -> server: where the pilot has flown their Phantom (Aerosmith). */
public record PilotMovePayload(double x, double y, double z, float yRot, float xRot) implements CustomPacketPayload {
    public static final Type<PilotMovePayload> TYPE = new Type<>(MobStands.id("pilot_move"));
    public static final StreamCodec<ByteBuf, PilotMovePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, PilotMovePayload::x,
            ByteBufCodecs.DOUBLE, PilotMovePayload::y,
            ByteBufCodecs.DOUBLE, PilotMovePayload::z,
            ByteBufCodecs.FLOAT, PilotMovePayload::yRot,
            ByteBufCodecs.FLOAT, PilotMovePayload::xRot,
            PilotMovePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PilotMovePayload p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer sp) com.mobstands.stand.StandManager.pilotMove(sp, p.x(), p.y(), p.z(), p.yRot(), p.xRot());
        });
    }
}
