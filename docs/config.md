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

The file is `config/you-only-live-twice.json`. Edit it and run `/yolt reload`, or use `/yolt config <key> <value>`,
which changes one key in the file and in the running server at once. Other `/yolt` commands that change a setting
save it to the file too.

No setting needs a restart. The "Restart?" column says when a change shows:

- **No:** at once.
- **Next join:** when a player next joins.
- **Next join, respawn or dimension change:** for each player, at the first of those.
- **New bodies:** for bodies made from then on. Bodies already standing keep how they were made.
- **New deaths:** for deaths from then on. Graves and lockouts already there stay as they are.

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
- `serverList.hoverLines` and `hide.tabHeaderLines` are lists, so they can only be changed in the file.
- `§` colour codes work in the text settings (the client draws them), for example `"§cNo one is coming."`.
- With no config file at startup (the first start, or a deleted file), YOLT writes the defaults and uses them.
- The file is rewritten at every start, so comments typed into it aren't kept.
- If the file can't be written back at startup (a read-only folder, say), what was read still applies, with a warning
  in the log.

## Top level

| Key | Default | What it does | Restart? |
|---|---|---|---|
| `configVersion` | `1` | The file's layout version, used for migrations. Leave it. A value of 0 or below logs a warning and reads as 1. | – |
| `restrictCommands` | `true` | Non-ops only get `/help` (plus `/w` when whispers are on, and `/yolt skin` when players may pick their skin). Ops below level 2 count as non-ops. Off means vanilla commands, and then `/msg`, `/tell`, `/teammsg`, `/list` and vanilla suggestions show who is online. | No |
| `netherScaleOneToOne` | `true` | Every dimension's coordinate scale is 1, modded dimensions included, so the Nether matches the Overworld and there is no fast travel. | No; it applies at once, and changing it on an existing world breaks portal links |
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
| `allowWhispers` | `false` | Non-ops can use `/w`. | No |
| `whisperRadius` | `0` | Blocks a whisper reaches. 0 means the same as `radius`. No range in `global` mode. | No |

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
| `coordinates` | `false` | Each player sees X and Z shifted by a private amount, new at every join, death respawn and dimension change. Admin view sees real ones. | Next join, respawn or dimension change |

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
| `lockAfterFirstChoice` | `false` | A non-op picks once; after that only an op changes it. | No |

[All pages](README.md)
