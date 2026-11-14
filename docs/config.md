# Config

Contents:

- [Top level](#top-level)
- [`worldBorder`](#worldborder)
- [`deathLockout`](#deathlockout)
- [`graves`](#graves)
- [`revive`](#revive)
- [`chat`](#chat)
- [`hide`](#hide)
- [`sleep`](#sleep)
- [`bodies`](#bodies)
- [`serverList`](#serverlist)
- [`skins`](#skins)
- [`hardcore`](#hardcore): the [hardcore layer](hardcore.md), off by default apart from the flat anvil price and Mending
- [`resourcePack`](#resourcepack)
- [File-only settings](#file-only-settings)
- [Load checks](#load-checks)

The file is `config/you-only-live-twice.json`. Edit it and run `/yolt reload`, or use `/yolt config <key> <value>`,
which changes one key in the file and in the running server at once. Other `/yolt` commands that change a setting
save it to the file too.

No setting needs a restart. The "Restart?" column says when a change shows:

- **No:** at once.
- **Next join:** when a player next joins.
- **Next join, respawn or dimension change:** for each player, at the first of those.
- **New bodies:** for bodies made from then on. Bodies already standing keep how they were made.
- **New deaths:** for deaths from then on. Graves and lockouts already there stay as they are.
- **As mobs load:** for each mob the next time it loads: when it spawns, or when its chunk loads again. Mobs already
  loaded keep the old value until then.

How the file is read:

- A bad value (the wrong type, or out of range) falls back on its own, with a warning in the log naming the key. The
  rest of the file still counts.
- At startup a bad or missing value takes its default, and the file is rewritten with every key. If anything you
  typed fell back, a copy of your file is kept first as `you-only-live-twice.json.bak`.
- On `/yolt reload` a bad value keeps the value in use. If the file can't be read (it isn't valid JSON, say, or it is
  missing), the reload changes nothing and tells you why.
- If the reload can't write the file back, what it read still applies, and the reply says the file is left as it was.
- To reset a key, set it to its default. Deleting a key from the file and reloading keeps the value in use, with no
  warning. A restart would give it its default.
- Unknown keys (most likely a misspelling) are warned about and left out when the file is rewritten.
- `serverList.hoverLines` and `hide.tabHeaderLines` are lists, so they can only be changed in the file. So are the
  hardcore layer's lists and maps, see [file-only settings](#file-only-settings).
- `§` colour codes work in the text settings (the client draws them), for example `"§cNo one is coming."`.
- With no config file at startup (the first start, or a deleted file), YOLT writes the defaults and uses them.
- The file is rewritten at every start, so comments typed into it aren't kept.
- If the file can't be written back at startup (a read-only folder, say), what was read still applies, with a warning
  in the log. With no file, or one that can't be read, in a folder that can't be written, YOLT runs on the defaults and
  leaves the folder as it is.
- Every write goes to a temp file first, which is forced to disk and then moved over the config, so a crash or a power
  cut can't leave half a file.

How an upgrade works:

- `configVersion` says which layout the file has. A file from an older YOLT is copied to
  `you-only-live-twice.json.v<old version>.bak` before anything is written, then upgraded, and the log says so.
- A file without `configVersion` was typed by hand, so it is read as the current version.
- An upgrade logs one line naming the sections and keys the old file didn't have, which take their defaults.
- A default only changes through an upgrade step that flips a value still at its old default. Upgrading from 1.0.0,
  `hide.coordinates`, `skins.lockAfterFirstChoice` and `chat.allowWhispers` are turned on only where the file still
  says `false`, 1.0.0's default, with one log line for each that says how to turn it off again, for example
  `/yolt config hide.coordinates false`. A `true`, a missing key or a wrong value is left alone.
- A file from a newer YOLT is read as far as this version can and never rewritten.

## Top level

| Key | Default | What it does | Restart? |
|---|---|---|---|
| `configVersion` | `2` | The file's layout version, used for upgrades. Leave it. A value of 0 or below logs a warning and reads as the current version. | – |
| `restrictCommands` | `true` | Non-ops only get `/help` (plus `/w`, `/msg` and `/tell` when whispers are on, `/status` while a hardcore feature is on, which it is by default, and `/yolt skin` when players may pick their skin). Ops below level 2 count as non-ops. Off means vanilla commands, and then `/teammsg`, `/list` and vanilla suggestions show who is online. `/msg` and `/tell` are YOLT's whisper either way. | No |
| `netherScaleOneToOne` | `true` | Every dimension's coordinate scale is 1, modded dimensions included, so the Nether matches the Overworld and there is no fast travel. A Nether portal then links only to an Overworld portal within 16 blocks of its exit. | No; it applies at once, and changing it on an existing world breaks portal links |
| `randomSpawn` | `true` | Players with no spawn point spawn at a random spot inside the border. | No |
| `immediateRespawn` | `true` | Turns on the `immediate_respawn` game rule at start, on `/yolt reload` and on `/yolt config`. | No |

## `worldBorder`

| Key | Default | What it does | Restart? |
|---|---|---|---|
| `enabled` | `true` | Manage the border. Off leaves the border alone, and `/yolt center` is refused. | No |
| `diameter` | `10000` | Overworld and Nether border width in blocks, 1 to 59,999,968. | No |
| `endScale` | `2.0` | The End border is `diameter` times this. Above 0; the End border is capped at 59,999,968. | No |

With `worldBorder.enabled` off, random spawn still picks spots in a square `worldBorder.diameter` blocks across,
centred on the border centre (so at most half the diameter from it on each axis), and inside the live border. Set the
diameter to the area you want people to spawn in.

## `deathLockout`

| Key | Default | What it does | Restart? |
|---|---|---|---|
| `enabled` | `true` | Dying kicks you and blocks rejoining. | No |
| `cooldownDays` | `7` | Days a death locks you out, 0 or more. 0 means no wait. | New deaths |
| `countCreativeAndSpectatorDeaths` | `false` | Off: a death in creative or spectator makes no lockout, grave or death record, and the player keeps their items, XP and score. On: they count, and a spectator's grave holds only the grave head. | New deaths |

## `graves`

| Key | Default | What it does | Restart? |
|---|---|---|---|
| `enabled` | `true` | Deaths make graves. Off only stops new ones. | New deaths |
| `enderChestOnDeath` | `"keep"` | `keep`, `grave` or `wipe`. | New deaths |
| `recheckSeconds` | `5` | How often graves in loaded chunks get a missing head put back. 0 turns that off (a chunk load still does it). | No |
| `tellLocation` | `"whenMoved"` | `never`, `whenMoved` or `always`: when the dead player is told where their grave is. | No |
| `tellLocationDistance` | `16` | Blocks from the death spot a grave must be for `whenMoved` to tell, 0 or more. | No |
| `compass` | `false` | A compass to the latest grave, given when the owner is next in and alive. | New deaths |
| `searchRadius` | `16` | How far out, in blocks, a new grave looks for safe ground when nothing close to the death is safe. 3 to 32. | No |
| `voidSearchRadius` | `16` | How far out, in blocks, a death over the void looks for land when the owner has no last ground there. 1 to 32. | No |

## `revive`

| Key | Default | What it does | Restart? |
|---|---|---|---|
| `enabled` | `true` | Revive papers work when right-clicked. | No |

## `chat`

| Key | Default | What it does | Restart? |
|---|---|---|---|
| `mode` | `"proximity"` | `proximity`, `self_only` or `global`. | No |
| `radius` | `48` | Blocks, for proximity chat, 1 or more. | No |
| `allowWhispers` | `true` | Non-ops can use `/w`, `/msg` and `/tell`, which are all the same whisper. | No |
| `whisperRadius` | `0` | Blocks a whisper reaches, for ops too. 0 means the same as `radius`. No range in `global` mode. | No |

## `hide`

| Key | Default | What it does | Restart? |
|---|---|---|---|
| `tabList` | `true` | Nobody is listed in the tab list. | No |
| `nametags` | `true` | Hides nametags with a scoreboard team. | No |
| `serverPlayerCount` | `true` | The server list, old pings and the query port show 0 online and no names. | No |
| `gameMessages` | `true` | Hides death, join, leave and advancement messages. | No |
| `tabHeader` | `true` | Shows a random flavour header in the tab list. Off clears it. | No |
| `tabHeaderLines` | 13 flavour lines | The lines the header is picked from, at most 64 of 256 characters each. `[]` shows no header. File only. | No (on `/yolt reload`) |
| `distantPlayers` | `true` | Each player's game only learns about players near them. | No |
| `locatorBar` | `true` | Turns the `locator_bar` game rule off. | No |
| `globalSounds` | `true` | Turns the `global_sound_events` game rule off. | No |
| `mapPlayers` | `true` | Other players show on shared maps only to holders who can see them. | No |
| `debugScreen` | `true` | Reduced F3 for every player. Admin view lifts it. | No |
| `serverBrand` | `false` | F3 names the server "vanilla" instead of "fabric". | Next join |
| `compassSpawn` | `true` | A normal compass points north in every dimension, not at the world spawn. Recovery, lodestone and grave compasses keep their own targets. Admin view shows the real spawn. | No |
| `coordinates` | `true` | Each player sees X and Z shifted by a private amount, new at every join, death respawn and dimension change. Admin view sees real ones. | Next join, respawn or dimension change |

Notes:

- With `hide.distantPlayers` off, the join packet lists offline names in our team.
- `/yolt reload` remakes the `invisible_players` team if someone removed it, and puts players and loaded player
  bodies back in it.
- `hide.mapPlayers`: a map held in hand still fills in terrain for every copy, which shows someone's path. That can't
  be hidden.
- `hide.compassSpawn`: a player within 1,000,000 blocks of the north edge of the world (Z below -28,999,984) gets a
  wrong needle. A 10,000-block border never gets close.
- With `hide.serverPlayerCount` on, the query port reports 0 players and no names. It also shows no names whenever
  `serverList.hoverLines` has lines. Vanilla caches the query port's full answer for 5 seconds, so a change can take
  that long to show there.
- A player who joins or changes dimension is put back in the `invisible_players` team, so a team an admin puts them
  in doesn't stick. Sharing `invisible_players` doesn't make players allies: pets, sword sweeps and mobs treat them
  as players in no team, and team options that need allies (friendly fire off, own-team collision) do nothing on it.
- Teams and scoreboard objectives that admins make are sent to every client. An objective an admin shows in the
  sidebar, below names or in the list reaches non-ops too, and it can show player names. YOLT doesn't hide it.
- Chat-bridge mods (Discord bridges and the like) don't see proximity chat.
- The plain-text chat fallback skips the Social Interactions block list and "Only Show Secure Chat".

## `sleep`

| Key | Default | What it does | Restart? |
|---|---|---|---|
| `hideSleepers` | `true` | The sleeper count reads zero and nobody gets the "players sleeping" line, so nobody can tell who is in bed. | No |
| `noNightSkip` | `true` | The night never skips, and nobody gets the "players sleeping" line. Getting into a bed still resets the phantom timer. | No |
| `noBedRespawn` | `true` | Beds and respawn anchors set no respawn point and say nothing. | No |

With `noBedRespawn` on, a death or an End exit takes you to the world spawn, which random spawn turns into a random
spot. Bed and anchor points saved earlier are ignored. `/spawnpoint` still works as in vanilla. Anchors still explode
outside the Nether.

## `bodies`

`enabled`, and the settings that describe how a body is built (`type`, `pose`, `movable`, `totems`, `copyEffects`),
apply to bodies made after the change. The rest apply at once. A player body is built again each time the server
starts, so after a restart it takes `pose` and `movable` from the config as it is then. A mannequin keeps the ones it
was made with, since it is saved with its chunk.

| Key | Default | What it does | Restart? |
|---|---|---|---|
| `enabled` | `true` | Logging out leaves a body. | New bodies |
| `type` | `"mannequin"` | `mannequin` or `player`. See [the two body types](logout-bodies.md#the-two-body-types). | New bodies |
| `duration` | `"offline"` | `"offline"`, or a whole number of minutes (1 or more). | No |
| `pose` | `"prone"` | `prone`, `sleeping` or `standing`. | New bodies |
| `mobsAttack` | `true` | Hunting mobs go after bodies. | No |
| `movable` | `true` | Bodies can be pushed, fall and drift. Off pins them in place. | New bodies |
| `totems` | `true` | A held totem can save a body. The totem is used up in your save too. | New bodies |
| `armourWears` | `true` | A body's armour wears down when hit, and the wear carries back to you. | No |
| `copyEffects` | `true` | A new body copies your potion effects, max health and absorption. When you log back in or the body expires, its effects replace yours. Off: the body gets no effects, and yours stay as they were. Each body keeps the setting it was made with. | New bodies |
| `ignoreVanillaKill` | `true` | Vanilla `/kill` leaves bodies standing and says how many it skipped. | No |

## `serverList`

| Key | Default | What it does | Restart? |
|---|---|---|---|
| `hoverLines` | `["You scream out for help, but no one answers."]` | One line per entry in the tooltip when you hover the player count in the multiplayer screen, at most 12 lines of 256 characters. File only. | No (on `/yolt reload`) |
| `shownMaxPlayers` | `1` | The slot count the server list shows while `hide.serverPlayerCount` hides the real one, 0 or more. | No |

Notes:

- The lines replace the player sample whatever `hide.serverPlayerCount` is, so real names never show there. With
  `[]` the hover shows no names while `hide.serverPlayerCount` is on, and vanilla's names while it is off.
- Lines past the caps are dropped, with a warning, when the config loads.
- The ping bars' hover shows the client's own measured ping, and the server can't change that. The version text can't
  be faked without marking the server incompatible. The MOTD stays in `server.properties`.

## `skins`

| Key | Default | What it does | Restart? |
|---|---|---|---|
| `mode` | `"auto"` | `auto`: players with no skin get the one of the Mojang account with their name. `off`: YOLT gives no skins and never asks Mojang. | Next join |
| `lookupTimeoutSeconds` | `5` | How long a join waits for Mojang, 1 to 10. | No |
| `refreshHours` | `24` | How long a skin is kept before Mojang is asked again, 0 or more. 0 asks on every join. | No |
| `allowPlayerChoice` | `true` | Non-ops can use `/yolt skin set` and `clear` on themselves. Off keeps the picks already made. | No |
| `refuseKnownPlayers` | `true` | A non-op can't pick the skin of a player who has joined this server, or one another player picked. | No |
| `lockAfterFirstChoice` | `true` | A non-op picks once; after that only an op changes it. | No |

## `hardcore`

The [hardcore layer](hardcore.md). Every feature ships off except the flat anvil price and Mending, and with a feature
off YOLT changes nothing for it. Since those two are on, non-ops have `/status` by default, with its Rules line. The
hunger tax has no switch: its default multiplier, 1.0, is vanilla. Shares and chances are 0 to 1 (0.2 is 20%).

### `hardcore.slow`

Shared by every slowdown: weight, broken legs, the limp, cold and low sugar. See [slowdowns](hardcore.md#slowdowns).

| Key | Default | What it does | Restart? |
|---|---|---|---|
| `cap` | `0.5` | The most all the slowdowns together can take, 0 to 1. Potions come on top. | No |
| `step` | `0.05` | The total moves in steps this big, rounded to the nearest, so the speed doesn't flicker. Above 0, and at most `cap`. | No |

### `hardcore.weight`

See [weight](hardcore.md#weight). Weights are stack weights, the weight of a full stack, except `toolWeight`,
`unstackableWeight` and `itemsEach`, which are per item.

| Key | Default | What it does | Restart? |
|---|---|---|---|
| `enabled` | `false` | What you carry slows you, and heavy jumps cost hunger. | No |
| `freeLoad` | `16.0` | The load you carry with no slowdown, 0 or more. Below `fullLoad`. | No |
| `fullLoad` | `144.0` | The load where the slowdown reaches `maxSlow`, 0 or more. | No |
| `maxSlow` | `0.5` | The slowdown at a full load, 0 to 1. | No |
| `defaultWeight` | `1.0` | Stack weight of anything nothing else names. | No |
| `unstackableWeight` | `0.1` | Weight of each other item that stacks to 1 (potions, buckets, beds, totems). | No |
| `toolWeight` | `0.25` | Weight of each sword, axe, pickaxe, shovel, hoe or spear. | No |
| `countContainers` | `true` | Shulker boxes and bundles add what is inside them. | No |
| `containerContents` | `1.0` | The share of a container's contents that counts, 0 to 1. 0.5 counts half. | No |
| `containerDepth` | `3` | How many containers deep the contents count, 0 to 8. | No |
| `countCursorAndGrid` | `true` | The item on the cursor, the 2x2 crafting grid and an open crafting table's grid count. | No |
| `heavyJumpExhaustion` | `0.2` | Extra hunger (exhaustion) per jump at a full load, scaled down to none at the free load. 0 or more. | No |

`groups`, `items`, `itemsEach` and `armourFactors` are [file only](#file-only-settings).

### `hardcore.legs`

See [broken legs](hardcore.md#broken-legs). Damage is fall damage after armour and protection enchantments, 2 a heart.
Ticks are per heal point, and 0 means no healing.

| Key | Default | What it does | Restart? |
|---|---|---|---|
| `enabled` | `false` | A long fall breaks your legs. | No |
| `breakAtDamage` | `6.0` | Damage from one fall that breaks your legs, 0 or more. | No |
| `heavyLandings` | `false` | With weight on too, a heavier load breaks legs from a shorter fall. | No |
| `breakAtDamageFullLoad` | `4.0` | The breaking damage at a full load with `heavyLandings`, scaled between the free and the full load. At most `breakAtDamage`. | No |
| `includeElytraCrash` | `false` | Flying into a wall counts as a fall. | No |
| `slow` | `0.2` | The slowdown while they are broken, 0 to 1. | No |
| `healPoints` | `30` | Points to heal, 0 or more. | No |
| `healTicks` | `80` | Ticks per point while food is 18 or more. | No |
| `healTicksSaturated` | `40` | Ticks per point while food is full and there is saturation. | No |
| `exhaustionPerPoint` | `3.0` | Food (exhaustion) each point costs, taken straight from the food bar, so the hunger tax doesn't scale it. | No |
| `healthFirst` | `true` | Legs heal only while health is full. | No |
| `regenEffectHeals` | `true` | Regeneration heals legs at its own pace, with no food used. | No |
| `healTicksNoRegenRule` | `1200` | Ticks per point, with no food used, while the `natural_health_regeneration` game rule is off. | No |
| `bodiesCanBreakLegs` | `false` | A logout body's fall breaks its owner's legs. Off, a body's fall only costs it health. | No |

### `hardcore.limp`

See [limp](hardcore.md#limp). Lines are in hearts.

| Key | Default | What it does | Restart? |
|---|---|---|---|
| `enabled` | `false` | Low health slows you. | No |
| `belowHearts` | `2.0` | Below this you limp. More than 0, and less than `recoverAtHearts`. | No |
| `recoverAtHearts` | `3.0` | At or above this you stop limping. More than 0. | No |
| `slow` | `0.2` | The slowdown while limping, 0 to 1. | No |
| `countAbsorption` | `false` | Absorption (golden) hearts count as health. | No |

### `hardcore.bedRest`

See [bed rest](hardcore.md#bed-rest).

| Key | Default | What it does | Restart? |
|---|---|---|---|
| `enabled` | `false` | Lying in a bed heals you and speeds up broken legs. | No |
| `allowDaytime` | `true` | You can lie down in the day too, wherever beds don't explode. Off: vanilla's rule decides. | No |
| `healEveryTicks` | `50` | Ticks per half heart healed in bed, 0 or more. 0 heals nothing. | No |
| `exhaustionPerHeal` | `0.0` | Food (exhaustion) each half heart costs, 0 or more. | No |
| `legHealSpeed` | `2.0` | How much faster broken legs heal in bed, 0 or more. 1.0 is no change. | No |

### `hardcore.curse`

See [Curse of the Undead](hardcore.md#curse-of-the-undead). In hearts.

| Key | Default | What it does | Restart? |
|---|---|---|---|
| `enabled` | `false` | Every return from death, by dying or by a totem, takes a max heart. | No |
| `heartsPerDeath` | `1` | Hearts a death takes, 0 or more. | New deaths |
| `heartsPerTotem` | `1` | Hearts a totem takes, 0 or more. 0 makes totems free. | New deaths |
| `maxLostHearts` | `4` | The most hearts the curse takes in all, 0 or more. | No |
| `healHearts` | `1` | Hearts that come back each time, 0 or more. | No |
| `healEveryDays` | `3.0` | Minecraft days (24,000 ticks, 20 minutes) of the player's own online play in survival or adventure per heal. Decimals work. Above 0. | No |
| `countAfk` | `true` | Online time counts while the player stands idle. Off: not after 5 minutes without moving or acting. | No |
| `reviveRefundHearts` | `0` | Hearts a revive paper gives back, 0 or more. | No |
| `unkillRefunds` | `true` | `/yolt unkill` gives back what the death it undoes took. | No |

### `hardcore.nutrients`

See [nutrients](hardcore.md#nutrients). Levels and shares are 0 to 1.

| Key | Default | What it does | Restart? |
|---|---|---|---|
| `enabled` | `false` | Foods carry protein, vitamins and sugar, and a low group costs you. | No |
| `gainPerPoint` | `0.03` | Level gained per point of a food's nutrition that the food bar takes (nothing at a full bar), split by its shares. 0 or more. | No |
| `decayPerPoint` | `0.01` | Level each group loses per point the food bar drops. 0 or more. | No |
| `startLevel` | `0.6` | Each group's level on first join and after each death, 0 to 1. | No |
| `deficientBelow` | `0.25` | Below this a group is low, 0 to 1. | No |
| `penalties.protein.healingMultiplier` | `0.5` | How fast health regen, broken legs and bed rest heal while protein is low. 0 or more. | No |
| `penalties.sugar.sprintSlow` | `0.1` | The slowdown while sprinting with low sugar, 0 to 1. | No |
| `penalties.vitamins.miningSlow` | `0.15` | How much slower mining is with low vitamins, 0 to 1. | No |
| `unknownFood` | `"none"` | What a food `foods` doesn't list gives: `none`, `protein`, `vitamins`, `sugar`, or `even` (a third to each). | No |

`foods` is [file only](#file-only-settings).

### `hardcore.hungerTax`

See [hunger tax](hardcore.md#hunger-tax).

| Key | Default | What it does | Restart? |
|---|---|---|---|
| `multiplier` | `1.0` | Multiplies the hunger actions cost, 0 or more. 1.0 is vanilla and turns it off; there is no `enabled` key. | No |

### `hardcore.mending`

See [Mending nerf](hardcore.md#mending-nerf).

| Key | Default | What it does | Restart? |
|---|---|---|---|
| `enabled` | `true` | Mending repairs less per XP point. | No |
| `durabilityPerXp` | `0.4` | Durability one XP point repairs, 0 or more. Vanilla's is 2. 0 sends all XP to the bar. | No |

### `hardcore.anvil`

See [flat anvil prices](hardcore.md#flat-anvil-prices). Prices are XP levels, 1 to 39: 0 can't be paid, and 40 or more
shows "Too Expensive!".

| Key | Default | What it does | Restart? |
|---|---|---|---|
| `enabled` | `true` | Each anvil use costs one flat price, set by its biggest job. | No |
| `enchantCost` | `6` | Price of a use that enchants. | No |
| `repairCost` | `5` | Price of a use that repairs and doesn't enchant. | No |
| `renameCost` | `1` | Price of a use that only renames. | No |
| `breakChance` | `0.12` | The chance per use that the anvil wears down a step, 0 to 1. | No |

### `hardcore.elytra`

See [elytra nerf](hardcore.md#elytra-nerf).

| Key | Default | What it does | Restart? |
|---|---|---|---|
| `enabled` | `false` | Gliding is slower, and rockets stop pushing past a speed. | No |
| `glideGravityMultiplier` | `0.6` | Gravity while gliding, 0 or more. 1.0 is vanilla. | No |
| `rocketCutoffSpeed` | `20.0` | Horizontal speed in m/s past which a rocket stops boosting, 0 or more. 0 means never. | No |

### `hardcore.tougherMobs`

See [tougher mobs](hardcore.md#tougher-mobs). Multipliers, 1.0 is vanilla.

| Key | Default | What it does | Restart? |
|---|---|---|---|
| `enabled` | `false` | Hostile mobs get more health, damage and speed. Turning it on or off changes each mob's health and speed as it loads, and its damage at once. | No |
| `health` | `1.5` | Max health. Above 0. | As mobs load |
| `damage` | `1.25` | Damage of any hit the mob causes, after the difficulty scaling. 0 or more. | No |
| `speed` | `1.1` | Movement speed, 0 or more. | As mobs load |
| `damageAppliesTo` | `"players"` | `players` (players and logout bodies take the extra damage) or `all`. | No |
| `includeSpawners` | `true` | Spawner mobs are boosted. Decided once per mob, the first time YOLT sees it. | As mobs load |
| `includeTrialSpawners` | `false` | Trial spawner mobs are boosted. Decided once per mob. | As mobs load |
| `includeCommandsAndEggs` | `false` | Mobs from commands, spawn eggs and dispensed eggs are boosted. Decided once per mob. | As mobs load |

`exclude` and `overrides` are [file only](#file-only-settings). The phantom override sets `speed` 1.0 too, so phantoms
stay fully vanilla.

### `hardcore.relentlessMobs`

See [relentless mobs](hardcore.md#relentless-mobs).

| Key | Default | What it does | Restart? |
|---|---|---|---|
| `enabled` | `false` | Hostile mobs track from further, and zombies break doors more. Turning it on or off changes each mob's range as it loads. | No |
| `followRange` | `1.5` | Multiplies how far hostile mobs track you. Above 0. | As mobs load |
| `doorsAnyDifficulty` | `true` | Zombies that can break doors do so on every difficulty, not only on Hard. | No |
| `doorBreakerChance` | `0.25` | The chance a zombie can break doors, 0 to 1. Each zombie rolls once. | As mobs load |
| `dropBrokenDoor` | `true` | A broken door drops as one item, as in vanilla. Off: it is gone and nothing drops. | No |

`exclude` is [file only](#file-only-settings).

### `hardcore.bloodMoon`

See [blood moon](hardcore.md#blood-moon). Ticks are times of day, 0 to 23999. The multipliers go on top of tougher
mobs' own, and the chances are the least a mob spawned in a blood moon gets.

| Key | Default | What it does | Restart? |
|---|---|---|---|
| `enabled` | `false` | Some nights are blood moons. Off ends one that is up, with no message. | No |
| `chance` | `0.1` | The chance each night is a blood moon, 0 to 1. | No |
| `minNightsBetween` | `5` | Calm nights at least between two, 0 or more. | No |
| `fullMoonsOnly` | `false` | Only full moons can roll. | No |
| `graceDays` | `8` | No blood moon in the world's first this many days (days count from 0), 0 or more. | No |
| `startTick` | `12542` | When the night starts. | No |
| `endTick` | `23460` | When the night ends. | No |
| `warnTick` | `9000` | When the warning goes out. | No |
| `health` | `1.25` | Max health of marked mobs. Above 0. | As mobs load |
| `damage` | `1.0` | Damage of marked mobs, 0 or more. | No |
| `speed` | `1.1` | Movement speed of marked mobs, 0 or more. | As mobs load |
| `spawnCap` | `2.0` | Multiplies the overworld's monster cap, 0 or more. | No |
| `capBoostNearPlayerBodies` | `false` | The bigger cap applies round `player`-type logout bodies too. | No |
| `canSleepThrough` | `false` | Sleeping can skip a blood moon night, on a server with `sleep.noNightSkip` off. | No |
| `armourChance` | `0.35` | The least chance a mob spawns with armour, 0 to 1. Rolled as the mob spawns. | As mobs load |
| `enchantChance` | `0.25` | The least chance its gear is enchanted, 0 to 1. Rolled as the mob spawns. | As mobs load |
| `xp` | `2.0` | Multiplies the XP marked mobs drop, 0 or more. | No |
| `extraLootRolls` | `1` | Extra rolls of a marked mob's own loot table when a player kills it, 0 or more. | No |
| `chargedCreeperChance` | `0.0` | The chance a creeper spawns charged, 0 to 1. Rolled as the creeper spawns. | As mobs load |
| `bossBar` | `true` | Shows the "Blood Moon" boss bar. | No |
| `darkenSky` | `true` | The boss bar darkens the sky. | No |
| `fog` | `false` | The boss bar adds fog. | No |
| `warning.title` | `true` | The warning sends a title. | No |
| `warning.chat` | `true` | The warning sends a chat line. | No |
| `warning.sound` | `true` | The warning plays the raid horn to each player. | No |

`warnTick`, `startTick` and `endTick` must keep 0 <= `warnTick` < `startTick` < `endTick` <= 23999. `bossBar`,
`darkenSky` and `fog` are read when a blood moon starts.

### `hardcore.cold`

See [cold](hardcore.md#cold). Chill runs from 0 to 1 and moves once a second.

| Key | Default | What it does | Restart? |
|---|---|---|---|
| `enabled` | `false` | Where it is cold enough to snow, a chill builds outdoors. Random spawns skip such spots. | No |
| `chillPerSecond` | `0.01` | Chill gained per second out in the cold, 0 or more. | No |
| `warmPerSecond` | `0.03` | Chill lost per second anywhere warm, 0 or more. | No |
| `shelteredBelowSkyLight` | `8` | Sky light below this counts as indoors, 0 to 15. | No |
| `heatRadius` | `4` | Blocks a heat source warms, 0 to 8. | No |
| `leatherPerPiece` | `0.25` | How much each leather piece cuts the chill's rise, 0 to 1. | No |
| `wetMultiplier` | `2.0` | Chill speed with rain or snow on you, 0 or more. | No |
| `waterMultiplier` | `4.0` | Chill speed in water, 0 or more. | No |
| `maxSlow` | `0.2` | The slowdown at full chill, 0 to 1. | No |
| `freezeDamage` | `true` | Full chill does freeze damage. | No |
| `damageEverySeconds` | `2` | Seconds between YOLT's freeze hits with 1 to 3 leather pieces, 1 or more. With no leather, vanilla's own hit comes every 2 seconds. | No |
| `canKill` | `false` | Freeze damage can kill. Off: it stops at half a heart. | No |
| `shiverExhaustionPerSecond` | `0.05` | Extra hunger (exhaustion) per second at full chill, less below. 0 or more. | No |

## `resourcePack`

See [revive paper texture](hardcore.md#revive-paper-texture). The pack itself is set in `server.properties`, not here.

| Key | Default | What it does | Restart? |
|---|---|---|---|
| `reviveTexture` | `false` | Revive papers show `reviveModel`, which the server's resource pack must hold. Papers in players' inventories follow a change within a second. | No |
| `reviveModel` | `"you-only-live-twice:revive_paper"` | The item model, an id such as `you-only-live-twice:revive_paper`. | No |

## File-only settings

These are lists and maps, so `/yolt config` can't change them. Edit the file and run `/yolt reload`. A map is read
whole: the map in the file replaces the default, so a name you leave out is gone. Ids and tags that name nothing
match nothing.

| Key | Entries | Default |
|---|---|---|
| `hardcore.weight.groups` | An item tag (without `#`) and a stack weight. An item in several takes the first in the list, so put specific tags first. | `c:ores`, `c:raw_materials`, `c:ingots`, `c:stones`, `c:cobblestones`, `c:obsidians`, `minecraft:stone_bricks`, `minecraft:logs`: 3.0. `c:storage_blocks/iron`, `/gold`, `/copper`, `minecraft:copper` (every full copper block, weathered or waxed), `c:storage_blocks/netherite`, `/raw_iron`, `/raw_gold`, `/raw_copper`: 27.0. `c:storage_blocks`: 9.0. `c:feathers`, `c:strings`: 0.25. |
| `hardcore.weight.items` | An item id and a stack weight. Beats armour, tools and groups. | `minecraft:paper` 0.25. 3.0 for stone-like blocks no group reaches: `deepslate_bricks`, `deepslate_tiles`, `polished_deepslate`, `chiseled_deepslate`, `bricks`, `smooth_stone`, `polished_andesite`, `polished_diorite`, `polished_granite`, `polished_tuff`, `tuff_bricks`, `blackstone`, `polished_blackstone`, `polished_blackstone_bricks`, `end_stone_bricks`, `mud_bricks`, `cracked_deepslate_bricks`, `cracked_deepslate_tiles`, `cracked_polished_blackstone_bricks`, `chiseled_polished_blackstone`, `chiseled_tuff`, `chiseled_tuff_bricks`, `gilded_blackstone`, `end_stone`, `cinnabar`, `polished_cinnabar`, `cinnabar_bricks`, `chiseled_cinnabar`, `sulfur`, `potent_sulfur`, `polished_sulfur`, `sulfur_bricks`, `chiseled_sulfur` (all `minecraft:`). |
| `hardcore.weight.itemsEach` | An item id and a weight for each single item. Beats everything else. | `elytra` 0.5, `mace` 1.0, `heavy_core` 1.0, `trident` 0.25, `anvil`, `chipped_anvil`, `damaged_anvil` 1.5 each. Horse armour: `leather_horse_armor` 0.15, `copper_horse_armor` 0.4, `iron_horse_armor` 0.6, `golden_horse_armor` 0.5, `diamond_horse_armor` 1.2, `netherite_horse_armor` 1.6. Nautilus armour: `copper_nautilus_armor` 0.4, `iron_nautilus_armor` 0.6, `golden_nautilus_armor` 0.5, `diamond_nautilus_armor` 1.2, `netherite_nautilus_armor` 1.6 (all `minecraft:`). |
| `hardcore.weight.armourFactors` | An armour material and a factor. An armour piece, worn or carried, weighs its armour points times its factor. A material not listed weighs `defaultWeight`. | `leather` 0.05, `copper` 0.1, `chainmail` 0.1, `gold` 0.1, `iron` 0.1, `turtle_scute` 0.1, `diamond` 0.15, `netherite` 0.2 |
| `hardcore.nutrients.foods` | An item id, or `#` and an item tag, and an object of shares: `{"protein": 0.5, "vitamins": 0.5}`. Groups are `protein`, `vitamins` and `sugar`, shares 0 to 1. An id beats a tag, and the first tag in the list wins. `{}` gives nothing. | Every vanilla food, 41 entries, listed below. |
| `hardcore.tougherMobs.exclude` | A list of mob ids never boosted. Relentless mobs leaves them alone too. | `minecraft:wither`, `minecraft:ender_dragon`, `minecraft:elder_guardian`, `minecraft:warden` |
| `hardcore.tougherMobs.overrides` | A mob id and an object naming only the values that differ for it: `health`, `damage`, `speed`. | `minecraft:ghast` `{"health": 2.0}`, `minecraft:phantom` `{"health": 1.0, "damage": 1.0, "speed": 1.0}`, `minecraft:creeper` `{"damage": 1.0}` |
| `hardcore.relentlessMobs.exclude` | A list of mob ids relentless mobs leaves alone. | `minecraft:warden` |

The default `foods` table (all `minecraft:` ids). Shares don't have to add up to 1. A group holds steady when about a
third of the food points you eat carry it, so no simple food covers all three on its own, two foods nearly do, and
three, or a dish with the staple it lacks, do.

| Shares | Foods |
|---|---|
| protein 1.0, vitamins 0.25 | `cooked_beef`, `cooked_porkchop`, `cooked_mutton`, `cooked_chicken`, `cooked_rabbit` |
| protein 0.5 | `beef`, `porkchop`, `mutton`, `chicken`, `rabbit` |
| protein 0.75, vitamins 0.5 | `cooked_cod` |
| protein 1.0, vitamins 0.5 | `cooked_salmon` |
| protein 0.5, vitamins 0.25 | `cod`, `salmon`, `tropical_fish` |
| vitamins 1.0, sugar 0.5 | `apple`, `carrot`, `beetroot_soup` |
| vitamins 0.75, sugar 0.5 | `beetroot` |
| vitamins 0.75, sugar 0.75 | `melon_slice`, `sweet_berries`, `glow_berries` |
| vitamins 0.5, sugar 0.5 | `dried_kelp` |
| sugar 1.0, vitamins 0.25 | `bread`, `baked_potato` |
| sugar 0.5, vitamins 0.25 | `potato` |
| sugar 1.0 | `cookie`, `honey_bottle` |
| sugar 1.0, protein 0.5, vitamins 0.25 | `cake` (eaten a slice at a time, 2 nutrition each) |
| sugar 1.0, vitamins 0.5, protein 0.25 | `pumpkin_pie` |
| protein 0.5, vitamins 0.5, sugar 0.5 | `rabbit_stew` |
| vitamins 0.5, protein 0.25 | `mushroom_stew` |
| vitamins 0.75, protein 0.25, sugar 0.25 | `suspicious_stew` |
| vitamins 1.0, sugar 1.0 | `golden_carrot` |
| protein 0.5, vitamins 1.0, sugar 0.5 | `golden_apple` |
| protein 1.0, vitamins 1.0, sugar 1.0 | `enchanted_golden_apple` |
| protein 0.25 | `rotten_flesh` |
| vitamins 0.5, sugar 0.25 | `chorus_fruit` |
| nothing | `spider_eye`, `pufferfish`, `poisonous_potato` |

## Load checks

The hardcore and `resourcePack` values are checked as the file loads, the same way as core's: a value that fails
falls back on its own (to the default at startup, to the value in use on `/yolt reload`), with a warning in the log
naming the key, and `/yolt config` refuses it. The rest of the file still counts.

- **0 to 1:** `slow.cap`, `weight.maxSlow`, `weight.containerContents`, `legs.slow`, `limp.slow`,
  `nutrients.startLevel`, `nutrients.deficientBelow`, `nutrients.penalties.sugar.sprintSlow`,
  `nutrients.penalties.vitamins.miningSlow`, `anvil.breakChance`, `relentlessMobs.doorBreakerChance`,
  `bloodMoon.chance`, `bloodMoon.armourChance`, `bloodMoon.enchantChance`, `bloodMoon.chargedCreeperChance`,
  `cold.leatherPerPiece`, `cold.maxSlow`.
- **Above 0:** `slow.step`, `curse.healEveryDays`, `tougherMobs.health`, `relentlessMobs.followRange`,
  `bloodMoon.health`, `limp.belowHearts`, `limp.recoverAtHearts`.
- **1 to 39:** `anvil.enchantCost`, `anvil.repairCost`, `anvil.renameCost`.
- **Ranges:** `weight.containerDepth` 0 to 8, `cold.shelteredBelowSkyLight` 0 to 15, `cold.heatRadius` 0 to 8,
  `cold.damageEverySeconds` 1 or more.
- **0 or more:** every other number.
- **Pairs,** where both go back since either could be the typo:
  - `slow.step` at most `slow.cap`;
  - `weight.freeLoad` below `weight.fullLoad`;
  - `legs.breakAtDamageFullLoad` at most `legs.breakAtDamage`;
  - `limp.belowHearts` below `limp.recoverAtHearts`;
  - `bloodMoon.warnTick`, `startTick` and `endTick` keep 0 <= `warnTick` < `startTick` < `endTick` <= 23999 (all
    three go back).
- **Curse and limp:** with both on, the curse may take a player down to `limp.recoverAtHearts` or fewer hearts. Below
  it, a player who starts to limp limps until a curse heart comes back. That is allowed, as a harsher punishment. The
  log only says so, at startup, at `/yolt reload` and after a `/yolt config` that makes the two cross.
- **Maps:** a map with a value below 0 falls back whole. In `foods`, every share must be 0 to 1 and name `protein`,
  `vitamins` or `sugar`. In `overrides`, every value must name `health`, `damage` or `speed` and be 0 or more, with
  `health` above 0. A map that isn't an object of numbers falls back whole too.
- **Choices:** `nutrients.unknownFood` and `tougherMobs.damageAppliesTo` must be one of their values.
- **`resourcePack.reviveModel`** must be an id such as `you-only-live-twice:revive_paper`.

Every key above is under `hardcore.` except `resourcePack.reviveModel`.

[All pages](README.md)
