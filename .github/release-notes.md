YOLT for Minecraft 26.3. It's a server-side Fabric mod: players join with a plain vanilla client, no client mod and no
resource pack.

## What it does

- **Death lockout.** Dying kicks you, and you can't rejoin for 7 days (configurable).
- **Graves.** Your items stay in a player head where you died, and anyone can loot it. Graves can't be broken and
  never expire.
- **Reviving.** Craft a grave head with a nether star into a revive head. Right-click with it to end its owner's
  lockout.
- **Logout bodies.** When you log out, your body stays where you stood. Whatever happens to it happens to you.
- **Hidden information.** Global chat becomes proximity chat. The tab list, nametags, commands, the online player
  count, death, join and advancement messages and the coordinates on F3 are hidden. Ops can turn on an admin view to
  see all of it.
- **World border.** The Overworld and the Nether share one border, 10,000 blocks across by default, with no Nether
  fast travel. New players spawn at a random spot inside it.
- **Off by default:** a grave compass that points at your latest grave, and fake X and Z coordinates for each player
  (`hide.coordinates`).

Every feature has a switch in `config/you-only-live-twice.json`, and `/yolt config` changes them in game. The
[README](https://github.com/BobbyNooby/youonlylivetwice/blob/release/README.md) explains each one.

## Install

You need Minecraft 26.3, Fabric Loader 0.20.1 or newer, Fabric API 0.165.2+26.3 or newer, and Java 25.

Put the jar in the server's `mods` folder next to Fabric API and start the server once. It writes the config and,
with the world border on (the default), sets the border to 10,000 blocks across.

## Good to know

- Coming from the old 1.21.5 build: its death database is moved aside at startup, and the lockouts in it end.
- Read "Known limits" in the README before you host.
