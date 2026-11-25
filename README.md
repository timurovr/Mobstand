# Mob Stands (Minecraft 1.21.1, NeoForge)

A JoJo-inspired mod: every player can awaken a Minecraft mob as their own **Stand**. It's a translucent, glowing spirit
that floats beside you, fights when you tell it to, and levels up with you. Some Stands can go further and awaken a
**Requiem**. On top of that there's Gyro's **Spin**, learned from a Steel Ball.

Current version: **1.3**

## What's new in 1.3
- **No more rarities.** Every Stand is just as likely to roll and hits just as hard (Requiems hit harder). Instead each
  Stand has a **type**: Close-Range Power, Long-Range, Automatic, Range Irrelevant, Support, Equipment, Colony or Mount.
- **New Stands**:
  - **Silver Fingers** (Silverfish) - zippers: zip through mobs, walls and space.
  - **Green Temperance** (Slime) - devour enemies, wear the goo as armor, disguise yourself as other mobs.
  - **Smoke and Mirrors** (Illusioner) - phantom arrows, mirror clones, blinding spells, Vanish and a Mirror Maze.
  - **Oh! Lonesome Llama** and **Reef Boy** - tool Stands (command only for now): press R with the Stand out for an
    extra slot next to your hotbar with a lasso or a fishing rod.
- **Signature tricks** that make the old Commons special: Bone Cage, Silk Swing, Sheer Heart Attack, Lullaby, Truffle
  Hunt, Cattle Car, Trample and Green Temperance's slime bounce.
- **Möbius Strip** (Weaver of Webs): Stone Free's infinite string, awakened by getting badly hurt.
- **Ancient Nose** is a full Hermit Purple: sacrifice a spyglass to track a structure with glowing thorn vines.
- **Iron Express** calls a real train - other players can right-click to climb on.
- **Cursed Stands**: rare spirits like **Cheap Trick** latch onto you and switch your own Stand off for a while.
- **Travel encounters**: Stand users show up naturally on the road, more often on the way to a Woodland Mansion.
- **The Mansion Evoker** stops time - and when you die near him, he rewinds it.
- **Upgraded animations**: time stop freezes everything (and everyone), a full-screen clock plays when time rewinds
  (even over the death screen), see-through zipper portals, green goo, mirror images, glowing vines.
- **Spin page** in the Stand menu that explains every technique.
- **PvP balance**: no single Stand hit can take more than 35% of a player's health, so nothing one-taps a player.
- Fixes: Bites the Dust revives players properly (no more invisible "ghost" players), Another World works on
  players and mobs outside it can't see or hurt you, the zipper crash is gone.

## Download
Grab the jar from the [Releases](../../releases) page and drop it in your `mods` folder (NeoForge 21.1.77 or newer
21.1.x, for Minecraft 1.21.1). Every release has its jar attached automatically.

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
* Every Stand in your pools is equally likely. Requiems are never rolled - you earn them (see below).
* Some Stands are command only for now: Oh! Lonesome Llama (`/stand set llama`) and Reef Boy (`/stand set guardian`).
* Shift + use an Echoing Arrow to give up your Stand (the arrow isn't used).

## Controls (rebindable in Options > Controls > Mob Stands)
| Key | Action |
|---|---|
| R | Summon / dismiss (tool Stands: tap R with the Stand out for the tool slot, hold R to dismiss) |
| Right click | Basic attack (only with empty hands, not looking at a block or villager) |
| Right click with a sword or axe | Stand punch |
| Z / X / C / V / B | Abilities 1-5 |
| J | Stand pose |
| G | Stand menu (abilities, passives, lore, Spin, Requiem trial progress) |

Some abilities are held instead of pressed: Crescent Shell's X and C open a direction wheel (move the mouse to an
arrow, let go). In a Barrage Clash, mash Z.

## Stand types
Every Stand is on the same level - they're different, not better or worse. Every rollable Stand hits at x1.35,
Requiems at x1.75.

| Type | What it means |
|---|---|
| Close-Range Power | Fights up close and hits hard |
| Long-Range | Shoots, sends things out, works from afar |
| Automatic | Acts on its own |
| Range Irrelevant | Bends the rules: time, weather, gravity, fate |
| Support | Finds, tracks, helps |
| Equipment | Becomes a tool in your hands |
| Colony | Many small pieces (a swarm, a goo, a herd) |
| Mount | You ride it |

## The Stands
| Stand | Mob | Type | Abilities (right click, Z, X, C, V...) |
|---|---|---|---|
| Green Temperance | Slime | Colony | Devour Touch, Temperance Armor, Masquerade, Devour. Passive: you bounce off landings like a slime block |
| Silver Fingers | Silverfish | Colony | Swarm Bite, Zipper Punch, Zip Away, Zip Through |
| **Scary Monsters** | Ravager | Colony / Close-Range Power | You turn into the Ravager: Raptor Bite, Stampede, Living Wall, Call the Herd. Passive: **Primal Infection** |
| Rattling Marksman | Skeleton | Long-Range | Bone Arrow, Rapid Fire, Piercing Shot, Arrow Storm, **Bone Cage** |
| Weaver of Webs | Spider | Close-Range Power | Fang Strike, Web Shot, Spider Climb, Web Cocoon, **Silk Swing**. **Möbius Strip** (see below) |
| Green Detonator | Creeper | Close-Range Power | No punch, it only explodes: Hissing Burst, Kamikaze Rush, Big Bang, **Sheer Heart Attack** |
| Little Lullaby | Allay | Automatic | Allay Rush, Allay Lift, Allay Hold, Roam, **Lullaby** |
| Muddy Fortune | Pig | Support | Oink (lures monsters away), **Truffle Hunt** |
| Gallop Crown | Horse | Mount | Runs with you. Passive: **Trample** |
| Iron Express | Minecart | Mount | Rail Rider, Powered Rails, Nitro (calls a train others can board), **Cattle Car** |
| Iron Colossus | Iron Golem | Close-Range Power | Iron Fist, Barrage, Ground Slam, World Breaker, Iron Launch |
| Midnight Wing | Phantom | Long-Range | Talon Swipe, Sky Rush, **Aerosmith** (pilot it), Midnight Swarm, Dream Walk |
| Weeping Deluge | Squid | Long-Range | Tidal Slam, November Rain, Downpour, Rain Shot, Drift |
| Ancient Nose | Sniffer | Support | Snout Bump, **Sniff Out**, Thorn Vine, Spirit Photograph, Vine Sense |
| Smoke and Mirrors | Illusioner | Long-Range | Phantom Arrow, Mirror Images, Blinding Spell, Vanish, Mirror Maze |
| Void Stalker | Enderman | Close-Range Power | Void Slash, Barrage, Blink, Void Collapse, Scatter, Swap |
| **Catch the Rainbow** | Dolphin | Range Irrelevant | Fin Strike, Rain Call, Rain Walk, Rain Blades, Rain Cage, Rain Dash |
| Deep Dark Monarch | Warden | Close-Range Power | Dark Fist, Barrage, Sonic Boom, Sonic Devastation, Stop Heart |
| Lazarus Smile | Axolotl | Range Irrelevant | Play Dead (passive), Soul Swap, **Soul Control** (possess a mob), **Lazarus** (revive the dead) |
| Ember Sovereign | Blaze *(Nether)* | Long-Range | Rod Strike, Rod Barrage, Ring of Fire, Inferno Burst, Flame Barrage |
| Gilded Swine | Piglin *(Nether)* | Close-Range Power | Gilded Jab, Snatch |
| Thunderborn Swine | Zombified Piglin | Close-Range Power | Rusted Thrust. Only from a Pig Stand struck by lightning |
| **Crescent Shell** | Shulker *(End)* | Range Irrelevant | Shell Strike, Gravity Zone, **Gravity Punch** (wheel), **Gravity Turn** (wheel), Orbit |
| Oh! Lonesome Llama | Llama | Equipment | Spit, Lone Rider, Oh! Lonesome Me, Stampede. Tool: lasso *(command only)* |
| Reef Boy | Guardian | Equipment | Guardian Laser, Heartbeat, Deep Reel, Elder Curse. Tool: fishing rod *(command only)* |

Every punch lands with a MUDA-style burst of ghost fists. Stands never punch on their own unless you turn auto-assist
on in the config. Max level is 10. XP comes from using abilities and from kills.

## Signature tricks (new in 1.3)
* **Bone Cage** (Rattling Marksman, V): a bone at the creature you're looking at, and a cage of bone blocks snaps shut
  around it for 5 seconds before crumbling.
* **Silk Swing** (Weaver of Webs, V): shoot a web line at any block within 36 blocks and swing up to it. Press again or
  sneak to let go. You always land softly.
* **Sheer Heart Attack** (Green Detonator, V): a tiny creeper that hunts on its own - anything burning first - and
  blows up on it, then reforms: up to 3 blasts in 20 seconds. Never breaks blocks, never hurts you.
* **Lullaby** (Little Lullaby, V): everything within 12 blocks falls asleep for 6 seconds unless something hits it.
  Players get very drowsy instead. Bosses don't listen.
* **Truffle Hunt** (Muddy Fortune, X): your pig digs something up - food, gold, emeralds, sometimes a diamond, and once
  in a long while a Stand Arrow.
* **Cattle Car** (Iron Express, V): a minecart scoops up the creature you're looking at and hauls it off for 6 seconds.
* **Trample** (Gallop Crown): while you Rush, anything you run into is knocked flying.
* **Gelatinous Body** (Green Temperance): no fall damage - you bounce back up instead. Sneak to stop the bounce.

## Möbius Strip
Weaver of Webs' version of Stone Free's infinite string. It's not a key: the first time a hit would drop you below a
third of your health, your string twists into a loop with only one side, and that hit can't kill you. After that it
happens on its own whenever you're badly hurt (at most once every 2 minutes and 20 seconds). For 20 seconds:
* 60% of every hit runs round the loop and comes back out into whoever threw it (you take the rest).
* Nothing can turn you around: gravity flips slide off and nothing knocks you back.
* A white figure-eight of string turns around your waist.

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
* **Ancient Nose (Sniffer)**: Sniff Out sacrifices a spyglass to pick a structure; glowing thorn vines lead you all the
  way there. Thorn Vine, Spirit Photograph and Vine Sense round out its Hermit Purple kit.
* **Silver Fingers (Silverfish)**: Zipper Punch leaves a zipper on whatever it hits and the creature comes apart.
  Zip Away opens a zipper in a wall or the ground onto a pocket to hide in (you can still see out), and Zip Through
  zips you through walls, floors and ceilings up to 12 blocks thick.
* **Green Temperance (Slime)**: Devour Touch and Devour eat creatures whole, Temperance Armor soaks up hits with green
  goo, Masquerade disguises you as a mob.
* **Scary Monsters (Ravager)**: Diego Brando's dinosaur. It doesn't stand behind you - summoning it turns *you* into a
  Ravager (third person while you're turned): Regeneration II, Speed II, a high jump, no fall damage, and you sidestep
  Steel Balls thrown at you. Whatever you hurt starts turning into a dinosaur the size it already was (faster at
  higher levels) and joins your herd - kill it first and it gets back up where it fell and keeps turning. Only you
  infect; the herd doesn't. Dinosaurs only see what moves: the herd hunts monsters and whatever fights you, and loses
  anything that stands still - and while you're turned, still creatures vanish from your view too. Stampede (Z) sends
  the herd at what you're looking at, Living Wall (X) rings you with them and the nearest takes 70% of each hit,
  Call the Herd (C) brings them all back with a roar. Up to 5 dinosaurs, 14 at level 10.
* **Smoke and Mirrors (Illusioner)**: Mirror Images puts four copies of you around you that can take hits for you,
  Vanish makes you disappear completely for 10 seconds, and Mirror Maze leaves everything within 14 blocks blind and
  seeing you everywhere.
* **Iron Express (Minecart)**: lays rails as you go and calls a train - other players right-click a car to ride along.
* **Tool Stands**: with Oh! Lonesome Llama or Reef Boy out, tap R for an extra slot next to your hotbar holding a
  lasso (rope a creature, then ride the rope to it or tear it apart along it) or a fishing rod (hook a creature and
  reel it all the way in).
* **Catch the Rainbow (Dolphin)**: call the rain, walk on the raindrops, stop the rain into blades or a cage around a
  creature, and dash through it. In the rain, hits can pass right through you (Raindrop Body).
* **Crescent Shell (Shulker)**: sits on your shoulder. Z turns your gravity toward the wall or ceiling you look at
  (look at the floor to go back). Hold C for the Gravity Turn wheel: up = ceiling, left/right = the walls beside you,
  down = normal, middle = the wall in front. Hold X for the Gravity Punch wheel, pick a direction, and your next punch
  turns that creature's gravity that way for 3 minutes. On walls you walk, run and jump like normal ground.

## Requiems
| Requiem | From | How to earn it | Abilities |
|---|---|---|---|
| **The Ender World** | Enderman Stand | Requiem Trial: kill the Ender Dragon with your Stand out, spend a full day in the End, then pose at midnight | Void Jab, Barrage, Time Skip, **Time Stop** |
| **Pigstep** (a Piglin Brute) | Piglin Stand | Requiem Trial: Snatch 30 things, Snatch a Piglin Brute's axe, kill the Wither with your Stand out | Brute Jab, Steal, Release, **Another World**, Null Bubble |
| **Guardian Experience Requiem** | Iron Golem Stand | Use a Stand Arrow during a raid at 3 hearts or less: a 50/50. On a fail the arrow breaks, and there's a 15% chance your Stand drops to level 1 | Requiem Fist, MUDA Barrage, **Return to Zero**, Iron Shot, Golden Barrier |
| **Green Detonator: Bites the Dust** | Creeper Stand | Get struck by lightning (you or your Stand) with a level 10 Creeper Stand | Primer, Heart Attack, Big Bang, **Bites the Dust** |
| **Wonder of Deep** | Warden Stand | Not earnable yet (`/stand set warden_requiem`) | Calamity (passive), Dark Arrival |

* **Time Stop** uses the game's real tick freeze. Every other player in the dimension is frozen solid - they can't
  move, turn, hit, eat or open their inventory, and their Stands freeze too (spectators and anyone stopping time
  themselves can still move). Arrows stop in the air, and damage dealt to frozen things lands all at once when time
  moves again.
* **Pigstep** (Soft & Wet style) steals from a creature: pick its own property (a slime's bounce, a creeper's
  volatility, an enderman's teleport...) or its sound, then Release it onto something else or let the sound's power
  loose. It can also take a creature's sight, friction, heat, water, breath or footing for 10 seconds.
  **Another World** takes you and one mob or player somewhere only you two can see each other - mobs left outside
  can't see, chase or blow you up. **Null Bubble** drifts slowly through walls and armor and pops inside its target.
* **Return to Zero** is a counter with a skippable cutscene: the attacker rewinds, its copies look back, and it dies
  over and over, gone forever. Its loot goes to your inventory. Against bosses it just nullifies the attack, unless the
  boss is at 10% health or less. Against players it nullifies the attack.
* **Iron Shot** flicks an iron nugget that pierces through everything in a line; what it kills is erased completely
  (slimes don't split).
* **Golden Barrier** (V) is a Love Train-style wall of light.
* **Primer** (Killer Queen's first bomb): look at mobs to prime them, press again to set them off. Each one swells and
  flashes like a creeper, then goes up in a big, slow blast that breaks nothing, hurts nothing around it and leaves no
  trace. Hold Shift when you set them off for a real explosion. Players and bosses take a heavy hit instead.
* **Bites the Dust** winds the last 6 seconds back for everything within 40 blocks: mobs and players go back where
  they were with the health and items they had, the dead come back (players on the death screen respawn properly),
  broken blocks, chests, furnaces and ender chests return. Whoever hurt you in those seconds explodes when time snaps
  back. 20 s cooldown.
* **Wonder of Deep** never fights: it stands right behind you, tall and still. Monsters that come for you meet calamity
  faster the closer they get (they trip, dripstone falls on them, lightning finds them) and never reach you. Hits and
  arrows go back to whoever threw them. Summoning it darkens everyone nearby for 3 seconds (Z or
  `/stand darkness on|off` to toggle).

## Cursed Stands
Rare cursed spirits can latch onto you: Cheap Trick, Survivor, Notorious B.I.G. and Boy II Man. While one is attached your own Stand is
switched off for a while. Cheap Trick whispers mob noises at you until you go insane.
Operators: `/curse list`, `/curse info <curse>`, `/curse check`, `/curse give <curse>`, `/curse lift`.

## The world
* **Travel encounters**: now and then a Stand user shows up while you travel, more often on the road to a Woodland
  Mansion. They can drop arrows, and sometimes a Spin scroll.
* **The Mansion Evoker**: a Woodland Mansion can hide an Evoker with a time-stopping Stand. If you die near him, he
  rewinds time.

## Spin
Right click a **Steel Ball** to learn the Spin. The Stand menu (G) has a Spin page that explains every technique.
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

## PvP
* No single Stand hit can take more than 35% of a player's health (big finishers like Primer, Iron Shot, Big Bang
  and stored time-stop damage are capped). Plain sword hits and arrows from a normal bow are unchanged.
* Respawning has a 1 second cooldown for everyone.

## Commands (operators)
* `/stand set <type>`, `/stand level <1-10>`, `/stand clear`
* `/stand aura on|off`, `/stand darkness on|off`
* `/stand spin learn|forget|level <n>`
* `/stand encounter party|solo` (start a travel encounter), `/stand evoker timestop|rewind` (test the Mansion Evoker)
* `/curse list|info|check|give|lift`

## Code layout
```
com.mobstands
  stand/     StandType, StandClass (Stand types), StandData (saved per player), StandManager, StandAbilities,
             Signatures (signature tricks), Mobius, Ger, TimeStop, TimeEvoker, RewindEvoker, SoundSteal,
             RequiemTrials, HorseSpin, CreeperRequiem, Mandom (rewind), Calamity, GravityFlip, Clash, Lazarus,
             RainPowers, PropertySwap, OtherWorld, Zipper, Goo, Illusion, Hermit, Train, StandTools, Curses,
             Encounters
  entity/    StandEntity (driven by its owner, no AI), SpinItemEntity, HeartAttackEntity, ZipperEntity,
             CurseEntity, TrainCarEntity
  network/   payloads between client and server
  client/    renderers, poses, HUD, Stand menu, cutscenes, Spin input and effects
  mixin/     Time Stop tick freeze, gravity camera / look / hitbox, creeper-style swelling
```

## Notes
* Mob models are vanilla ones, rendered translucent with an extra glow.
* Explosions don't break blocks unless you turn that on in the config.
