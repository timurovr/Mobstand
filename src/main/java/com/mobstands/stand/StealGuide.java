package com.mobstands.stand;

import java.util.List;

/**
 * Pigstep's Steal, explained in full for the Stand menu (ACT 4 only): how it works, every property, every theft and
 * every sound, with what each does and an example. Plain text so it's safe on both sides.
 */
public final class StealGuide {
    private StealGuide() {}

    /** One line of the guide. {@code header} = a section title; otherwise a named entry with its colour. */
    public record Entry(String name, int color, String text, boolean header) {
        static Entry h(String name) { return new Entry(name, 0xFFD9A8FF, "", true); }
        static Entry e(String name, int color, String text) { return new Entry(name, color, text, false); }
    }

    public static final List<Entry> ENTRIES = List.of(
            Entry.h("How Steal works"),
            Entry.e("1. Steal", 0xB07CFF, "Look at a creature and press Steal: a menu opens on it. Pick its Property, its Sound, or one of six "
                    + "Thefts. Nothing is used up (and there's no cooldown) until you actually pick something."),
            Entry.e("2. Release", 0xB07CFF, "Properties and Sounds are held by your Stand until you press Release. Look at a creature to give a "
                    + "property to it, or look at nothing to take it yourself. Your Stand holds one property at a time - stealing "
                    + "a new one replaces it (and clears any held sounds). Thefts don't need Release: they work straight away."),
            Entry.e("The victim", 0xB07CFF, "Taking a creature's Property drains it: Slowness II and Weakness for 10 seconds. A creeper whose "
                    + "Volatility you took can't explode - it swells up and fizzles."),

            Entry.h("Properties - what makes a mob what it is"),
            Entry.e("Bounce", 0x7EFF6E, "From slimes and magma cubes. Whoever gets it can't stop bouncing for 10 seconds - big boings every "
                    + "few ticks, no fall damage. Example: give it to a skeleton and it can't stand still to aim."),
            Entry.e("Lightness", 0xE8E8FF, "From ghasts, phantoms, bats, bees, parrots, allays, vexes and chickens. A mob floats up "
                    + "helplessly for a few seconds, then drops. Yourself: Slow Falling and Jump Boost II for 30 seconds. "
                    + "Example: float a zombie up off a cliff and let it fall."),
            Entry.e("Leap", 0xC9A27A, "From rabbits, goats and frogs. Jump Boost IV for 20 seconds. Example: take it yourself to jump up "
                    + "walls and over fences."),
            Entry.e("Speed", 0x9FE0FF, "From foxes, ocelots, cats, wolves and horses. Speed II for 20 seconds. Example: take a horse's "
                    + "speed to outrun anything."),
            Entry.e("Toughness", 0xB0B0B0, "From turtles, armadillos, iron golems and ravagers. Resistance II for 20 seconds. Example: "
                    + "steal it from an iron golem and wear it into a fight."),
            Entry.e("Fireproofing", 0xFF8A3A, "From blazes, striders, hoglins and piglins. Fire Resistance for 30 seconds. Example: take a "
                    + "blaze's before crossing lava."),
            Entry.e("Teleport", 0xC066FF, "From endermen, endermites and shulkers. Give it to a monster, then look at who it should hunt "
                    + "and press Release again: for 30 seconds it blinks right next to them whenever they're far away or out of "
                    + "sight. A peaceful animal just blinks about at random; on yourself, so do you. Example: give it to a "
                    + "skeleton and point it at another player."),
            Entry.e("Volatility", 0x4CFF4C, "From creepers. Whatever gets it becomes a walking creeper for 30 seconds: it chases its "
                    + "target (pick one with a second Release, or it goes for the nearest player - you too!), hisses up close and "
                    + "blows up in a creeper's blast. Keep it yourself and it goes off where you're looking. Example: turn a cow "
                    + "into a bomb and send it at a crowd."),
            Entry.e("Cold", 0xA8E4FF, "From snow golems, strays and polar bears. Slowness IV and freezing for 10 seconds. Example: "
                    + "freeze a charging ravager in place."),
            Entry.e("Gills", 0x5B8FD6, "From fish, squids, dolphins, axolotls, drowned and guardians. A land mob with gills drowns in "
                    + "the open air for 10 seconds. Yourself: Water Breathing for 60 seconds and Dolphin's Grace for 30. "
                    + "Example: give a zombie gills and watch it choke."),
            Entry.e("Potions", 0x9B59B6, "From witches. A mob gets a random bad effect for 10 seconds (Poison, Weakness, Slowness, "
                    + "Hunger, Blindness or Wither); you get a random good one (Regeneration, Strength, Speed, Fire Resistance or "
                    + "Invisibility)."),
            Entry.e("Glow", 0x7FFFD4, "From glow squids. Glowing for 30 seconds, so it's seen through walls. Yourself: Night Vision too."),
            Entry.e("Poison", 0x6E8F2B, "From cave spiders and bogged. A mob gets Poison II for 6 seconds; on yourself it turns into "
                    + "Regeneration II."),
            Entry.e("Vigor", 0xFF6E6E, "From everything else (zombies, cows, villagers, players...). Taking it rips 4 health out of "
                    + "them. Giving it heals 4 (yourself: plus Absorption for 30 seconds). Example: drain an enemy and heal "
                    + "your wolf."),

            Entry.h("Thefts - it goes without for 10 seconds"),
            Entry.e("Sight", 0xE0E0E0, "Blindness and Darkness, and a mob forgets who it was after - it can't chase anyone. "
                    + "Example: blind a skeleton that has you pinned."),
            Entry.e("Friction", 0xA0E8FF, "Like walking on ice: once it moves it can't stop or turn, sliding wherever it was going. "
                    + "Players are slowed heavily instead. Example: send a mob sliding off a ledge."),
            Entry.e("Heat", 0x9FD8FF, "Frozen through like powder snow: slow, shaking and taking freezing damage."),
            Entry.e("Water", 0x3F76E4, "Weakness and Slowness. Water creatures dry out and take damage. Example: steal a guardian's "
                    + "water inside its own temple."),
            Entry.e("Breath", 0xC8F0FF, "No air even on dry land: it starts drowning. Example: suffocate a mob that's tough to hit."),
            Entry.e("Footing", 0xC9A27A, "It can't jump or climb - spiders slide off walls. Example: stop a spider climbing up to you."),

            Entry.h("Sounds - the power that came with the voice"),
            Entry.e("How sounds work", 0xFFD24A, "Pick Sound in the menu: the creature goes silent and your Stand keeps its voice "
                    + "(up to 3; a 4th pushes out the oldest). Release lets the most recent one loose as its power. Look up into a "
                    + "thunderstorm with nothing in sight and press Steal to steal the Thunder itself."),
            Entry.e("Blasts", 0xFFD24A, "Creeper: an explosion where you look. Ghast: a ghast fireball. Wither: three wither skulls. "
                    + "Ender Dragon: a spreading cloud of dragon's breath. Thunder: a lightning bolt where you look."),
            Entry.e("Beams and shots", 0xFFD24A, "Warden: a sonic boom straight through everything in a line. Guardian: a charging "
                    + "laser on what you look at (Elder: stronger, plus Mining Fatigue). Skeleton / stray / bogged / pillager: a "
                    + "volley of 6 arrows. Shulker: a homing shulker bullet. Drowned: a trident. Snow golem: a spray of snowballs "
                    + "that slows. Witch: a nasty splash potion. Evoker: a line of fangs along the ground."),
            Entry.e("Around you", 0xFFD24A, "Blaze: everything near you catches fire. Ravager / polar bear: a roar that hurls everyone "
                    + "back. Wolf: a howl that scares enemies off and weakens them. Cat / ocelot: creepers and phantoms flee. "
                    + "Endermite / silverfish: nearby enemies slowed, you sped up. Glow squid / bat: everything within 32 blocks "
                    + "glows through walls (bat: you get Night Vision)."),
            Entry.e("In front of you", 0xFFD24A, "Bee: a poisoning buzz. Spider / cave spider: webs that slow (cave spider poisons). "
                    + "Zombie / husk: a hungering groan that hurts and slows. Wither skeleton: withering. Squid: an ink cloud that "
                    + "blinds. Llama: spit that confuses and weakens."),
            Entry.e("Hits", 0xFFD24A, "Iron golem: a big uppercut into the air. Hoglin / zoglin: a heavy toss. Piglin brute / "
                    + "vindicator: one huge axe blow. Frog: its tongue pulls the target to you. Goat: you ram forward, launching "
                    + "whatever you hit."),
            Entry.e("Movement", 0xFFD24A, "Slime: launched high, landing soft. Magma cube: the same, setting things nearby alight. "
                    + "Breeze: a wind jump that blows enemies back. Phantom: a swooping glide. Camel: a long dash. Enderman: "
                    + "teleport to where you look. Horse: Speed III and Jump Boost. Chicken: Slow Falling."),
            Entry.e("Protection and help", 0xFFD24A, "Cow: drinks away your bad effects and puts out fire. Turtle: Resistance III but "
                    + "slow. Armadillo: Resistance III. Axolotl: Regeneration. Dolphin: Dolphin's Grace and Water Breathing. "
                    + "Vex: you turn invisible and mobs lose you. Villager: Hero of the Village. Allay: every dropped item "
                    + "nearby comes to you. Sniffer: an ancient seed."),
            Entry.e("Anything else", 0xFFD24A, "A decoy: every mob nearby stops what it's doing and goes to see what made the noise.")
    );
}
