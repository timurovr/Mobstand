package com.mobstands.stand;

import com.mobstands.entity.StandProjectile;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.EntityType;

/**
 * Every Stand "species". Each one defines its own abilities, progression (unlock levels, passive perk),
 * visual theme and rarity.
 */
public enum StandType {
    SLIME("slime", "Jelly Rebound", "Slime", StandRarity.COMMON, 0x7EFF6E, EntityType.SLIME,
            ParticleTypes.ITEM_SLIME, 0.06, 0.0, 2.0, null,
            StandAbilities.slime(),
            new Passive(4, "Gelatinous Body", "You take no fall damage while your Stand is out.")),

    SKELETON("skeleton", "Rattling Marksman", "Skeleton", StandRarity.COMMON, 0xE8E8E8, EntityType.SKELETON,
            ParticleTypes.WHITE_ASH, 0.06, 0.05, 1.0, StandProjectile.Kind.BONE_ARROW,
            StandAbilities.skeleton(),
            new Passive(4, "Tireless Bones", "Permanent Haste I while your Stand is out.")),

    SPIDER("spider", "Weaver of Webs", "Spider", StandRarity.UNCOMMON, 0xC0392B, EntityType.SPIDER,
            ParticleTypes.ITEM_COBWEB, 0.07, 0.0, 0.8, null,
            StandAbilities.spider(),
            new Passive(4, "Eight Legs", "Permanent Jump Boost I while your Stand is out.")),

    CREEPER("creeper", "Green Detonator", "Creeper", StandRarity.UNCOMMON, 0x4CFF4C, EntityType.CREEPER,
            ParticleTypes.HAPPY_VILLAGER, 0.07, 0.05, 1.05, null,
            StandAbilities.creeper(),
            new Passive(4, "Blast Proof", "Explosions deal 60% less damage to you while your Stand is out.")),

    BLAZE("blaze", "Ember Sovereign", "Blaze", StandRarity.RARE, 0xFFA030, EntityType.BLAZE,
            ParticleTypes.FLAME, 0.08, 0.1, 1.0, StandProjectile.Kind.FIREBALL,
            StandAbilities.blaze(),
            new Passive(4, "Heat Shield", "Permanent Fire Resistance while your Stand is out.")),

    IRON_GOLEM("iron_golem", "Iron Colossus", "Iron Golem", StandRarity.RARE, 0xD0D8E8, EntityType.IRON_GOLEM,
            ParticleTypes.CRIT, 0.08, 0.3, 0.85, null,
            StandAbilities.ironGolem(),
            new Passive(4, "Iron Skin", "Permanent Resistance I while your Stand is out.")),

    // Iron Golem Requiem, Gold Experience Requiem style: a protector whose defense is infinite.
    GER("guardian_requiem", "Guardian Experience Requiem", "Iron Golem", StandRarity.LEGENDARY, 0xF2C744, EntityType.IRON_GOLEM,
            ParticleTypes.CRIT, 0.08, 0.3, 0.9, null,
            StandAbilities.ger(),
            new Passive(1, "Infinite Guard", "Permanent Resistance II while your Stand is out.")),

    PHANTOM("phantom", "Midnight Wing", "Phantom", StandRarity.RARE, 0x6F8CFF, EntityType.PHANTOM,
            ParticleTypes.SOUL, 0.08, 1.95, 1.25, null,
            StandAbilities.phantom(),
            new Passive(99, "", "")),

    ENDERMAN("enderman", "Void Stalker", "Enderman", StandRarity.EPIC, 0xC066FF, EntityType.ENDERMAN,
            ParticleTypes.PORTAL, 0.09, 0.4, 0.9, null,
            StandAbilities.enderman(),
            new Passive(4, "Fleet Step", "Permanent Speed I while your Stand is out.")),

    HORSE("horse", "Gallop Crown", "Horse", StandRarity.COMMON, 0xC08A55, EntityType.HORSE,
            ParticleTypes.CLOUD, 0.0, 0.0, 1.1, null,
            new Ability[]{Ability.none(), Ability.none(), Ability.none(), Ability.none()},
            new Passive(99, "", "")),

    ALLAY("allay", "Little Lullaby", "Allay", StandRarity.UNCOMMON, 0x6FE0FF, EntityType.ALLAY,
            ParticleTypes.NOTE, 0.06, 1.15, 1.25, null,
            StandAbilities.allay(),
            new Passive(99, "", "")),

    PIG("pig", "Muddy Fortune", "Pig", StandRarity.COMMON, 0xF0A0A0, EntityType.PIG,
            ParticleTypes.HAPPY_VILLAGER, 0.0, 0.0, 0.9, null,
            StandAbilities.pig(),
            new Passive(99, "", "")),

    // Only obtainable by a Pig Stand being struck by lightning (never rolled).
    ZOMBIFIED_PIGLIN("zombified_piglin", "Thunderborn Swine", "Zombified Piglin", StandRarity.RARE, 0x7FA35A, EntityType.ZOMBIFIED_PIGLIN,
            ParticleTypes.ELECTRIC_SPARK, 0.08, 0.05, 1.0, null,
            StandAbilities.zombifiedPiglin(),
            new Passive(99, "", "")),

    PIGLIN("piglin", "Gilded Swine", "Piglin", StandRarity.RARE, 0xF2C94C, EntityType.PIGLIN,
            ParticleTypes.WAX_ON, 0.08, 0.05, 1.0, null,
            StandAbilities.piglin(),
            new Passive(99, "", "")),

    // Piglin Requiem, Soft & Wet style: it steals the sound out of a creature - and the power that came with it.
    PIGLIN_REQUIEM("gilded_silence", "Gilded Silence", "Piglin Brute", StandRarity.LEGENDARY, 0xB07CFF, EntityType.PIGLIN_BRUTE,
            ParticleTypes.WAX_ON, 0.08, 0.05, 1.0, null,
            StandAbilities.piglinRequiem(),
            new Passive(99, "", "")),


    AXOLOTL("axolotl", "Lazarus Smile", "Axolotl", StandRarity.LEGENDARY, 0xF7A1C4, EntityType.AXOLOTL,
            ParticleTypes.HEART, 0.0, 0.9, 1.0, null,
            StandAbilities.axolotl(),
            new Passive(99, "", "")),

    SQUID("squid", "Weeping Deluge", "Squid", StandRarity.RARE, 0x5B8FD6, EntityType.SQUID,
            ParticleTypes.DRIPPING_WATER, 0.08, 0.0, 3.0, null,
            StandAbilities.squid(),
            new Passive(4, "Tidebound", "Permanent Water Breathing while your Stand is out.")),

    // The World, Enderman-style: a clock on its chest, and it can stop time.
    ENDER_WORLD("ender_world", "The Ender World", "Enderman", StandRarity.LEGENDARY, 0xFFD54A, EntityType.ENDERMAN,
            ParticleTypes.END_ROD, 0.10, 0.4, 0.95, null,
            StandAbilities.enderWorld(),
            new Passive(4, "Fleet Step", "Permanent Speed I while your Stand is out.")),

    // Sniffs out structures - a gamble every time.
    SNIFFER("sniffer", "Ancient Nose", "Sniffer", StandRarity.RARE, 0x5FD18A, EntityType.SNIFFER,
            ParticleTypes.COMPOSTER, 0.05, 0.0, 0.5, null,
            StandAbilities.sniffer(),
            new Passive(99, "", "")),

    MINECART("minecart", "Iron Express", "Minecart", StandRarity.UNCOMMON, 0xA7B0B8, EntityType.MINECART,
            ParticleTypes.CRIT, 0.06, 0.3, 0.9, null,
            StandAbilities.minecart(),
            new Passive(99, "", "")),

    WARDEN("warden", "Deep Dark Monarch", "Warden", StandRarity.LEGENDARY, 0x1FB8C4, EntityType.WARDEN,
            ParticleTypes.SCULK_SOUL, 0.10, 0.3, 0.8, null,
            StandAbilities.warden(),
            new Passive(5, "Deep Senses", "Permanent Night Vision while your Stand is out."));

    public record Passive(int level, String name, String description) {}

    private final String id;
    private final String standName;
    private final String mobName;
    private final StandRarity rarity;
    private final int color;
    private final EntityType<?> vanilla;
    private final ParticleOptions aura;
    private final double powerPerLevel;
    private final double hover;
    private final double scale;
    private final StandProjectile.Kind autoKind;
    private final Ability[] abilities;
    private final Passive passive;

    StandType(String id, String standName, String mobName, StandRarity rarity, int color, EntityType<?> vanilla,
              ParticleOptions aura, double powerPerLevel, double hover, double scale,
              StandProjectile.Kind autoKind, Ability[] abilities, Passive passive) {
        if (abilities.length > StandData.SLOTS || abilities.length < 4) throw new IllegalStateException("Stand needs 4 or 5 abilities: " + id);
        if (abilities.length < StandData.SLOTS) {
            // Most Stands have 4 abilities; the 5th key (V) is empty for them.
            Ability[] padded = java.util.Arrays.copyOf(abilities, StandData.SLOTS);
            for (int i = abilities.length; i < padded.length; i++) padded[i] = Ability.none();
            abilities = padded;
        }
        this.id = id;
        this.standName = standName;
        this.mobName = mobName;
        this.rarity = rarity;
        this.color = color;
        this.vanilla = vanilla;
        this.aura = aura;
        this.powerPerLevel = powerPerLevel;
        this.hover = hover;
        this.scale = scale;
        this.autoKind = autoKind;
        this.abilities = abilities;
        this.passive = passive;
    }

    public String id() { return id; }
    public String standName() { return standName; }
    public String mobName() { return mobName; }
    public StandRarity rarity() { return rarity; }
    public int color() { return color; }
    /** The vanilla mob this Stand is modelled on (used only client-side for the model). */
    public EntityType<?> vanilla() { return vanilla; }
    public ParticleOptions aura() { return aura; }
    public double powerPerLevel() { return powerPerLevel; }
    /** How high above the ground the Stand floats. */
    public double hover() { return hover; }
    public double scale() { return scale; }
    /** Non-null for Stands whose auto-assist is ranged. */
    public StandProjectile.Kind autoKind() { return autoKind; }
    public Passive passive() { return passive; }
    public Ability ability(int slot) { return abilities[slot]; }

    /**
     * Regular-sized Stands stand pressed right up behind their user, head beside the user's head,
     * instead of floating a block away.
     */
    public boolean closeBehind() {
        return this == SKELETON || this == CREEPER || this == BLAZE;
    }

    /** Stands whose basic attack is a melee strike (everyone except the archer and the fireball thrower). */
    public boolean meleeBasic() {
        return this != SKELETON && hasBasic();
    }

    /** Stands with a basic attack on right click (the Squid and the Axolotl don't fight with their own body). */
    public boolean hasBasic() {
        return this != SQUID && this != AXOLOTL && this != HORSE && this != PIG && !isCart();
    }

    /**
     * Can come from a random roll. The Piglin forms only come from a Pig Stand struck by lightning, and Requiems are
     * never rolled: each one has to be earned.
     */
    public boolean rollable() {
        return this != PIGLIN && this != ZOMBIFIED_PIGLIN && !requiem();
    }

    /** Where in the world this Stand comes from. Stand Arrows only roll the pools you've reached. */
    public enum Pool { OVERWORLD, NETHER, END }

    public Pool pool() {
        return switch (this) {
            case BLAZE, PIGLIN, ZOMBIFIED_PIGLIN, PIGLIN_REQUIEM -> Pool.NETHER;
            default -> Pool.OVERWORLD;
        };
    }

    /** Only the niche Stands get the sparkle + glow materialize effect, so it stays special. */
    public boolean summonEffects() {
        return this == IRON_GOLEM || this == GER || this == ENDERMAN || this == ENDER_WORLD || this == WARDEN || this == AXOLOTL;
    }

    /** Intimidating Stands get the menacing blade sound when they appear. */
    public boolean menacing() {
        return this == WARDEN || this == ENDERMAN || this == ENDER_WORLD || this == IRON_GOLEM || this == GER || this == AXOLOTL;
    }

    /** Requiem Stands: already complete. No levels - every ability is there from the start, at full strength. */
    public boolean requiem() {
        return this == ENDER_WORLD || this == GER || this == PIGLIN_REQUIEM;
    }

    /** A bit of story for the Stand menu (empty if there's none). */
    public String lore() {
        return switch (this) {
            case ENDER_WORLD -> "An ordinary Enderman can only teleport: it vanishes from one place and appears in another, "
                    + "and the instant in between is nothing to it. The Ender World is a Requiem: it can stay inside that instant - "
                    + "step into the teleport itself and move around in it. To everyone else, the world simply stops. "
                    + "That is Time Stop: not freezing time, but living inside the space between one teleport and the next.";
            case HORSE -> "Some ran the great race on horseback. One ran it on foot - and kept up. This Stand doesn't carry you: "
                    + "it teaches your body to run the way a horse runs. Triple-tap sprint, and the ground itself can't hold you back.";
            case GER -> "An Iron Golem exists for one reason: to protect the village. When its user stands at the edge of death "
                    + "with the village burning around them, that purpose reaches its final form. Guardian Experience Requiem doesn't "
                    + "block attacks - it nullifies them. Any action aimed at what it protects is returned to zero, as if it never "
                    + "happened. Whoever tried it is left reliving that moment, dying again and again, never arriving anywhere. "
                    + "Its defense isn't strong. It's infinite.";
            case PIGLIN_REQUIEM -> "A Piglin takes whatever it wants. Its Requiem learned that the things worth stealing aren't only "
                    + "the ones you can hold: it steals the sound out of a creature - and with the sound, the power that came with it. "
                    + "A creeper with no hiss can't explode. But you can.";
            default -> "";
        };
    }

    /** The Minecart Stands (the Iron Express and its Requiem): you ride them. */
    public boolean isCart() {
        return this == MINECART;
    }

    /** Only Stands with real fists get the ORA barrage (multi-hit flurry + flying fists). */
    public boolean barrage() {
        return this == IRON_GOLEM || this == GER || this == ENDERMAN || this == ENDER_WORLD || this == WARDEN;
    }

    /** Ticks the Skeleton spends drawing its bow before the basic shot is released. */
    public static final int BOW_DRAW_TICKS = 13;

    public static StandType byId(String id) {
        if (id == null) return null;
        if (id.equals("heaven_express")) return MINECART; // removed Requiem: back to the Iron Express
        for (StandType t : values()) {
            if (t.id.equals(id)) return t;
        }
        return null;
    }
}
