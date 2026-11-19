package com.mobstands.stand;

public record Ability(String name, String description, int unlockLevel, int baseCooldownTicks, AbilityAction action) {

    /** An unused slot (the Stand simply has no ability on that key). Hidden in the HUD and info screen. */
    public static Ability none() {
        return new Ability("", "", 1000, 0, c -> false);
    }

    public boolean isEmpty() {
        return name.isEmpty();
    }

    /** Cooldown shrinks with each Stand level, down to 30% off at max level (10). */
    public int cooldownTicks(int standLevel) {
        double reduction = Math.min(0.30D, 0.30D / 9.0D * (standLevel - 1));
        return Math.max(4, (int) (baseCooldownTicks * (1.0D - reduction)));
    }
}
