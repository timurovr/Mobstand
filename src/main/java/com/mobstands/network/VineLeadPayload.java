package com.mobstands.network;

import com.mobstands.MobStands;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server -> one player: where the Spirit Photograph's ground vine points (on = false: it's gone). */
public record VineLeadPayload(boolean on, Vec3 to) implements CustomPacketPayload {
    public static final Type<VineLeadPayload> TYPE = new Type<>(MobStands.id("vine_lead"));
    public static final StreamCodec<ByteBuf, VineLeadPayload> CODEC = StreamCodec.of(
            (buf, p) -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                b.writeBoolean(p.on);
                b.writeDouble(p.to.x); b.writeDouble(p.to.y); b.writeDouble(p.to.z);
            },
            buf -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                return new VineLeadPayload(b.readBoolean(), new Vec3(b.readDouble(), b.readDouble(), b.readDouble()));
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(VineLeadPayload p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.mobstands.client.VineFx.setLead(p.on ? p.to : null));
    }
}
