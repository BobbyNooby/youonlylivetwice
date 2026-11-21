# YOLT Elytra

YOLT Elytra is a server-side Fabric mod for Minecraft 26.3 that makes gliding slower and floatier, and stops rockets
pushing you past 20 m/s. It is the elytra part of [You Only Live Twice](../../README.md), as a mod of its own for
servers that want only this. Players join with a plain vanilla client.

## What it does

It is on as soon as it is installed.

- While you glide, gravity is 0.6 times vanilla's (`glideGravityMultiplier`). Lighter gravity makes the glide slower,
  and you sink slower too, so you cover about the same ground for each block of height. Walking, jumping and falling
  stay vanilla.
- While a rocket boosts you and your horizontal speed passes 20 m/s (`rocketCutoffSpeed`), the rocket ends early,
  with no explosion and no damage. 0 turns the cut-off off.
- A rocket fired while you are already past the cut-off does nothing and isn't used up.
- The client flies with the server's gravity itself, so the flight stays smooth.
- Players in creative or spectator keep vanilla's elytra. Adventure counts as survival.

## Install

- You need Minecraft 26.3, Fabric Loader 0.20.1 or newer, [Fabric API](https://modrinth.com/mod/fabric-api)
  0.165.2+26.3 or newer, and Java 25.
- Get `yolt-elytra-<version>.jar` from the
  [latest release](https://github.com/BobbyNooby/youonlylivetwice/releases/latest) and put it in the server's `mods`
  folder, next to Fabric API. The client doesn't need it.
- Running You Only Live Twice? Then you have this already: YOLT's jar ships it, set from YOLT's own config, where it
  is off until you turn it on. Installing both does no harm: Fabric loads one copy, this jar when it has YOLT's version
  and the one inside YOLT's jar when it doesn't. Beside a You Only Live Twice from before it shipped this mod, the
  server won't start, and the log says why.

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
| `enabled` | `true` | Gliding is slower, and rockets stop pushing past a speed. `false` gives vanilla's elytra back. |
| `glideGravityMultiplier` | `0.6` | Gravity while gliding, 0 or more. 1.0 is vanilla. |
| `rocketCutoffSpeed` | `20.0` | Horizontal speed in m/s past which a rocket stops boosting, 0 or more. 0 means never. |

- Each key is read on its own, so one bad value only keeps that value: the default at a server start, the value in use
  at a reload. The server log names it at a start, and `/yolt-elytra reload` names it at a reload.
- A key missing from the file is added to it. A file with a refused value is left as you typed it, so the mistake stays
  where you made it, until you fix it.
- Adding a key writes the whole file again with the mod's own keys only. A misspelt key and any comment in the file are
  gone after that. The server log at a start, or the reload's reply, says which key wasn't a setting.
- A file that isn't valid JSON is never written over. The defaults are used at a start, and a reload changes nothing.

## Commands

Ops only.

| Command | What it does |
|---|---|
| `/yolt-elytra` | Shows the glide gravity and rocket cut-off in use. |
| `/yolt-elytra reload` | Reads `config/yolt-elytra.json` again. It applies from the next tick, no restart needed. |

Inside You Only Live Twice there is no `/yolt-elytra`: the settings are YOLT's
[`hardcore.elytra`](../../docs/config.md#hardcoreelytra), changed with `/yolt config` or `/yolt reload`.

## For mod authors

Another mod can ship YOLT Elytra inside its own jar and set it from its own config, as YOLT does. Call
`YoltElytra.hostedBy(name, settings, exempt)` from your `onInitialize`:

- `settings` is read every tick for each player and at every rocket, so it can hand out your live config as an
  `ElytraSettings`.
- `exempt` names the players who keep vanilla's elytra.

From then on the file is never read or written and `/yolt-elytra` isn't registered. The mod still ticks its players
itself, with your settings.
