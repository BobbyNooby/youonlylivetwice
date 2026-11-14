# Commands

All `/yolt` commands need op (permission level 2) or the server console, except `/yolt skin` for your own skin. The
console has no player, so `/yolt adminview`, `/status` and the own-skin forms of `/yolt skin` don't work from there.

| Command | Who | What it does |
|---|---|---|
| `/yolt border <diameter> [confirm]` | Ops, console | Sets the Overworld and Nether border width and saves it. With the border feature off it only saves the value. |
| `/yolt center <x> <z> [confirm]` | Ops, console | Sets the border centre (End stays at 0, 0). Vanilla saves the centre with the world, not in the config. Refused while `worldBorder.enabled` is off. |
| `/yolt endscaling <scaling> [confirm]` | Ops, console | Sets `worldBorder.endScale`. Must be above 0. |
| `/yolt respawncooldown <days>` | Ops, console | Sets `deathLockout.cooldownDays`, for deaths from now on. |
| `/yolt kill <player>` | Ops, console | Kills a player, online or offline. An offline kill makes a grave and starts the lockout. |
| `/yolt unkill <player>` | Ops, console | Ends a player's lockout. With `hardcore.curse.enabled` and `hardcore.curse.unkillRefunds` on, it also gives back the max hearts that death took. |
| `/yolt grave list` | Ops, console | Lists every grave: owner, dimension, position. The positions are real, so with `hide.coordinates` on, remove a listed grave with admin view on or from the console. |
| `/yolt grave remove <x> <y> <z>` | Ops, console | Drops a grave's items on the ground and deletes the grave. The drops never despawn. If there is nowhere safe nearby to drop them, it refuses and changes nothing. It uses the command's dimension, so from the console run `execute in <dimension> run yolt grave remove ...`. The grave's chunk must be loaded, so stand near it or `/forceload` it first. |
| `/yolt adminview` | Ops (a player) | Turns admin view on or off for you. |
| `/yolt config <key> [value]` | Ops, console | Shows a config key, or sets it in the file and the running server at once. A border key's value can end in `confirm`. Tab-complete lists the keys. |
| `/yolt reload` | Ops, console | Re-reads the config file and re-applies it. |
| `/yolt status <player>` | Ops, console | Shows an online player's `/status` report. |
| `/yolt curse <player> set <hearts>` | Ops, console | Sets how many max hearts the Curse of the Undead has taken, online or offline, up to `hardcore.curse.maxLostHearts`. Refused while the curse is off. |
| `/yolt curse <player> clear` | Ops, console | Clears a player's curse. Refused while the curse is off. |
| `/yolt bloodmoon start` | Ops, console | Starts a blood moon now, for a night's length, whatever tonight's roll says. It counts as the last blood moon for `hardcore.bloodMoon.minNightsBetween`. Refused while the blood moon is off. |
| `/yolt bloodmoon stop` | Ops, console | Ends the blood moon that is up, as dawn would. The roll won't start another that day. |
| `/yolt bloodmoon status` | Ops, console | Says whether a blood moon is up and how much of it is left, or whether one rises tonight. |
| `/yolt skin set <mojangName>` | Everyone, when `skins.allowPlayerChoice` is on | Wears that Mojang account's skin from your next join. |
| `/yolt skin clear` | Everyone, when `skins.allowPlayerChoice` is on | Goes back to the default skin. |
| `/yolt skin set <player> <mojangName>` | Ops, console | Sets another player's skin. |
| `/yolt skin clear <player>` | Ops, console | Clears another player's pick. |
| `/w <player> <message>` | Everyone when `chat.allowWhispers` is on (the default); ops always | Whispers to a player within `chat.whisperRadius`, for ops too. Doesn't tab-complete names. `/msg` and `/tell` are the same command. |
| `/status` | Everyone while a hardcore feature is on, as the anvil and Mending rules are by default; ops always | Shows a report on your own body. See [`/status`](hardcore.md#status). |
| `/help` | Everyone | Answers with a spooky message. |

A command that names a player finds them only where this server knows them: online players, the server's name cache,
the death log and YOLT's record of joins. It never asks Mojang. Anyone else gets "No player named X has joined this
server.", so an op can't kill, unkill or pick a skin for a player who has never joined.

`/status` takes no target, so a player only ever sees their own body. While `restrictCommands` is on, a non-op is
only given it while at least one hardcore feature is on (a switch, or a `hardcore.hungerTax.multiplier` other than
1.0). The anvil and Mending rules ship on, so a default server gives it to everyone. With `restrictCommands` off, everyone can run it at any time. With nothing to report it says "Nothing to
report".

Ops lose `/w @a`-style selector targets, and `/msg` and `/tell` with them, because all three are replaced by the
whisper. The console and command blocks can't whisper, since a whisper needs a player to send it. Use `/tellraw`
there instead.

## Admin view

Ops can run `/yolt adminview` to see what everyone else can't: the full tab list, nametags, every chat message, every
death, join and advancement message, every map marker, the full F3 screen and the real world spawn on a compass. It
is off by default and remembered per admin. It stops working when the admin is de-opped, and comes back if they are
opped again. In admin view you don't get other players' whispers, since those are private.

With `hide.coordinates` on, admin view also gives you real coordinates, from your next join, respawn or dimension
change.

[All pages](README.md)
