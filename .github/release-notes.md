YOLT 1.2.1 for Minecraft 26.3. It's a server-side Fabric mod: players join with a plain vanilla client and no client
mod.

This release closes five ways a player's client could learn what YOLT hides, fixes a set of bugs found in two audits,
adds settings for YOLT's texts and more, and makes the flat anvil price a mod of its own. No default changes.

## Hidden information: five leaks closed

- **Lodestone compasses in sign and head text.** A compass shown in the hover text of a sign, a hanging sign or a
  player head carried its real target. Those blocks now go through the coordinate offset too.
- **The decimals of a position.** A shifted position could keep enough of its decimals to work the offset out. Shifted
  positions are now rounded onto a fixed grid.
- **The world seed hash.** Every client got the real one. Each connection now gets its own made-up hash; admin view
  still gets the real one.
- **A pet's death message.** It reached the pet's owner even with game messages hidden. Now only in admin view, or
  with `hide.gameMessages` off.
- **"The server is full."** A refused join said the server was full, which tells how many are online. It now says
  "You can't join right now. Try again later." (`messages.serverFull`).

## Fixes

- A fall that lands right after another hit is judged whole, so it still breaks legs.
- The Curse of the Undead never takes your last heart, whatever else raised your maximum health.
- A cake slice counts only the part of it that fits your food bar toward your nutrients.
- The blood moon blocks the night skip from its warning on, not only once it has risen.
- A lockout's wait can't grow past its cooldown if the server's clock is set back, and a body's logout time is fixed
  the same way.
- A logout saves the body file at once, and a failed write is tried again at the next save.
- An item that can't be saved is left out of a grave or a body, instead of the whole file failing to save.
- A hardcore state that couldn't be read is never saved over.
- One slowdown that fails no longer stops the others.
- At most 16 skin lookups wait on Mojang at once.
- Revive papers follow a changed `resourcePack.reviveModel`.
- `/yolt reload` fails cleanly when it can't keep a copy of an older config.
- `hardcore.slow.cap` can be 0, which turns every slowdown off.

## New settings

- **`messages`:** the 15 texts YOLT tells players can be reworded, from the lockout screens and `/help` to the grave
  lines, the revive paper's name, the grave menu's title and the blood moon lines. `/yolt config messages.<key>
  <text>` sets one in game.
- **`allowedCommands`:** more commands a non-op keeps while `restrictCommands` is on, such as another mod's `vote`.
- **`hide.tabHeaderFrame`:** turn off the grey rows of dashes around the tab list header.
- **`hardcore.curse.afkMinutes`:** with `countAfk` off, how long a player may stand idle before curse time stops.
- **`hardcore.bloodMoon.barColour`:** the blood moon boss bar's colour.

The [config documentation](https://github.com/BobbyNooby/youonlylivetwice/blob/release/docs/config.md) has the
details.

## Faster

- Carried weight is no longer worked out from scratch every tally, so shulkers full of bundles stop costing the server
  time.
- Graves are found by chunk, bodies by their entity, and mobs skip the body search when no body is loaded.
- Less work per packet for the coordinate offset, and one body file write when the server stops.

## YOLT Anvils, a mod of its own

The flat anvil price is now also `yolt-anvils-1.2.1.jar`, for a Fabric server that wants only that part. On its own
it reads `config/yolt-anvils.json` and gives ops `/yolt-anvils reload`. YOLT's own jar already holds it and sets it from
`hardcore.anvil`, so a YOLT server needs nothing extra. Its
[readme](https://github.com/BobbyNooby/youonlylivetwice/blob/release/extras/anvil/README.md) has the rest.

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

Put `you-only-live-twice-1.2.1.jar` in the server's `mods` folder next to Fabric API and start the server once. It
writes the config and, with the world border on (the default), sets the border to 10,000 blocks across. The other jar,
`yolt-anvils-1.2.1.jar`, is only for servers without YOLT.

Updating from 1.2.0 needs nothing else:

- The new keys arrive at their defaults at the next start, and the `messages` defaults are YOLT's words from before.
- A choice in the config file must now be written exactly as the docs show it, such as `"proximity"` for `chat.mode`
  or `"whenMoved"` for `graves.tellLocation`. One written another way, such as `"PROXIMITY"`, falls back to its default
  with a line in the log.
- Going back to 1.2.0 is safe: it warns about the new keys and leaves them out of the file, so reworded texts are lost.

Updating from 1.1.0 or 1.0.0 works as it did for 1.2.0: the
[1.2.0 notes](https://github.com/BobbyNooby/youonlylivetwice/releases/tag/v1.2.0) say what happens.

## Good to know

- Revive papers can get their own look from a resource pack, `you-only-live-twice-revive-pack-1.2.1.zip` on this
  release. YOLT doesn't send it: host it and set it in `server.properties`.
- Read the [known limits](https://github.com/BobbyNooby/youonlylivetwice/blob/release/docs/known-limits.md) before
  you host.
