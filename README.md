# You Only Live Twice

You Only Live Twice (YOLT) is a server-side Fabric mod for Minecraft 26.3. It raises the stakes of a multiplayer
survival world and pushes players to actually talk to each other in the game, ideally over proximity voice chat.

- Dying locks you out of the server for 7 days (configurable).
- Your items stay in a grave that anyone can loot.
- The things players usually lean on are hidden: global chat, the tab list, nametags, commands, the online player
  count, death, join and advancement messages, and the coordinates on the F3 screen.
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
sets the border to 10,000 blocks across. If that border would leave a body, a grave or the End's arrival platform
outside (or the world spawn, when new players start right on it), YOLT leaves the world's border as it is and says so
in the log.

On Java 25 the server log warns that a "restricted method" was called by `org.sqlite.SQLiteJDBCLoader`. That is the
bundled SQLite driver loading its native code, and it is harmless. If a later Java blocks it, start the server with
`--enable-native-access=ALL-UNNAMED`.

## Recommended server settings

- Set `spawn-protection=0` in `server.properties`. Vanilla stops non-ops from using blocks near the world spawn, and
  graves can end up there (for example when someone dies in the void and the grave falls back to the spawn column), so
  players could not open them.
- Keep `enable-query` off unless you need it. With `hide.serverPlayerCount` on, the query port reports 0 players and
  no names. With that switch off it reports the real count, and lists names only when `serverList.hoverLines` is
  empty. The default has one line, so by default it lists no names.
- Pre-generate the world with Chunky. Random spawn searches on the server thread, and a spot in a chunk that doesn't
  exist yet is generated on the spot, which lags.

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
(`deathLockout.cooldownDays`). The kick and the login screen say how you died and how long is left:

```
You died: Steve was slain by Zombie
Try again in 1 week.
```

- Each death keeps the cooldown it was recorded with. Changing `cooldownDays` only affects deaths from then on. Use
  `/yolt unkill` to end a lockout early.
- With a cooldown of 0 there is no wait and no kick.
- With the `show_death_messages` game rule off, the text is only "You died.", so it never names a killer.
- If the body of a player who is offline dies, they are told "Killed while away", with how the body died. That can
  name a killer they never saw.
- The death message is written in English, whatever language the player's game uses.
- Ops are locked out like everyone else. An op who locks themselves out needs the console to run `/yolt unkill`.
- A locked-out player who tries to join a full server is told how long they have left, not "server is full". Bans and
  the whitelist still come first.
- Deaths in creative or spectator mode don't count (`deathLockout.countCreativeAndSpectatorDeaths`): no lockout, no
  grave, and the player keeps their items, XP and score. With the switch on they count. A spectator still keeps
  everything, as in vanilla, so their grave holds only the revive head.
- If the death database can't be opened or read, the lockout lets everyone in and logs an error, instead of locking
  everyone out. Don't let another program hold a write lock on the database while the server runs: a login check
  waits at most 250 ms for the lock, then lets the player in and logs an error.

## Graves

When you die, a player head with your skin appears where you died, holding your items. It shows up even if your
inventory was empty, because the head is also the revive token.

- Right-click it to open it. Anyone can loot it. You can only take things out, not put things in.
- Graves can't be broken (not by hand, explosions, pistons or fluids) and never expire. If a head goes missing
  anyway, for example from an op's `/setblock`, the mod puts it back, both when the chunk loads and every
  `graves.recheckSeconds` (default 5) while it is loaded.
- When the last item is taken the head disappears.
- If a looter logs out holding a grave item on the cursor, it goes back into the grave.
- Your ender chest: `graves.enderChestOnDeath` can be `keep` (vanilla, the default), `grave` (the contents also go
  in the grave) or `wipe` (deleted). More than 54 stacks make a second grave next to the first.

Where the grave goes:

- On the death spot, or the nearest spot near it where items are safe. Never in lava or fire.
- If nothing close is safe, for example in the middle of a lava lake, it looks up to `graves.searchRadius` blocks out
  (default 16). A lava lake wider than that gets the grave over the lava, with a warning in the log. The items are
  safe in the grave, and players can bridge to it.
- If you die in mid-air, the grave lands where your items would have fallen: on the first block below, or on top of
  water, never under it.
- If you fall into the void, the grave goes to the last ground you stood on in that dimension. If there is none, it
  looks for land up to `graves.voidSearchRadius` blocks out (default 16), and then falls back to a fixed spot such as
  the world spawn.
- In the End, graves keep out of the spots the game rebuilds by itself: the exit portal, the arrival platform, the
  gateways and the obsidian spikes. A grave that ends up in one is moved out.
- Graves always go inside the world border.
- If a grave can't be made at all, your items and the revive head drop where it would have gone, and never despawn.
  With `keepInventory` you keep your items, and only the head drops.

`graves.tellLocation` says whether you are told where your grave is: `never`, `whenMoved` (the default: only when it
is more than `graves.tellLocationDistance` blocks, default 16, from where you died, or in another dimension) or
`always`. You are told in the lockout kick and the login screen, or in chat after you respawn if the death kicked
nobody. Only you are told.

With `graves.enabled` off, deaths drop items the vanilla way. A death while you're offline (your body's, or `/yolt
kill`) drops them where a grave's items would be safe instead, and they never despawn, since you aren't there to pick
them up. Graves already in the world stay protected and openable, and missing heads are still put back. The switch
only stops new graves.

## Grave compass

With `graves.compass` on (it is off by default), you get a compass the next time you are in the game and alive after
a death. With the lockout on, that is once it is over or you are revived.

- It is named "<your name>'s grave" and points at the grave of your latest death. It shows no coordinates.
- If the grave is moved (a missing head put back somewhere else), the compass follows it.
- Once the grave is gone it spins for good. It keeps its name, and never points north or at the spawn the way a plain
  compass does.
- In another dimension it spins, as a lodestone compass does in vanilla.
- If you use it on a lodestone, it becomes an ordinary lodestone compass. Your choice wins.
- A full inventory drops it at your feet, and that drop never despawns.
- A death while the switch was on still gets its compass after the switch is turned off.
- A compass in a chest doesn't update until someone carries it.

## Reviving someone

Craft a grave head together with a nether star in a normal crafting table. The result is a revive head. Right-click
with it while its owner is locked out and their lockout ends ("<name> can come back now."). One head is used up. If
the owner isn't locked out you get "<name> is not dead." (or "is not locked out." with the lockout off) and nothing
is used. A revived player comes back with nothing, at a random spot.

A revive head shows a purple name and lore, not an enchantment glint. Vanilla never draws the glint on player heads.

Grave and revive heads can't get Curse of Vanishing in an anvil, and a head that has it anyway still goes into the
grave.

The recipe is `data/you-only-live-twice/recipe/revive.json`, so a datapack can replace it with your own.

## Chat

- `proximity` (default): a message reaches players within `chat.radius` blocks (default 48) in the same dimension.
- `self_only`: you only see your own messages.
- `global`: vanilla chat.

Messages from ops and the console (`/say`, `/tellraw`) still reach everyone. Admins with admin view on see
everything.

With `chat.allowWhispers` on, non-ops can use `/w <player> <message>`. Ops can always use it. You always see "You
whisper to X", but X only gets it if they are within `chat.whisperRadius` blocks (0, the default, means the same as
`chat.radius`), so the sender can't tell who is online or nearby. In `global` mode a whisper has no range.

## Hidden information

- **Commands.** Non-ops only get `/help`, which answers with a spooky message, plus `/w` when whispers are on and
  `/yolt skin` when players may pick their skin. Ops below level 2 count as non-ops. A command on a sign an op wrote
  still runs when a non-op clicks it.
- **Tab list.** Empty, with a random flavour header (`hide.tabHeaderLines`).
- **Server list.** 0 players online out of `serverList.shownMaxPlayers` (default 1). Hovering the count shows
  `serverList.hoverLines` instead of names. The old ping and the query port show the same counts.
- **Nametags** are hidden, and death, join, leave and advancement messages are gone.
- **Nearby players only.** Each player's game is only told about players near them, so names can't leak through chat
  tab-complete or the Social Interactions screen. "Near" is the range at which the game shows another player: the
  smaller of the server's view distance and the player tracking range, which `entity-broadcast-range-percentage` in
  `server.properties` scales.
- **Shared maps** show other players' markers only to holders who can see them right now (`hide.mapPlayers`).
- **The locator bar** is turned off (it points at far-away players).
- **Global sounds** are turned off (`hide.globalSounds`): the wither spawning, the dragon dying and an End portal
  opening play for everyone in vanilla, from the direction they happened in.
- **The creaking heart's trail** reaches only the normal 32 blocks, not 512 (with `hide.distantPlayers` on).
- **Sleep.** The sleeper count reads zero and nobody gets the "players sleeping" line, the night never skips, and
  beds and respawn anchors set no respawn point.
- **Compasses** point north, not at the world spawn (`hide.compassSpawn`).
- **F3** is reduced for everyone (`hide.debugScreen`). See "Debug privacy" below.

New players spawn at a random spot inside the border, on ground where vanilla would spawn a player.

## Debug privacy

`hide.debugScreen` (on by default) gives every player vanilla's reduced F3 screen, the one the `reduced_debug_info`
game rule gives.

- Gone: the position, the targeted block and entity, the biome, light levels, the day count, speed and the other
  world details.
- Still there: performance numbers, the version, and the "Section-relative" line, which is your position inside the
  current 16-block section. Vanilla always shows that line.
- The F3+B hitboxes, F3+G chunk borders and F3+C copy-location keys stop working too, for admins as well, unless
  admin view is on.
- Admin view gives an op the full screen back, at once. The vanilla game rule still wins: with `reduced_debug_info`
  on, everyone gets the reduced screen.

`hide.serverBrand` (off by default) makes F3 name the server "vanilla" instead of "fabric". It applies when a player
next joins. It doesn't hide that the server runs Fabric from a modded client: Fabric's own channel registration and
its configuration ping still show it.

What the server can and can't do:

- It is all or nothing. The server sends one "reduced" flag per player, and the client decides line by line from
  that one flag. YOLT can't hide one line and keep another.
- Minimap mods and hacked clients ignore the flag and show whatever coordinates they are sent. Those are real unless
  `hide.coordinates` is on: see "Coordinate offset" below.

## Coordinate offset

`hide.coordinates` (off by default) gives each player fake X and Z numbers that even a minimap mod or a hacked client
can't get past.

- Every position the server sends a player is shifted by a private amount, and every position that player's game
  sends back is shifted back, so the server itself always works in real coordinates.
- Only X and Z move. Height stays real.
- The amount is new at each join, each death respawn and each dimension change, never on a timer. Each stretch starts
  you within 16 blocks of 0, 0.
- The End is shifted too, with an amount of its own.
- F3 stays reduced (`hide.debugScreen`). A client that ignores that still only shows shifted numbers.
- Minimap waypoints and Nether portal maths don't carry across a reset, as the numbers change under them.
- Border walls only show within the server's view distance plus 128 blocks. A wall further off goes out at the edge
  of the world instead, so seeing one tells you that one real axis, nothing more.
- Compasses still point north (`hide.compassSpawn`). With that off, they point at the world spawn, shifted, which is a
  point every player shares.
- Maps are unchanged: a map you hold still tells you your real X and Z, modulo the map's size.
- The seed fingerprint the server sends is unchanged.
- Admin view gives real coordinates from your next join, respawn or dimension change, and the `/yolt adminview` reply
  says so.
- Ops without admin view type coordinates in their own shifted numbers, and `~` and `^` work as usual. A particle's
  spread is a distance, so it stays as typed. Only what a player types counts: the console, command blocks, functions
  (a `/function` an op types included), advancement rewards and scheduled functions use real coordinates. Command
  replies show real numbers.
- A command in clickable chat text (from a data pack, say) is sent by the clicker's game as if they typed it, so its
  absolute numbers are read as the clicker's shifted ones.
- The grave notice: the kick and the login refusal name only the dimension. The chat line after a respawn shows the
  grave in your own numbers, or only the dimension when the grave is in another one.
- Text in chat, signs and books is never changed, so numbers someone writes stay as written. Only an item a text
  shows on hover, such as a compass in a death message, has its target shifted.
- Not handled: other mods that send positions in their own packets (voice chat, Distant Horizons) and packet-level
  anticheats. Don't run them with this on.

## World border and Nether

The Overworld and Nether share one border (default 10,000 blocks across). The End border is the same size times
`worldBorder.endScale` (default 2), up to vanilla's largest border. The Nether runs 1:1 with the Overworld, so there
is no Nether fast travel.

- `/yolt border`, `center`, `endscaling` and `/yolt config worldBorder.*` refuse a change that would leave online
  players, bodies, graves, the End's arrival platform, or the world spawn (when new players start right on it) outside
  the border. Add `confirm` to the command to do it anyway.
- `/yolt reload` can't be confirmed, so a reloaded border that would leave someone outside isn't applied. The live
  border stays, and the reply says how to apply it.
- A timed vanilla `/worldborder` change is left to finish at startup and on a reload. `/yolt border`, `endscaling`
  and `/yolt config worldBorder.*` stop it and set the size straight away, while the border feature is on.
- If the config's border values can't be read at startup, YOLT leaves the world's own border as it is until you fix
  the config and run `/yolt reload`.

## Admin view

Ops can run `/yolt adminview` to see what everyone else can't: the full tab list, nametags, every chat message, every
death, join and advancement message, every map marker, the full F3 screen and the real world spawn on a compass. It
is off by default and remembered per admin. It stops working when the admin is de-opped, and comes back if they are
opped again. In admin view you don't get other players' whispers, since those are private.

With `hide.coordinates` on, admin view also gives you real coordinates, from your next join, respawn or dimension
change.

## Logout bodies

When you log out, a body appears where you stood. It wears your skin, armour and held items and has no nametag.
While you're offline the body is you:

- **What carries over:** health, armour and items in hand, and fall, fire and air state. With `bodies.copyEffects`
  on, also your potion effects, max health and absorption hearts. Hunger and XP stay as they were. Nobody can take
  items off a body.
- **Killing a body kills you.** A grave appears at the body with your saved inventory, your save is cleared, and
  your lockout starts. `/yolt kill` on an offline player does the same.
- **Vanilla `/kill` skips bodies** (`bodies.ignoreVanillaKill`). A lag clear like `/kill @e[type=!player]` leaves
  them standing and says how many it skipped. `/yolt kill <player>` is the way to kill one on purpose. Damage and the
  void still kill a body, and so does a mod that kills entities in its own code. A mod that removes entities without
  killing them is safe: the owner takes over the body's last saved state at their next login.
- **Logging back in:** you take over the body as it is now. You appear where it is, with its health, effects and
  gear (a popped totem stays used, worn armour stays worn). Then the body is removed.
- **How long it stays:** `bodies.duration` is `offline` (the default, the body stays the whole time you're away) or
  a number of minutes. The number counts real time, so server downtime counts too. Every player online when the
  server stops gets a body.
- **Poses:** `bodies.pose` is `prone` (lying face-down, the default), `sleeping` or `standing`. `standing` can
  suffocate a body that was crawling through a 1-block gap.
- **Mobs hunt bodies** (`bodies.mobsAttack`). Mobs that hunt players on sight hunt bodies the same way. Mobs that
  are calm until provoked stay calm: endermen, zombified piglins, and spiders in daylight. Piglins, hoglins, wardens,
  breezes and creakings ignore mannequin bodies (a player body is another matter, see below). Slimes and magma cubes
  follow a body but can't hurt it by touch.
- **Difficulty counts.** A mannequin body takes the damage you would take on the server's difficulty, from mobs,
  explosions and everything else the difficulty scales.
- **Totems, armour wear and potion effects** work on the body like they would on you (each has a switch).
- **Bodies move like players:** gravity, knockback, water and fall damage all apply, and you log back in wherever the
  body ended up. They **can't use portals yet**; portal travel for bodies is on the list below.
- **Riding:** if you logged out on a boat or minecart, vanilla puts you back on the vehicle, so you land where the
  vehicle is, not where the body ended up. Your health, effects and gear still come from the body.
- **Race at login:** if a body dies just as its owner is logging in, the owner is kicked with the lockout message
  and their lockout stands. If it expires just then, they arrive at their logout spot with the body's state. Nothing
  is lost or duplicated.
- **Leftover items** a body has to drop (an item a dispenser put on it, for example) never despawn. Under water they
  float and can drift. A body that dies drops them where a grave's items would be safe, never into the lava or the
  void that killed it.

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
    - Piglins, hoglins, wardens, breezes and creakings see a real player in it and go after it. They ignore a
      mannequin.
    - With the `pvp` game rule off, or friendly fire off on a team, players can't hurt it (they can hurt a mannequin).
    - Players near it get a player entry for it with the owner's name, kept out of the tab list. A vanilla client
      reads every player entry for chat tab-complete and the Social Interactions screen, listed or not, so a nearby
      player can find the offline owner's name there, the same as if the owner stood there. The body is you.
    - With `hide.nametags` off, it shows its owner's nametag. A mannequin never shows one.
    - After a server restart, a player body comes back with the default main hand and the default max health (plus
      any health boost effect it has), and health above that is lost. When its owner logs in, they take over the
      body's health and gear as usual. Only their max health comes from their save.
    - It uses a Fabric internal class to run with no real connection (`UntrackedPacketListener`), which a Fabric
      update could move. If a Fabric update breaks it, that is the first place to look.

## Skins

On an offline-mode server nobody has a skin. With `skins.mode: auto` (the default), a player who joins without one
gets the skin of the Mojang account with their name: on themselves, their body, and their grave and revive heads. On
an online-mode server players already have their skin, and YOLT only changes it for players who pick one.

- **Picking a skin.** `/yolt skin set <mojangName>` wears that real Mojang account's skin from your next join on, over
  every other skin. `/yolt skin clear` goes back to the default. Non-ops get the command while
  `skins.allowPlayerChoice` is on, and wait 5 seconds between picks. Ops and the console can set and clear anyone's.
- **Other players' skins.** With `skins.refuseKnownPlayers` on (the default), a non-op can't pick the skin of a player
  who has joined this server, or one another player picked. Your own name is always allowed. If you picked someone's
  skin before they first joined, the pick goes at your next login and you are told why. A pick an op made stays.
- **Picking once.** With `skins.lockAfterFirstChoice` on, a non-op picks once, and after that only an op can change
  it.
- **Slow Mojang.** A join waits up to `skins.lookupTimeoutSeconds` (default 5, at most 10) for Mojang. Then the player
  joins with the skin saved before, or none. A join is never refused because of skins.
- **Caching.** Skins are kept for `skins.refreshHours` (default 24; 0 asks Mojang on every join).
- **Other skin mods.** YOLT leaves skins alone when SkinsRestorer or SkinRestorer is installed. Use one of those for
  custom or URL skins.
- **Older bodies and heads** keep the skin they were made with.

**Privacy.** In `auto` mode YOLT sends names to Mojang's servers, from the server's IP: the name of each player who
joins without a skin, and every name picked with `/yolt skin set`. With `skins.mode: off`, nothing is ever sent to
Mojang.

# Config

The file is `config/you-only-live-twice.json`. Edit it and run `/yolt reload`, or use `/yolt config <key> <value>`,
which changes one key in the file and in the running server at once. Other `/yolt` commands that change a setting
save it to the file too.

No setting needs a restart. The "Restart?" column says when a change shows:

- **No:** at once.
- **Next join:** when a player next joins.
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
| `countCreativeAndSpectatorDeaths` | `false` | Off: a death in creative or spectator makes no lockout, grave or death record, and the player keeps their items, XP and score. On: they count, and a spectator's grave holds only the revive head. | New deaths |

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
| `enabled` | `true` | Revive heads work when right-clicked. | No |

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
| `coordinates` | `false` | Each player sees X and Z shifted by a private amount, new at every join, death respawn and dimension change. Admin view sees real ones. | Next join |

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
- Teams and scoreboard objectives that admins make are sent to every client. An objective an admin shows in the
  sidebar, below names or in the list reaches non-ops too, and it can show player names. YOLT doesn't hide it.
- Chat-bridge mods (Discord bridges and the like) don't see proximity chat.
- The plain-text chat fallback skips the Social Interactions block list and "Only Show Secure Chat".

## `sleep`

| Key | Default | What it does | Restart? |
|---|---|---|---|
| `hideSleepers` | `true` | The sleeper count reads zero and nobody gets the "players sleeping" line, so nobody can tell who is in bed. | No |
| `noNightSkip` | `true` | The night never skips. Getting into a bed still resets the phantom timer. | No |
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
| `type` | `"mannequin"` | `mannequin` or `player`. See above. | New bodies |
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

## Updating and backups

YOLT keeps its data in versioned files, so a newer build can upgrade them:

- **Config.** An older config file is upgraded at startup, and the old file is kept as
  `you-only-live-twice.json.v<version>.bak`. A config from a newer YOLT is read as far as this one can, but never
  rewritten. At startup, a file that can't be read at all is kept as `you-only-live-twice.json.bak` and replaced with
  defaults.
  A backup name that already holds a different file is never written over: the copy gets the time added
  (`.bak-<time>`).
- **Graves, bodies and skins** live in the world's `data/you-only-live-twice` folder (`graves.dat`, `bodies.dat`,
  `skins.dat`). An older file is upgraded as it loads, and the items in it go through Minecraft's own data fixer, so
  a Minecraft update doesn't make them unreadable. A file from a newer YOLT or Minecraft is copied to
  `<file>.newer-<time>` before it is saved over. A file, or part of one, that can't be read is copied to
  `<file>.unreadable-<time>`.
- **Death database** (`database/you-only-live-twice.db` in the world folder). Its layout is upgraded in place. A
  database from the old 1.21.5 build is moved aside to `you-only-live-twice.db.1.21.5-<time>.bak` at startup, and
  the lockouts in it end. One from a newer YOLT is left untouched, and the lockout then lets everyone in.

### On every Minecraft update

The coordinate offset reads every packet, so a new Minecraft version can add a position it doesn't know about. Before
a release on a new version:

- Run the coverage and leak tests (`CoordinateOffsetCoverageGameTests` and `LeakSweepGameTests`). Every new packet,
  item component, entity data type, particle, block entity, debug subscription, position source and waypoint type
  has to be sorted into "shifted" or "holds no position" first.
- Compare the packet classes that changed between the two versions, and look in them for new positions, items, NBT
  and particles.
- Check that every method the hooks sit on still exists with the same arguments: sending and receiving on the
  connection, the respawn, the teleport, the parsing of a typed command, the container click, the coordinate
  arguments, the debug subscriptions and the collision shape.
- Check that the order of things still holds: on a dimension change the respawn comes before the old chunks are
  forgotten, a dead player is sent no forgets, the client keeps its chunks on a respawn in the same dimension, and
  accepting a teleport ends it.
- Read the update commit for that version in CoordinateOffset (the Paper plugin that does the same job). It lists
  what moved.

# Commands

All `/yolt` commands need op (permission level 2) or the server console, except `/yolt skin` for your own skin. The
console has no player, so `/yolt adminview` and the own-skin forms of `/yolt skin` don't work from there.

| Command | Who | What it does |
|---|---|---|
| `/yolt border <diameter> [confirm]` | Ops, console | Sets the Overworld and Nether border width and saves it. With the border feature off it only saves the value. |
| `/yolt center <x> <z> [confirm]` | Ops, console | Sets the border centre (End stays at 0, 0). Vanilla saves the centre with the world, not in the config. Refused while `worldBorder.enabled` is off. |
| `/yolt endscaling <scaling> [confirm]` | Ops, console | Sets `worldBorder.endScale`. Must be above 0. |
| `/yolt respawncooldown <days>` | Ops, console | Sets `deathLockout.cooldownDays`, for deaths from now on. |
| `/yolt kill <player>` | Ops, console | Kills a player, online or offline. An offline kill makes a grave and starts the lockout. |
| `/yolt unkill <player>` | Ops, console | Ends a player's lockout. |
| `/yolt grave list` | Ops, console | Lists every grave: owner, dimension, position. |
| `/yolt grave remove <x> <y> <z>` | Ops, console | Drops a grave's items on the ground and deletes the grave. The drops never despawn. If there is nowhere safe nearby to drop them, it refuses and changes nothing. It uses the command's dimension, so from the console run `execute in <dimension> run yolt grave remove ...`. |
| `/yolt adminview` | Ops (a player) | Turns admin view on or off for you. |
| `/yolt config <key> [value]` | Ops, console | Shows a config key, or sets it in the file and the running server at once. A border key's value can end in `confirm`. Tab-complete lists the keys. |
| `/yolt reload` | Ops, console | Re-reads the config file and re-applies it. |
| `/yolt skin set <mojangName>` | Everyone, when `skins.allowPlayerChoice` is on | Wears that Mojang account's skin from your next join. |
| `/yolt skin clear` | Everyone, when `skins.allowPlayerChoice` is on | Goes back to the default skin. |
| `/yolt skin set <player> <mojangName>` | Ops, console | Sets another player's skin. |
| `/yolt skin clear <player>` | Ops, console | Clears another player's pick. |
| `/w <player> <message>` | Everyone when `chat.allowWhispers` is on; ops always | Whispers to a player within `chat.whisperRadius`. Doesn't tab-complete names. |
| `/help` | Everyone | Answers with a spooky message. |

A command that names a player finds them only where this server knows them: online players, the server's name cache,
the death log and YOLT's record of joins. It never asks Mojang. Anyone else gets "No player named X has joined this
server.", so an op can't kill, unkill or pick a skin for a player who has never joined.

Ops lose `/w @a`-style selector targets, because `/w` is replaced. Use `/msg` or `/tell` instead.

# Known limits

- **Debug privacy is all or nothing**, and minimap mods or hacked clients still show real coordinates, unless
  `hide.coordinates` is on.
- **Admins lose F3 hitboxes and chunk borders** (F3+B, F3+G) unless admin view is on.
- **Ops are locked out like everyone else.** The console can `/yolt unkill` them.
- **Offline-mode servers.** A lockout can't hold against a name change, since an offline player's id comes from their
  name. Anyone can also connect under a locked-out player's name, and the login refusal can show them where that
  player's grave is.
- **Adventure mode.** When the server's default game mode is adventure, vanilla puts players right on the world spawn,
  so random spawn doesn't run.
- **Beds and anchors** set no respawn point with `noBedRespawn` on. `/spawnpoint` still works as in vanilla.
- **A grave compass in a chest** doesn't spin until someone carries it.
- **Every death saves the grave file at once**, for crash safety. With 1,000 graves that takes about 0.1 to 0.2
  seconds on the server thread, and the cost grows with the number of graves, because graves never expire. On a big
  server, remove old graves with `/yolt grave remove`.
- **Portals at 1:1.** A Nether portal can link to an existing Overworld portal up to 128 blocks away, instead of making
  a new one at the same coordinates.
- **Shared maps** show other players only to holders who can see them (`hide.mapPlayers`), but a held map still fills
  in terrain for every copy.
- **Random spawn lag.** Pre-generate the world with Chunky.
- **Offline deaths and statistics.** An offline death (a mannequin body killed, or `/yolt kill` on an offline player)
  doesn't count in the death statistic or a `deathCount` scoreboard. A player-type body's death does, under the
  owner's name.
- **A vanilla `/kill` with `bodies.ignoreVanillaKill` off** kills every logged-out player whose body it matches, for
  real. Use `/yolt kill` for bodies.
- **Switching a game rule back.** Turning off `immediateRespawn`, `hide.locatorBar` or `hide.globalSounds` stops YOLT
  setting the rule, but leaves it as it is. Turning off `worldBorder.enabled` leaves the border as it is. Reset them
  with vanilla commands: `/gamerule immediate_respawn false`, `/gamerule locator_bar true`,
  `/gamerule global_sound_events true` and `/worldborder set <size>`.
- **Chat after a reload.** After a `/yolt reload` that turns whispers off or `restrictCommands` on, a command a player
  sends at that same moment can break their signed chat until they rejoin. Vanilla has the same race on `/deop`.
- **`/trigger` menus** that use chat or book click events don't work for non-ops while `restrictCommands` is on.
- **Looting and crashes.** If the server crashes after a looter logs out, before the next world save, the grave gets
  back what they took, and they keep it too. Vanilla chests behave the same.
- **Unreadable items** (from a removed mod, say) stay in a player's save when they die offline, then vanish at their
  next respawn, as in vanilla. The warning in the log holds the raw entry, so an admin can put it back.
- **Removed mods and bodies.** If a mod is removed and then added back while a player is offline, their body's copy
  of that mod's item was already dropped, so the takeover clears the real item.
- **Undoing an uncounted death.** An op who removes the `yolt_uncounted_death` tag from a dead player before they
  respawn takes back that death's hand-back of items, XP and score.
- **`keepInventory`.** Turning it off before an offline-killed player respawns loses the items kept in their save.
  With it on, items on the cursor or in the crafting grid still drop at respawn (vanilla).
- **Graves from offline kills** with no body loaded (for example `/yolt kill` while the body's chunk is unloaded) show
  the skin YOLT last knew for the player (their pick, or the skin they last joined with), or the default skin with
  `skins.mode: off`.
- **Coordinate offset** (`hide.coordinates`):
    - A wall of a border whose size and centre are known gives that real axis away once you see it.
    - A map you hold gives your real X and Z modulo its size, and a known seed gives everything away through the
      terrain.
    - In the End, the main island at real 0, 0 gives the End's amount away. It is new at every trip there.
    - Amounts stop at 999,984 blocks. A player about 1,000,000 or more blocks out on an axis (a border has to reach
      that far) starts a stretch far from 0, 0, at their real numbers moved 999,984 blocks towards 0.
    - With `max-world-size` set in `server.properties`, the edge of the world counts as a border wall: only its near
      part shows.
    - These go out as an op or a data pack wrote them, with any position in them real:
        - a position under an NBT key YOLT doesn't know. It shifts vanilla's own: keys ending in `pos`,
          `exit_portal`, `leash`, `patrol_target`, `wander_target`, `beam_target`, a breeze's jump target and map
          markers;
        - the `minecraft:lock`, `minecraft:can_place_on` and `minecraft:can_break` predicates on an item, and a
          trade's costs;
        - the items shown in recipes, advancements and dialogs;
        - an item shown on hover in text held in a registry entry (an instrument, a jukebox song, a trim, a painting),
          in an item's attribute display, in the separator of a selector or NBT text, in a dialog or custom click
          event, or in a chat type's own style.
    - Not yet tried in a real client: walking through bamboo, a sculk sensor's particle in flight, dragging a compass
      around the inventory, a border wall coming into view as you walk up to it, and minimap mods.
- **Skins:**
    - A name shows the skin of whichever Mojang account owns that name now. That may be a stranger, and it changes if
      the account is renamed. A name with no account keeps the default skin.
    - A pick is stored as a name, so after a rename it follows the name, not the account.
    - Refusing a non-op's pick tells them the name is in use on this server. No wording can hide that.
    - Two players who already shared a pick from before this build, or from while `refuseKnownPlayers` was off, both
      keep it.
    - A stale name-cache entry after a rename can refuse a skin that is actually free. It only ever refuses too much.
    - A skin an op set for themselves counts as op-made, so it stays when its owner joins, even after the op is
      de-opped.

# Still to come

- Bodies travelling through portals (backburner). For now they can't.
- Time slowdown and multi-version support (parked).
- Getting your ender chest back after a dragon kill (parked, undecided).

Not planned: recipe balancing beyond the one revive recipe, and custom textures or models.

# Testing (for contributors)

Everything builds and tests on GitHub Actions, nothing needs to run locally. A run does the build, the JUnit tests,
the server GameTests, and the client GameTests, which save screenshots in the `client-screenshots` artifact. The
jar is in the `Artifacts` download. Real skins aren't checked in CI: test players have no skins, and CI never asks
Mojang. These tests haven't replaced a hands-on session in a real client, which I haven't done yet.
