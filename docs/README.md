# YOLT documentation

You Only Live Twice (YOLT) is a server-side Fabric mod for Minecraft 26.3. Players join with a plain vanilla client, no
client mod and no resource pack. These pages explain each feature, its settings and its limits.

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
- [Commands and admin view](commands.md): the `/yolt` commands, and what ops can see.
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
- [Coordinate offset](coordinate-offset.md): fake X and Z numbers for each player (off by default).
  - [Known limits](coordinate-offset.md#known-limits)
- [World border and Nether](world-border.md): the shared border, the 1:1 Nether and random spawn.
  - [Random spawn](world-border.md#random-spawn)
- [Skins](skins.md): skins on offline-mode servers, and picking your own.
  - [Known limits](skins.md#known-limits)

### Before you host

- [Known limits](known-limits.md): what YOLT can't do, and the trade-offs it makes.
- [Contributing](contributing.md): how the tests run, and what to check on every Minecraft update.
  - [Testing](contributing.md#testing)
  - [On every Minecraft update](contributing.md#on-every-minecraft-update)

## Still to come

- Bodies travelling through portals (backburner). For now they can't.
- Time slowdown and multi-version support (parked).
- Getting your ender chest back after a dragon kill (parked, undecided).

Not planned: recipe balancing beyond the one revive recipe, and custom textures or models.
