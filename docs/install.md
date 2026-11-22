# Installing

Contents:

- [The release's files](#the-releases-files)
- [Recommended server settings](#recommended-server-settings)
- [Companion mods](#companion-mods)
- [Updating and backups](#updating-and-backups)

You need:

- Minecraft 26.3
- Fabric Loader 0.20.1 or newer
- [Fabric API](https://modrinth.com/mod/fabric-api) 0.165.2+26.3 or newer
- Java 25

Get `you-only-live-twice-<version>.jar` from the
[latest release](https://github.com/BobbyNooby/youonlylivetwice/releases/latest), or from the `Artifacts` download on a
successful run of the `build` workflow on GitHub. Put it in the server's `mods` folder, next to Fabric API. That one
jar is all a YOLT server needs: the `yolt-` jars beside it are parts of YOLT as mods of their own, for servers without
YOLT (see [The release's files](#the-releases-files)).

The mod is server-side. It loads in single-player but does nothing there.

Start the server once. It writes `config/you-only-live-twice.json` and, with the world border feature on (the default),
sets the border to 10,000 blocks across. If that border would leave a body, a grave or the End's arrival platform
outside (or the world spawn, when new players start right on it), YOLT leaves the world's border as it is and says so
in the log.

On Java 25 the server log warns that a "restricted method" was called by `org.sqlite.SQLiteJDBCLoader`. That is the
bundled SQLite driver loading its native code, and it is harmless. If a later Java blocks it, start the server with
`--enable-native-access=ALL-UNNAMED`.

## The release's files

| File | What it is |
|---|---|
| `you-only-live-twice-<version>.jar` | YOLT, with every feature. It holds YOLT Hardcore and YOLT Coordinates, and YOLT Hardcore holds YOLT Anvils, YOLT Mending and YOLT Elytra. |
| `yolt-hardcore-<version>.jar` | [YOLT Hardcore](../hardcore/README.md): the [hardcore layer](hardcore.md) without YOLT. It holds YOLT Anvils, YOLT Mending and YOLT Elytra. |
| `yolt-coordinates-<version>.jar` | [YOLT Coordinates](../coordinates/README.md): the [coordinate offset](coordinate-offset.md) without YOLT. It only runs on a dedicated server. |
| `yolt-anvils-<version>.jar` | [YOLT Anvils](../extras/anvil/README.md): the [flat anvil price](hardcore.md#flat-anvil-prices) alone. |
| `yolt-mending-<version>.jar` | [YOLT Mending](../extras/mending/README.md): the [Mending nerf](hardcore.md#mending-nerf) alone. |
| `yolt-elytra-<version>.jar` | [YOLT Elytra](../extras/elytra/README.md): the [elytra nerf](hardcore.md#elytra-nerf) alone. |
| `you-only-live-twice-revive-pack-<version>.zip` | The optional resource pack with the [revive paper's texture](hardcore.md#revive-paper-texture). |

Which to pick:

- A YOLT server: YOLT's jar. It needs none of the others.
- The hardcore layer without YOLT: YOLT Hardcore's jar. YOLT Anvils, YOLT Mending and YOLT Elytra come with it.
- The coordinate offset without YOLT: YOLT Coordinates' jar. It can go beside YOLT Hardcore's.
- Only the flat anvil price, the Mending nerf or the elytra nerf: that mod's own jar.

Each mod's readme says how to set it up. Every older YOLT has the coordinate offset built in, and 1.1.0 to 1.2.1 the
other parts too, so beside one a mod's own jar stops the server from starting, and the log says why. Beside 1.2.1,
which ships YOLT Anvils itself, the server starts with that copy and leaves the YOLT Anvils jar out.

## Recommended server settings

- Set `spawn-protection=0` in `server.properties`. Vanilla stops non-ops from using blocks near the world spawn, and
  graves can end up there (for example when someone dies in the void and the grave falls back to the spawn column), so
  players could not open them. With `hide.coordinates` on there is a second reason: vanilla's "is under spawn
  protection" line names the block. YOLT names it in the player's own numbers, or as "This block" while YOLT reduces F3,
  but with the setting at 0 the line never comes up.
- Keep `enable-query` off unless you need it. With `hide.serverPlayerCount` on, the query port reports 0 players and
  no names. With that switch off it reports the real count, and lists names only when `serverList.hoverLines` is
  empty. The default has one line, so by default it lists no names.
- Pre-generate the world with Chunky. Random spawn searches on the server thread, and a spot in a chunk that doesn't
  exist yet is generated on the spot, which lags.

## Companion mods

I recommend these, none are required:

- [Simple Voice Chat (client and server)](https://modrinth.com/project/9eGKb6K1)
    - The mod I originally made this for. Proximity chat in text works fine, but voice is the real thing.
- [Chunky (server-side)](https://modrinth.com/project/fALzjamp)
    - The world border is small, so you can pre-generate the whole world.
- [Voice Chat Interaction (server-side)](https://modrinth.com/project/qsSP2ZZ0)
    - Voice chat sets off skulk sensors and wakes wardens.
- [Hardcore Hearts (resource pack)](https://modrinth.com/project/5AMcQaG0)
    - Changes the hearts to hardcore hearts. It is a client resource pack, optional, and the mod doesn't need it.

Other mods may cause problems. I haven't tested combinations.

## Updating and backups

YOLT keeps its data in versioned files, so a newer build can upgrade them:

- **Config.** An older config file is upgraded at startup, and the old file is kept as
  `you-only-live-twice.json.v<version>.bak`. A config from a newer YOLT is read as far as this one can, but never
  rewritten. At startup, a file that can't be read at all is kept as `you-only-live-twice.json.bak` and replaced with
  defaults.
  A backup name that already holds a different file is never written over: the copy gets the time added
  (`.bak-<time>`).
- **Graves, bodies and skins** live in the world's `data/you-only-live-twice` folder (`graves.dat`, `bodies.dat`,
  `skins.dat`). An older file is upgraded as it loads, and the items in it go through Minecraft's own data fixer, so
  a Minecraft update doesn't make them unreadable. A file from a newer YOLT or Minecraft is copied to
  `<file>.newer-<time>` before it is saved over. A file, or part of one, that can't be read is copied to
  `<file>.unreadable-<time>`.
- **Hardcore database** (`database/you-only-live-twice-hardcore.db` in the world folder), made only once the
  [hardcore layer](hardcore.md) first stores something. One from a newer YOLT is left untouched. YOLT Hardcore on its
  own uses the same file, so a world keeps its hardcore state when it moves between YOLT and YOLT Hardcore.
- **Death database** (`database/you-only-live-twice.db` in the world folder). Its layout is upgraded in place. A
  database from the old 1.21.5 build is moved aside to `you-only-live-twice.db.1.21.5-<time>.bak` at startup, and
  the lockouts in it end. One from a newer YOLT is left untouched, and the lockout then lets everyone in.

[All pages](README.md)
