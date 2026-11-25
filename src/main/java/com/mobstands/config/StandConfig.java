package com.mobstands.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class StandConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue BLOCK_DAMAGE;
    public static final ModConfigSpec.DoubleValue DAMAGE_MULTIPLIER;
    public static final ModConfigSpec.BooleanValue AUTO_ASSIST;
    public static final ModConfigSpec.BooleanValue PANIC_SUMMON;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        BLOCK_DAMAGE = b.comment("If true, Stand explosions (Creeper Stand) destroy blocks like a real creeper. Default: true.")
                .define("explosionsDestroyBlocks", true);
        DAMAGE_MULTIPLIER = b.comment("Global multiplier applied to all Stand damage.")
                .defineInRange("damageMultiplier", 1.0D, 0.1D, 10.0D);
        AUTO_ASSIST = b.comment("No longer used: Stands never attack on their own (kept so old config files still load).")
                .define("autoAssist", false);
        PANIC_SUMMON = b.comment("Hitting mobs rapidly (with anything, e.g. a pickaxe) makes your Stand appear and fight with you.")
                .define("panicSummon", true);
        SPEC = b.build();
    }

    private StandConfig() {}
}
