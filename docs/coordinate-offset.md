# Coordinate offset

`hide.coordinates` (off by default) gives each player fake X and Z numbers. A minimap mod or a hacked client gets the
fake ones too, though a client built for it can still work some real numbers out (see [Known limits](#known-limits)
below).

- Every position the server sends a player is shifted by a private amount, and every position that player's game
  sends back is shifted back, so the server itself always works in real coordinates.
- Only X and Z move. Height stays real.
- The amount is new at each join, each death respawn and each dimension change, never on a timer. Each stretch starts
  you within 16 blocks of 0, 0.
- Every dimension is shifted, each stretch with its amount. A spot in another dimension than the one you are in, such
  as your last death or a lodestone compass's target, is shifted by a separate hidden amount for that dimension, which
  stays the same until you next join.
- F3 stays reduced (`hide.debugScreen`). A client that ignores that still only shows shifted numbers.
- Minimap waypoints and Nether portal maths don't carry across a reset, as the numbers change under them.
- Border walls only show within the server's view distance plus 128 blocks. A wall further off goes out at the edge
  of the world instead, so seeing one tells you that one real axis, nothing more.
- Compasses still point north (`hide.compassSpawn`). With that off, they point at the world spawn, shifted, which is a
  point every player shares.
- Maps are unchanged: a map you hold still tells you your real X and Z, modulo the map's size.
- The seed fingerprint the server sends is unchanged.
- Admin view gives real coordinates from your next join, respawn or dimension change, and the `/yolt adminview` reply
  says so.
- Ops without admin view type coordinates in their own shifted numbers, and `~` and `^` work as usual. A particle's
  spread is a distance, so it stays as typed. Only what a player types counts: the console, command blocks, functions
  (a `/function` an op types included), advancement rewards and scheduled functions use real coordinates. Command
  replies show real numbers.
- Only position arguments are read in the op's numbers. These are read as real even when an op types them: a
  selector's `x=`, `y=` and `z=`; positions inside typed SNBT or components, such as a lodestone target in `/give`,
  NBT in `/setblock`, `/summon` or `/data`, or a particle's `trail` target or `vibration` destination; and a function
  macro's arguments.
- A command in clickable chat text is sent by the clicker's game as if they typed it, so its absolute numbers are read
  as the clicker's shifted ones. Vanilla's own `/locate` reply is one: clicking it puts `/tp @s` and the real numbers
  in the chat box, and sent as it is, it takes the op about one offset away.
- The grave notice: the kick and the login refusal name only the dimension. The chat line after a respawn shows the
  grave in your own numbers, or only the dimension when the grave is in another one.
- Text in chat, signs and books is never changed, so numbers someone writes stay as written. Only an item a text
  shows on hover, such as a compass in a death message, has its target shifted.
- Not handled: other mods that send positions in their own packets (voice chat, Distant Horizons) and packet-level
  anticheats. Don't run them with this on.

## Known limits

- A wall of a border whose size and centre are known gives that real axis away once you see it.
- A map you hold gives your real X and Z modulo its size, and a known seed gives everything away through the
  terrain.
- Some terrain is the same in every world, whatever the seed: where snow starts on a mountain, where frozen-ocean
  ice ends, and which flowers grow where in a flower field. A client built for it can match what it sees against
  that and find its real X and Z. The seed fingerprint the server sends also lets it check where snowy biomes end.
- In the End, the main island at real 0, 0 gives the End's amount away. It is new at every trip there, and spots
  in other dimensions are never shown in it.
- Each stretch puts you in chunk 0, 0 at its start, so a stretch that starts in the real chunk at 0, 0 shows real
  numbers, for spots in other dimensions too. With `randomSpawn` off, a player with no bed respawns at the world spawn
  each time, so those stretches all get the same amount, and anyone who knows where the spawn is knows it.
- With `hide.compassSpawn` off, a player who knows where the world spawn really is (often near 0, 0) can work out
  their amount from where the compass points.
- A modified client can carry its own numbers across a death or a relog: your last death spot and the grave
  compass's target come again in the new numbers, so it can work out how far they moved. It still learns no real
  coordinate.
- Amounts stop at 999,984 blocks. A player about 1,000,000 or more blocks out on an axis (a border has to reach
  that far) starts a stretch far from 0, 0, at their real numbers moved 999,984 blocks towards 0.
- With `max-world-size` set in `server.properties`, the edge of the world counts as a border wall: only its near
  part shows.
- When that edge cuts a border into a rectangle and a player is near two opposite walls, their client is sent a
  square as wide as the rectangle, so its north and south walls show in the wrong place. They give nothing away.
- These go out as an op or a data pack wrote them, with any position in them real:
    - a position under an NBT key YOLT doesn't know. It shifts vanilla's own: keys ending in `pos`,
      `exit_portal`, `leash`, `patrol_target`, `wander_target`, `beam_target`, a breeze's jump target and map
      markers;
    - the `minecraft:lock`, `minecraft:can_place_on` and `minecraft:can_break` predicates on an item, and a
      trade's costs;
    - the items shown in recipes, advancements and dialogs;
    - a command block minecart's command and last output, which every client near it is sent;
    - an item shown on hover in text held in a registry entry (an instrument, a jukebox song, a trim, a painting),
      in an item's attribute display, in the separator of a selector or NBT text, in a dialog or custom click
      event, or in a chat type's own style.
- Not yet tried in a real client: walking through bamboo, a sculk sensor's particle in flight, dragging a compass
  around the inventory, a border wall coming into view as you walk up to it, and minimap mods.

Settings: `hide.coordinates` in [`hide`](config.md#hide).

[All pages](README.md)
