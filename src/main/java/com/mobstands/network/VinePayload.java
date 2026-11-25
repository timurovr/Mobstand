package com.mobstands.network;

import com.mobstands.MobStands;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server -> nearby players: draw one of Hermit Purple's vines from a to b. */
public record VinePayload(Vec3 a, Vec3 b, int life, float thick, boolean ground) implements CustomPacketPayload {
    public static final Type<VinePayload> TYPE = new Type<>(MobStands.id("vine"));
    public static final StreamCodec<ByteBuf, VinePayload> CODEC = StreamCodec.of(
            (buf, p) -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                b.writeDouble(p.a.x); b.writeDouble(p.a.y); b.writeDouble(p.a.z);
                b.writeDouble(p.b.x); b.writeDouble(p.b.y); b.writeDouble(p.b.z);
                b.writeVarInt(p.life);
                b.writeFloat(p.thick);
                b.writeBoolean(p.ground);
            },
            buf -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                Vec3 a = new Vec3(b.readDouble(), b.readDouble(), b.readDouble());
                Vec3 c = new Vec3(b.readDouble(), b.readDouble(), b.readDouble());
                return new VinePayload(a, c, b.readVarInt(), b.readFloat(), b.readBoolean());
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(VinePayload p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.mobstands.client.VineFx.add(p.a, p.b, p.life, p.thick, p.ground));
    }
}
