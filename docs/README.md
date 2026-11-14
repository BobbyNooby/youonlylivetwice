# YOLT documentation

You Only Live Twice (YOLT) is a server-side Fabric mod for Minecraft 26.3. Players join with a plain vanilla client and
no client mod. The only resource pack is an optional one for the revive paper's texture. These pages explain each
feature, its settings and its limits.

## Table of contents

### Setting up

- [Installing](install.md): what you need, recommended server settings, companion mods, updates and backups.
  - [Recommended server settings](install.md#recommended-server-settings)
  - [Companion mods](install.md#companion-mods)
  - [Updating and backups](install.md#updating-and-backups)
- [Config](config.md): every setting, its default and when a change shows.
  - [Top level](config.md#top-level)
  - [`worldBorder`](config.md#worldborder)
  - [`deathLockout`](config.md#deathlockout)
  - [`graves`](config.md#graves)
  - [`revive`](config.md#revive)
  - [`chat`](config.md#chat)
  - [`hide`](config.md#hide)
  - [`sleep`](config.md#sleep)
  - [`bodies`](config.md#bodies)
  - [`serverList`](config.md#serverlist)
  - [`skins`](config.md#skins)
  - [`hardcore`](config.md#hardcore)
  - [`resourcePack`](config.md#resourcepack)
  - [File-only settings](config.md#file-only-settings)
  - [Load checks](config.md#load-checks)
- [Commands and admin view](commands.md): the `/yolt` commands, `/status`, and what ops can see.
  - [Admin view](commands.md#admin-view)

### How it plays

- [Death lockout](death-lockout.md): dying kicks you for 7 days.
- [Graves](graves.md): where your items go, and the grave compass.
  - [Grave compass](graves.md#grave-compass)
- [Reviving](reviving.md): the revive paper that ends someone's lockout.
- [Logout bodies](logout-bodies.md): the body you leave behind when you log out.
  - [The two body types](logout-bodies.md#the-two-body-types)
- [Chat](chat.md): proximity chat and whispers.
- [Hidden information](hidden-information.md): the tab list, nametags, commands, the F3 screen and the rest.
  - [Debug privacy](hidden-information.md#debug-privacy)
- [Coordinate offset](coordinate-offset.md): fake X and Z numbers for each player (on by default).
  - [Known limits](coordinate-offset.md#known-limits)
- [World border and Nether](world-border.md): the shared border, the 1:1 Nether and random spawn.
  - [Random spawn](world-border.md#random-spawn)
- [Skins](skins.md): skins on offline-mode servers, and picking your own.
  - [Known limits](skins.md#known-limits)
- [Hardcore layer](hardcore.md): weight, broken legs, the curse, nutrients, tougher mobs, blood moons, cold and more
  (off by default, apart from the curse, the flat anvil price and Mending).
  - [Who it applies to](hardcore.md#who-it-applies-to)
  - [`/status`](hardcore.md#status)
  - [Slowdowns](hardcore.md#slowdowns)
  - [Weight](hardcore.md#weight)
  - [Broken legs](hardcore.md#broken-legs)
  - [Limp](hardcore.md#limp)
  - [Bed rest](hardcore.md#bed-rest)
  - [Curse of the Undead](hardcore.md#curse-of-the-undead)
  - [Nutrients](hardcore.md#nutrients)
  - [Hunger tax](hardcore.md#hunger-tax)
  - [Mending nerf](hardcore.md#mending-nerf)
  - [Flat anvil prices](hardcore.md#flat-anvil-prices)
  - [Elytra nerf](hardcore.md#elytra-nerf)
  - [Tougher mobs](hardcore.md#tougher-mobs)
  - [Relentless mobs](hardcore.md#relentless-mobs)
  - [Blood moon](hardcore.md#blood-moon)
  - [Cold](hardcore.md#cold)
  - [Revive paper texture](hardcore.md#revive-paper-texture)
  - [Where it is stored](hardcore.md#where-it-is-stored)

### Before you host

- [Known limits](known-limits.md): what YOLT can't do, and the trade-offs it makes.
- [Contributing](contributing.md): how the tests run, and what to check on every Minecraft update.
  - [Testing](contributing.md#testing)
  - [On every Minecraft update](contributing.md#on-every-minecraft-update)

## Still to come

- Bodies travelling through portals (backburner). For now they can't.
- Time slowdown and multi-version support (parked).
- Getting your ender chest back after a dragon kill (parked, undecided).

Not planned: recipe balancing beyond the one revive recipe, and custom textures or models beyond the revive paper's.
