# YOLT Elytra

YOLT Elytra is a server-side Fabric mod for Minecraft 26.3 that makes gliding slower and floatier, and cuts a rocket's
push short once you fly faster than 20 m/s. It is the elytra part of [You Only Live Twice](../../README.md), as a mod
of its own for servers that want only this. Players join with a plain vanilla client.

## What it does

It is on as soon as it is installed.

- While you glide, gravity is 0.6 times vanilla's (`glideGravityMultiplier`). Lighter gravity makes the glide slower,
  and you sink slower too, so you cover about the same ground for each block of height. Walking, jumping and falling
  stay vanilla.
- While a rocket boosts you and your horizontal speed passes 20 m/s (`rocketCutoffSpeed`), the rocket ends early,
  with no explosion and no damage. 0 turns the cut-off off.
- This makes a rocket's push shorter, but doesn't hold you at 20 m/s. Your own game moves you and does the boosting,
  and the server only learns how fast you went a moment later, so a rocket goes on pushing for a few ticks past the
  cut-off. Measured with one rocket fired from a slow glide: the push ended after about a fifth of a second, where
  without the cut-off it lasted about a second, and it still took the player to about 32 m/s. The cut-off never slows
  you itself: it only ends the rocket.
- A rocket fired while you are already past the cut-off does nothing and isn't used up.
- The client flies with the server's gravity itself, so the flight stays smooth.
- Players in creative or spectator keep vanilla's elytra. Adventure counts as survival.

## Install

- You need Minecraft 26.3, Fabric Loader 0.20.1 or newer, [Fabric API](https://modrinth.com/mod/fabric-api)
  0.165.2+26.3 or newer, and Java 25.
- Get `yolt-elytra-<version>.jar` from the
  [latest release](https://github.com/BobbyNooby/youonlylivetwice/releases/latest) and put it in the server's `mods`
  folder, next to Fabric API. The client doesn't need it.
- Running You Only Live Twice or [YOLT Hardcore](../../hardcore/README.md)? Then you have this already: YOLT Hardcore's
  jar ships it, and YOLT's jar ships YOLT Hardcore's. It is set from `hardcore.elytra`, in YOLT's config or in YOLT
  Hardcore's own, where it is off until you turn it on. Installing both does no harm: Fabric loads one copy, this jar
  when it has YOLT Hardcore's version and the one inside YOLT Hardcore's jar when it doesn't. Beside a You Only Live
  Twice from before it shipped this mod, the server won't start, and the log says why.
- Tried with a real client, on a server with only this mod and in a single-player world: the gravity the client flies
  with is 0.6 of vanilla's from just after a glide starts until it lands, and vanilla's again after. A rocket fired
  from a slow glide took the client past 20 m/s and was gone in fewer than 20 ticks, where a plain rocket lasts at
  least 20. Measured in those runs, from under 1 m/s: it pushed for 4 ticks and took the client to about 32 m/s, and
  the client then slowed by about 0.2 m/s a tick. In one run with the cut-off off, the same rocket pushed for 21
  ticks, to about 34 m/s. A rocket fired past 20 m/s wasn't used up and didn't speed the client up.

## Config

The first server start writes `config/yolt-elytra.json`:

```json
{
  "enabled": true,
  "glideGravityMultiplier": 0.6,
  "rocketCutoffSpeed": 20.0
}
```

| Key | Default | What it does |
|---|---|---|
| `enabled` | `true` | Gliding is slower, and a rocket's push is cut short past a speed. `false` gives vanilla's elytra back. |
| `glideGravityMultiplier` | `0.6` | Gravity while gliding, 0 or more. 1.0 is vanilla. |
| `rocketCutoffSpeed` | `20.0` | Horizontal speed in m/s past which a rocket's push ends, a few ticks later, 0 or more. 0 means never. One rocket can still take you well past it: see [What it does](#what-it-does). |

- Each key is read on its own, so one bad value only keeps that value: the default at a server start, the value in use
  at a reload. The server log names it at a start, and `/yolt-elytra reload` names it at a reload.
- A key missing from the file is added to it. A file with a refused value is left as you typed it, so the mistake stays
  where you made it, until you fix it.
- Adding a key writes the whole file again with the mod's own keys only. A misspelt key and any comment in the file are
  gone after that. The server log at a start, or the reload's reply, says which key wasn't a setting.
- A file that is a symbolic link is written where the link points, so the link stays.
- A file the mod can't read as JSON at all is never written over. The defaults are used at a start, and a reload
  changes nothing.

## Commands

Ops only.

| Command | What it does |
|---|---|
| `/yolt-elytra` | Shows the glide gravity and rocket cut-off in use. |
| `/yolt-elytra reload` | Reads `config/yolt-elytra.json` again. It applies from the next tick, no restart needed. |

Inside You Only Live Twice there is no `/yolt-elytra`: the settings are YOLT's
[`hardcore.elytra`](../../docs/config.md#hardcoreelytra), changed with `/yolt config` or `/yolt reload`. With YOLT
Hardcore on its own there is none either: they are `hardcore.elytra` in `config/yolt-hardcore.json`, read again with
`/yolt-hardcore reload`.

## For mod authors

Another mod can ship YOLT Elytra inside its own jar and set it from its own config, as YOLT Hardcore does. Call
`YoltElytra.hostedBy(name, settings, exempt)` from your `onInitialize`:

- `settings` is read every tick for each player and at every rocket, so it can hand out your live config as an
  `ElytraSettings`.
- `exempt` names the players who keep vanilla's elytra.

From then on the file is never read or written and `/yolt-elytra` isn't registered. The mod still ticks its players
itself, with your settings.
