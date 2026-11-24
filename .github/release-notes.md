YOLT 1.3.0 for Minecraft 26.3. It's a server-side Fabric mod: players join with a plain vanilla client and no client
mod.

This release makes four more parts of YOLT mods of their own, beside YOLT Anvils from 1.2.1: the hardcore layer, as
YOLT Hardcore, the coordinate offset, as YOLT Coordinates, the Mending nerf, as YOLT Mending, and the elytra nerf, as
YOLT Elytra. Nothing changes in the game for a YOLT server: no setting, default, command, message or `/status` text,
but for three small fixes:

- A daytime nap sends nobody vanilla's sleep line, such as "Sleeping through this night" or "1/2 players sleeping",
  when it starts or when it ends, since a nap never skips the night. On a YOLT server the line only came with
  `hardcore.bedRest.enabled` on and `sleep.hideSleepers` and `sleep.noNightSkip` both off.
- When a player joins while still dead, such as after leaving from the death screen, the Curse of the Undead now tells
  them at their respawn about the max heart that death took. It told them as they joined, behind the death screen. The
  notice counts only hearts still lost: those a revive paper gave back meanwhile (`hardcore.curse.reviveRefundHearts`,
  0 as it ships) come off it, and with none left it isn't sent. On a YOLT server this shows with the defaults, for a
  player who comes back after a revive paper or the end of their lockout, with no server restart since the death.
- A player joining for the first time while the border shrinks or moves (`/yolt border`, `/yolt center`, a reload) no
  longer lands outside it. If their random spot, picked as they log in, is outside the area random spots come from
  (the border, cut to `worldBorder.diameter`) when they are placed a moment later, YOLT picks a new one inside it, and
  the log names both spots. With no ground for a new one, as over a void, they join at the old spot. It needs
  `randomSpawn` on, as it ships.

## Five mods of their own

Each is for a Fabric server that wants only that part, with no YOLT on it.

- **YOLT Hardcore** (`yolt-hardcore-1.3.0.jar`), new. The hardcore layer: weight, broken legs, the limp, bed
  rest, the Curse of the Undead, nutrients, the hunger tax, tougher and relentless mobs, blood moons and cold. YOLT
  Anvils, YOLT Mending and YOLT Elytra come inside its jar. It reads `config/yolt-hardcore.json`, which has the same
  `hardcore` block as YOLT's config, key for key, and the six blood moon texts. Its defaults are YOLT's: only the Curse
  of the Undead, the flat anvil price and the Mending nerf are on. Everyone has `/status`, and ops have
  `/yolt-hardcore` with `reload`, `status`, `bloodmoon` and `curse`. There are no logout bodies without YOLT, so the
  rules for bodies do nothing. [Readme](https://github.com/BobbyNooby/youonlylivetwice/blob/release/hardcore/README.md).
- **YOLT Coordinates** (`yolt-coordinates-1.3.0.jar`), new. Each player's game gets its own fake X and Z, new at each
  join, respawn and dimension change, though players who start at the world spawn get about the same amount (see
  [What it gives away](https://github.com/BobbyNooby/youonlylivetwice/blob/release/coordinates/README.md#what-it-gives-away)).
  It reads `config/yolt-coordinates.json` and gives ops `/yolt-coordinates`, `/yolt-coordinates reload` and
  `/yolt-coordinates real`, which gives an op real coordinates. It only runs on a dedicated server.
  [Readme](https://github.com/BobbyNooby/youonlylivetwice/blob/release/coordinates/README.md).
- **YOLT Mending** (`yolt-mending-1.3.0.jar`), new. Mending repairs 0.4 durability per XP point instead of vanilla's
  2, and no XP is lost. It reads `config/yolt-mending.json` and gives ops `/yolt-mending` and `/yolt-mending reload`.
  [Readme](https://github.com/BobbyNooby/youonlylivetwice/blob/release/extras/mending/README.md).
- **YOLT Elytra** (`yolt-elytra-1.3.0.jar`), new. Gliding is slower and floatier, and a rocket's push is cut short
  once you fly faster than 20 m/s. That doesn't hold you at 20: your own game does the boosting and the server learns
  your speed a moment later, so the push goes on for a few ticks. Measured with one rocket fired from a slow glide,
  the push lasted about a fifth of a second, where without the cut-off it lasted about a second, and still took the
  player to about 32 m/s. A rocket fired while you are past the cut-off does nothing and isn't used up. It reads
  `config/yolt-elytra.json` and gives ops `/yolt-elytra` and `/yolt-elytra reload`. On its own it is on by default.
  [Readme](https://github.com/BobbyNooby/youonlylivetwice/blob/release/extras/elytra/README.md).
- **YOLT Anvils** (`yolt-anvils-1.3.0.jar`) prices anvils as it did in 1.2.1.
  [Readme](https://github.com/BobbyNooby/youonlylivetwice/blob/release/extras/anvil/README.md).

In each, a bad value in the config file is refused: the default is used at a server start, and the value in use at a
reload. Players in creative or spectator keep vanilla, except in YOLT Coordinates, which shifts everyone.

## For a YOLT server

- YOLT's own jar already holds all five and sets them from its own config, as before: the hardcore layer from
  `hardcore` and the blood moon texts in `messages`, the coordinate offset from `hide.coordinates`, and the three small
  mods from `hardcore.mending`, `hardcore.anvil` and `hardcore.elytra`. A YOLT server needs nothing extra. The Elytra
  nerf stays off there until you turn on `hardcore.elytra.enabled`.
- YOLT's jar now holds YOLT Hardcore's and YOLT Coordinates' jars, and YOLT Hardcore's holds the three small mods and
  the SQLite driver.
- Log lines of the coordinate offset now come from a logger named "YOLT Coordinates", and those of the hardcore layer
  from "YOLT Hardcore". They came from "you-only-live-twice". The three small mods log under their own names, such as
  "YOLT Mending".
- A start logs `YOLT Coordinates takes its settings from You Only Live Twice`, and a line like it for YOLT Anvils, YOLT
  Mending and YOLT Elytra.
- YOLT Hardcore keeps the ids the hardcore layer had inside YOLT: its modifiers and tags keep the
  `you-only-live-twice` namespace, its marks on mobs keep their `yolt.` names, and its store is still
  `database/you-only-live-twice-hardcore.db` in the world folder.
- The two heat tags moved from YOLT's jar into YOLT Hardcore's, so a YOLT server has one more built-in data pack,
  `yolt-hardcore`. The first start on an older world logs
  `Found new data pack yolt-hardcore, loading it automatically`.
- Every older YOLT has the coordinate offset built in, and 1.1.0 to 1.2.1 the other parts too, so each jar refuses to
  load beside one. Beside 1.2.1, which ships YOLT Anvils itself, the server starts with that copy.
- The gravity change on a glider is YOLT Elytra's own, so it now has the id `yolt-elytra:glide_gravity`. It was
  `you-only-live-twice:elytra_gravity`, which matters only to someone who reads it with `/attribute`.
- The elytra nerf's rocket cut-off works as it did in 1.2.1. The docs said rockets can't push you past 20 m/s, which
  isn't so, and now say what happens, as in the YOLT Elytra entry above.

## What it does

- **Death lockout.** Dying kicks you, and you can't rejoin for 7 days (configurable).
- **Graves.** Your items stay in a player head where you died, and anyone can loot it. Graves can't be broken and
  never expire.
- **Reviving.** Craft a grave head with a nether star, a netherite ingot, a diamond, a gold ingot and an iron ingot
  into a paper called "Revive <name>". Right-click with it to end that player's lockout.
- **Logout bodies.** When you log out, your body stays where you stood. Whatever happens to it happens to you.
- **Hidden information.** Global chat becomes proximity chat. The tab list, nametags, commands, the online player
  count, death, join and advancement messages and the coordinates on F3 are hidden. The server list shows no names
  and a flavour MOTD. Ops can turn on an admin view to see all of it.
- **World border.** The Overworld and the Nether share one border, 10,000 blocks across by default, with no Nether
  fast travel. New players spawn at a random spot inside it.
- **Hardcore layer.** Extra rules that make survival itself harder, each with its own switch. The Curse of the
  Undead, the flat anvil price and the Mending nerf are on by default. The rest is off until you turn it on.

The [documentation](https://github.com/BobbyNooby/youonlylivetwice/blob/release/docs/README.md) explains each feature.

## Install

You need Minecraft 26.3, Fabric Loader 0.20.1 or newer, Fabric API 0.165.2+26.3 or newer, and Java 25.

Put `you-only-live-twice-1.3.0.jar` in the server's `mods` folder next to Fabric API and start the server once. It
writes the config and, with the world border on (the default), sets the border to 10,000 blocks across. The other
five jars are only for servers without YOLT.

Updating from 1.2.1 needs nothing else: there are no new settings and no changed defaults.

Going back to 1.2.1 works too, and leaves the config file as it is. Take any `yolt-hardcore`, `yolt-coordinates`,
`yolt-mending` or `yolt-elytra` jar out of `mods` first: 1.2.1 won't start beside one. Its log then warns
`Missing data pack yolt-hardcore`, which does no harm: 1.2.1 has those two heat tags in its own jar.

Updating from 1.2.0 or older works as it did for 1.2.1: the
[1.2.1 notes](https://github.com/BobbyNooby/youonlylivetwice/releases/tag/v1.2.1) say what happens.

## Good to know

- Revive papers can get their own look from a resource pack, `you-only-live-twice-revive-pack-1.3.0.zip` on this
  release. YOLT doesn't send it: host it and set it in `server.properties`.
- Each of the five mods has been tried alone in automated client tests: a real Minecraft client joined a dedicated
  server with only that mod's jar, and opened single-player worlds with it. YOLT Hardcore and the three small mods run
  in single-player too, and YOLT Coordinates does nothing there. Each readme says what was tried.
- Not tried yet: two real players at once (the second player in YOLT Coordinates' test is a test player, not a second
  client), a busy server, and how any of it feels over an evening of play.
- Read the [known limits](https://github.com/BobbyNooby/youonlylivetwice/blob/release/docs/known-limits.md) before
  you host.
