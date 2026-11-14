YOLT 1.1.0 for Minecraft 26.3. It's a server-side Fabric mod: players join with a plain vanilla client and no client
mod.

## What's new: the hardcore layer

A set of optional rules that make survival itself harder. Each part has its own switch, and with a part off nothing
changes for it. **Only the flat anvil price and the Mending nerf are on by default**, so every player now has
`/status`, with a Rules line. The rest is off until you turn it on.

- **Weight.** Everything you carry weighs something, and a heavy load slows you down.
- **Broken legs.** A long fall breaks your legs and slows you until they heal.
- **Limp.** Very low health slows you.
- **Bed rest.** Lying in a bed heals you, day or night.
- **Curse of the Undead.** Each return from death, by dying or by a totem, costs a max heart. They come back with play
  time.
- **Nutrients.** Foods carry protein, vitamins and sugar, and running low on one costs you.
- **Hunger tax, Mending nerf, flat anvil prices and an elytra nerf.**
- **Tougher and relentless mobs.** More health, harder hits, a longer chase, and zombies that break doors.
- **Blood moons.** Some nights bring more, tougher and better geared mobs that drop more.
- **Cold.** Out in the snow a chill builds, and torches, fires and leather keep it off.
- **`/status`** shows each player a report on their own body.
- **The curse can outlast the limp.** A server may let the curse take a player low enough to limp until a heart comes
  back; the log only notes it.
- **Revive paper texture.** Revive papers can get their own look from a resource pack (a placeholder texture for
  now). The pack is `you-only-live-twice-revive-pack-1.1.0.zip` on this release. YOLT doesn't send it: host it and set
  it in `server.properties`.

The [hardcore documentation](https://github.com/BobbyNooby/youonlylivetwice/blob/release/docs/hardcore.md) explains
each part and how to set up the pack.

## New defaults

- `hide.coordinates` is on: every player sees X and Z shifted by a private amount.
- `chat.allowWhispers` is on: everyone can `/w` a player in range.
- `skins.lockAfterFirstChoice` is on: a player picks their skin once, then only an op changes it.
- `hardcore.anvil.enabled` and `hardcore.mending.enabled` are on.
- `hardcore.curse.maxLostHearts` is 4 and `hardcore.curse.healEveryDays` is 3.0. The curse itself stays off.

## What it does

- **Death lockout.** Dying kicks you, and you can't rejoin for 7 days (configurable).
- **Graves.** Your items stay in a player head where you died, and anyone can loot it. Graves can't be broken and
  never expire.
- **Reviving.** Craft a grave head with a nether star, a netherite ingot, a diamond, a gold ingot and an iron ingot
  into a paper called "Revive <name>". Right-click with it to end that player's lockout.
- **Logout bodies.** When you log out, your body stays where you stood. Whatever happens to it happens to you.
- **Hidden information.** Global chat becomes proximity chat. The tab list, nametags, commands, the online player
  count, death, join and advancement messages and the coordinates on F3 are hidden. Ops can turn on an admin view to
  see all of it.
- **World border.** The Overworld and the Nether share one border, 10,000 blocks across by default, with no Nether
  fast travel. New players spawn at a random spot inside it.

The [documentation](https://github.com/BobbyNooby/youonlylivetwice/blob/release/docs/README.md) explains each feature.

## Install

You need Minecraft 26.3, Fabric Loader 0.20.1 or newer, Fabric API 0.165.2+26.3 or newer, and Java 25.

Put the jar in the server's `mods` folder next to Fabric API and start the server once. It writes the config and,
with the world border on (the default), sets the border to 10,000 blocks across.

Updating from 1.0.0 needs nothing else:

- The old config is kept as `you-only-live-twice.json.v1.bak`, and the file goes to `configVersion` 2.
- `hide.coordinates`, `chat.allowWhispers` and `skins.lockAfterFirstChoice` are turned on only where your file still
  says `false`, 1.0.0's default. Each one turned on gets a line in the log with the command to turn it off again, such
  as `/yolt config hide.coordinates false`. One you already set to `true` stays.
- The hardcore and resource pack settings are added at their defaults, and the log names them.
- 1.0.0 leaves a 1.1.0 config alone if you go back, since it is from a newer version.
- A config folder the server can't write no longer stops the start: YOLT runs on the defaults and says so.

## Good to know

- Coming from the old 1.21.5 build: its death database is moved aside at startup, and the lockouts in it end.
- Read the [known limits](https://github.com/BobbyNooby/youonlylivetwice/blob/release/docs/known-limits.md) before
  you host.
