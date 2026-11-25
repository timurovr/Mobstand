package com.mobstands.network;

import com.mobstands.MobStands;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server -> players: draw (or stop drawing) a lasso rope / fishing line from a player to a creature or a spot. */
public record RopePayload(int owner, int target, double x, double y, double z, int kind, boolean on) implements CustomPacketPayload {
    public static final Type<RopePayload> TYPE = new Type<>(MobStands.id("rope"));
    public static final StreamCodec<ByteBuf, RopePayload> CODEC = StreamCodec.of(
            (buf, p) -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                b.writeVarInt(p.owner);
                b.writeVarInt(p.target + 1);
                b.writeDouble(p.x); b.writeDouble(p.y); b.writeDouble(p.z);
                b.writeVarInt(p.kind);
                b.writeBoolean(p.on);
            },
            buf -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                return new RopePayload(b.readVarInt(), b.readVarInt() - 1, b.readDouble(), b.readDouble(), b.readDouble(), b.readVarInt(), b.readBoolean());
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RopePayload p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.mobstands.client.RopeFx.set(p.owner, p.target, p.x, p.y, p.z, p.kind, p.on));
    }
}
