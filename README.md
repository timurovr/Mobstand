# Mob Stands (Minecraft 1.21.1, NeoForge)

Every player can awaken a Minecraft mob as a personal, JoJo-style **Stand**: a translucent, glowing spirit that
floats beside you, fights when you tell it to, protects you on its own, and levels up with you.

## Build and run
Requires **JDK 21**. This archive has no Gradle wrapper (it could not be downloaded offline), so do one of:
* copy `gradlew`, `gradlew.bat` and the `gradle/` folder from the official NeoForge 1.21.1 MDK, **or**
* with Gradle 8.8+ installed run `gradle wrapper --gradle-version 8.10.2`.

Then:
```
./gradlew runClient      # dev client
./gradlew build          # jar in build/libs/
```
`neo_version` in `gradle.properties` is `21.1.77`; bump it to the newest 21.1.x if Gradle cannot resolve it.

## Getting a Stand
* **Echoing Arrow**: right click to awaken a random Stand; if you already have one, it rerolls into a *different* Stand (level/XP reset). Craft: arrow + emerald + echo shard (shapeless). Also in the creative tab "Combat".
* Rarity (roll weight per Stand): Common 50, Uncommon 28, Rare 14, Epic 6, Legendary 2. Rarer Stands deal more damage and have more unusual abilities.
* Operators: `/stand set <type>`, `/stand level <1-15>`, `/stand clear`.

## Controls (all rebindable in Options > Controls > Mob Stands)
| Key | Action |
|---|---|
| R | Summon / dismiss |
| Right click | Basic attack (see note) |
| Z / X / C | Ability 1 / Ability 2 / Ultimate |
| G | Stand info screen |

Note: right click is shared with vanilla "use". The Stand only takes it when your hands are empty and you are not looking at a block or a villager. Rebind the basic attack to any other key to remove this restriction.

## The Stands
| Stand | Mob | Rarity | Basic / Z / X / C |
|---|---|---|---|
| Jelly Rebound | Slime | Common | Squish Slam / Ricochet Blob (bouncing projectile) / Super Bounce / Mega Quake |
| Rattling Marksman | Skeleton | Common | Bone Arrow / Rapid Fire / Piercing Shot / Arrow Storm |
| Weaver of Webs | Spider | Uncommon | Fang Strike (poison) / Web Shot / Spider Climb / Web Cocoon |
| Green Detonator | Creeper | Uncommon | Fuse Punch / Hissing Burst / Kamikaze Rush (self-detonation, Stand reforms) / Big Bang |
| Ember Sovereign | Blaze | Rare | Fire Bolt / Flame Barrage / Ring of Fire / Inferno Burst |
| Iron Colossus | Iron Golem | Rare | Iron Fist / Ground Slam / Iron Guard / World Breaker |
| Midnight Wing | Phantom | Rare | Talon Swipe / Sky Rush (dash) / Wing Lift / Midnight Swarm |
| Void Stalker | Enderman | Epic | Void Slash / Blink / Shadow Step (backstab) / Void Collapse |
| Deep Dark Monarch | Warden | Legendary | Dark Fist / Sonic Boom / Sculk Pulse / Sonic Devastation |

Each Stand has its own unlock levels (rarer = later), a passive perk, and its own damage growth per level.
Max level is 15. XP comes from using abilities and from kills made by you or your Stand.

## Architecture
```
com.mobstands
  MobStands               common entry point, registers everything
  config/StandConfig      explosions-destroy-blocks, damage multiplier, auto-assist
  stand/                  StandType (9 Stands), StandData (persistent attachment), StandManager (server authority),
                          StandAbilities (36 abilities), AbilityContext (targeting/damage/FX helpers),
                          StandCombat (never hits owner, pets, villagers), StandPassives, TickScheduler
  entity/StandEntity      NOT a Mob: no AI/pathing/spawning/damage; position driven by the owner every tick
  entity/StandProjectile  invisible server-driven projectile (fireball, arrow, web, bouncing slime), particle visuals
  registry/ item/ network/ event/   registries, Echoing Arrow, 2 payloads, events + /stand command
  client/                 renderer (translucent + additive aura), key mappings, HUD, info screen
```
Key design points:
* **Persistence**: `StandData` is a data attachment with `copyOnDeath`; the Stand entity is `noSave` and is re-created from the data on login, respawn, dimension change and after teleports (self-healing check once per second).
* **One Stand per player**: the owner's data stores the active Stand's UUID; any entity whose UUID does not match removes itself. Toggle has an anti-spam delay.
* **Multiplayer**: the entity is tracked like any entity, so everyone sees it; type/animation/hidden state are synced entity data. Only the owner receives the data packet used for HUD/GUI.
* **Never attacks the owner / mob spawning untouched**: all damage goes through `StandCombat.valid`; the Stand is `MobCategory.MISC` and has no spawn placement.
* **Protect AI**: if a hostile mob is targeting the owner (or recently hit/was hit by the owner) the Stand strikes it on its own at ~60% of manual damage.
* **Look**: vanilla mob model rendered translucent, tinted, fullbright, plus an additive glow pass, hover bob, lunge/pulse animations, per-type aura particles.

## Known limitations (please read)
* **This code was written without being able to download Minecraft/NeoForge in the authoring sandbox.** Every file was syntax-checked with javac and cross-references between the mod's own classes were verified, but it has *not* been compiled against NeoForge or run in-game. Expect to fix a handful of small API mismatches on the first `./gradlew build`; send me the output and I will fix them.
* Mob models are reused from vanilla, so vanilla secondary layers (e.g. Warden glow layers, Creeper charge) are not drawn; Enderman, Spider and Phantom eyes are.
* Spider Climb is server-assisted (vertical push when you press against a wall) and can look slightly jerky on high latency.
* Sounds and particles are vanilla ones (no custom sound/texture assets).
* Explosions never destroy blocks unless enabled in the config.
