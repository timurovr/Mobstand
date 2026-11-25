package com.mobstands.stand;

import com.mobstands.entity.StandProjectile;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.EntityType;

/**
 * Every Stand "species". Each one defines its own abilities, progression (unlock levels, passive perk),
 * visual theme and kind of Stand (see StandClass).
 */
public enum StandType {
    // Yellow Temperance, Slime-style - green: a flesh-eating goo you wear as armour, that can take any shape.
    SLIME("slime", "Green Temperance", "Slime", 0x7EFF6E, EntityType.SLIME,
            ParticleTypes.ITEM_SLIME, 0.06, 0.0, 2.0, null,
            StandAbilities.slime(),
            new Passive(4, "Gelatinous Body", "You take no fall damage while your Stand is out - you bounce back up instead, like off a slime block. Sneak as you land to stop the bounce.")),

    // Creeper Requiem, a charged creeper - Killer Queen: Bites the Dust style. Never rolled: lightning makes it.
    CREEPER_REQUIEM("creeper_requiem", "Green Detonator: Bites the Dust", "Charged Creeper", 0x7FD4FF, EntityType.CREEPER,
            ParticleTypes.ELECTRIC_SPARK, 0.08, 0.05, 1.05, null,
            StandAbilities.creeperRequiem(),
            new Passive(1, "Blast Proof", "Explosions deal 60% less damage to you while your Stand is out.")),

    SKELETON("skeleton", "Rattling Marksman", "Skeleton", 0xE8E8E8, EntityType.SKELETON,
            ParticleTypes.WHITE_ASH, 0.06, 0.05, 1.0, StandProjectile.Kind.BONE_ARROW,
            StandAbilities.skeleton(),
            new Passive(4, "Tireless Bones", "Permanent Haste I while your Stand is out.")),

    SPIDER("spider", "Weaver of Webs", "Spider", 0xC0392B, EntityType.SPIDER,
            ParticleTypes.ITEM_COBWEB, 0.07, 0.0, 0.8, null,
            StandAbilities.spider(),
            new Passive(4, "Eight Legs", "Permanent Jump Boost I while your Stand is out.")),

    CREEPER("creeper", "Green Detonator", "Creeper", 0x4CFF4C, EntityType.CREEPER,
            ParticleTypes.HAPPY_VILLAGER, 0.07, 0.05, 1.05, null,
            StandAbilities.creeper(),
            new Passive(4, "Blast Proof", "Explosions deal 60% less damage to you while your Stand is out.")),

    BLAZE("blaze", "Ember Sovereign", "Blaze", 0xFFA030, EntityType.BLAZE,
            ParticleTypes.FLAME, 0.08, 0.1, 1.0, StandProjectile.Kind.FIREBALL,
            StandAbilities.blaze(),
            new Passive(4, "Heat Shield", "Permanent Fire Resistance while your Stand is out.")),

    IRON_GOLEM("iron_golem", "Iron Colossus", "Iron Golem", 0xD0D8E8, EntityType.IRON_GOLEM,
            ParticleTypes.CRIT, 0.08, 0.3, 0.85, null,
            StandAbilities.ironGolem(),
            new Passive(4, "Iron Skin", "Permanent Resistance I while your Stand is out.")),

    // Iron Golem Requiem, Gold Experience Requiem style: a protector whose defense is infinite.
    GER("guardian_requiem", "Guardian Experience Requiem", "Iron Golem", 0xF2C744, EntityType.IRON_GOLEM,
            ParticleTypes.CRIT, 0.08, 0.3, 0.9, null,
            StandAbilities.ger(),
            new Passive(1, "Infinite Guard", "Permanent Resistance II while your Stand is out.")),

    PHANTOM("phantom", "Midnight Wing", "Phantom", 0x6F8CFF, EntityType.PHANTOM,
            ParticleTypes.SOUL, 0.08, 1.95, 1.25, null,
            StandAbilities.phantom(),
            new Passive(99, "", "")),

    ENDERMAN("enderman", "Void Stalker", "Enderman", 0xC066FF, EntityType.ENDERMAN,
            ParticleTypes.PORTAL, 0.09, 0.4, 0.9, null,
            StandAbilities.enderman(),
            new Passive(4, "Fleet Step", "Permanent Speed I while your Stand is out.")),

    HORSE("horse", "Gallop Crown", "Horse", 0xC08A55, EntityType.HORSE,
            ParticleTypes.CLOUD, 0.0, 0.0, 1.1, null,
            new Ability[]{Ability.none(), Ability.none(), Ability.none(), Ability.none()},
            new Passive(1, "Trample", "While you Rush, anything you run into is trampled: knocked flying and hurt.")),

    ALLAY("allay", "Little Lullaby", "Allay", 0x6FE0FF, EntityType.ALLAY,
            ParticleTypes.NOTE, 0.06, 1.15, 1.25, null,
            StandAbilities.allay(),
            new Passive(99, "", "")),

    PIG("pig", "Muddy Fortune", "Pig", 0xF0A0A0, EntityType.PIG,
            ParticleTypes.HAPPY_VILLAGER, 0.0, 0.0, 0.9, null,
            StandAbilities.pig(),
            new Passive(99, "", "")),

    // Only obtainable by a Pig Stand being struck by lightning (never rolled).
    ZOMBIFIED_PIGLIN("zombified_piglin", "Thunderborn Swine", "Zombified Piglin", 0x7FA35A, EntityType.ZOMBIFIED_PIGLIN,
            ParticleTypes.ELECTRIC_SPARK, 0.08, 0.05, 1.0, null,
            StandAbilities.zombifiedPiglin(),
            new Passive(99, "", "")),

    PIGLIN("piglin", "Gilded Swine", "Piglin", 0xF2C94C, EntityType.PIGLIN,
            ParticleTypes.WAX_ON, 0.08, 0.05, 1.0, null,
            StandAbilities.piglin(),
            new Passive(99, "", "")),

    // Piglin Requiem, Soft & Wet style: it steals the sound out of a creature - and the power that came with it.
    PIGLIN_REQUIEM("gilded_silence", "Pigstep", "Piglin Brute", 0xB07CFF, EntityType.PIGLIN_BRUTE,
            ParticleTypes.WAX_ON, 0.08, 0.05, 1.0, null,
            StandAbilities.piglinRequiem(),
            new Passive(99, "", "")),


    AXOLOTL("axolotl", "Lazarus Smile", "Axolotl", 0xF7A1C4, EntityType.AXOLOTL,
            ParticleTypes.HEART, 0.0, 0.9, 1.0, null,
            StandAbilities.axolotl(),
            new Passive(99, "", "")),

    SQUID("squid", "Weeping Deluge", "Squid", 0x5B8FD6, EntityType.SQUID,
            ParticleTypes.DRIPPING_WATER, 0.08, 0.0, 3.0, null,
            StandAbilities.squid(),
            new Passive(4, "Tidebound", "Permanent Water Breathing while your Stand is out.")),

    // The World, Enderman-style: a clock on its chest, and it can stop time.
    ENDER_WORLD("ender_world", "The Ender World", "Enderman", 0xFFD54A, EntityType.ENDERMAN,
            ParticleTypes.END_ROD, 0.10, 0.4, 0.95, null,
            StandAbilities.enderWorld(),
            new Passive(4, "Fleet Step", "Permanent Speed I while your Stand is out.")),

    // Sniffs out structures - a gamble every time.
    SNIFFER("sniffer", "Ancient Nose", "Sniffer", 0x5FD18A, EntityType.SNIFFER,
            ParticleTypes.COMPOSTER, 0.05, 0.0, 0.5, null,
            StandAbilities.sniffer(),
            new Passive(99, "", "")),

    MINECART("minecart", "Iron Express", "Minecart", 0xA7B0B8, EntityType.MINECART,
            ParticleTypes.CRIT, 0.06, 0.3, 0.9, null,
            StandAbilities.minecart(),
            new Passive(99, "", "")),

    WARDEN("warden", "Deep Dark Monarch", "Warden", 0x1FB8C4, EntityType.WARDEN,
            ParticleTypes.SCULK_SOUL, 0.10, 0.3, 0.8, null,
            StandAbilities.warden(),
            new Passive(5, "Deep Senses", "Permanent Night Vision while your Stand is out.")),

    // Wonder of U, Warden-style (working name): nobody wants to come after you - and the ones who do meet calamity.
    WARDEN_REQUIEM("warden_requiem", "Wonder of Deep", "Warden", 0x6FF0F7, EntityType.WARDEN,
            ParticleTypes.SCULK_SOUL, 0.10, 0.3, 0.8, null,
            StandAbilities.wardenRequiem(),
            new Passive(1, "Calamity", "Monsters that come for you meet calamity faster the closer they get - none of them ever reach you. "
                    + "Other creatures mostly won't come after you (70% ignore you, 10% panic and run). The ones that do "
                    + "build up calamity: they trip, stalactites fall on them, they catch fire, lightning finds them... until it kills "
                    + "them. Hitting you makes it much worse. Players get it too, at half speed. Bosses aren't afraid of you.")),

    // Catch the Rainbow: stops raindrops in the air - walks on them, cuts with them, comes apart into them.
    DOLPHIN("dolphin", "Catch the Rainbow", "Dolphin", 0x8FC9FF, EntityType.DOLPHIN,
            ParticleTypes.SPLASH, 0.08, 0.7, 0.75, null,
            StandAbilities.dolphin(),
            new Passive(1, "Raindrop Body", "In the rain, a hit can pass right through you: your body comes apart into "
                    + "raindrops. 20% chance, up to 38% at level 10. And in water you swim like a dolphin: Dolphin's Grace "
                    + "while you're swimming.")),

    // Sticky Fingers, Silverfish-style: a swarm that tames to you as it grows, and zippers that open onto a pocket dimension.
    SILVERFISH("silverfish", "Silver Fingers", "Silverfish", 0xA9B4BF, EntityType.SILVERFISH,
            ParticleTypes.ASH, 0.08, 0.0, 1.0, null,
            StandAbilities.silverfish(),
            new Passive(5, "Living Armour", "The silverfish clinging to you take part of every hit: 20% less damage while your "
                    + "Stand is out.")),

    // Mountain Tim's Oh! Lonesome Me, Llama-style: a Stand you hold - a lasso.
    LLAMA("llama", "Oh! Lonesome Llama", "Llama", 0xD9B48A, EntityType.LLAMA,
            ParticleTypes.SPIT, 0.07, 0.0, 1.0, null,
            StandAbilities.llama(),
            new Passive(5, "Thick Wool", "Arrows and other projectiles do 20% less damage to you while your Stand is out.")),

    // Beach Boy, Guardian-style: a Stand you hold - a fishing rod whose line goes through walls.
    GUARDIAN("guardian", "Reef Boy", "Guardian", 0x5FC9B8, EntityType.GUARDIAN,
            ParticleTypes.BUBBLE, 0.07, 0.0, 1.0, null,
            StandAbilities.guardian(),
            new Passive(5, "Spiked Shell", "Anything that hits you in melee while your Stand is out gets spiked for 2 damage.")),

    // The Illusioner: a Stand that never fights fair - mirror images, blindness, vanishing, a crowd turned on itself.
    ILLUSIONER("illusioner", "Smoke and Mirrors", "Illusioner", 0x5B8DD9, EntityType.ILLUSIONER,
            ParticleTypes.CLOUD, 0.08, 0.0, 1.0, null,
            StandAbilities.illusioner(),
            new Passive(5, "Last Illusion", "About to die (under 30% health): you vanish for 3 seconds and everything chasing you "
                    + "loses you. Once a minute.")),

    // C-Moon: a little Shulker riding your shoulder, gravity turned inside out around it.
    SHULKER("shulker", "Crescent Shell", "Shulker", 0xB38BC9, EntityType.SHULKER,
            ParticleTypes.END_ROD, 0.10, 0.0, 0.35, null,
            StandAbilities.shulker(),
            new Passive(1, "Inside Out", "While your gravity is turned, walls and ceilings are your floor: you walk, "
                    + "run and jump on them like normal ground, and you never take fall damage."));

    public record Passive(int level, String name, String description) {}

    private final String id;
    private final String standName;
    private final String mobName;
    private final int color;
    private final EntityType<?> vanilla;
    private final ParticleOptions aura;
    private final double powerPerLevel;
    private final double hover;
    private final double scale;
    private final StandProjectile.Kind autoKind;
    private final Ability[] abilities;
    private final Passive passive;

    StandType(String id, String standName, String mobName, int color, EntityType<?> vanilla,
              ParticleOptions aura, double powerPerLevel, double hover, double scale,
              StandProjectile.Kind autoKind, Ability[] abilities, Passive passive) {
        if (abilities.length > StandData.SLOTS || abilities.length < 4) throw new IllegalStateException("Stand needs 4 to 6 abilities: " + id);
        if (abilities.length < StandData.SLOTS) {
            // Most Stands have 4 abilities; the 5th key (V) is empty for them.
            Ability[] padded = java.util.Arrays.copyOf(abilities, StandData.SLOTS);
            for (int i = abilities.length; i < padded.length; i++) padded[i] = Ability.none();
            abilities = padded;
        }
        this.id = id;
        this.standName = standName;
        this.mobName = mobName;
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

    /** The Pig line's Acts (Echoes style): Pig 1, Zombified Piglin 2, Piglin 3, Pigstep 4. 0 for every other Stand. */
    public int pigAct() {
        return switch (this) {
            case PIG -> 1;
            case ZOMBIFIED_PIGLIN -> 2;
            case PIGLIN -> 3;
            case PIGLIN_REQUIEM -> 4;
            default -> 0;
        };
    }

    public static StandType pigActType(int act) {
        return switch (act) {
            case 2 -> ZOMBIFIED_PIGLIN;
            case 3 -> PIGLIN;
            case 4 -> PIGLIN_REQUIEM;
            default -> PIG;
        };
    }

    /** "ACT 2" etc. for the HUD and info screen ("" for Stands without Acts). */
    public String actLabel() {
        return pigAct() > 0 ? "ACT " + pigAct() : "";
    }
    public String mobName() { return mobName; }
    /** What kind of Stand it is (they're all on the same level - just different). */
    public StandClass kind() {
        return switch (this) {
            case IRON_GOLEM, GER, ENDERMAN, ENDER_WORLD, WARDEN, PIGLIN, ZOMBIFIED_PIGLIN, CREEPER, SPIDER -> StandClass.CLOSE_RANGE;
            case SKELETON, BLAZE, PHANTOM, SQUID, ILLUSIONER -> StandClass.LONG_RANGE;
            case ALLAY -> StandClass.AUTOMATIC;
            case DOLPHIN, WARDEN_REQUIEM, CREEPER_REQUIEM, PIGLIN_REQUIEM, AXOLOTL, SHULKER -> StandClass.RANGE_IRRELEVANT;
            case PIG, SNIFFER -> StandClass.SUPPORT;
            case LLAMA, GUARDIAN -> StandClass.EQUIPMENT;
            case SLIME, SILVERFISH -> StandClass.COLONY;
            case HORSE, MINECART -> StandClass.MOUNT;
        };
    }
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
        return this == SKELETON || this == CREEPER || this == CREEPER_REQUIEM || this == BLAZE;
    }

    /** Stands whose basic attack is a melee strike (everyone except the archer and the fireball thrower). */
    public boolean meleeBasic() {
        return this != SKELETON && this != SQUID && this != SILVERFISH && hasBasic(); // (Silver Fingers bites with its swarm, not fists) // (the Squid's right click is its Tidal Slam, not a punch)
    }

    /** Stands with a basic attack on right click (the Axolotl doesn't fight with its own body). */
    public boolean hasBasic() {
        // (a creeper doesn't punch: it explodes)
        return this != AXOLOTL && this != HORSE && this != PIG && this != CREEPER && this != CREEPER_REQUIEM
                && this != WARDEN_REQUIEM && !isCart(); // (Wonder of Deep never fights with its own hands)
    }

    /**
     * Can come from a random roll. The Piglin forms only come from a Pig Stand struck by lightning, and Requiems are
     * never rolled: each one has to be earned.
     */
    public boolean rollable() {
        // (Oh! Lonesome Llama and Reef Boy: only from /stand set, for now)
        return this != PIGLIN && this != ZOMBIFIED_PIGLIN && this != LLAMA && this != GUARDIAN && !requiem();
    }

    /** Where in the world this Stand comes from. Stand Arrows only roll the pools you've reached. */
    public enum Pool { OVERWORLD, NETHER, END }

    public Pool pool() {
        return switch (this) {
            case BLAZE, PIGLIN, ZOMBIFIED_PIGLIN, PIGLIN_REQUIEM -> Pool.NETHER;
            case SHULKER -> Pool.END;
            default -> Pool.OVERWORLD;
        };
    }

    /** Only the niche Stands get the sparkle + glow materialize effect, so it stays special. */
    public boolean summonEffects() {
        return this == IRON_GOLEM || this == GER || this == ENDERMAN || this == ENDER_WORLD || this == WARDEN || this == WARDEN_REQUIEM || this == AXOLOTL
                || this == SHULKER;
    }

    /** Intimidating Stands get the menacing blade sound when they appear. */
    public boolean menacing() {
        return this == WARDEN || this == WARDEN_REQUIEM || this == ENDERMAN || this == ENDER_WORLD || this == IRON_GOLEM || this == GER || this == AXOLOTL;
    }

    /** Requiem Stands: already complete. No levels - every ability is there from the start, at full strength. */
    public boolean requiem() {
        return this == ENDER_WORLD || this == GER || this == PIGLIN_REQUIEM || this == CREEPER_REQUIEM || this == WARDEN_REQUIEM;
    }

    /** A bit of story for the Stand menu (empty if there's none). */
    public String lore() {
        return switch (this) {
            case ENDER_WORLD -> "An ordinary Enderman can only teleport: it vanishes from one place and appears in another, "
                    + "and the instant in between is nothing to it. The Ender World is a Requiem: it can stay inside that instant - "
                    + "step into the teleport itself and move around in it. To everyone else, the world simply stops. "
                    + "That is Time Stop: not freezing time, but living inside the space between one teleport and the next.";
            case LLAMA -> "A llama never goes anywhere alone - it's always roped into a caravan. Oh! Lonesome Llama is the rope: "
                    + "a lasso that's a Stand. Rope anything and it goes where you go; lasso a ledge and you go where it is.";
            case GUARDIAN -> "Guardians hunt with their eyes shut - they feel for you through the water. Reef Boy feels through "
                    + "anything: a fishing rod whose line passes straight through walls. Once it's hooked, you can feel its heart "
                    + "beating down the line.";
            case ILLUSIONER -> "Illusioners never fight as themselves - they hide behind copies, cast blindness, and are gone "
                    + "before you can swing. Smoke and Mirrors is all of that at once: you're never quite where they think you are.";
            case SLIME -> "A slime looks harmless - a little jelly cube, bouncing. Green Temperance is what a slime really is: a "
                    + "living, flesh-eating goo that wears whatever shape it likes. Wear it and it's armour no fist can get through; "
                    + "touch it and it starts to eat you.";
            case SILVERFISH -> "A silverfish lives inside the stone - it hides in the walls of the world and nobody ever finds it. "
                    + "Silver Fingers is that hiding place turned into a power: a zipper on any surface, and behind it a little purple "
                    + "pocket of nowhere. The swarm is wild at first, skittering around you; the stronger you grow, the more of them "
                    + "climb onto you and stay.";
            case HORSE -> "Some ran the great race on horseback. One ran it on foot - and kept up. This Stand doesn't carry you: "
                    + "it teaches your body to run the way a horse runs. Triple-tap sprint, and the ground itself can't hold you back.";
            case GER -> "An Iron Golem exists for one reason: to protect the village. When its user stands at the edge of death "
                    + "with the village burning around them, that purpose reaches its final form. Guardian Experience Requiem doesn't "
                    + "block attacks - it nullifies them. Any action aimed at what it protects is returned to zero, as if it never "
                    + "happened. Whoever tried it is left reliving that moment, dying again and again, never arriving anywhere. "
                    + "Its defense isn't strong. It's infinite.";
            case CREEPER_REQUIEM -> "A creeper's whole life is one moment: the hiss, the flash, gone. Struck by lightning, "
                    + "this one got that moment back - and learned to keep it. Anything it touches can become the bomb. And when "
                    + "things go wrong, it winds the world back a few seconds... but whoever hurt you still carries the charge, "
                    + "and when time snaps back into place, they bite the dust.";
            case WARDEN_REQUIEM -> "A Warden is blind. It hunts by feeling the vibrations of whatever comes near it. "
                    + "Its Requiem only feels one thing: someone coming after you. It doesn't fight them. The cave does. "
                    + "They trip, dripstone falls, their arrows turn around, and the closer they get the worse it gets. "
                    + "Nobody who chases you ever reaches you.";
            case PIGLIN_REQUIEM -> "A Piglin takes whatever it wants. Its Requiem learned that the things worth stealing aren't only "
                    + "the ones you can hold: it steals the sound out of a creature - and with the sound, the power that came with it. "
                    + "A creeper with no hiss can't explode. But you can.";
            case SPIDER -> "A spider's silk is its whole life: it builds with it, hunts with it, falls on it and climbs back up it. "
                    + "Weaver of Webs is that silk - and when its user is nearly killed, it learns what Stone Free learned: twist the "
                    + "string half a turn and join the ends, and you get a loop with only one side. The Möbius Strip. It happens by "
                    + "itself when you're badly hurt: for 20 seconds nothing can turn you around (gravity flips slide off, nothing "
                    + "knocks you back), and most of every hit runs round the loop and comes back out into whoever threw it.";
            default -> "";
        };
    }

    /** The Minecart Stands (the Iron Express and its Requiem): you ride them. */
    public boolean isCart() {
        return this == MINECART;
    }

    /** Only Stands with real fists get the ORA barrage (multi-hit flurry + flying fists). */
    public boolean barrage() {
        return this == IRON_GOLEM || this == GER || this == ENDERMAN || this == ENDER_WORLD || this == WARDEN || this == BLAZE; // (the Blaze barrages with its rods)
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
