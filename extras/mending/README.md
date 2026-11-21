# YOLT Mending

YOLT Mending is a server-side Fabric mod for Minecraft 26.3 that makes Mending repair less per XP point. It is the
Mending part of [You Only Live Twice](../../README.md), as a mod of its own for servers that want only this. Players
join with a plain vanilla client.

## What it does

Mending repairs 0.4 durability per XP point (`durabilityPerXp`) instead of vanilla's 2.

- XP you pick up still goes to damaged Mending gear first, but repairs a fifth as much.
- No XP is lost. A part of a point too small to repair anything yet is kept for your next pickup (until you log out),
  and XP the gear doesn't need goes to your XP bar.
- 0 turns Mending off: all XP goes to the bar.
- The anvil becomes the main way to keep gear going.
- Players in creative or spectator keep vanilla's Mending. Adventure counts as survival.

## Install

- You need Minecraft 26.3, Fabric Loader 0.20.1 or newer, [Fabric API](https://modrinth.com/mod/fabric-api)
  0.165.2+26.3 or newer, and Java 25.
- Get `yolt-mending-<version>.jar` from the
  [latest release](https://github.com/BobbyNooby/youonlylivetwice/releases/latest) and put it in the server's `mods`
  folder, next to Fabric API. The client doesn't need it.
- Running You Only Live Twice? Then you have this already: YOLT's jar ships it, set from YOLT's own config. Installing
  both does no harm: Fabric loads one copy, this jar when it has YOLT's version and the one inside YOLT's jar when it
  doesn't. Beside a You Only Live Twice from before it shipped this mod, the server won't start, and the log says why.

## Config

The first server start writes `config/yolt-mending.json`:

```json
{
  "enabled": true,
  "durabilityPerXp": 0.4
}
```

| Key | Default | What it does |
|---|---|---|
| `enabled` | `true` | Mending repairs less per XP point. `false` gives vanilla's Mending back. |
| `durabilityPerXp` | `0.4` | Durability one XP point repairs, 0 or more. Vanilla's is 2. 0 sends all XP to the bar. |

- Each key is read on its own, so one bad value only keeps that value: the default at a server start, the value in use
  at a reload. The server log names it at a start, and `/yolt-mending reload` names it at a reload.
- A key missing from the file is added to it. A file with a refused value is left as you typed it, so the mistake stays
  where you made it, until you fix it.
- Adding a key writes the whole file again with the mod's own keys only. A misspelt key and any comment in the file are
  gone after that. The server log at a start, or the reload's reply, says which key wasn't a setting.
- A file that isn't valid JSON is never written over. The defaults are used at a start, and a reload changes nothing.

## Commands

Ops only.

| Command | What it does |
|---|---|
| `/yolt-mending` | Shows the rate in use. |
| `/yolt-mending reload` | Reads `config/yolt-mending.json` again. It applies at the next XP pickup, no restart needed. |

Inside You Only Live Twice there is no `/yolt-mending`: the settings are YOLT's
[`hardcore.mending`](../../docs/config.md#hardcoremending), changed with `/yolt config` or `/yolt reload`.

## For mod authors

Another mod can ship YOLT Mending inside its own jar and set it from its own config, as YOLT does. Call
`YoltMending.hostedBy(name, settings, exempt)` from your `onInitialize`:

- `settings` is read at every XP pickup, so it can hand out your live config as a `MendingSettings`.
- `exempt` names the players who keep vanilla's Mending.

From then on the file is never read or written and `/yolt-mending` isn't registered.
