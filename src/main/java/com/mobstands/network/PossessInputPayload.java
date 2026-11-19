package com.mobstands.network;

import com.mobstands.MobStands;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client -> server: Soul Control keys and look. */
public record PossessInputPayload(float forward, float strafe, boolean jump, boolean down, float yaw, float pitch) implements CustomPacketPayload {
    public static final Type<PossessInputPayload> TYPE = new Type<>(MobStands.id("possess_input"));
    public static final StreamCodec<ByteBuf, PossessInputPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.FLOAT, PossessInputPayload::forward,
            ByteBufCodecs.FLOAT, PossessInputPayload::strafe,
            ByteBufCodecs.BOOL, PossessInputPayload::jump,
            ByteBufCodecs.BOOL, PossessInputPayload::down,
            ByteBufCodecs.FLOAT, PossessInputPayload::yaw,
            ByteBufCodecs.FLOAT, PossessInputPayload::pitch,
            PossessInputPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PossessInputPayload p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer sp)
                com.mobstands.stand.SoulControl.input(sp, p.forward(), p.strafe(), p.jump(), p.down(), p.yaw(), p.pitch());
        });
    }
}
