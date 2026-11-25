package com.mobstands.network;

import com.mobstands.MobStands;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * Server -> players: Scary Monsters' infection on a creature. It started turning at game time {@code start} and is a
 * dinosaur after {@code duration} ticks; {@code owner} is whose herd it joins. {@code duration} 0 = it's clean again.
 */
public record DinoPayload(int entityId, long start, int duration, UUID owner) implements CustomPacketPayload {
    public static final Type<DinoPayload> TYPE = new Type<>(MobStands.id("dino"));
    public static final StreamCodec<ByteBuf, DinoPayload> CODEC = StreamCodec.of(
            (buf, p) -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                b.writeVarInt(p.entityId);
                b.writeLong(p.start);
                b.writeVarInt(p.duration);
                b.writeUUID(p.owner);
            },
            buf -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                return new DinoPayload(b.readVarInt(), b.readLong(), b.readVarInt(), b.readUUID());
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(DinoPayload p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.mobstands.client.DinoClient.set(p.entityId, p.start, p.duration, p.owner));
    }
}
