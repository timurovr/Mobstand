package com.mobstands.network;

import com.mobstands.MobStands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import io.netty.buffer.ByteBuf;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server -> client: the owner's full Stand data (type, level, XP, cooldown end times, summoned). */
public record SyncStandPayload(CompoundTag tag) implements CustomPacketPayload {
    public static final Type<SyncStandPayload> TYPE = new Type<>(MobStands.id("sync_stand"));
    public static final StreamCodec<ByteBuf, SyncStandPayload> CODEC =
            StreamCodec.composite(ByteBufCodecs.COMPOUND_TAG, SyncStandPayload::tag, SyncStandPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SyncStandPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ClientStandCache.set(payload.tag()));
    }
}
