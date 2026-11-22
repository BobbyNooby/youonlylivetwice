# YOLT Hardcore

YOLT Hardcore is a server-side Fabric mod for Minecraft 26.3 that makes survival itself harder: what you carry slows
you down, falls break your legs, every return from death costs a max heart, food matters, mobs are tougher, some nights
are blood moons and the cold can freeze you. It is the hardcore layer of [You Only Live Twice](../README.md), as a mod
of its own for servers that want only this. Players join with a plain vanilla client.

## What it does

Each feature has its own switch. Three ship on: the Curse of the Undead, the flat anvil price and the Mending nerf.
Everything else ships off.

| Feature | What it does | Switch | Default |
|---|---|---|---|
| Weight | Everything you carry weighs something. Over the free load you walk slower, and each jump costs extra hunger. Your ender chest doesn't count. | `hardcore.weight.enabled` | off |
| Broken legs | A long fall breaks your legs. They slow you until they heal, which takes food the way health regen does, once your health is full. | `hardcore.legs.enabled` | off |
| Limp | Below 2 hearts you are slowed until you heal back to 3. | `hardcore.limp.enabled` | off |
| Bed rest | Lying in a bed heals you slowly and heals broken legs twice as fast. You can nap in the day too, where beds don't blow up, and a nap never skips the night. | `hardcore.bedRest.enabled` | off |
| Curse of the Undead | Every return from death, by dying or by a totem, takes a max heart, up to 4. They come back one at a time, one for every 3 Minecraft days (an hour) of your own time online. | `hardcore.curse.enabled` | on |
| Nutrients | Foods carry hidden protein, vitamins and sugar, and using food drains them. Low protein slows healing, low sugar slows sprinting and low vitamins slow mining. | `hardcore.nutrients.enabled` | off |
| Hunger tax | Multiplies the hunger actions cost, such as sprinting, jumping, mining and taking damage. Healing isn't taxed. | `hardcore.hungerTax.multiplier` | `1.0`, vanilla |
| Mending nerf | Mending repairs 0.4 durability per XP point instead of vanilla's 2. This is YOLT Mending. | `hardcore.mending.enabled` | on |
| Flat anvil prices | Each anvil use costs one flat price, 1 level to rename, 5 to repair or 6 to enchant, which never grows with use. This is YOLT Anvils. | `hardcore.anvil.enabled` | on |
| Elytra nerf | A slower, floatier glide, and rockets stop pushing past 20 m/s. This is YOLT Elytra. | `hardcore.elytra.enabled` | off |
| Tougher mobs | Most hostile mobs have more health and speed, and hit players harder. | `hardcore.tougherMobs.enabled` | off |
| Relentless mobs | Most hostile mobs track you from further away, and some zombies can break wooden doors on any difficulty. | `hardcore.relentlessMobs.enabled` | off |
| Blood moon | Some nights are blood moons: more mobs spawn, tougher and better geared, and the night can't be slept through. | `hardcore.bloodMoon.enabled` | off |
| Cold | Out in the open where it is cold enough to snow, a chill builds. Heat, shelter and leather bring it down, and a full chill freezes you. | `hardcore.cold.enabled` | off |

A switch such as `hardcore.weight.enabled` is `enabled` in the `weight` part of the file's `hardcore` block.

[docs/hardcore.md](../docs/hardcore.md) describes every feature and [docs/config.md](../docs/config.md#hardcore) every
setting. Both are written for YOLT, but this mod's `hardcore` block is the same block, key for key, so a block copied
from one file to the other works. Where they name `/yolt config` or `/yolt reload`, here you change the file and run
`/yolt-hardcore reload`.

Players in survival and adventure play by these rules. Creative and spectator players are left out: switching to either
takes every slowdown and lost heart off within a tick, and a switch back puts them on again. A death in creative or
spectator takes no heart.

## Install

- You need Minecraft 26.3, Fabric Loader 0.20.1 or newer, [Fabric API](https://modrinth.com/mod/fabric-api)
  0.165.2+26.3 or newer, and Java 25.
- Get `yolt-hardcore-<version>.jar` from the
  [latest release](https://github.com/BobbyNooby/youonlylivetwice/releases/latest) and put it in the server's `mods`
  folder, next to Fabric API. The client doesn't need it. `./gradlew :hardcore:build` builds it into
  `hardcore/build/libs/`.
- The jar already holds YOLT Anvils, YOLT Mending and YOLT Elytra, and sets them from its own file, so you don't need
  their jars. Inside it they read and write no file of their own and have no command: their settings are
  `hardcore.anvil`, `hardcore.mending` and `hardcore.elytra` here.
- It hasn't been tried in a single-player world.

## Config

The first server start writes `config/yolt-hardcore.json`. It has three parts:

- `configVersion`: the layout of the file, `1`. Leave it as it is.
- `hardcore`: every feature's settings. [docs/config.md](../docs/config.md#hardcore) lists every key.
- `messages`: the six blood moon texts, under the names they have in YOLT's `messages`. Each is at most 256 characters.

| Key | When it shows | Default |
|---|---|---|
| `bloodMoonWarningTitle` | The title before dark on a night that will be a blood moon | `The moon will rise red tonight…` |
| `bloodMoonWarningLine` | The chat line with that warning | `A blood moon rises tonight. Find somewhere safe before dark.` |
| `bloodMoonRisenLine` | As it rises | `The blood moon has risen. Mobs are stronger and more numerous until dawn.` |
| `bloodMoonUpLine` | To a player who arrives while one is up, and to everyone after a restart during one | `A blood moon is up. Mobs are stronger and more numerous until dawn.` |
| `bloodMoonSetLine` | As it sets | `The blood moon has set.` |
| `bloodMoonBarTitle` | The boss bar's title | `Blood Moon` |

- The file is read at each server start and on `/yolt-hardcore reload`. No change needs a restart:
  [docs/config.md](../docs/config.md#hardcore) says when each shows, which for most is at once and for some only for
  mobs as they load, for new deaths or at the next blood moon.
- Each key is read on its own, so one bad value only loses that value: a wrong type, a value a rule refuses and an
  unknown key are each named, with YOLT's rules and words, in the server log at a start and in the reply to a reload.
  A bad value falls back to the default at a start and to the value in use at a reload, and an unknown key is ignored.
- At a start the file is written again with every key, with the default in place of each bad value. When that replaces
  a bad value or drops an unknown key, the old file is kept first as `yolt-hardcore.json.bak`, or beside it with the
  time added when a different `.bak` is there already.
- A file that isn't valid JSON is kept as `yolt-hardcore.json.bak` at a start, and the defaults are written in its
  place.
- A reload never writes the file. A reload of a file that is missing or isn't valid JSON changes nothing.
- A file from a newer version (`configVersion` above 1) is read, but never written over.

## Commands

| Command | Who | What it does |
|---|---|---|
| `/status` | Everyone | A report on your own body: a line from each feature that is on and has something to say. |
| `/yolt-hardcore` | Ops | Says which features are on. |
| `/yolt-hardcore reload` | Ops | Reads `config/yolt-hardcore.json` again. |
| `/yolt-hardcore status <player>` | Ops | Shows an online player's `/status` report. |
| `/yolt-hardcore bloodmoon start` | Ops | Starts a blood moon now, for a night's length, whatever tonight's roll says. |
| `/yolt-hardcore bloodmoon stop` | Ops | Ends the blood moon that is up, as dawn would. |
| `/yolt-hardcore bloodmoon status` | Ops | Says whether a blood moon is up and how much of it is left, or whether one rises tonight. |
| `/yolt-hardcore curse <player> set <hearts>` | Ops | Sets how many max hearts the Curse of the Undead has taken from an online player, up to `hardcore.curse.maxLostHearts`. |
| `/yolt-hardcore curse <player> clear` | Ops | Clears an online player's curse. |

The console can run the `/yolt-hardcore` commands. The `bloodmoon` ones are refused while the blood moon is off, and the
`curse` ones while the curse is off.

## Without You Only Live Twice

[docs/hardcore.md](../docs/hardcore.md) is written for a server that runs YOLT. On its own the mod differs in these
ways:

- There are no logout bodies, so the rules for bodies do nothing, `hardcore.legs.bodiesCanBreakLegs` and
  `hardcore.bloodMoon.capBoostNearPlayerBodies` among them.
- There is no death lockout. A death takes a max heart and the player just respawns with it gone, where under YOLT they
  are locked out first.
- There are no revive papers and no `/yolt unkill`, so `hardcore.curse.reviveRefundHearts` and
  `hardcore.curse.unkillRefunds` do nothing.
- `/yolt-hardcore curse` and `/yolt-hardcore status` take an online player only.

## Where it is stored

Every player's hardcore state (broken legs, the curse, nutrient levels and chill) and the days of the last blood moon
are kept in `<world>/database/you-only-live-twice-hardcore.db`. It is made only when something is first stored. An
online player's state is saved when they leave, every 5 minutes, and at once after a death the curse counts. YOLT keeps
the state in the same file, so a world keeps it when a server moves between YOLT and this mod.

Its ids keep YOLT's namespace, `you-only-live-twice`: the modifiers it saves on mobs (`you-only-live-twice:tougher`,
`you-only-live-twice:blood_moon` and `you-only-live-twice:relentless`) and the two tags a data pack can override, the
block tag `you-only-live-twice:heat_sources` (what warms you nearby) and the item tag `you-only-live-twice:held_heat`
(what warms you held in a hand).

## Inside You Only Live Twice

You Only Live Twice ships this mod inside its own jar and sets it from YOLT's config: its `hardcore` block and the six
blood moon texts in its `messages`. There the mod has no file and no `/yolt-hardcore` of its own, and the commands are
YOLT's. Beside a You Only Live Twice older than this mod, which has the hardcore layer built in, the server won't
start, and the log says why.
