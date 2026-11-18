package com.mobstands.entity;

import com.mobstands.stand.StandType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Client-side record of which players currently have their Stand out (works for everyone you can see,
 * since Stand entities are synced to all nearby players). Plain Java so it is safe to load on any side.
 */
public final class StandPresence {
    private record Entry(StandType type, long time, boolean posing, boolean lifting, boolean riding, int boost, boolean dead, int boostEnd) {}

    private static final Map<UUID, Entry> ACTIVE = new HashMap<>();

    private StandPresence() {}

    public static void mark(UUID owner, StandType type, long gameTime, boolean posing, boolean lifting) {
        mark(owner, type, gameTime, posing, lifting, false);
    }

    public static void mark(UUID owner, StandType type, long gameTime, boolean posing, boolean lifting, boolean riding) {
        mark(owner, type, gameTime, posing, lifting, riding, 0);
    }

    public static void mark(UUID owner, StandType type, long gameTime, boolean posing, boolean lifting, boolean riding, int boost) {
        mark(owner, type, gameTime, posing, lifting, riding, boost, false);
    }

    public static void mark(UUID owner, StandType type, long gameTime, boolean posing, boolean lifting, boolean riding, int boost, boolean dead) {
        mark(owner, type, gameTime, posing, lifting, riding, boost, dead, 0);
    }

    public static void mark(UUID owner, StandType type, long gameTime, boolean posing, boolean lifting, boolean riding, int boost, boolean dead, int boostEnd) {
        ACTIVE.put(owner, new Entry(type, gameTime, posing, lifting, riding, boost, dead, boostEnd));
    }

    /** When the current cart boost runs out (game time). */
    public static int cartBoostEnd(UUID owner) {
        Entry e = ACTIVE.get(owner);
        return e == null ? 0 : e.boostEnd();
    }

    /** True while this player is lying "dead" (the Axolotl's Fake Funeral). */
    public static boolean playingDead(UUID owner, long gameTime) {
        Entry e = ACTIVE.get(owner);
        return e != null && e.dead() && gameTime - e.time() <= 4 && gameTime >= e.time();
    }

    /** Minecart Stand boost in effect: 0 none, 1 Powered Rails, 2 Nitro. */
    public static int cartBoost(UUID owner, long gameTime) {
        Entry e = ACTIVE.get(owner);
        return e != null && e.riding() && gameTime - e.time() <= 4 && gameTime >= e.time() ? e.boost() : 0;
    }

    /** True while this player is riding their Minecart Stand. */
    public static boolean riding(UUID owner, long gameTime) {
        Entry e = ACTIVE.get(owner);
        return e != null && e.riding() && gameTime - e.time() <= 4 && gameTime >= e.time();
    }

    /** True while this player's Allay is carrying them (they hang from it, arms up). */
    public static boolean lifting(UUID owner, long gameTime) {
        Entry e = ACTIVE.get(owner);
        return e != null && e.lifting() && gameTime - e.time() <= 4 && gameTime >= e.time();
    }

    /** True if this player's Stand is out and they've struck their pose (P). */
    public static boolean posing(UUID owner, long gameTime) {
        Entry e = ACTIVE.get(owner);
        return e != null && e.posing() && gameTime - e.time() <= 4 && gameTime >= e.time();
    }

    /** The owner's Stand type if it was seen in the last few ticks, else null. */
    public static StandType get(UUID owner, long gameTime) {
        Entry e = ACTIVE.get(owner);
        if (e == null) return null;
        if (gameTime - e.time() > 4 || gameTime < e.time()) {
            ACTIVE.remove(owner);
            return null;
        }
        return e.type();
    }
}
