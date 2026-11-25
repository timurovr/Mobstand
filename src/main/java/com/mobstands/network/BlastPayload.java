package com.mobstands.network;

import com.mobstands.MobStands;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server -> nearby clients: a creeper Stand's blast went off here (fireball, shockwave rings, camera shake). */
public record BlastPayload(double x, double y, double z, float power, boolean requiem) implements CustomPacketPayload {
    public static final Type<BlastPayload> TYPE = new Type<>(MobStands.id("blast"));
    public static final StreamCodec<ByteBuf, BlastPayload> CODEC = StreamCodec.of(
            (buf, p) -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                b.writeDouble(p.x);
                b.writeDouble(p.y);
                b.writeDouble(p.z);
                b.writeFloat(p.power);
                b.writeBoolean(p.requiem);
            },
            buf -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                return new BlastPayload(b.readDouble(), b.readDouble(), b.readDouble(), b.readFloat(), b.readBoolean());
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BlastPayload p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.mobstands.client.BlastFx.add(p.x, p.y, p.z, p.power, p.requiem));
    }
}
