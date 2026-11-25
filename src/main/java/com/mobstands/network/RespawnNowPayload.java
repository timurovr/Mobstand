package com.mobstands.network;

import com.mobstands.MobStands;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server -> a dead player: time just wound back - press Respawn for them (the server puts them back where they were). */
public record RespawnNowPayload() implements CustomPacketPayload {
    public static final Type<RespawnNowPayload> TYPE = new Type<>(MobStands.id("respawn_now"));
    public static final StreamCodec<ByteBuf, RespawnNowPayload> CODEC = StreamCodec.unit(new RespawnNowPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RespawnNowPayload p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player != null && mc.player.isDeadOrDying()) {
                mc.player.respawn();
                mc.setScreen(null);
            }
        });
    }
}
