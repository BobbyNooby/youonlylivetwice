YOLT 1.3.0 for Minecraft 26.3. It's a server-side Fabric mod: players join with a plain vanilla client and no client
mod.

This release makes two more of YOLT's rules mods of their own, the Mending nerf and the Elytra nerf, beside YOLT
Anvils. Nothing changes in the game for a YOLT server: no setting, default, command or message.

## Two more mods of their own

Each is for a Fabric server that wants only that part, with no YOLT on it.

- **YOLT Mending** (`yolt-mending-1.3.0.jar`). Mending repairs 0.4 durability per XP point instead of vanilla's 2, and
  no XP is lost. It reads `config/yolt-mending.json` and gives ops `/yolt-mending` and `/yolt-mending reload`.
  [Readme](https://github.com/BobbyNooby/youonlylivetwice/blob/release/extras/mending/README.md).
- **YOLT Elytra** (`yolt-elytra-1.3.0.jar`). Gliding is slower and floatier, and rockets can't push you past 20 m/s.
  It reads `config/yolt-elytra.json` and gives ops `/yolt-elytra` and `/yolt-elytra reload`. On its own it is on by
  default. [Readme](https://github.com/BobbyNooby/youonlylivetwice/blob/release/extras/elytra/README.md).
- **YOLT Anvils** (`yolt-anvils-1.3.0.jar`) does what it did in 1.2.1.
  [Readme](https://github.com/BobbyNooby/youonlylivetwice/blob/release/extras/anvil/README.md).

In each, a bad value in the config file is refused: the default is used at a server start, and the value in use at a
reload. Players in creative or spectator keep vanilla.

## For a YOLT server

- YOLT's own jar already holds all three and sets them from `hardcore.mending`, `hardcore.anvil` and
  `hardcore.elytra`, so a YOLT server needs nothing extra. The Elytra nerf stays off there until you turn on
  `hardcore.elytra.enabled`.
- Putting one of the three jars next to YOLT does no harm: Fabric loads one copy.
- Each of the three jars refuses to start beside a You Only Live Twice from before it shipped that mod, such as 1.2.1
  for Mending and Elytra. That YOLT has the rule itself, and it would otherwise run twice.
- The server log has a line for each of the three at a start, such as `YOLT Mending takes its settings from You Only
  Live Twice`.
- The gravity change on a glider now has the id `yolt-elytra:glide_gravity`. It was
  `you-only-live-twice:elytra_gravity`, which matters only to someone who reads it with `/attribute`.

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
three jars are only for servers without YOLT.

Updating from 1.2.1 needs nothing else: there are no new settings and no changed defaults. Going back to 1.2.1 works
too, and leaves the config file as it is. Take a `yolt-mending` or `yolt-elytra` jar out of `mods` first if you added
one.

Updating from 1.2.0 or older works as it did for 1.2.1: the
[1.2.1 notes](https://github.com/BobbyNooby/youonlylivetwice/releases/tag/v1.2.1) say what happens.

## Good to know

- Revive papers can get their own look from a resource pack, `you-only-live-twice-revive-pack-1.3.0.zip` on this
  release. YOLT doesn't send it: host it and set it in `server.properties`.
- The three small mods haven't been tried in a single-player world.
- Read the [known limits](https://github.com/BobbyNooby/youonlylivetwice/blob/release/docs/known-limits.md) before
  you host.
