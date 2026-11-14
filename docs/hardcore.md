# Hardcore layer

The hardcore layer makes survival itself harder: what you carry slows you down, falls break your legs, every return
from death costs a max heart, food matters, mobs are tougher, some nights are blood moons and the cold can freeze you.

**Everything here is off by default, except the flat anvil price and Mending.** Each feature has its own switch
(`hardcore.<feature>.enabled`, and `hardcore.hungerTax.multiplier` for the hunger tax), and with a feature off YOLT
changes nothing for it. Turn on only the parts you want, and turn the anvil and Mending off if you want them vanilla. Every setting is in the [`hardcore` part of the config](config.md#hardcore).

Contents:

- [Who it applies to](#who-it-applies-to)
- [`/status`](#status)
- [Slowdowns](#slowdowns)
- [Weight](#weight)
- [Broken legs](#broken-legs)
- [Limp](#limp)
- [Bed rest](#bed-rest)
- [Curse of the Undead](#curse-of-the-undead)
- [Nutrients](#nutrients)
- [Hunger tax](#hunger-tax)
- [Mending nerf](#mending-nerf)
- [Flat anvil prices](#flat-anvil-prices)
- [Elytra nerf](#elytra-nerf)
- [Tougher mobs](#tougher-mobs)
- [Relentless mobs](#relentless-mobs)
- [Blood moon](#blood-moon)
- [Cold](#cold)
- [Revive paper texture](#revive-paper-texture)
- [Where it is stored](#where-it-is-stored)

## Who it applies to

- Players in survival and adventure play by these rules. Creative and spectator players are left out: switching to
  either takes every hardcore slowdown and heart off at once, and they come back on a switch back.
- Logout bodies are you, so some features reach them. Each section below says how. In short: a body has your curse,
  a body's totem costs you a heart, and mobs hit a body as hard as they hit you. A body carries no weight, no
  nutrients and no chill. Its fall doesn't break your legs unless the server turns that on.
- A death resets what a new life resets: broken legs clear, chill goes to 0, and nutrients go back to their start
  level (nutrients only after a death in survival or adventure). The curse grows.
- While you are logged out nothing about you heals or ticks: no curse healing, no leg healing, no nutrient drain, no
  chill. A body can only lose health. Only what happens to your body reaches you.
- Nothing in this layer puts an icon on screen. Vanilla clients see only vanilla things: a slower walk, a shorter heart
  bar, frost on the screen, a boss bar.

## `/status`

Every player can type `/status` while at least one hardcore feature is on, which on a default server is always,
since the anvil and Mending rules ship on (see [Commands](commands.md) for the exact rule). It shows a report on your own body only, and each line has a hover with a plain explanation. Only features that
are on, and that have something to say, give a line:

```
Your body
Load 64.6 / 144 (free up to 16) → 19% slower
  heaviest: Cobblestone ×640 (30.0), Shulker Box (27.1), Oak Log ×128 (6.0)
Legs   broken → 20% slower · healing 12/30 (uses food once your health is full)
Total slowdown 46% (cap 50%; potions not included)
Curse of the Undead  −4 max hearts (the most it takes) · next heart back after 1h 12m more play
Nutrients  protein 62% · vitamins 18% (low: mining 15% slower) · sugar 40%
Cold   chilly 35% · warming (torch)
Resting  healing (bed)
Rules  hunger ×1.5 · Mending 0.4/XP (vanilla 2) · anvil: rename 1, repair 5, enchant 6
Blood moon tonight
```

With nothing to show it says "Nothing to report". It never shows coordinates, heights, biomes, distances or anything
about other players. One-off events (your legs breaking, the curse taking a heart, a nutrient running low) come as a
line over the hotbar instead.

Ops can read anyone's report with `/yolt status <player>`, for players who are online.

## Slowdowns

Weight, broken legs, the limp, cold and low sugar all slow you down. They are added together, and the total stops at a
cap.

- `hardcore.slow.cap` is 0.5: you are never more than 50% slower from YOLT. Example: legs 20% + limp 20% + weight 12%
  is 52%, so you get 50%.
- The total moves in steps of `hardcore.slow.step` (0.05, so 5%), rounded to the nearest step, so your speed and view
  don't wobble.
- Vanilla potions are separate and come on top. Slowness under a 50% slowdown slows you further.
- It slows walking and running on the ground. It doesn't slow jumping through the air: a sprint-jump gets a fixed push
  from the game, so sprint-jumping mostly skips the slowdown. Swimming, ladders, elytra, horses, boats and minecarts
  ignore it too.
- `/status` shows the total, capped, as "Total slowdown 46% (cap 50%; potions not included)".

**Field of view:** a slowed vanilla client narrows its view a little, the same way it widens it when you sprint. That
is the cue that you are slow. Players who dislike it can turn down vanilla's "FOV Effects" slider in their own game
settings.

Settings: [`hardcore.slow`](config.md#hardcoreslow).

## Weight

Everything you carry weighs something. The heavier your load, the slower you walk, and heavy jumps cost extra hunger.

- Each item has a **stack weight**, the weight of a full stack. One item weighs its share: a stack of cobblestone weighs
  3.0, so one cobblestone weighs 3/64.
- Your **load** is everything you carry: hotbar, inventory, armour, off-hand, the item on your cursor and your 2x2
  crafting grid (`countCursorAndGrid`). A shulker box or bundle counts with everything inside it, up to 3 containers
  deep (`containerDepth`), and `containerContents` (1.0) sets how much of what is inside counts. Your ender chest
  doesn't count.
- Up to a load of 16 (`freeLoad`) you aren't slowed. From 16 to 144 (`fullLoad`) the slowdown rises evenly to 50%
  (`maxSlow`), and it goes no further.
- Armour and tools barely count. With your kit you walk at full speed carrying about a dozen stacks of ordinary blocks
  or four of stone. A full inventory of ordinary things is about 10% slower, a full inventory of stone about a third.
- Over the free load, each jump costs extra hunger: `heavyJumpExhaustion` (0.2) at a full load, less in between, none at
  the free load. It goes through the game's own hunger, so the hunger tax multiplies it.
- Weight never changes how high you jump, only the hunger a jump costs.
- When your load first slows you, the hotbar line says "Your load is slowing you down."
- One full shulker box of stone is a quarter slower by itself, and two are a full load, on purpose. Moving bulk is a
  job for horses, boats and minecarts, which weight never slows.
- Logout bodies carry no weight.

What weighs what, from first match to last:

1. `itemsEach`: a weight per single item. Elytra 0.5, mace 1.0, anvil 1.5 (about the iron it is made of).
2. `items`: a stack weight per item id. Paper 0.25, and 3.0 for stone-like blocks no tag below reaches, such as
   deepslate bricks and tiles, bricks, smooth stone, polished stones, tuff bricks, blackstone and end stone bricks.
3. Armour (worn or carried): its armour points times its material's factor (`armourFactors`). An iron chestplate
   (6 points, factor 0.1) weighs 0.6, a full iron set 1.5 and a full netherite set 4.0. Horse and wolf armour aren't
   armour for this.
4. Swords, axes, pickaxes, shovels, hoes and spears: 0.25 each (`toolWeight`).
5. `groups`: a stack weight per item tag, the first tag in the list that matches. Ores, raw metals, ingots, stone,
   cobblestone, obsidian, stone bricks and logs weigh 3.0 a stack. Iron, gold, copper, netherite and raw metal blocks
   weigh 27.0, other storage blocks 9.0, so crafting ingots into blocks doesn't make them lighter. Feathers and string
   weigh 0.25.
6. Anything else that stacks to 1 (potions, buckets, beds, totems, shields, a shulker box itself): 0.1 each
   (`unstackableWeight`).
7. Anything else: 1.0 a stack (`defaultWeight`).

`/status` shows your load, your slowdown and your three heaviest kinds of thing:

```
Load 64.6 / 144 (free up to 16) → 19% slower
  heaviest: Cobblestone ×640 (30.0), Shulker Box (27.1), Oak Log ×128 (6.0)
```

Settings: [`hardcore.weight`](config.md#hardcoreweight).

## Broken legs

A long fall breaks your legs. You are 20% slower (`slow`) until they heal, and healing uses food the way health regen
does.

- Your legs break when one fall does 6 damage (3 hearts, `breakAtDamage`) or more after armour and protection
  enchantments, so Feather Falling and Protection help. With no Feather Falling or Protection that is a fall of about
  9 blocks. The hotbar line says "Your legs broke."
- Falls and stalagmites count. Ender pearls never break legs. Flying into a wall with an elytra counts only with
  `includeElytraCrash` on.
- Soft landings (hay, beds, slime, water and the rest) cut the damage as in vanilla, so they protect your legs too.
- **Heavy landings** (`heavyLandings`, off): with weight on as well, a heavier load breaks your legs from a shorter
  fall, down to 4 damage (2 hearts, `breakAtDamageFullLoad`) at a full load. It only changes when legs break, never
  the fall damage.
- **Healing** takes 30 points (`healPoints`). By default your legs only heal once your health is full (`healthFirst`).
  Then they gain a point every 80 ticks (4 seconds, `healTicks`) while your food bar is at 18 or more, or every 40
  ticks (2 seconds, `healTicksSaturated`) when it is full and you have saturation. Below 18 food they don't heal. That
  is 2 minutes, or 1 minute when saturated.
- Each point costs 3.0 exhaustion (`exhaustionPerPoint`), straight from your food, so the hunger tax doesn't add to it.
- Regeneration (golden apples, potions, a beacon) heals legs at its own pace, with no food used (`regenEffectHeals`).
- With the `natural_health_regeneration` game rule off, legs heal a point every 1200 ticks (`healTicksNoRegenRule`)
  with no food used. 0 means they never heal then.
- Bed rest makes them heal twice as fast, and low protein half as fast.
- Breaking them again while broken starts the healing over. Dying clears them; a totem doesn't. When they heal, the
  hotbar line says "Your legs have healed."
- **Logout bodies:** a body's fall only costs it health, by default. Nothing heals while you are logged out, so legs
  broken then would stay broken until you came back. A server that wants it anyway turns on `bodiesCanBreakLegs`: a
  body's fall then breaks your legs at the plain 6 damage, and they heal once you log back in.

Settings: [`hardcore.legs`](config.md#hardcorelegs).

## Limp

With very low health you limp: 20% slower (`slow`) until you heal back up.

- You start limping below 2 hearts (`belowHearts`) and stop at 3 hearts or more (`recoverAtHearts`). The gap stops it
  switching on and off while health creeps back up.
- Absorption (golden) hearts don't count, unless `countAbsorption` is on.
- A totem leaves you at half a heart, so you limp until you heal to 3 hearts.
- It has no `/status` line of its own. The "Total slowdown" line's hover says when a limp is part of it.
- A server can let the curse take you down to `recoverAtHearts` or fewer, as a harsher punishment. Then, once you are
  low, you limp until a curse heart comes back. The log notes such a mix, but allows it.

Settings: [`hardcore.limp`](config.md#hardcorelimp).

## Bed rest

Lying in a bed heals you at Regeneration's pace and makes broken legs heal faster, until you get up.

- With `allowDaytime` on, you can lie down in the day too, wherever beds don't explode. Vanilla's monster check still
  applies. With it off, vanilla's own rule decides when you can sleep.
- In bed you heal half a heart every 50 ticks (2.5 seconds, `healEveryTicks`), the pace of Regeneration I, with no food
  used (`exhaustionPerHeal` 0). 0 means no healing. It only fills the hearts you have; it never lifts the curse.
- Broken legs heal twice as fast in bed (`legHealSpeed`). The usual leg-healing rules still apply: full health first
  (by default), and food at 18 or more, except under Regeneration or with `natural_health_regeneration` off.
- Getting up stops it. Taking damage wakes you, as in vanilla.
- Low protein halves it, as it does other healing.
- A daytime nap never skips time: it doesn't count towards skipping the night, even on a server with
  `sleep.noNightSkip` off. A nap still resets the time phantoms count from, as any rest does.
- There is no potion icon. It heals like Regeneration, but it isn't the effect, so milk has nothing to take away.
- `/status` shows "Resting  healing (bed)".

Settings: [`hardcore.bedRest`](config.md#hardcorebedrest).

## Curse of the Undead

Every return from death, by dying or by a totem, takes a max heart. The hearts come back one at a time as you play.

- **Dying:** when you come back (after the lockout, or early with a revive paper), you have 1 fewer heart
  (`heartsPerDeath`). `/yolt unkill` gives it back (see below). The hotbar line tells you when you are back.
- **A totem** (or anything with death protection) costs 1 max heart at once (`heartsPerTotem`).
- It takes at most 4 hearts in all (`maxLostHearts`). Past that, more deaths and totems cost nothing extra until a
  heart comes back.
- **Healing:** 1 heart (`healHearts`) comes back after every 3 Minecraft days (`healEveryDays`, decimals allowed) of
  your own online play in survival or adventure. A day is 24,000 ticks, so that is 60 minutes of play. Only your
  time online counts, never world time. Losing another heart doesn't restart the count.
- With `countAfk` off, time doesn't count while you haven't moved or acted for 5 minutes.
- Every counted death counts: dying online, your logout body being killed, and `/yolt kill` while you are offline.
  Deaths in creative or spectator never count, even with `deathLockout.countCreativeAndSpectatorDeaths` on.
- `/yolt unkill` gives back what the death it undoes took (`unkillRefunds`). A revive paper gives back nothing unless
  `reviveRefundHearts` is above 0. Ops can also set or clear anyone's curse with `/yolt curse`.
- It is a name, not a potion effect: your heart bar is shorter and there is no icon. Health Boost still adds hearts on
  top.
- **Logout bodies** have your curse, so a body never has more hearts than you. A totem that saves your body costs you
  the heart.
- The hotbar line reads "Curse of the Undead: −1 max heart." when you lose one, and "+1 max heart" when one comes
  back.
- `/status` shows the hearts lost and the play time until the next one comes back.

Settings: [`hardcore.curse`](config.md#hardcorecurse).

## Nutrients

Foods carry hidden protein, vitamins and sugar. Eat a mix, or the group you are short on costs you.

- You have three hidden levels, 0 to 100%. You start at 60% in each (`startLevel`), and again after each death.
- **Eating** fills the food's groups: each point of the food's nutrition adds 3% (`gainPerPoint`), split between its
  groups. Cooked beef (8 nutrition, all protein) gives 24% protein. Only the hunger a food actually restores counts:
  eaten at a full bar it gives nothing, and at 19 one point's worth. A slice of cake counts as 2 nutrition.
- **Using food** drains all three: each time your food bar drops by 1, each group loses 1% (`decayPerPoint`).
  Saturation going down doesn't count.
- So with the default numbers a group holds steady when about a third of the food points you eat carry it.
- **Below 25%** (`deficientBelow`) you are short on that group:
  - low protein: health regen, broken legs and bed rest heal at half speed (`penalties.protein.healingMultiplier`);
  - low sugar: 10% slower while sprinting (`penalties.sugar.sprintSlow`), inside the slowdown cap;
  - low vitamins: mining is 15% slower (`penalties.vitamins.miningSlow`).
- The hotbar line tells you once when a group runs low ("You're low on vitamins.") and once when it is back up.
- Milk doesn't cure it, since these aren't potion effects. On Peaceful the levels don't move, but a group that is
  already low still costs you.
- Which food is in which group is in the [`foods` table](config.md#file-only-settings). Meat and fish are protein,
  fruit and vegetables vitamins, bread, cookies, honey and cake sugar, and a few foods are mixed. A food the table
  doesn't list gives nothing (`unknownFood`).
- Logout bodies carry no nutrients.
- `/status` shows each level, and which are low and what that costs.

Settings: [`hardcore.nutrients`](config.md#hardcorenutrients).

## Hunger tax

One multiplier on the hunger that actions cost: sprinting, jumping, attacking, mining, swimming, taking damage, heavy
jumps and shivering. It has no switch: 1.0 (`multiplier`) is vanilla.

- At 2, everything you do drains food twice as fast.
- Healing is never taxed: health regen, broken legs and bed rest put their cost straight on your food.
- While it isn't 1, the `/status` "Rules" line shows "hunger ×2".

Settings: [`hardcore.hungerTax`](config.md#hardcorehungertax).

## Mending nerf

Mending repairs 0.4 durability per XP point (`durabilityPerXp`) instead of vanilla's 2.

- XP you pick up still goes to damaged Mending gear first, but repairs a fifth as much.
- No XP is lost. A part of a point too small to repair anything yet is kept for your next pickup (until you log out),
  and XP the gear doesn't need goes to your XP bar.
- 0 turns Mending off: all XP goes to the bar.
- The anvil becomes the main way to keep gear going.
- The `/status` "Rules" line shows "Mending 0.4/XP (vanilla 2)", since the tooltip still just says "Mending".

Settings: [`hardcore.mending`](config.md#hardcoremending).

## Flat anvil prices

Every anvil use costs one fixed price, set by the biggest kind of job in it, and nothing ever becomes "Too Expensive!".

| The use includes | Price |
|---|---|
| any enchanting | 6 (`enchantCost`) |
| repairing, but no enchanting | 5 (`repairCost`) |
| only a rename | 1 (`renameCost`) |

- One use that enchants and renames costs 6, not 7. Combining two worn swords where the right one adds an
  enchantment costs 6, not 11.
- The price never grows with use. Vanilla's hidden "prior work" counter adds nothing to the price while this is on,
  and every item that comes out of an anvil has it at 0, even one that had a counter from before, on either side.
- A use that wouldn't change the item has no result, and nothing is charged or used up.
- What stays vanilla: enchanting a whole stack at once stays refused, which enchantments go together and their
  levels, the materials used up, and the name length limit.
- Anvils still wear down, with a 12% chance per use (`breakChance`).
- Turning it off later: every item that went through an anvil while it was on prices like a new one, and vanilla
  carries on from there. Items that never went through one keep their old counter.
- Creative players keep vanilla's anvil.
- The `/status` "Rules" line shows "anvil: rename 1, repair 5, enchant 6".

Settings: [`hardcore.anvil`](config.md#hardcoreanvil).

## Elytra nerf

Gliding is slower and floatier, and rockets can't push you past 20 m/s.

- While you glide, gravity is 0.6 times vanilla's (`glideGravityMultiplier`). Lighter gravity makes the glide slower,
  and you sink slower too, so you cover about the same ground for each block of height. Walking, jumping and falling
  stay vanilla.
- While a rocket boosts you and your horizontal speed passes 20 m/s (`rocketCutoffSpeed`), the rocket ends early,
  with no explosion and no damage. 0 turns the cut-off off.
- A rocket fired while you are already past the cut-off does nothing and isn't used up.
- The client flies with the server's gravity itself, so the flight stays smooth.
- Weight, legs and the limp don't slow gliding.

Settings: [`hardcore.elytra`](config.md#hardcoreelytra).

## Tougher mobs

Hostile mobs have more health, hit harder and move a little faster.

- Every hostile mob gets 1.5 times the health (`health`), 1.25 times the damage (`damage`) and 1.1 times the speed
  (`speed`).
- The damage boost covers any hit the mob causes: melee, arrows and fireballs. Poison, wither and fire damage stay
  vanilla. The game's difficulty scaling comes first, then the multiplier.
- By default only players and logout bodies take the extra damage (`damageAppliesTo: players`). Villagers, golems and
  pets take vanilla damage. `all` boosts hits on everything.
- Spawner mobs are boosted (`includeSpawners`). Trial spawner mobs (`includeTrialSpawners`) and mobs from spawn eggs,
  dispensed eggs and commands (`includeCommandsAndEggs`) stay vanilla by default. How a mob spawned is judged once,
  the first time YOLT sees it.
- Bosses and the warden are left alone by default (`exclude`: wither, ender dragon, elder guardian, warden).
- Per-mob overrides: ghasts get 2 times the health, phantoms stay fully vanilla (health, damage and speed 1.0), and
  creepers get no damage boost.
- Endermen, zombified piglins, piglins and spiders count as hostile, so they are boosted too. Add them to `exclude` if
  you want them left alone.
- A mob keeps its boost through chunk unloads and restarts, without it stacking, and a mob that converts (a zombie
  that drowns, a skeleton that freezes) keeps its boost.
- Health and speed changes reach each mob the next time it loads. Turning the feature off takes the boost off a mob
  the next time it loads. A `damage` change applies at once, since it is read as each hit lands.

Settings: [`hardcore.tougherMobs`](config.md#hardcoretoughermobs).

## Relentless mobs

Mobs notice and chase you from further away, and zombies break doors on every difficulty.

- Hostile mobs track you from 1.5 times as far as vanilla (`followRange`). Most still need to see you to target you.
- **Doors:** in vanilla only some zombies can break doors, and only on Hard. With this on, a quarter of zombies and
  their kin (`doorBreakerChance`) can break wooden doors, on every difficulty (`doorsAnyDifficulty`).
- Each zombie rolls once, as it loads, and only when it can really break doors: a drowned in water rolls the next time
  it loads on land. A zombie that won keeps it for good, even after the feature is turned off.
- A broken door drops as one item, as in vanilla (`dropBrokenDoor`). Off: the door is gone and nothing drops.
- The warden is left alone (`exclude`), and so is everything on tougher mobs' `exclude` list.
- Mobs hunting logout bodies still search at vanilla's range.

Settings: [`hardcore.relentlessMobs`](config.md#hardcorerelentlessmobs).

## Blood moon

On some nights, picked by chance, more mobs come out, and they are tougher, better geared and drop more.

**When:**

- Each night has a 10% chance (`chance`) of being a blood moon, with at least 5 calm nights between two
  (`minNightsBetween`), and none in the first 8 days of the world (`graceDays`). `fullMoonsOnly` lets only full moons
  roll.
- The roll comes from the world seed and the day number, so a restart can't roll a night again, and who is online
  changes nothing. `/time set` moves to another night's roll; the gap still counts from the last blood moon.
- Overworld only. The night runs from tick 12542 to 23460 of the day (`startTick`, `endTick`). Even with time frozen,
  one lasts at most that many ticks.

**What players see:**

- At tick 9000 (`warnTick`), about 3 minutes before dark, every player in the overworld gets a title ("The moon will
  rise red tonight…"), a chat line and the raid horn (`warning.title`, `warning.chat`, `warning.sound`). Each is sent
  to each player on their own, never played in the world.
- During the night, a red "Blood Moon" boss bar drains as the night passes (`bossBar`), the sky darkens (`darkenSky`),
  and optionally there is fog (`fog`). A player who joins or comes into the overworld gets the bar and one chat line.
- At dawn a line says the blood moon has set.
- `/status` shows "Blood moon tonight" from the warning until it ends, in the overworld.

**What changes:**

- Twice as many mobs can spawn (`spawnCap`). Around a `player`-type logout body the cap stays vanilla, or every logged
  out body would be swarmed (`capBoostNearPlayerBodies`).
- Mobs that spawn during the night are marked for life. The mark goes on the same mobs tougher mobs would touch (its
  `exclude` list and its spawner, egg and command switches), whether or not tougher mobs is on. Mobs that come through
  a portal during the night aren't marked. Marked mobs get, on top of tougher mobs:
  - 1.25 times the health (`health`) and 1.1 times the speed (`speed`);
  - no extra damage by default (`damage` 1.0);
  - armour at least 35% of the time (`armourChance`) and enchanted gear at least 25% of the time (`enchantChance`),
    rolled as they spawn;
  - 2 times the XP (`xp`);
  - one extra roll of their own loot table when a player kills them (`extraLootRolls`). Gear they wear or picked up
    drops once, as in vanilla.
- `chargedCreeperChance` (0) can make creepers spawn charged.
- You can't sleep through it. Core blocks the night skip already (`sleep.noNightSkip`); on a server that turned that
  off, a blood moon still blocks the skip for its night, unless `canSleepThrough` is on.
- Marked mobs keep their boosts after dawn. Most burn in the sun anyway.

Ops can test it with `/yolt bloodmoon start`, `stop` and `status`. A started one that a restart cuts short comes back
as that day's blood moon: after the restart it runs on to `endTick`, or, if the restart came before dark, the warning
comes again and then a whole night.

Settings: [`hardcore.bloodMoon`](config.md#hardcorebloodmoon).

## Cold

Out in the open where it is cold enough to snow, a hidden chill rises. Frost creeps over your screen, you slow down
and shiver, and when the chill is full you freeze. Heat and leather stop it.

- **Where:** anywhere in the overworld cold enough to snow: snowy and frozen biomes, and high up on some mountains.
  Never in the Nether or the End.
- **Only outdoors:** where the sky light is below 8 (`shelteredBelowSkyLight`), under a roof or in a cave, you count
  as indoors and your chill falls.
- **The chill (0 to 100%):** out in the cold it rises 1% a second (`chillPerSecond`), about 100 seconds from warm to
  frozen. Rain or snow on you makes it twice as fast (`wetMultiplier`), and water four times (`waterMultiplier`).
  Anywhere warm it falls 3% a second (`warmPerSecond`), so a full chill clears in about 33 seconds.
- **What keeps you warm:**
  - holding a torch or lantern in either hand (torches, soul torches, copper torches and every lantern);
  - being within 4 blocks (`heatRadius`) of a lit campfire, fire, lava, a magma block, or a lit furnace, smoker or
    blast furnace;
  - leather armour: each piece cuts how fast the chill rises by 25% (`leatherPerPiece`), so a full set stops it. Other
    armour does nothing.
- **What it does:** vanilla's frost creeps in from the screen edges as the chill grows. You are up to 20% slower at
  full chill (`maxSlow`), inside the slowdown cap, and shivering costs a little hunger (`shiverExhaustionPerSecond`
  0.05 a second at full chill, less below). At 100% your hearts turn blue, your character shakes, and you take half a
  heart of freeze damage every 2 seconds (`freezeDamage`). With no leather that is vanilla's own freeze hit. With 1 to
  3 leather pieces vanilla stops freezing you, so YOLT deals the hit instead, every `damageEverySeconds` (2).
- **Cold doesn't kill by default** (`canKill` off): freeze damage stops at half a heart, the way starvation does on
  Normal. With half a heart left, any mob or fall still can.
- Powder snow keeps vanilla's rules. Whichever is stronger, the chill or the powder snow, is what you get. Fire clears
  all freezing.
- **Spawning:** while cold is on, random spawns skip spots cold enough to snow, for first joins and respawns. If every
  try lands somewhere cold (a snowy world, say), it falls back to the normal pick rather than not spawning you.
- Chill is saved, so a relog keeps it. A death resets it to 0.
- Logout bodies and graves never get cold.
- `/status` shows "Cold   chilly 35% · warming (torch)": how cold you are and why it is moving, never where you are.

The torches and the heat blocks are the item tag `#you-only-live-twice:held_heat` and the block tag
`#you-only-live-twice:heat_sources`, so a datapack can change them.

Settings: [`hardcore.cold`](config.md#hardcorecold).

## Revive paper texture

Revive papers can get a texture of their own from a server resource pack. This is the one part of YOLT that uses a
resource pack, and it is off by default (`resourcePack.reviveTexture`).

YOLT never sends or hosts the pack itself. To set it up:

1. Download `you-only-live-twice-revive-pack-<version>.zip` from the
   [release](https://github.com/BobbyNooby/youonlylivetwice/releases/latest) and host it at a URL players can reach.
   The file on the release page works as it is.
2. In `server.properties`, set `resource-pack` to that URL, `resource-pack-sha1` to the zip's SHA-1 hash,
   `require-resource-pack=true`, and if you like a `resource-pack-prompt` message.
3. Turn the switch on: `/yolt config resourcePack.reviveTexture true`.

What the switch does:

- It only sets the item model on revive papers to `resourcePack.reviveModel`
  (`you-only-live-twice:revive_paper`, the model in the pack). The name and lore still say whose paper it is.
- Papers in a player's inventory, and in a container the player has open, follow the switch within a second, both
  ways. Papers elsewhere (in item frames, on the ground, in bundles, shulker boxes and closed chests) keep the model
  they had until they are back in a player's inventory or in a container a player has open. So after you turn the
  switch off, those show the purple-and-black missing texture to players without the pack.
- A model an admin set on a paper by hand is never touched.
- With the pack required, players who decline it can't join (vanilla). With it not required, players without it see
  the missing texture on revive papers.
- The texture in the pack is a placeholder for now.

Settings: [`resourcePack`](config.md#resourcepack).

## Where it is stored

Every player's hardcore state (broken legs, the curse, nutrient levels and chill) and the day of the last blood moon
are kept in one file for the world, `<world>/database/you-only-live-twice-hardcore.db`. It is made only when something
is first stored, so a server that never turns the layer on never gets one. An online player's state is saved when
they leave and every 5 minutes. If the file can't be opened, the state lasts until the server stops and an error is
logged.

[All pages](README.md)
