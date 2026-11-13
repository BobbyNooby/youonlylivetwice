# World border and Nether

The Overworld and Nether share one border (default 10,000 blocks across). The End border is the same size times
`worldBorder.endScale` (default 2), up to vanilla's largest border. The Nether runs 1:1 with the Overworld, so there
is no Nether fast travel.

- `/yolt border`, `center`, `endscaling` and `/yolt config worldBorder.*` refuse a change that would leave online
  players, bodies, graves, the End's arrival platform, or the world spawn (when new players start right on it) outside
  the border. Add `confirm` to the command to do it anyway.
- `/yolt reload` can't be confirmed, so a reloaded border that would leave someone outside isn't applied. The live
  border stays, and the reply says how to apply it.
- A timed vanilla `/worldborder` change is left to finish at startup and on a reload. `/yolt border`, `endscaling`
  and `/yolt config worldBorder.*` stop it and set the size straight away, while the border feature is on.
- If the config's border values can't be read at startup, YOLT leaves the world's own border as it is until you fix
  the config and run `/yolt reload`.
- The same happens at startup when the config's border would leave the world spawn, the End's arrival platform, a body
  or a grave outside, for example after you edit `worldBorder.diameter` while the server is down. The log says what
  it would leave out. Change the config and run `/yolt reload`, or use `/yolt border` or `endscaling` with `confirm`.
- Offline players are only checked through their bodies. With `bodies.enabled` off or a timed `bodies.duration`, an
  offline player without a body can end up outside, and the border commands' reply says they weren't checked.

## Random spawn

New players spawn at a random spot inside the border, on ground where vanilla would spawn a player.

Settings: [`worldBorder`](config.md#worldborder), and `netherScaleOneToOne` and `randomSpawn` in the
[top level](config.md#top-level).

[All pages](README.md)
