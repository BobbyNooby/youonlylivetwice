# Hidden information

- **Commands.** Non-ops only get `/help`, which answers with a spooky message, plus `/w` when whispers are on,
  `/status` while a hardcore feature is on (by default both are), and `/yolt skin` when players may pick their skin. Ops below level 2 count
  as non-ops. A command on a sign an op wrote still runs when a non-op clicks it.
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
- **F3** is reduced for everyone (`hide.debugScreen`). See [Debug privacy](#debug-privacy) below.
- **Admin command feedback.** Ops of any level, even below level 2, see the "[name: ...]" lines vanilla commands send
  admins, and those can name players. `/gamerule send_command_feedback false` stops them. It also hides every
  player's own command replies, `/yolt` ones included.

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
  `hide.coordinates` is on: see [Coordinate offset](coordinate-offset.md).

Settings: [`hide`](config.md#hide), [`sleep`](config.md#sleep), [`serverList`](config.md#serverlist) and
`restrictCommands` in the [top level](config.md#top-level).

[All pages](README.md)
