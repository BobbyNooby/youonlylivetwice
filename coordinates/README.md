# YOLT Coordinates

YOLT Coordinates is a server-side Fabric mod for Minecraft 26.3 that hides where players really are: each player's game
is sent its own fake X and Z, while the server keeps working in the real ones. It is the coordinate offset of
[You Only Live Twice](../README.md), as a mod of its own for servers that want only this. Players join with a plain
vanilla client.

## What it does

It is on as soon as it is installed.

- Every position the server sends a player is shifted by a private amount, and every position that player's game sends
  back is shifted back, so the server itself always works in real coordinates. A minimap mod or a hacked client gets
  the fake numbers too, though a client built for it can still work some real numbers out (see
  [What it gives away](#what-it-gives-away)).
- Only X and Z move. Height stays real.
- The amount is new at each join, respawn and dimension change, never on a timer. Each stretch starts you in the chunk
  at 0, 0.
- Every dimension is shifted, each stretch with its own amount. A spot in another dimension than the one you are in,
  such as your last death, is shifted by a separate hidden amount for that dimension, which stays the same until you
  next join.
- Everyone is shifted: ops, and players in creative or spectator, too.
- Minimap waypoints and Nether portal maths don't carry across a new stretch, as the numbers change under them.
- The seed fingerprint the server sends is swapped for a random one, new for each connection. A client only uses it to
  blur biome edges, so those look a little different at each login. A player with real coordinates gets the real one.
- Every shifted X and Z is rounded to a grid of 2^-27 of a block, far finer than anything a player can see. Unrounded,
  the low digits of a position would tell how far from 0, 0 it really is.
- Border walls only show within the server's view distance plus 128 blocks. A wall further off goes out at the edge of
  the world instead, so seeing one tells you that one real axis, nothing more.
- Maps are unchanged: a map you hold still tells you your real X and Z, modulo the map's size.
- Text in chat, signs and books is never changed, so numbers someone writes stay as written. Only an item a text shows
  on hover, such as a compass in a death message, has its target shifted.

## What changes for ops

- An op is shifted like everyone else until they run `/yolt-coordinates real`, which gives them real coordinates from
  their next join, respawn or dimension change. Running it again shifts them again from the next one. It is kept until
  the server stops, across logins, and only counts while the player is an op: a player who is de-opped is shifted
  again from their next join, respawn or dimension change, and opped again they get real coordinates back from the
  next one.
- An op with an offset types coordinates in their own shifted numbers, and `~` and `^` work as usual. A particle's
  spread is a distance, so it stays as typed. Only what a player types counts: the console, command blocks, functions
  (a `/function` an op types included), advancement rewards and scheduled functions use real coordinates. Command
  replies show real numbers.
- Only position arguments are read in the op's numbers. A selector's `x=`, `y=` and `z=`, positions inside typed SNBT
  or components, and a function macro's arguments are read as real even when an op types them.
- A command in clickable chat text is sent by the clicker's game as if they typed it, so its numbers are read as the
  clicker's shifted ones.
- An op with an offset gets an empty save when their game asks the server for an entity's or a block entity's data,
  and none of vanilla's debug values but the server's tick times. Real coordinates give both back.

## What it gives away

Inside You Only Live Twice, by default, compasses point north, F3 stays reduced and players with no spawn point start
at a random spot. On its own the mod has none of these:

- A compass points at the world spawn, shifted like everything else. A player who knows where the world spawn really
  is can work out their amount from where the compass points.
- The mod doesn't reduce F3: it shows vanilla's screen, in your shifted numbers.
- Random spawn: a new player and anyone who respawns with no bed start at the world spawn, so all those stretches get
  the same amount, give or take a chunk, and players in them can share coordinates. The world spawn is often near real
  0, 0, so those numbers are close to the real ones, and real when it is in the chunk at 0, 0.

These limits apply as they do inside YOLT:

- A wall of a border whose size and centre are known gives that real axis away once you see it.
- A map you hold gives your real X and Z modulo its size, and a known seed gives everything away through the terrain.
- Some terrain is the same in every world, whatever the seed: where snow starts on a mountain, where frozen-ocean ice
  ends, and which flowers grow where in a flower field. A client built for it can match what it sees against that and
  find its real X and Z.
- In the End, the main island at real 0, 0 gives the End's amount away. It is new at every trip there, and spots in
  other dimensions are never shown in it.
- Each stretch puts you in chunk 0, 0 at its start, so a stretch that starts in the real chunk at 0, 0 shows real
  numbers, for spots in other dimensions too, and anyone who knows where a stretch started knows its amount.
- A modified client can carry its own numbers across a death or a relog: your last death spot comes again in the new
  numbers, so it can work out how far they moved. It still learns no real coordinate.
- Amounts stop at 999,984 blocks. A player about 1,000,000 or more blocks out on an axis starts a stretch far from
  0, 0, at their real numbers moved 999,984 blocks towards 0.
- With `max-world-size` set in `server.properties`, the edge of the world counts as a border wall: only its near part
  shows.
- Some things go out as an op or a data pack wrote them, with any position in them real, such as a position under an
  NBT key the mod doesn't know.
- Not handled: another mod's own packets, which go out as that mod sent them, real positions included, and
  packet-level anticheats. Don't run either with this on.
- Not yet tried in a real client: walking through bamboo, a sculk sensor's particle in flight, dragging a compass around
  the inventory, a border wall coming into view as you walk up to it, and minimap mods.

[docs/coordinate-offset.md](../docs/coordinate-offset.md) has the full list. It is written for YOLT: there the setting
is `hide.coordinates`, and admin view does what `/yolt-coordinates real` does here.

Tried in a real client: on a server with only this mod the F3 screen shows the shifted numbers, a walk and a block
broken and placed in creative land on the real spot, a respawn starts a new stretch in chunk 0, 0, and
`/yolt-coordinates real` gives an op real numbers from their next respawn and, run again, shifted ones from the respawn
after; in a single-player world the client is shown real numbers and `/yolt-coordinates` says the mod does nothing
there.

## Install

- You need Minecraft 26.3, Fabric Loader 0.20.1 or newer, [Fabric API](https://modrinth.com/mod/fabric-api)
  0.165.2+26.3 or newer, and Java 25.
- Get `yolt-coordinates-<version>.jar` from the
  [latest release](https://github.com/BobbyNooby/youonlylivetwice/releases/latest) and put it in the server's `mods`
  folder, next to Fabric API. The client doesn't need it.
- It only runs on a dedicated server. In a single-player world, or one opened to LAN, it does nothing: the log says so
  at each start, and so do `/yolt-coordinates` and `/yolt-coordinates real`. The file is still written there.
- Running You Only Live Twice? Then you have this already: YOLT's jar ships it, set from YOLT's own
  [`hide.coordinates`](../docs/config.md#hide). Beside a You Only Live Twice older than this mod, which has the offset
  built in, the server won't start.
- It can run beside [YOLT Hardcore](../hardcore/README.md), the hardcore layer of You Only Live Twice as a mod of its
  own.

## Config

The first server start writes `config/yolt-coordinates.json`:

```json
{
  "enabled": true
}
```

| Key | Default | What it does |
|---|---|---|
| `enabled` | `true` | Each player's X and Z are shifted. `false` turns the offset off. |

- A change applies from each player's next join, respawn or dimension change. Until then everyone keeps the numbers
  they have, so their game and the server never disagree.
- A value that isn't `true` or `false` keeps the value in use: the default at a server start, the value in use at a
  reload. The server log names it at a start, and `/yolt-coordinates reload` names it at a reload.
- A missing `enabled` is added to the file. A file with a refused value is left as you typed it, so the mistake stays
  where you made it, until you fix it.
- Adding it writes the whole file again with the mod's own key only. A misspelt key and any comment in the file are
  gone after that. The server log at a start, or the reload's reply, says which key wasn't a setting.
- A file the mod can't read as JSON at all is never written over. The default is used at a start, and a reload
  changes nothing.

## Commands

Ops only.

| Command | What it does |
|---|---|
| `/yolt-coordinates` | Shows whether the offset is on, or that it does nothing in a single-player world. |
| `/yolt-coordinates reload` | Reads `config/yolt-coordinates.json` again, no restart needed. |
| `/yolt-coordinates real` | Turns real coordinates on or off for you, from your next join, respawn or dimension change. Run plainly from the console it does nothing: the console always uses real coordinates. Through `/execute as <player>`, from the console or by an op, it does this for that player, who must be an op: anyone else is refused. In a single-player world it says the mod does nothing there. |

Inside You Only Live Twice there is no `/yolt-coordinates`: the setting is YOLT's `hide.coordinates`, changed with
`/yolt config` or `/yolt reload`, and `/yolt adminview` gives an op real coordinates.
