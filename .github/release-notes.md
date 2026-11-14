YOLT 1.2.0 for Minecraft 26.3. It's a server-side Fabric mod: players join with a plain vanilla client and no client
mod.

## What's new: a flavour MOTD

- **The server list shows a flavour line as the MOTD.** A new one each time a player refreshes the list, never the
  same one twice in a row. The lines are `serverList.motdLines` in the config. By default they are the 13 lines of
  the tab list header, colours and all. A line can hold one `\n` to fill both rows of the MOTD.
- **A server updating to 1.2.0 gets it too**, since the new key arrives at its default. To keep your own MOTD from
  `server.properties`, set `"motdLines": []` in the `serverList` section and run `/yolt reload`.
- Only the server list's normal ping shows the line. Clients older than 1.7, the query port (`enable-query`) and the
  game itself after a join get the MOTD from `server.properties`.

The [config documentation](https://github.com/BobbyNooby/youonlylivetwice/blob/release/docs/config.md#serverlist)
has the details.

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

Put the jar in the server's `mods` folder next to Fabric API and start the server once. It writes the config and,
with the world border on (the default), sets the border to 10,000 blocks across.

Updating from 1.1.0 needs nothing else. At its next start the config file gets `serverList.motdLines` at its default.
The log doesn't name it, since the file's layout (`configVersion` 2) is the same. Going back to 1.1.0 is safe: it
warns that `serverList.motdLines` is an unknown key and leaves it out of the file.

Updating from 1.0.0 needs nothing else either:

- The old config is kept as `you-only-live-twice.json.v1.bak`, and the file goes to `configVersion` 2.
- `hide.coordinates`, `chat.allowWhispers` and `skins.lockAfterFirstChoice` are turned on only where your file still
  says `false`, 1.0.0's default. Each one turned on gets a line in the log with the command to turn it off again, such
  as `/yolt config hide.coordinates false`. One you already set to `true` stays.
- The keys and sections 1.0.0 didn't have are added at their defaults, and the log names them. So the flavour MOTD,
  the Curse of the Undead, the flat anvil price and the Mending nerf come on too.
- 1.0.0 leaves the newer config alone if you go back, since it is from a newer version.

## Good to know

- Revive papers can get their own look from a resource pack, `you-only-live-twice-revive-pack-1.2.0.zip` on this
  release. YOLT doesn't send it: host it and set it in `server.properties`.
- Coming from the old 1.21.5 build: its death database is moved aside at startup, and the lockouts in it end.
- Read the [known limits](https://github.com/BobbyNooby/youonlylivetwice/blob/release/docs/known-limits.md) before
  you host.
