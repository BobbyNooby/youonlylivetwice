# You Only Live Twice

You Only Live Twice (YOLT) is a server-side Fabric mod for Minecraft 26.3. It raises the stakes of a multiplayer
survival world and pushes players to actually talk to each other in the game, ideally over proximity voice chat.

- Dying locks you out of the server for 7 days (configurable).
- Your items stay in a grave that anyone can loot.
- The things players usually lean on are hidden: global chat, the tab list, nametags, commands, the online player
  count, and death, join and advancement messages.
- When you log out, a body stays behind where you stood. Whatever happens to it happens to you.

Players join with a plain vanilla client. No client mod and no resource pack.

Every feature has a switch in the config, so you can turn off whatever you don't want.

# Install

You need:

- Minecraft 26.3
- Fabric Loader 0.20.1 or newer
- [Fabric API](https://modrinth.com/mod/fabric-api)
- Java 25

Get the mod jar from a release, or from the `Artifacts` download on a successful run of the `build` workflow on
GitHub (the jar is the file in `build/libs`). Put it in the server's `mods` folder, next to Fabric API.

The mod is server-side. It loads in single-player but does nothing there.

Start the server once. It writes `config/you-only-live-twice.json` and, with the world border feature on (the default),
sets the border to 10,000 blocks across.

## Recommended server setting

Set `spawn-protection=0` in `server.properties`. Vanilla stops non-ops from using blocks near the world spawn, and
graves can end up there (for example when someone dies in the void and the grave falls back to the spawn column), so
players could not open them.

# Companion mods

I recommend these, none are required:

- [Simple Voice Chat (client and server)](https://modrinth.com/project/9eGKb6K1)
    - The mod I originally made this for. Proximity chat in text works fine, but voice is the real thing.
- [Chunky (server-side)](https://modrinth.com/project/fALzjamp)
    - The world border is small, so you can pre-generate the whole world.
- [Voice Chat Interaction (server-side)](https://modrinth.com/project/qsSP2ZZ0)
    - Voice chat sets off skulk sensors and wakes wardens.
- [Hardcore Hearts (resource pack)](https://modrinth.com/project/5AMcQaG0)
    - Changes the hearts to hardcore hearts. It is a client resource pack, optional, and the mod doesn't need it.

Other mods may cause problems. I haven't tested combinations.

# How it plays

## Death lockout

When you die you are kicked, and you can't rejoin until the cooldown is over. The default is 7 days
(`deathLockout.cooldownDays`). Set it to 0 and there is no wait.

## Graves

When you die, a player head with your skin appears where you died, holding your items. It shows up even if your
inventory was empty, because the head is also the revive token.

- Right-click it to open it. Anyone can loot it. You can only take things out, not put things in.
- Graves can't be broken (not by hand, explosions, pistons or fluids) and never expire. If a head goes missing
  anyway, for example from an op's `/setblock`, the mod puts it back when the chunk next loads.
- When the last item is taken the head disappears.
- Your ender chest: `graves.enderChestOnDeath` can be `keep` (vanilla, the default), `grave` (the contents also go
  in the grave) or `wipe` (deleted).

## Reviving someone

Craft a grave head together with a nether star in a normal crafting table. The result is a revive head. Right-click
with it while its owner is locked out and their lockout ends. One head is used up. If the owner isn't dead you get
"is not dead" and nothing is used. A revived player comes back with nothing, at a random spot.

A revive head shows a purple name and lore, not an enchantment glint. Vanilla never draws the glint on player heads.

## Chat

- `proximity` (default): a message reaches players within `chat.radius` blocks (default 48) in the same dimension.
- `self_only`: you only see your own messages.
- `global`: vanilla chat.

Messages from ops and the console (`/say`, `/tellraw`) still reach everyone. Admins with admin view on see
everything. With `chat.allowWhispers` on, non-ops can use `/w <player> <message>`. You always see "You whisper to
X", but X only gets it if they are within the radius, so the sender can't tell who is online or nearby.

## Hidden information

Non-ops only get `/help`, which answers with a spooky message. The tab list is empty with a random header, the
server list shows 0/1 players, nametags are hidden, and death, join, leave and advancement messages are gone. Each
player's game is only told about players near them, so names can't leak through chat tab-complete or the
Social Interactions screen. The locator bar is turned off (it points at far-away players).

Sleeping players are hidden, the night never skips, and nobody respawns at a bed. New players spawn at a random
spot inside the border, away from water.

## World border and Nether

The Overworld and Nether share one border (default 10,000 blocks across). The End border is the same size times
`worldBorder.endScale` (default 2). The Nether runs 1:1 with the Overworld, so there is no Nether fast travel.

## Admin view

Ops can run `/yolt adminview` to see what everyone else can't: the full tab list, every chat message and every
death, join and advancement message. It is off by default and remembered per admin. In admin view you don't get other
players' whispers, since those are private.

## Logout bodies

When you log out, a body appears where you stood. It wears your skin, armour and held items and has no nametag.
While you're offline the body is you:

- **What carries over:** health, absorption hearts, potion effects, armour and items in hand, and fall, fire and air
  state. The body also starts with your max health. Hunger and XP stay as they were. Nobody can take items off a body.
- **Killing a body kills you.** A grave appears at the body with your saved inventory, your save is cleared, and
  your lockout starts. `/yolt kill` on an offline player does the same.
- **Logging back in:** you take over the body as it is now. You appear where it is, with its health, effects and
  gear (a popped totem stays used, worn armour stays worn). Then the body is removed.
- **How long it stays:** `bodies.duration` is `offline` (the default, the body stays the whole time you're away) or
  a number of minutes. The number counts real time, so server downtime counts too. Every player online when the
  server stops gets a body.
- **Poses:** `bodies.pose` is `prone` (lying face-down, the default), `sleeping` or `standing`. `standing` can
  suffocate a body that was crawling through a 1-block gap.
- **Mobs hunt bodies** (`bodies.mobsAttack`). Mobs that hunt players on sight hunt bodies the same way. Mobs that
  are calm until provoked stay calm: endermen, zombified piglins, and spiders in daylight. Piglins, hoglins, wardens,
  breezes and creakings ignore bodies. Slimes and magma cubes follow a body but can't hurt it by touch.
- **Totems, armour wear and potion effects** work on the body like they would on you (each has a switch).
- **Bodies move like players:** gravity, knockback, water and fall damage all apply, and you log back in wherever the
  body ended up. They **can't use portals yet**; portal travel for bodies is on the list below.
- **Riding:** if you logged out on a boat or minecart, vanilla puts you back on the vehicle, so you land where the
  vehicle is, not where the body ended up. Your health, effects and gear still come from the body.
- **Race at login:** if a body dies just as its owner is logging in, the owner is kicked with the lockout message
  and their lockout stands. If it expires just then, they arrive at their logout spot with the body's state. Nothing
  is lost or duplicated.

### The two body types

`bodies.type` picks what a body is. Bodies already in the world keep the type they were made as until their owner
logs in. Only new logouts use the new setting.

- **`mannequin` (default):** a vanilla mannequin entity. It costs nothing while nobody is near it. It is frozen
  unless a player is around, so a body far from everyone is safe from mobs.
- **`player`:** opt-in. The body counts as a player for the world: its area keeps loading, mobs spawn around it and
  don't despawn near it. Whether the server keeps running with nobody online is vanilla's
  `pause-when-empty-seconds` in `server.properties` (0 means never pause). The trade-offs:
    - Selectors that look in the world (`@p`, `@a[distance=..]`, `@e[type=player]`) can pick a player body. An item
      given that way (say `/give @p` next to one) is lost when the owner takes the body back.
    - Scoreboard values (health, food, air, armour, XP, level) keep updating under the owner's name, and a body death
      adds to the owner's death count.
    - World events treat it as a player. A Bad Omen effect copied onto it (`bodies.copyEffects`) can start a village
      raid, and trial spawners, patrols and wandering traders can pick it.
    - It never gets phantoms, doesn't pick up items, and slimes, magma cubes and pufferfish don't hurt it by touch.
    - With the `pvp` game rule off, or friendly fire off on a team, players can't hurt it (they can hurt a mannequin).
    - After a server restart, a player body has the default main hand and max health. Gear and health are restored
      from the save when its owner logs in.
    - It uses a Fabric internal class to run with no real connection (`UntrackedPacketListener`), which a Fabric
      update could move. If a Fabric update breaks it, that is the first place to look.

Skins and the prone pose are not checked in a real client yet. I haven't done the hands-on session. Treat how bodies
look as unverified.

# Config

The file is `config/you-only-live-twice.json`. Edit it and run `/yolt reload`, or use the `/yolt` commands, which
save to it. If the file can't be read, `/yolt reload` changes nothing and tells you why. Everything below is read
live, except where the table says restart.

"Restart" means the setting is only read when the server starts, so changing it needs a restart.

## Top level

| Key | Default | What it does | Restart? |
|---|---|---|---|
| `configVersion` | `1` | Used for future migrations. Leave it. | No |
| `restrictCommands` | `true` | Non-ops only get `/help` (and `/w` if whispers are on). Off means vanilla commands, and then `/msg`, `/tell`, `/teammsg`, `/list` and vanilla suggestions show who is online. | No |
| `netherScaleOneToOne` | `true` | Nether coordinates match the Overworld, so no fast travel. | Yes |
| `randomSpawn` | `true` | Players with no spawn point spawn at a random spot inside the border. | No |
| `immediateRespawn` | `true` | Turns on the `immediate_respawn` game rule. Off leaves whatever the rule is. | No (applied at start and on `/yolt reload`) |

## `worldBorder`

| Key | Default | What it does | Restart? |
|---|---|---|---|
| `enabled` | `true` | Manage the border. Off leaves the border alone. | No |
| `diameter` | `10000` | Overworld and Nether border width in blocks. | No |
| `endScale` | `2.0` | The End border is `diameter` times this. | No |

## `deathLockout`

| Key | Default | What it does | Restart? |
|---|---|---|---|
| `enabled` | `true` | Dying kicks you and blocks rejoining. | No |
| `cooldownDays` | `7` | Days locked out. 0 means no wait. | No |

## `graves`

| Key | Default | What it does | Restart? |
|---|---|---|---|
| `enabled` | `true` | Deaths make graves. | No |
| `enderChestOnDeath` | `"keep"` | `keep`, `grave` or `wipe`. | No |

If `graves.enabled` is turned off, existing graves lose their protection and can't be opened. `/yolt grave remove`
still works on them.

## `revive`

| Key | Default | What it does | Restart? |
|---|---|---|---|
| `enabled` | `true` | Revive heads work when right-clicked. | No |

## `chat`

| Key | Default | What it does | Restart? |
|---|---|---|---|
| `mode` | `"proximity"` | `proximity`, `self_only` or `global`. | No |
| `radius` | `48` | Blocks, for proximity chat and whispers. | No |
| `allowWhispers` | `false` | Non-ops can use `/w`. | No |

## `hide`

| Key | Default | What it does | Restart? |
|---|---|---|---|
| `tabList` | `true` | Nobody is listed in the tab list. | No |
| `nametags` | `true` | Hides nametags with a scoreboard team. | No |
| `serverPlayerCount` | `true` | Server list shows 0/1 players. | No |
| `gameMessages` | `true` | Hides death, join, leave and advancement messages. | No |
| `tabHeader` | `true` | Shows a random flavour header in the tab list. | No (shown on join) |
| `distantPlayers` | `true` | Each player's game only learns about players near them. | No |
| `locatorBar` | `true` | Turns the locator bar game rule off. | No (applied at start and on `/yolt reload`) |

Notes:

- With `hide.distantPlayers` off, the join packet lists offline names in our team.
- Turning `hide.locatorBar` off doesn't turn the bar back on. Run `/gamerule locator_bar true`.
- Teams and scoreboard objectives that admins make are sent to every client.
- `enable-query=true` in `server.properties` exposes the full player list. Keep it off.
- Chat-bridge mods (Discord bridges and the like) don't see proximity chat.
- The plain-text chat fallback skips the Social Interactions block list and "Only Show Secure Chat".

## `sleep`

| Key | Default | What it does | Restart? |
|---|---|---|---|
| `hideSleepers` | `true` | Sleeping players are hidden. | No |
| `noNightSkip` | `true` | The night never skips. | No |
| `noBedRespawn` | `true` | Nobody respawns at a bed. | No |

## `bodies`

Settings that describe how a body is built (`type`, `pose`, `movable`, `totems`) apply to bodies made after the
change. The rest apply at once.

| Key | Default | What it does | Restart? |
|---|---|---|---|
| `enabled` | `true` | Logging out leaves a body. | No |
| `type` | `"mannequin"` | `mannequin` or `player`. See above. | No |
| `duration` | `"offline"` | `"offline"`, or a whole number of minutes (1 or more). | No |
| `pose` | `"prone"` | `prone`, `sleeping` or `standing`. | No |
| `mobsAttack` | `true` | Hunting mobs go after bodies. | No |
| `movable` | `true` | Bodies can be pushed, fall and drift. Off pins them in place. | No |
| `totems` | `true` | A held totem can save a body. The totem is used up in your save too. | No |
| `armourWears` | `true` | A body's armour wears down when hit, and the wear carries back to you. | No |
| `copyEffects` | `true` | A new body copies your potion effects and max health. When you log back in or the body expires, its effects replace yours. Off: the body gets no effects, and yours stay as they were. | No |

# Commands

All `/yolt` commands need op (permission level 2) or the server console. The console has no player, so
`/yolt adminview` doesn't work from there.

| Command | Who | What it does |
|---|---|---|
| `/yolt border <diameter>` | Ops, console | Sets the Overworld and Nether border width and saves it. |
| `/yolt center <x> <z>` | Ops, console | Sets the border centre (End stays at 0, 0). Vanilla saves the centre with the world, not in the config. |
| `/yolt endscaling <scaling>` | Ops, console | Sets `worldBorder.endScale`. Must be above 0. |
| `/yolt respawncooldown <days>` | Ops, console | Sets `deathLockout.cooldownDays`. |
| `/yolt kill <player>` | Ops, console | Kills a player, online or offline. An offline kill makes a grave and starts the lockout. |
| `/yolt unkill <player>` | Ops, console | Ends a player's lockout. |
| `/yolt grave list` | Ops, console | Lists every grave: owner, dimension, position. |
| `/yolt grave remove <x> <y> <z>` | Ops, console | Drops a grave's items on the ground and deletes the grave. It uses the command's dimension, so from the console run `execute in <dimension> run yolt grave remove ...`. |
| `/yolt adminview` | Ops (a player) | Turns admin view on or off for you. |
| `/yolt reload` | Ops, console | Re-reads the config file and re-applies it. |
| `/w <player> <message>` | Everyone, when `chat.allowWhispers` is on | Whispers to a player within `chat.radius`. Doesn't tab-complete names. |
| `/help` | Everyone | Answers with a spooky message. |

Ops lose `/w @a`-style selector targets, because `/w` is replaced. Use `/msg` or `/tell` instead.

# Known limits

- Turning `keepInventory` off before an offline-killed player respawns loses the items kept in their save.
- With `keepInventory` on, items on the cursor or in the crafting grid still drop at respawn (vanilla).
- A grave from an offline kill shows the default skin when no body was loaded, for example `/yolt kill` while the
  body's chunk is unloaded.
- Skins on bodies and graves, and how the poses look, are untested in a real client.

# Still to come

- Bodies travelling through portals (backburner). For now they can't.
- Time slowdown and multi-version support (parked).
- Getting your ender chest back after a dragon kill (parked, undecided).

Not planned: recipe balancing beyond the one revive recipe, and custom textures or models.

# Testing (for contributors)

Everything builds and tests on GitHub Actions, nothing needs to run locally. A run does the build, the JUnit tests,
the server GameTests, and the client GameTests, which save screenshots in the `client-screenshots` artifact. The
jar is in the `Artifacts` download. These tests haven't replaced a hands-on session in a real client, which I
haven't done yet.
