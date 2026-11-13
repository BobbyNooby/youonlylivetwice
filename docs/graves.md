# Graves

When you die, a player head with your skin appears where you died, holding your items. It shows up even if your
inventory was empty, because the head is also the revive token.

- Right-click it to open it. Anyone can loot it. You can only take things out, not put things in.
- Graves can't be broken (not by hand, explosions, pistons or fluids) and never expire. If a head goes missing
  anyway, for example from an op's `/setblock`, the mod puts it back, both when the chunk loads and every
  `graves.recheckSeconds` (default 5) while it is loaded.
- When the last item is taken the head disappears.
- If a looter logs out holding a grave item on the cursor, it goes back into the grave.
- Your ender chest: `graves.enderChestOnDeath` can be `keep` (vanilla, the default), `grave` (the contents also go
  in the grave) or `wipe` (deleted). More than 54 stacks make a second grave next to the first. It only applies while
  `graves.enabled` is on.

Where the grave goes:

- On the death spot, or the nearest spot near it where items are safe. Never in lava or fire.
- If nothing close is safe, for example in the middle of a lava lake, it looks up to `graves.searchRadius` blocks out
  (default 16). A lava lake wider than that gets the grave over the lava, with a warning in the log. The items are
  safe in the grave, and players can bridge to it.
- If you die in mid-air, the grave lands where your items would have fallen: on the first block below, or on top of
  water, never under it.
- If you fall into the void, the grave goes to the last ground you stood on in that dimension. If there is none, it
  looks for land up to `graves.voidSearchRadius` blocks out (default 16), and then falls back to a fixed spot such as
  the world spawn.
- In the End, graves keep out of the spots the game rebuilds by itself: the exit portal, the arrival platform, the
  gateways and the obsidian spikes. A grave that ends up in one is moved out.
- Graves always go inside the world border.
- If a grave can't be made at all, your items and your grave head drop where it would have gone, and never despawn.
  With `keepInventory` you keep your items, and only the head drops.

`graves.tellLocation` says whether you are told where your grave is: `never`, `whenMoved` (the default: only when it
is more than `graves.tellLocationDistance` blocks, default 16, from where you died, or in another dimension) or
`always`. You are told in the lockout kick and the login screen, or in chat after you respawn if the death kicked
nobody. Only you are told.

With `graves.enabled` off, deaths drop items the vanilla way. A death while you're offline (your body's, or `/yolt
kill`) drops them where a grave's items would be safe instead, and they never despawn, since you aren't there to pick
them up. Graves already in the world stay protected and openable, and missing heads are still put back. The switch
only stops new graves.

## Grave compass

With `graves.compass` on (it is off by default), you get a compass the next time you are in the game and alive after
a death. With the lockout on, that is once it is over or you are revived.

- It is named "<your name>'s grave" and points at the grave of your latest death. It shows no coordinates.
- If the grave is moved (a missing head put back somewhere else), the compass follows it.
- Once the grave is gone it spins for good. It keeps its name, and never points north or at the spawn the way a plain
  compass does.
- In another dimension it spins, as a lodestone compass does in vanilla.
- If you use it on a lodestone, it becomes an ordinary lodestone compass. Your choice wins.
- A full inventory drops it at your feet, and that drop never despawns.
- A death while the switch was on still gets its compass after the switch is turned off.
- A compass in a chest doesn't update until someone carries it.

Settings: [`graves`](config.md#graves).

[All pages](README.md)
