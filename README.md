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
| Z / X / C / V | Abilities 1-4 |
| J | Stand pose |
| G | Stand menu (abilities, passives, lore, Requiem trial progress) |

## The Stands
| Stand | Mob | Abilities |
|---|---|---|
| Jelly Rebound | Slime | Squish Slam, Ricochet Blob, Super Bounce, Mega Quake |
| Rattling Marksman | Skeleton | Bone Arrow, Rapid Fire, Piercing Shot, Arrow Storm |
| Weaver of Webs | Spider | Fang Strike, Web Shot, Spider Climb, Web Cocoon |
| Green Detonator | Creeper | Hissing Burst, Kamikaze Rush, Big Bang, Heart Attack (Sheer Heart Attack-style mini creeper) |
| Iron Colossus | Iron Golem | Iron Fist, Barrage, Ground Slam, World Breaker |
| Midnight Wing | Phantom | Talon Swipe, Sky Rush, **Aerosmith** (pilot it, see below), Midnight Swarm, Dream Walk |
| Void Stalker | Enderman | Void Slash, Barrage, Blink, Void Collapse |
| Deep Dark Monarch | Warden | Dark Fist, Barrage, Sonic Boom, Sonic Devastation |
| Little Lullaby | Allay | Allay Rush, Allay Lift, Allay Hold, Roam |
| Muddy Fortune | Pig | Oink (lures monsters away) |
| Lazarus Smile | Axolotl | Play Dead, Soul Swap, **Soul Control** (possess a mob, see below), Neoteny (rewinds 6 seconds, items and inventories too) |
| Weeping Deluge | Squid | November Rain, Downpour, Rain Shot |
| Iron Express | Minecart | Rail Rider, Powered Rails, Nitro |
| Ancient Nose | Sniffer | Snout Bump, **Sniff Out** (structure gamble, see below) |
| Gallop Crown | Horse | Runs with you |
| Ember Sovereign | Blaze *(Nether)* | Rod Strike, Flame Barrage, Ring of Fire, Inferno Burst |
| Gilded Swine | Piglin *(Nether)* | Gilded Jab, Snatch |
| Thunderborn Swine | Zombified Piglin | Rusted Thrust. Only from a Pig Stand struck by lightning |

Every punch lands with a MUDA-style burst of ghost fists. Stands never punch on their own unless you turn auto-assist
on in the config. Max level is 10. XP comes from using abilities and from kills.

## Stand highlights
* **Aerosmith (Phantom, X)**: fly your Phantom remotely like creative flight (WASD, Space up, Shift down, Ctrl faster)
  while your body stays put. Right click fires a stream of weak arrows; the gun overheats after 70 rounds (bar on the
  right of the screen). F5 changes the camera distance. It can't leave a 64-block range.
* **Soul Control (Axolotl, X)**: take over the mob you're looking at for 2 seconds (up to 10 at level 10). Walk it,
  jump, attack with left click and use its own power with right click: creepers explode, Wardens Sonic Boom (you go
  blind inside one), skeletons shoot, Blazes and Ghasts throw fire, Endermen teleport and more. If its body dies, your
  soul snaps back.
* **Sniff Out (Sniffer, Z)**: three slot reels roll random structures; pick one and the Sniffer finds the nearest one
  you haven't visited and leaves a scent trail to it. Pass and wait 10 minutes; pass three times and you get a
  search bar. Its nose weakens away from home: one reel in the Nether, and in the End it only flashes the way to an
  End City for one second.

## Requiems
| Requiem | From | How to earn it | Abilities |
|---|---|---|---|
| **The Ender World** | Enderman Stand | Requiem Trial: kill the Ender Dragon with your Stand out, spend a full day in the End, then pose at midnight | Void Jab, Barrage, Time Skip, **Time Stop** |
| **Pigstep** (a Piglin Brute) | Piglin Stand | Requiem Trial (shown in the Stand menu) | Brute Jab, Steal, Release, Another World, Null Bubble |
| **Guardian Experience Requiem** | Iron Golem Stand | Use a Stand Arrow during a raid at 3 hearts or less: a 50/50. On a fail the arrow breaks, and there's a 15% chance your Stand drops to level 1 | Requiem Fist, MUDA Barrage, **Return to Zero**, Life Giver, Golden Barrier |

* **Time Stop** uses the game's real tick freeze. Arrows stop in the air, and nothing frozen can hurt you.
* **Pigstep** (Soft & Wet style) steals from a creature: pick its own property (a slime's bounce, a creeper's
  volatility, an enderman's teleport...) or its sound, then Release it onto something else or let the sound's power
  loose. It can also take a creature's sight, friction, heat, water, breath or footing for 10 seconds.
  **Null Bubble** drifts slowly through walls and armor and pops inside its target.
* **Return to Zero** is a counter with a skippable cutscene: the attacker rewinds, its copies look back, and it dies
  over and over, gone forever. Its loot goes to your inventory. Against bosses it just nullifies the attack, unless the
  boss is at 10% health or less.
* **Golden Barrier** (V) is a Love Train-style wall of light.

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
`/stand set <type>`, `/stand level <1-10>`, `/stand clear`, `/stand aura on|off`, `/stand spin learn|forget`

## Code layout
```
com.mobstands
  stand/     StandType, StandData (saved per player), StandManager, StandAbilities, Ger, TimeStop,
             SoundSteal, RequiemTrials, HorseSpin
  entity/    StandEntity (driven by its owner, no AI), SpinItemEntity (spinning and thrown items), HeartAttackEntity
  network/   payloads between client and server
  client/    renderers, poses, HUD, Stand menu, cutscenes, Spin input and effects
  mixin/     TickRateManagerMixin (Time Stop)
```

## Notes
* Mob models are vanilla ones, rendered translucent with an extra glow.
* Explosions don't break blocks unless you turn that on in the config.
