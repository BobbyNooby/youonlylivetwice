# Skins

On an offline-mode server nobody has a skin. With `skins.mode: auto` (the default), a player who joins without one
gets the skin of the Mojang account with their name: on themselves, their body, and their grave head. On
an online-mode server players already have their skin, and YOLT only changes it for players who pick one.

- **Picking a skin.** `/yolt skin set <mojangName>` wears that real Mojang account's skin from your next join on, over
  every other skin. `/yolt skin clear` goes back to the default. Non-ops get the command while
  `skins.allowPlayerChoice` is on, and wait 5 seconds between picks. Ops and the console can set and clear anyone's.
- **Other players' skins.** With `skins.refuseKnownPlayers` on (the default), a non-op can't pick the skin of a player
  who has joined this server, or one another player picked. Your own name is always allowed. If you picked someone's
  skin before they first joined, the pick goes at your next login and you are told why. A pick an op made stays.
- **Picking once.** With `skins.lockAfterFirstChoice` on (the default), a non-op picks once, and after that only an op
  can change it.
- **Slow Mojang.** A join waits up to `skins.lookupTimeoutSeconds` (default 5, at most 10) for Mojang. Then the player
  joins with the skin saved before, or none. A join is never refused because of skins.
- **Caching.** Skins are kept for `skins.refreshHours` (default 24; 0 asks Mojang on every join).
- **Other skin mods.** YOLT leaves skins alone when SkinsRestorer or SkinRestorer is installed. Use one of those for
  custom or URL skins.
- **Older bodies and heads** keep the skin they were made with.

**Privacy.** In `auto` mode YOLT sends names to Mojang's servers, from the server's IP: the name of each player who
joins without a skin, and every name picked with `/yolt skin set`. With `skins.mode: off`, nothing is ever sent to
Mojang.

## Known limits

- A name shows the skin of whichever Mojang account owns that name now. That may be a stranger, and it changes if
  the account is renamed. A name with no account keeps the default skin.
- A pick is stored as a name, so after a rename it follows the name, not the account.
- Refusing a non-op's pick tells them the name is in use on this server. No wording can hide that. On an
  offline-mode server this holds for any name a player has joined with, a Mojang account or not. A non-op who
  keeps picking a player's name sees the refusal start the moment that player first joins.
- Two players who already shared a pick from before this build, or from while `refuseKnownPlayers` was off, both
  keep it.
- A stale name-cache entry after a rename can refuse a skin that is actually free. It only ever refuses too much.
- A skin an op set for themselves counts as op-made, so it stays when its owner joins, even after the op is
  de-opped.

Settings: [`skins`](config.md#skins).

[All pages](README.md)
