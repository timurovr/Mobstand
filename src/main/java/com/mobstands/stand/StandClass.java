package com.mobstands.stand;

/**
 * What kind of Stand it is - not how good it is. Every Stand rolls just as often and hits just as hard; they're
 * different, not better or worse.
 */
public enum StandClass {
    CLOSE_RANGE("Close-Range Power", 0xFF6B5B),
    LONG_RANGE("Long-Range", 0x5BB8FF),
    AUTOMATIC("Automatic", 0xFFB347),
    RANGE_IRRELEVANT("Range Irrelevant", 0xC77DFF),
    SUPPORT("Support", 0x7CFC9A),
    EQUIPMENT("Equipment", 0xE8C872),
    COLONY("Colony", 0xC9D1D9),
    MOUNT("Mount", 0xD9A066);

    private final String label;
    private final int color;

    StandClass(String label, int color) {
        this.label = label;
        this.color = color;
    }

    public String label() { return label; }
    public int color() { return color; }

    /** Damage multiplier: the same for every Stand. Requiems are a finished form, and hit harder. */
    public static double power(StandType t) {
        return t.requiem() ? 1.75D : 1.35D;
    }
}
