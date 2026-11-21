# YOLT Anvils

YOLT Anvils is a server-side Fabric mod for Minecraft 26.3 that gives anvils flat prices. It is the anvil part of
[You Only Live Twice](../../README.md), as a mod of its own for servers that want only this. Players join with a plain
vanilla client.

## What it does

Every anvil use costs one fixed price, set by the biggest kind of job in it, and nothing ever becomes "Too Expensive!".

| The use includes | Price |
|---|---|
| any enchanting | 6 levels (`enchantCost`) |
| repairing, but no enchanting | 5 levels (`repairCost`) |
| only a rename | 1 level (`renameCost`) |

- One use that enchants and renames costs 6, not 7. Combining two worn swords where the right one adds an enchantment
  costs 6, not 11.
- The price never grows with use. Vanilla's hidden "prior work" counter adds nothing to the price, and every item that
  comes out of an anvil has it at 0, even one that had a counter from before, on either side.
- A use that wouldn't change the item has no result, and nothing is charged or used up.
- What stays vanilla: enchanting a whole stack at once stays refused, which enchantments go together and their
  levels, the materials used up, and the name length limit.
- Anvils still wear down, with a 12% chance per use (`breakChance`).
- Turning it off later: every item that went through an anvil while it was on prices like a new one, and vanilla
  carries on from there.
- Players in creative or spectator keep vanilla's anvil. Adventure counts as survival.

## Install

- You need Minecraft 26.3, Fabric Loader 0.20.1 or newer, [Fabric API](https://modrinth.com/mod/fabric-api)
  0.165.2+26.3 or newer, and Java 25.
- Get `yolt-anvils-<version>.jar` from the
  [latest release](https://github.com/BobbyNooby/youonlylivetwice/releases/latest) and put it in the server's `mods`
  folder, next to Fabric API. The client doesn't need it.
- Running You Only Live Twice? Then you have this already: YOLT's jar ships it, set from YOLT's own config. Installing
  both does no harm; Fabric loads one copy, the newer. This jar refuses to start beside an older You Only Live Twice.

## Config

The first server start writes `config/yolt-anvils.json`:

```json
{
  "enabled": true,
  "enchantCost": 6,
  "repairCost": 5,
  "renameCost": 1,
  "breakChance": 0.12
}
```

| Key | Default | What it does |
|---|---|---|
| `enabled` | `true` | Each anvil use costs one flat price, set by its biggest job. `false` gives vanilla's anvil back. |
| `enchantCost` | `6` | Price of a use that enchants, 1 to 39. |
| `repairCost` | `5` | Price of a use that repairs and doesn't enchant, 1 to 39. |
| `renameCost` | `1` | Price of a use that only renames, 1 to 39. |
| `breakChance` | `0.12` | The chance per use that the anvil wears down a step, 0 to 1. Vanilla's is 0.12. |

Prices are XP levels. 0 can't be paid, and the client shows "Too Expensive!" for 40 or more.

- Each key is read on its own, so one bad value only keeps that value: the default at a server start, the value in use
  at a reload. The server log names it.
- A key missing from the file is added to it. A file with a refused value is left as you typed it, so the mistake stays
  where you made it, until you fix it.
- A file that isn't valid JSON is never written over. The defaults are used at a start, and a reload changes nothing.

## Commands

Ops only.

| Command | What it does |
|---|---|
| `/yolt-anvils` | Shows the prices and break chance in use. |
| `/yolt-anvils reload` | Reads `config/yolt-anvils.json` again. It applies at the next anvil use, no restart needed. |

Inside You Only Live Twice there is no `/yolt-anvils`: the settings are YOLT's
[`hardcore.anvil`](../../docs/config.md#hardcoreanvil), changed with `/yolt config` or `/yolt reload`.

## For mod authors

Another mod can ship YOLT Anvils inside its own jar and set it from its own config, as YOLT does. Call
`YoltAnvils.hostedBy(name, settings, exempt)` from your `onInitialize`:

- `settings` is read at every anvil use, so it can hand out your live config as an `AnvilSettings`.
- `exempt` names the players who keep vanilla's anvil.

From then on the file is never read or written and `/yolt-anvils` isn't registered.
