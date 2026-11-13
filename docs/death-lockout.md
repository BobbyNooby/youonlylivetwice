# Death lockout

When you die you are kicked, and you can't rejoin until the cooldown is over. The default is 7 days
(`deathLockout.cooldownDays`). The kick and the login screen say how you died and how long is left:

```
You died: Steve was slain by Zombie
Try again in 1 week.
```

- Each death keeps the cooldown it was recorded with. Changing `cooldownDays` only affects deaths from then on. Use
  `/yolt unkill` to end a lockout early.
- With a cooldown of 0 there is no wait and no kick.
- With the `show_death_messages` game rule off, the text is only "You died.", so it never names a killer.
- If the body of a player who is offline dies, they are told "Killed while away", with how the body died. That can
  name a killer they never saw.
- The death message is written in English, whatever language the player's game uses.
- Ops are locked out like everyone else. An op who locks themselves out needs the console to run `/yolt unkill`.
- A locked-out player who tries to join a full server is told how long they have left, not "server is full". Bans and
  the whitelist still come first.
- Deaths in creative or spectator mode don't count (`deathLockout.countCreativeAndSpectatorDeaths`): no lockout, no
  grave, and the player keeps their items, XP and score. With the switch on they count. A spectator still keeps
  everything, as in vanilla, so their grave holds only their grave head.
- If the death database can't be opened or read, the lockout lets everyone in and logs an error, instead of locking
  everyone out. Don't let another program hold a write lock on the database while the server runs: a login check
  waits at most 250 ms for the lock, then lets the player in and logs an error.

Settings: [`deathLockout`](config.md#deathlockout).

[All pages](README.md)
