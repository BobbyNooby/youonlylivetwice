# You Only Live Twice

You Only Live Twice (YOLT) is a server-side Fabric mod for Minecraft 26.3. It raises the stakes of a multiplayer
survival world and pushes players to actually talk to each other in the game, ideally over proximity voice chat.
Players join with a plain vanilla client. No client mod, and the only resource pack is an optional one for the revive
paper's texture.

**[Read the full documentation](docs/README.md)**: every feature, setting, command and known limit.

## What it does

- **Death lockout.** Dying kicks you, and you can't rejoin for 7 days.
- **Graves.** Your items stay in a player head where you died. Anyone can loot it, and it can't be broken and never
  expires.
- **Reviving.** Craft a grave head with a nether star, a netherite ingot, a diamond, a gold ingot and an iron ingot
  into a paper called "Revive <name>". Right-click with it to end that player's lockout.
- **Logout bodies.** When you log out, your body stays where you stood. Whatever happens to it happens to you.
- **Hidden information.** Global chat becomes proximity chat. The tab list, nametags, commands, the online player
  count, death, join and advancement messages and the coordinates on F3 are hidden.
- **Admin view.** Ops can turn it on to see everything that is hidden.
- **World border.** The Overworld and the Nether share one border, 10,000 blocks across, with no Nether fast travel.
  New players spawn at a random spot inside it.
- **Skins.** On an offline-mode server, players get the skin of the Mojang account with their name, and can pick
  another with `/yolt skin set`.
- **Fake coordinates.** Each player sees X and Z shifted by a private amount, new at every join, respawn and dimension
  change.
- **Off by default:** a grave compass that points at your latest grave.
- **Hardcore layer.** Extra rules that make survival itself harder. Each part has its own switch, and only the first
  group is on by default. [Hardcore layer](docs/hardcore.md) explains them all.
  - **On by default:** a Curse of the Undead that takes a max heart at every return from death, flat anvil prices and
    a Mending nerf. With these on, every player has `/status`, a report on their own body.
  - **Your body:** carried weight, broken legs and a limp slow you down. Bed rest heals you and your legs faster.
  - **Food:** nutrients and a hunger tax.
  - **Gear:** an elytra nerf.
  - **The world:** tougher and more relentless mobs, blood moons and cold.
  - **Looks:** a texture for revive papers, from the optional resource pack.

Every feature has a switch in the config.

## Install

- You need Minecraft 26.3, Fabric Loader 0.20.1 or newer, [Fabric API](https://modrinth.com/mod/fabric-api)
  0.165.2+26.3 or newer, and Java 25.
- Get the jar from the [latest release](https://github.com/BobbyNooby/youonlylivetwice/releases/latest) and put it in
  the server's `mods` folder, next to Fabric API.
- Start the server once. It writes `config/you-only-live-twice.json` and sets the border to 10,000 blocks across.
- Set `spawn-protection=0` in `server.properties`, and pre-generate the world with Chunky.
  [Installing](docs/install.md) says why, and lists the companion mods I recommend.

## Documentation

The [documentation](docs/README.md) covers every feature, setting and command:

- [Installing](docs/install.md)
- [Config](docs/config.md)
- [Commands and admin view](docs/commands.md)
- [Death lockout](docs/death-lockout.md)
- [Graves](docs/graves.md)
- [Reviving](docs/reviving.md)
- [Logout bodies](docs/logout-bodies.md)
- [Chat](docs/chat.md)
- [Hidden information](docs/hidden-information.md)
- [Coordinate offset](docs/coordinate-offset.md)
- [World border and Nether](docs/world-border.md)
- [Skins](docs/skins.md)
- [Hardcore layer](docs/hardcore.md)
- [Known limits](docs/known-limits.md): read these before you host.
- [Contributing](docs/contributing.md)
