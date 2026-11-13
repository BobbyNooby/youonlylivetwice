# Logout bodies

When you log out, a body appears where you stood. It wears your skin, armour and held items and has no nametag.
While you're offline the body is you:

- **What carries over:** health, armour and items in hand, and fall, fire and air state. With `bodies.copyEffects`
  on, also your potion effects, max health and absorption hearts. Hunger and XP stay as they were. Nobody can take
  items off a body.
- **Killing a body kills you.** A grave appears at the body with your saved inventory, your save is cleared, and
  your lockout starts. `/yolt kill` on an offline player does the same.
- **Vanilla `/kill` skips bodies** (`bodies.ignoreVanillaKill`). A lag clear like `/kill @e[type=!player]` leaves
  them standing and says how many it skipped. `/yolt kill <player>` is the way to kill one on purpose. Damage and the
  void still kill a body, and so does a mod that kills entities in its own code. A mod that removes entities without
  killing them is safe: the owner takes over the body's last saved state at their next login.
- **Logging back in:** you take over the body as it is now. You appear where it is, with its health, effects and
  gear (a popped totem stays used, worn armour stays worn). Then the body is removed.
- **How long it stays:** `bodies.duration` is `offline` (the default, the body stays the whole time you're away) or
  a number of minutes. The number counts real time, so server downtime counts too. Every player online when the
  server stops gets a body.
- **Poses:** `bodies.pose` is `prone` (lying face-down, the default), `sleeping` or `standing`. `standing` can
  suffocate a body that was crawling through a 1-block gap.
- **Mobs hunt bodies** (`bodies.mobsAttack`). Mobs that hunt players on sight hunt bodies the same way. Mobs that
  are calm until provoked stay calm: endermen, zombified piglins, and spiders in daylight. Piglins, hoglins, wardens,
  breezes and creakings ignore mannequin bodies (a player body is another matter, see below). Slimes and magma cubes
  follow a body but can't hurt it by touch.
- **Difficulty counts.** A mannequin body takes the damage you would take on the server's difficulty, from mobs,
  explosions and everything else the difficulty scales.
- **Totems, armour wear and potion effects** work on the body like they would on you (each has a switch).
- **Bodies move like players:** gravity, knockback, water and fall damage all apply, and you log back in wherever the
  body ended up. The one exception is a body held after a logout on a vehicle (see Riding). They **can't use portals
  yet**; portal travel for bodies is on the [still to come](README.md#still-to-come) list.
- **Riding:** a vehicle you ride alone leaves with you, and you come back on it, where the vehicle is. While you're
  away your body lies on top of where the vehicle was, or lower if there is no room. Over water, lava or in the air
  it is held in place, so it can't sink, burn or fall. A minecart on rails counts as off the ground, so a cart
  rider's body is held too. The same goes when you log out of a boat or mount that someone else stays on: the
  vehicle stays, and you come back where your body is. On dry ground the body drops and moves like any other. Your
  health, effects and gear still come from the body.
- **Race at login:** if a body dies just as its owner is logging in, the owner is kicked with the lockout message
  and their lockout stands. If it expires just then, they arrive at their logout spot with the body's state. Nothing
  is lost or duplicated.
- **Leftover items** a body has to drop (an item a dispenser put on it, for example) never despawn. Under water they
  float and can drift. A body that dies drops them where a grave's items would be safe, never into the lava or the
  void that killed it.

## The two body types

`bodies.type` picks what a body is. Bodies already in the world keep the type they were made as until their owner
logs in. Only new logouts use the new setting.

- **`mannequin` (default):** a vanilla mannequin entity. It costs nothing while nobody is near it. It is frozen
  unless a player is around, so a body far from everyone is safe from mobs.
- **`player`:** opt-in. The body counts as a player for the world: its area keeps loading, mobs spawn around it and
  don't despawn near it. Whether the server keeps running with nobody online is vanilla's
  `pause-when-empty-seconds` in `server.properties` (0 means never pause). The trade-offs:
    - Selectors that look in the world (`@p`, `@a[distance=..]`, `@e[type=player]`) can pick a player body. An item
      given that way (say `/give @p` next to one) is lost when the owner takes the body back.
    - Scoreboard values (health, food, air, armour, XP, level) keep updating under the owner's name, and a body death
      adds to the owner's death count.
    - World events treat it as a player. A Bad Omen effect copied onto it (`bodies.copyEffects`) can start a village
      raid, and trial spawners, patrols and wandering traders can pick it.
    - It never gets phantoms, doesn't pick up items, and slimes, magma cubes and pufferfish don't hurt it by touch.
    - XP orbs fly to it as they would to a player, and circle it until they despawn or a real player comes closer.
    - A player's punch hurts it but doesn't knock it back. Mob hits, arrows and explosions still do.
    - Piglins, hoglins, wardens, breezes and creakings see a real player in it and go after it. They ignore a
      mannequin.
    - With the `pvp` game rule off, or friendly fire off on a team, players can't hurt it (they can hurt a mannequin).
    - Players near it get a player entry for it with the owner's name, kept out of the tab list. A vanilla client
      reads every player entry for chat tab-complete and the Social Interactions screen, listed or not, so a nearby
      player can find the offline owner's name there, the same as if the owner stood there. The body is you.
    - With `hide.nametags` off, it shows its owner's nametag. A mannequin never shows one.
    - After a server restart, a player body comes back with the default main hand and the default max health (plus
      any health boost effect it has), and health above that is lost. When its owner logs in, they take over the
      body's health and gear as usual. Only their max health comes from their save.
    - It uses a Fabric internal class to run with no real connection (`UntrackedPacketListener`), which a Fabric
      update could move. If a Fabric update breaks it, that is the first place to look.

Settings: [`bodies`](config.md#bodies).

[All pages](README.md)
