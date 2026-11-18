package com.mobstands.stand;

public enum StandRarity {
    COMMON("Common", 50, 1.00, 0xBFBFBF),
    UNCOMMON("Uncommon", 28, 1.10, 0x55FF55),
    RARE("Rare", 14, 1.25, 0x55AAFF),
    EPIC("Epic", 6, 1.45, 0xB35CFF),
    LEGENDARY("Legendary", 2, 1.75, 0xFFAA00);

    private final String label;
    private final int weight;
    private final double power;
    private final int color;

    StandRarity(String label, int weight, double power, int color) {
        this.label = label;
        this.weight = weight;
        this.power = power;
        this.color = color;
    }

    public String label() { return label; }
    /** Roll weight per Stand of this rarity. */
    public int weight() { return weight; }
    /** Damage/effect multiplier. Rarer Stands hit harder. */
    public double power() { return power; }
    public int color() { return color; }
}
