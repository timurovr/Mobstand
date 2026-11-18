package com.mobstands.network;

import com.mobstands.stand.StandData;
import net.minecraft.nbt.CompoundTag;

/** Client copy of the local player's StandData. Contains no client-only classes, so it is safe on any side. */
public final class ClientStandCache {
    private static StandData data = new StandData();

    private ClientStandCache() {}

    public static StandData get() {
        return data;
    }

    public static void set(CompoundTag tag) {
        StandData d = new StandData();
        d.load(tag);
        data = d;
    }

    public static void clear() {
        data = new StandData();
    }
}
