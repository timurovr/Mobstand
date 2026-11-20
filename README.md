# Mob Stands (Minecraft 1.21.1, NeoForge)

A JoJo-inspired mod: every player can awaken a Minecraft mob as their own **Stand**. It's a translucent, glowing spirit
that floats beside you, fights when you tell it to, and levels up with you. Some Stands can go further and awaken a
**Requiem**. On top of that there's Gyro's **Spin**, learned from a Steel Ball.

Current version: **1.2**

## What's new in 1.2
- **Barrage Clash**: two players throw a Barrage at each other face to face and the fists meet - mash the Barrage key to push the clash point; the winner breaks through with a finishing blow.
- **Bites the Dust**: the rewind now brings back everything from the last 6 seconds - players still on the death screen, mobs erased by Primer, broken boats and item frames, chest and furnace contents, ender chests. New menacing sound, 20 s cooldown.
- **Primer** erases a mob Killer Queen-style: it swells and flashes like a creeper, then a big slow blast that harms nothing around it and leaves no trace.
- **Wonder of Deep**: monsters that come for you meet calamity faster the closer they get and never reach you; hits and arrows go back to whoever threw them; real falling dripstone. It stands right behind you - tall, still, faded and wrong. No punch. Summoning it brings 3 s of Darkness (toggle with Z or `/stand darkness on|off`).
- **C-Moon**: hold X for the Gravity Punch wheel, hold C for the Gravity Turn wheel (your own gravity, no aiming). Hitbox lies along your body on walls, vanilla walking animation, no more random gravity flips.
- **Lazarus Smile**: new ability Lazarus (C) - players who died nearby and haven't respawned get back up, and pets and peaceful mobs come back.
- **Creeper Stands** don't punch - they only explode. Shift + Echoing Arrow gives up your Stand.
- Multiplayer fixes (no item duplication through rewinds, no reflected-hit crash, no stale gravity on reused entities) and performance work.

## Download
Grab the jar from the [Releases](../../releases) page and drop it in your `mods` folder (NeoForge 21.1.x for
Minecraft 1.21.1). Every release has its jar attached automatically.

## Build and run
Requires **JDK 21**.
```
./gradlew runClient      # dev client
./gradlew build          # jar in build/libs/
```

## Getting a Stand
* **Echoing Arrow**: right click to awaken a random Stand. If you already have one, it rerolls into a *different*
  Stand (level and XP reset). Craft: arrow + emerald + echo shard (shapeless).
* **Stand pools**: at first you can only roll Overworld Stands. Reaching the Nether unlocks the Nether Stands
  (Blaze, Piglin...), and reaching the End unlocks the rest. The Stand menu (G) shows which pools you've unlocked.
* Rarity (roll weight): Common 50, Uncommon 28, Rare 14, Epic 6, Legendary 2.
* Requiems are never rolled. You earn them (see below).

## Controls (rebindable in Options > Controls > Mob Stands)
| Key | Action |
|---|---|
| R | Summon / dismiss |
| Right click | Basic attack (only with empty hands, not looking at a block or villager) |
| Z / X / C / V / B | Abilities 1-5 |
| J | Stand pose |
| G | Stand menu (abilities, passives, lore, Requiem trial progress) |

Some abilities are held instead of pressed: C-Moon's X and C open a direction wheel (move the mouse to an arrow, let go).
In a Barrage Clash, mash Z.

## The Stands
| Stand | Mob | Rarity | Abilities (right click, Z, X, C, V...) |
|---|---|---|---|
| Jelly Rebound | Slime | Common | Squish Slam, Ricochet Blob, Super Bounce, Mega Quake |
| Rattling Marksman | Skeleton | Common | Bone Arrow, Rapid Fire, Piercing Shot, Arrow Storm |
| Muddy Fortune | Pig | Common | Oink (lures monsters away) |
| Gallop Crown | Horse | Common | Runs with you |
| Weaver of Webs | Spider | Uncommon | Fang Strike, Web Shot, Spider Climb, Web Cocoon |
| Green Detonator | Creeper | Uncommon | No punch, it only explodes: Hissing Burst, Kamikaze Rush, Big Bang, Heart Attack (Sheer Heart Attack-style mini creeper) |
| Little Lullaby | Allay | Uncommon | Allay Rush, Allay Lift, Allay Hold, Roam |
| Iron Express | Minecart | Uncommon | Rail Rider, Powered Rails, Nitro |
| Iron Colossus | Iron Golem | Rare | Iron Fist, Barrage, Ground Slam, World Breaker |
| Midnight Wing | Phantom | Rare | Talon Swipe, Sky Rush, **Aerosmith** (pilot it), Midnight Swarm, Dream Walk |
| Weeping Deluge | Squid | Rare | Tidal Slam, November Rain, Downpour, Rain Shot |
| Ancient Nose | Sniffer | Rare | Snout Bump, **Sniff Out** (structure gamble) |
| Void Stalker | Enderman | Epic | Void Slash, Barrage, Blink, Void Collapse |
| **Catch the Rainbow** | Dolphin | Epic | Fin Strike, Rain Call, Rain Walk, Rain Blades, Rain Cage, Rain Dash |
| Deep Dark Monarch | Warden | Legendary | Dark Fist, Barrage, Sonic Boom, Sonic Devastation |
| Lazarus Smile | Axolotl | Legendary | Play Dead (passive), Soul Swap, **Soul Control** (possess a mob), **Lazarus** (revive the dead) |
| Ember Sovereign | Blaze *(Nether)* | Rare | Rod Strike, Flame Barrage, Ring of Fire, Inferno Burst |
| Gilded Swine | Piglin *(Nether)* | Rare | Gilded Jab, Snatch |
| Thunderborn Swine | Zombified Piglin | Rare | Rusted Thrust. Only from a Pig Stand struck by lightning |
| **C-Moon** | Shulker *(End)* | Legendary | Shell Strike, Gravity Shift, **Gravity Punch** (wheel), **Gravity Turn** (wheel) |

Every punch lands with a MUDA-style burst of ghost fists. Stands never punch on their own unless you turn auto-assist
on in the config. Max level is 10. XP comes from using abilities and from kills. Shift + use an Echoing Arrow to give
up your Stand (the arrow isn't used).

## Stand highlights
* **Barrage Clash**: when two players with Barrage Stands face each other and throw a Barrage within a second, the
  fists meet in the middle. Mash Z to push the clash point (a tug-of-war bar shows who's winning). Get 30 presses
  ahead or lead when the 5 seconds run out and your Stand breaks through with a finishing blow; a tie blows you apart.
* **Aerosmith (Phantom, X)**: fly your Phantom remotely like creative flight (WASD, Space up, Shift down, Ctrl faster)
  while your body stays put. Right click fires a stream of weak arrows; the gun overheats after 70 rounds (bar on the
  right of the screen). F5 changes the camera distance. It can't leave a 64-block range.
* **Soul Control (Axolotl, X)**: take over the mob you're looking at for 2 seconds (up to 10 at level 10). Walk it,
  jump, attack with left click and use its own power with right click: creepers explode, Wardens Sonic Boom (you go
  blind inside one), skeletons shoot, Blazes and Ghasts throw fire, Endermen teleport and more. If its body dies, your
  soul snaps back.
* **Lazarus (Axolotl, C, level 8)**: players who died within 16 blocks in the last minute and haven't pressed Respawn
  get back up where they fell; pets and peaceful mobs that died nearby come back whole (their drops vanish). Monsters
  stay dead. 90 s cooldown.
* **Sniff Out (Sniffer, Z)**: three slot reels roll random structures; pick one and the Sniffer finds the nearest one
  you haven't visited and leaves a scent trail to it. Pass and wait 10 minutes; pass three times and you get a
  search bar. Its nose weakens away from home: one reel in the Nether, and in the End it only flashes the way to an
  End City for one second.
* **Catch the Rainbow (Dolphin)**: call the rain, walk on the raindrops, stop the rain into blades or a cage around a
  creature, and dash through it. In the rain, hits can pass right through you (Raindrop Body).
* **C-Moon (Shulker)**: sits on your shoulder. Z turns your gravity toward the wall or ceiling you look at (look at the
  floor to go back). Hold C for the Gravity Turn wheel: up = ceiling, left/right = the walls beside you, down = normal,
  middle = the wall in front. Hold X for the Gravity Punch wheel, pick a direction, and your next punch turns that
  creature's gravity that way for 3 minutes. On walls you walk, run and jump like normal ground.

## Requiems
| Requiem | From | How to earn it | Abilities |
|---|---|---|---|
| **The Ender World** | Enderman Stand | Requiem Trial: kill the Ender Dragon with your Stand out, spend a full day in the End, then pose at midnight | Void Jab, Barrage, Time Skip, **Time Stop** |
| **Pigstep** (a Piglin Brute) | Piglin Stand | Requiem Trial: Snatch 30 things, Snatch a Piglin Brute's axe, kill the Wither with your Stand out | Brute Jab, Steal, Release, Another World, Null Bubble |
| **Guardian Experience Requiem** | Iron Golem Stand | Use a Stand Arrow during a raid at 3 hearts or less: a 50/50. On a fail the arrow breaks, and there's a 15% chance your Stand drops to level 1 | Requiem Fist, MUDA Barrage, **Return to Zero**, Life Giver, Golden Barrier |
| **Green Detonator: Bites the Dust** | Creeper Stand | Get struck by lightning (you or your Stand) with a level 10 Creeper Stand | Primer, Heart Attack, Big Bang, **Bites the Dust** |
| **Wonder of Deep** | Warden Stand | Not earnable yet (`/stand set warden_requiem`) | Calamity (passive), Dark Arrival |

* **Time Stop** uses the game's real tick freeze. Arrows stop in the air, and nothing frozen can hurt you.
* **Pigstep** (Soft & Wet style) steals from a creature: pick its own property (a slime's bounce, a creeper's
  volatility, an enderman's teleport...) or its sound, then Release it onto something else or let the sound's power
  loose. It can also take a creature's sight, friction, heat, water, breath or footing for 10 seconds.
  **Null Bubble** drifts slowly through walls and armor and pops inside its target.
* **Return to Zero** is a counter with a skippable cutscene: the attacker rewinds, its copies look back, and it dies
  over and over, gone forever. Its loot goes to your inventory. Against bosses it just nullifies the attack, unless the
  boss is at 10% health or less.
* **Golden Barrier** (V) is a Love Train-style wall of light.
* **Primer** (Killer Queen's first bomb): look at mobs to prime them, press again to set them off. Each one swells and
  flashes like a creeper, then goes up in a big, slow blast that breaks nothing, hurts nothing around it and leaves no
  trace. Players and bosses take a heavy hit instead.
* **Bites the Dust** winds the last 6 seconds back for everything within 40 blocks: mobs and players go back where
  they were with the health and items they had, the dead come back (players too, if they haven't pressed Respawn),
  broken blocks, chests, furnaces and ender chests return. Whoever hurt you in those seconds explodes when time snaps
  back. 20 s cooldown.
* **Wonder of Deep** never fights: it stands right behind you, tall and still. Monsters that come for you meet calamity
  faster the closer they get (they trip, dripstone falls on them, lightning finds them) and never reach you. Hits and
  arrows go back to whoever threw them. Summoning it darkens everyone nearby for 3 seconds (Z or
  `/stand darkness on|off` to toggle).

## Spin
Right click a **Steel Ball** to learn the Spin.
* **Spin something**: hold Q with an item in hand, then tap Q on the beat. Shift spins a second Steel Ball as well.
* **Throw**: hold right click to wind up, let go to throw. Steel Balls bounce, chain between mobs and fly back to you.
  Two balls thrown together go faster and further.
* **Missile**: throw while looking up, then steer it with your view.
* **Wall Sense**: throw while sneaking. The ball sticks to the wall, counts the vibrations of evil mobs within 9 blocks,
  then goes for one around corners. Sneak + look up to steer it like a missile first.
* **Call it back**: right click any of your balls from up to 64 blocks away, thrown or dropped.
* **Archers misfire**: a Steel Ball hit spins skeletons and pillagers. They turn their bows on other mobs, and those
  mobs fight back 40% of the time.
* **On horseback**:
  * Throw while looking down: the balls spin on the horse's hips and boost its speed and jump. Zig-zagging gains extra
    speed, like a ship tacking against the wind.
  * Hold Q, then press Shift, to spin both balls without dismounting.
  * Look down and right click with an empty hand to take the balls back.
  * Missile + look down: the ball dives under the horse and launches it into the air.

## Commands (operators)
`/stand set <type>`, `/stand level <1-10>`, `/stand clear`, `/stand aura on|off`, `/stand darkness on|off`,
`/stand spin learn|forget|level <n>`

## Code layout
```
com.mobstands
  stand/     StandType, StandData (saved per player), StandManager, StandAbilities, Ger, TimeStop,
             SoundSteal, RequiemTrials, HorseSpin, CreeperRequiem, Mandom (rewind), Calamity, GravityFlip,
             Clash, Lazarus, RainPowers, PropertySwap
  entity/    StandEntity (driven by its owner, no AI), SpinItemEntity (spinning and thrown items), HeartAttackEntity
  network/   payloads between client and server
  client/    renderers, poses, HUD, Stand menu, cutscenes, Spin input and effects
  mixin/     Time Stop tick freeze, C-Moon camera / look / hitbox, creeper-style swelling
```

## Notes
* Mob models are vanilla ones, rendered translucent with an extra glow.
* Explosions don't break blocks unless you turn that on in the config.
