# Known limits

- **Debug privacy is all or nothing**, and minimap mods or hacked clients still show real coordinates, unless
  `hide.coordinates` is on, as it is by default.
- **Admins lose F3 hitboxes and chunk borders** (F3+B, F3+G) unless admin view is on.
- **Ops are locked out like everyone else.** The console can `/yolt unkill` them.
- **Offline-mode servers.** A lockout can't hold against a name change, since an offline player's id comes from their
  name. Anyone can also connect under a locked-out player's name, and the login refusal can show them where that
  player's grave is.
- **Adventure mode.** When the server's default game mode is adventure, vanilla puts players right on the world spawn,
  so random spawn doesn't run.
- **Beds and anchors** set no respawn point with `noBedRespawn` on. `/spawnpoint` still works as in vanilla.
- **A grave compass in a chest** doesn't spin until someone carries it.
- **Every death saves the grave file at once**, for crash safety, and so does every logout of a player who looted a
  grave. With 1,000 graves that takes about 0.1 to 0.2 seconds on the server thread, and the cost grows with the number of graves, because graves never expire. On a big
  server, remove old graves with `/yolt grave remove`. The grave, body and skin files are written to a temp file
  first and then swapped in, so a crash in the middle of a save leaves the last whole file.
- **Portals at 1:1.** A Nether portal only links to an Overworld portal within 16 blocks of its exit. A pair of
  portals that linked further apart before this version makes a new Overworld portal the first time someone goes
  through, and links to that one from then on.
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
- **Spectators** with a modified client can teleport to any online player, which shows who is online. Give spectator
  mode only to people you trust.
- **Raids** show their boss bar to players near the village, and the raid horn reaches anyone within 64 blocks.
- **`/bossbar`** bars reach whoever an admin adds to them.
- **Player sounds.** With `view-distance` under 10, a few loud sounds a player causes (a sculk shrieker, a returning
  trident) reach further than players are shown. Keep `view-distance` at 10 or more.
- **Lodestone compasses** hold the lodestone's position, so a looted or traded one points at someone's base.
- **Looting and crashes.** A crash can duplicate or lose what a player took from a grave since they were last saved
  (when they log out, or at a world save). Their logout saves the grave file too, so a looter who logged out keeps
  what they took and the grave doesn't get it back. If they stayed online and the grave file was saved since (any
  death saves it), the items are in neither. Vanilla chests behave the same.
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
- **Hardcore slowdowns** slow walking and running on the ground only. Sprint-jumping mostly skips them, since a jump
  gets a fixed push from the game. Swimming, ladders, elytra, horses, boats and minecarts ignore them.
- **Hardcore and modified clients.** Vanilla sends a nearby player's attributes and frozen ticks to clients that can
  see them, so a modified client can tell that a player it already sees is slowed, cursed or cold. It can't learn who
  is online or where anyone is.
- **Mob farms** with spawners are slower with tougher mobs on, since spawner mobs are boosted by default
  (`hardcore.tougherMobs.includeSpawners`).
- **Mobs from before tougher mobs.** A mob saved before tougher mobs was first turned on counts as naturally spawned
  when it next loads, so it is boosted even if it came from a spawn egg or a command back then.
- **The revive paper texture** comes from a resource pack the server can make optional or required. With it required,
  players who decline it can't join; with it optional, they see the missing texture on revive papers. Revive papers
  outside a player's inventory or open container (item frames, the ground, bundles, shulker boxes, closed chests)
  keep the model they had until a player has them again, so after the switch is turned off they show the missing
  texture to players without the pack.
- **Before removing YOLT.** The boosts from tougher mobs, the blood moon and relentless mobs are saved on each mob,
  and the game keeps them once YOLT is gone. First turn off `hardcore.tougherMobs.enabled`,
  `hardcore.bloodMoon.enabled` and `hardcore.relentlessMobs.enabled`, then visit the places whose mobs you care about:
  a mob loses its health, speed and follow-range boosts the next time it loads with the feature off. Mobs in chunks
  that never load again keep theirs. What doesn't come off at all: a zombie that won its door roll keeps breaking
  doors (only on Hard once YOLT is gone, as in vanilla), a charged creeper stays charged, the gear a blood moon gave
  stays, and the `yolt.` tags on mobs stay (they do nothing without YOLT). Turn `resourcePack.reviveTexture` off while
  the players who hold revive papers are online, so their papers lose the pack's model; papers elsewhere keep it and
  show the missing texture once the pack is gone. Players keep nothing: the curse and the slowdowns are never saved
  with the player, so lost max hearts come back once YOLT is gone, and frost from the cold thaws in a few seconds. The
  [hardcore database](hardcore.md#where-it-is-stored) stays in the world folder until you delete it.
- **Coordinate offset** and **skins** have their own lists, on the
  [coordinate offset](coordinate-offset.md#known-limits) and [skins](skins.md#known-limits) pages.

[All pages](README.md)
