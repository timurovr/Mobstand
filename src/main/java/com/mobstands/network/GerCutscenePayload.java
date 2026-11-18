package com.mobstands.network;

import com.mobstands.MobStands;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server -> client: GER's Return to Zero caught a mob. {@code cinematic} = you're the one who countered (you get the
 * camera cutscene); everyone else nearby just sees the loop.
 */
public record GerCutscenePayload(int mobId, int ownerId, double ox, double oy, double oz, int d0, int d1, int d2, boolean cinematic)
        implements CustomPacketPayload {
    public static final Type<GerCutscenePayload> TYPE = new Type<>(MobStands.id("ger_cutscene"));
    public static final StreamCodec<ByteBuf, GerCutscenePayload> CODEC = StreamCodec.of(
            (buf, p) -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                b.writeVarInt(p.mobId);
                b.writeVarInt(p.ownerId);
                b.writeDouble(p.ox);
                b.writeDouble(p.oy);
                b.writeDouble(p.oz);
                b.writeVarInt(p.d0);
                b.writeVarInt(p.d1);
                b.writeVarInt(p.d2);
                b.writeBoolean(p.cinematic);
            },
            buf -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                return new GerCutscenePayload(b.readVarInt(), b.readVarInt(), b.readDouble(), b.readDouble(), b.readDouble(),
                        b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readBoolean());
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(GerCutscenePayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.mobstands.client.GerCutscene.start(payload));
    }
}
