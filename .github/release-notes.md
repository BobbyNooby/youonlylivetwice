YOLT 1.4.0 for Minecraft 26.3. It's a server-side Fabric mod: players join with a plain vanilla client and no client
mod.

This release makes two more parts of YOLT mods of their own: the hardcore layer, as YOLT Hardcore, and the coordinate
offset, as YOLT Coordinates. With YOLT Anvils, YOLT Mending and YOLT Elytra, that makes five. Nothing changes in the
game for a YOLT server: no setting, default, command, message or `/status` text.

## Five mods of their own

Each is for a Fabric server that wants only that part, with no YOLT on it.

- **YOLT Hardcore** (`yolt-hardcore-1.4.0.jar`), new. The hardcore layer: weight, broken legs, the limp, bed
  rest, the Curse of the Undead, nutrients, the hunger tax, tougher and relentless mobs, blood moons and cold. YOLT
  Anvils, YOLT Mending and YOLT Elytra come inside its jar. It reads `config/yolt-hardcore.json`, which has the same
  `hardcore` block as YOLT's config, key for key, and the six blood moon texts. Its defaults are YOLT's: only the Curse
  of the Undead, the flat anvil price and the Mending nerf are on. Everyone has `/status`, and ops have
  `/yolt-hardcore` with `reload`, `status`, `bloodmoon` and `curse`. There are no logout bodies without YOLT, so the
  rules for bodies do nothing. [Readme](https://github.com/BobbyNooby/youonlylivetwice/blob/release/hardcore/README.md).
- **YOLT Coordinates** (`yolt-coordinates-1.4.0.jar`), new. Each player's game gets its own fake X and Z, new at each
  join, respawn and dimension change. It reads `config/yolt-coordinates.json` and gives ops `/yolt-coordinates`,
  `/yolt-coordinates reload` and `/yolt-coordinates real`, which gives an op real coordinates. It only runs on a
  dedicated server. [Readme](https://github.com/BobbyNooby/youonlylivetwice/blob/release/coordinates/README.md).
- **YOLT Mending** (`yolt-mending-1.4.0.jar`), new since 1.2.1. Mending repairs 0.4 durability per XP point instead of
  vanilla's 2, and no XP is lost. It reads `config/yolt-mending.json` and gives ops `/yolt-mending` and
  `/yolt-mending reload`.
  [Readme](https://github.com/BobbyNooby/youonlylivetwice/blob/release/extras/mending/README.md).
- **YOLT Elytra** (`yolt-elytra-1.4.0.jar`), new since 1.2.1. Gliding is slower and floatier, and rockets can't push
  you past 20 m/s. It reads `config/yolt-elytra.json` and gives ops `/yolt-elytra` and `/yolt-elytra reload`. On its
  own it is on by default.
  [Readme](https://github.com/BobbyNooby/youonlylivetwice/blob/release/extras/elytra/README.md).
- **YOLT Anvils** (`yolt-anvils-1.4.0.jar`) prices anvils as it did in 1.2.1.
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
- YOLT Hardcore keeps the ids the hardcore layer had inside YOLT: its modifiers, tags and marks keep the
  `you-only-live-twice` namespace, and its store is still `database/you-only-live-twice-hardcore.db` in the world
  folder.
- Each of the five jars refuses to load beside a You Only Live Twice older than itself. An older YOLT has these parts
  in it, and they would otherwise run twice.
- The gravity change on a glider is YOLT Elytra's own, so since 1.2.1 it has the id `yolt-elytra:glide_gravity`. It
  was `you-only-live-twice:elytra_gravity`, which matters only to someone who reads it with `/attribute`.

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

Put `you-only-live-twice-1.4.0.jar` in the server's `mods` folder next to Fabric API and start the server once. It
writes the config and, with the world border on (the default), sets the border to 10,000 blocks across. The other
five jars are only for servers without YOLT.

Updating from 1.2.1 needs nothing else: there are no new settings and no changed defaults.

Updating from 1.2.0 or older works as it did for 1.2.1: the
[1.2.1 notes](https://github.com/BobbyNooby/youonlylivetwice/releases/tag/v1.2.1) say what happens.

## Good to know

- Revive papers can get their own look from a resource pack, `you-only-live-twice-revive-pack-1.4.0.zip` on this
  release. YOLT doesn't send it: host it and set it in `server.properties`.
- YOLT Hardcore and the three small mods are meant to run in a single-player world too, but none of them has been
  tried in one. YOLT Coordinates does nothing there.
- YOLT Hardcore and YOLT Coordinates have not been played on in a real client without YOLT yet.
- Read the [known limits](https://github.com/BobbyNooby/youonlylivetwice/blob/release/docs/known-limits.md) before
  you host.
