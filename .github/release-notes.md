YOLT 1.1.0 for Minecraft 26.3. It's a server-side Fabric mod: players join with a plain vanilla client and no client mod.

## What's new: the hardcore layer

A set of optional rules that make survival itself harder. **All of it is off by default**, each part has its own
switch, and with a part off nothing changes for it.

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
- **Revive paper texture.** Revive papers can get their own look from a resource pack (a placeholder texture for
  now). The pack is `you-only-live-twice-revive-pack-1.1.0.zip` on this release. YOLT doesn't send it: host it and set
  it in `server.properties`.

The [hardcore documentation](https://github.com/BobbyNooby/youonlylivetwice/blob/release/docs/hardcore.md) explains
each part and how to set up the pack.

## Install

You need Minecraft 26.3, Fabric Loader 0.20.1 or newer, Fabric API 0.165.2+26.3 or newer, and Java 25.

Put the jar in the server's `mods` folder next to Fabric API and start the server once. Updating from 1.0.0 needs
nothing else: the hardcore settings are added to the config, all off.

## Good to know

- Read the [known limits](https://github.com/BobbyNooby/youonlylivetwice/blob/release/docs/known-limits.md) before you host.
