# Contributing

Contents:

- [Layout](#layout)
- [Hosting YOLT Hardcore](#hosting-yolt-hardcore)
- [Testing](#testing)
- [Changing a default](#changing-a-default)
- [On every Minecraft update](#on-every-minecraft-update)

## Layout

- The root project is You Only Live Twice itself: `src/main` the mod, `src/test` its JUnit tests, `src/gametest` its
  GameTests, server and client.
- Five mods of their own, each a Gradle project with its own `src/main`, `src/test` and `src/gametest`. Their
  GameTests run each one with no YOLT on the server.
  - `hardcore/` is [YOLT Hardcore](../hardcore/README.md), the hardcore layer (`bobbynooby.dev.hardcore`).
  - `coordinates/` is [YOLT Coordinates](../coordinates/README.md), the coordinate offset (`bobbynooby.dev.coords`).
  - The tiny mods live under `extras/`: `extras/anvil/` is [YOLT Anvils](../extras/anvil/README.md), the flat anvil
    price, `extras/mending/` is [YOLT Mending](../extras/mending/README.md), the Mending nerf, and `extras/elytra/` is
    [YOLT Elytra](../extras/elytra/README.md), the elytra nerf.
- What ships and hosts what:
  - YOLT's jar ships YOLT Hardcore's and YOLT Coordinates' jars inside (`include` in the root `build.gradle`) and
    hosts both, from `src/main/java/bobbynooby/dev/hosted/`: `HardcoreLayer.java` calls `YoltHardcore.hostedBy` and
    `CoordinateOffset.java` calls `YoltCoordinates.hostedBy`.
  - YOLT Hardcore's jar ships the three tiny mods' jars and `sqlite-jdbc` inside (`include` in
    `hardcore/build.gradle`) and hosts the tiny mods, under YOLT and on its own alike. Its bridges are `Anvils.java`,
    `Mending.java` and `Elytra.java` in `hardcore/src/main/java/bobbynooby/dev/hardcore/`, each of which calls its
    mod's `hostedBy`. So YOLT's jar holds every mod, the tiny ones inside YOLT Hardcore's.
  - A hosted mod always has its host's version. A host's `fabric.mod.json` asks for each mod it hosts at the host's
    own version or newer (`depends`): YOLT's for YOLT Hardcore and YOLT Coordinates, YOLT Hardcore's for each tiny
    mod. Each hosted mod refuses an older host (`breaks`): YOLT Hardcore and YOLT Coordinates an older
    `you-only-live-twice`, and each tiny mod an older `you-only-live-twice` or `yolt-hardcore`.
  - YOLT's hardcore, anvil, Mending, Elytra and coordinate offset GameTests check each mod hosted, as a YOLT server
    runs it.
- Every mod besides YOLT shares one build, `gradle/mod.gradle`. A mod's own `build.gradle` sets `ext.modId`, and
  `ext.sharedPackage` if it uses `shared/`, and applies that file. YOLT Hardcore sets no `ext.sharedPackage`: it uses
  nothing in `shared/`.
- `shared/` is source, not a mod and never a jar: the settings file, its kinds of key, the switch between hosted and
  on its own, and the command that every tiny mod and YOLT Coordinates have. At each build a mod that sets
  `ext.sharedPackage` copies it into its own `build/` folder, with the package `bobbynooby.dev.shared` rewritten to
  that one, such as `bobbynooby.dev.anvils.shared`, and compiles it into itself. So no two jars hold a class of the
  same name, and each jar is complete on its own, with its own copy. `shared/` has no Gradle project of its own, so an
  editor shows no errors there. Change it in `shared/`, never in the copy under a mod's `build/`, which the next build
  writes over, and build a mod that uses it to check it.
- `anvil/` holds only a readme that points at `extras/anvil/`. The 1.2.1 release notes and the 1.2.1 anvil jar link
  to that folder, so it stays.
- Every project takes its versions from the root `gradle.properties`, so one `mod_version` covers every jar.

To add a tiny mod that works both on its own and inside YOLT Hardcore, and so inside YOLT (`extras/mending/` is the
smallest one to copy):

1. Make a folder under `extras/` with the mod's own files, copied from the mending ones:
   `src/main/resources/fabric.mod.json`, whose id starts with `yolt-`, with its two `breaks` entries, on an older
   `you-only-live-twice` and an older `yolt-hardcore`; its mixins file beside it, named after the mod id; and
   `src/gametest/resources/fabric.mod.json`, whose id is the mod id with `-gametest` on the end.
2. Write its settings, as in `extras/mending/src/main/java/bobbynooby/dev/mending/`: a settings record with `DEFAULTS`
   and a rule method for each number (`MendingSettings`), a `SettingsFile` of `Key`s that use those rules
   (`MendingConfig`), and a `TinyMod` in its `ModInitializer`, which the rest of the mod asks for its settings and for
   who is left out (`YoltMending`, with its `hostedBy`).
3. Wire up the build. Its `build.gradle`, like the mending one, sets `ext.modId` and `ext.sharedPackage`, the mod's own
   package with `.shared` on the end, and then applies `gradle/mod.gradle`. Add the folder's name to the list in
   `settings.gradle`, and the folder's name and the mod id to `tinyMods` in the root `build.gradle`: that loads it in a
   dev run. Add an `implementation include(project(':<folder>'))` line to `hardcore/build.gradle`: that ships its jar
   inside YOLT Hardcore's, which YOLT's jar holds. Add the mod id to `depends` in
   `hardcore/src/main/resources/fabric.mod.json`, at `>=${version}`.
4. Write a bridge in `hardcore/src/main/java/bobbynooby/dev/hardcore/`, like `Mending.java`. Its `init()` calls the
   mod's `hostedBy(Host.name(), ...)` with settings read live from the mod's section of the hardcore settings
   (`HardcoreConfig`, through `Host`) and the players the hardcore layer leaves out, and `Hardcore.init()` calls that
   `init()`. Without a bridge the mod runs inside YOLT Hardcore as if on its own: a server with YOLT or YOLT Hardcore
   gets its `/<mod id>` command and its own file in `config/`.
5. Add its section to `HardcoreConfig` and check it in `HardcoreChecks`
   (`hardcore/src/main/java/bobbynooby/dev/hardcore/HardcoreChecks.java`) with the mod's own rule methods, as
   `MendingSettings.isRate` is used there. YOLT's `ConfigChecks` and YOLT Hardcore's own file both go through
   `HardcoreChecks`, so YOLT's config, `config/yolt-hardcore.json` and the mod's own file refuse the same values.
6. Add its switch to `Hardcore.featuresOn`, under its section's name. That list is what `/yolt-hardcore` and YOLT
   Hardcore's start line name, and `Hardcore.anyOn()` is true only while the list has a name in it. Left out, with
   only this mod's switch on, `/status` says "Nothing to report", and under YOLT, while `restrictCommands` is on,
   non-ops don't get `/status`.
7. Add it to the tests that list every tiny mod: `TinyModDefaultsTest` (YOLT ships the mod's numbers),
   `HostedModsGameTests` (inside YOLT it has no file and no command), `HardcoreAloneGameTests` in `hardcore/`
   (inside YOLT Hardcore on its own it has no command either), and, if it adds attribute modifiers or anything saved
   with a player, `Ours` in YOLT's gametest package.

## Hosting YOLT Hardcore

YOLT is the one host today, and another mod can host YOLT Hardcore the same way:

- It says so in its `fabric.mod.json`, with `"custom": {"yolt-hardcore:host": true}`, as YOLT's does.
- It calls `YoltHardcore.hostedBy(HardcoreHost)` once, from its own `onInitialize`. YOLT Hardcore sets itself up
  inside that call, so its event hooks register at that place in the host's start-up. YOLT calls it last, from
  `HardcoreLayer.init()`, so the hardcore join hook runs after the body handover.
- `HardcoreHost` hands the mod the hardcore settings and the six blood moon texts, read at every use, whether the
  host's rules apply in this world, and the host's stand-ins and bodies.
- While hosted, YOLT Hardcore reads no file, registers no command (no `/status` and no `/yolt-hardcore`) and has no
  entity-load hook: the host calls `MobBoosts.onLoad` for each mob as it loads, from a hook of its own.
- With a host declared, the mod's own `onInitialize` only registers a check for the server start. A host that declares
  itself and never calls `hostedBy` leaves every hardcore feature off, and the log says so at the server start. YOLT
  Anvils, YOLT Mending and YOLT Elytra then run as if on their own, with their own files and commands.

## Testing

Everything runs locally, with Java 25:

- `./gradlew build --continue`: every jar, the JUnit tests and the server GameTests of YOLT and of each of the five
  mods of its own. Results are in `build/test-results/` for YOLT, `hardcore/build/test-results/`,
  `coordinates/build/test-results/` and, for each tiny mod, `extras/<name>/build/test-results/`; the jars in the
  `build/libs/` folder of each.
- `./gradlew :anvil:test :anvil:runGameTest`: the tests of the anvil tiny mod alone, in under a minute. The same works
  for `:mending` and `:elytra`.
- `./gradlew :hardcore:test :hardcore:runGameTest` and `./gradlew :coordinates:test :coordinates:runGameTest`: the
  tests of YOLT Hardcore or YOLT Coordinates alone, with no YOLT on the server.
- `./gradlew test`: the JUnit tests alone, in seconds.
- `./gradlew :runGameTest -Dfabric-api.gametest.filter=you-only-live-twice-gametest:lockout_game_tests_*`: some of
  YOLT's server GameTests. Without the `:` every other mod's GameTests run too, unfiltered. Add
  `-Dfabric-api.gametest.verify=true` to repeat each of them and find flakes.
- `scripts/client-tests.sh`: the client GameTests, in a real Minecraft window, about 5 minutes. Screenshots land in
  `build/run/clientGameTest/screenshots/`. `scripts/client-tests.sh PresenceLeak,ServerList` runs only the tests whose
  class names start with those. On NixOS the script finds the game's native libraries in nixpkgs, and keeps the game on
  Mesa, the integrated GPU.

GitHub Actions only runs for a push to `release`, where the tested jars become the draft release, and for runs started
by hand, where the `client` option adds the client tests. A run's results are in the `test-results` artifact, kept for
14 days, the client screenshots in `client-screenshots`, and the jars and the revive paper's resource pack zip in
`Artifacts`. The draft release gets YOLT's jar, the five mods' own jars and the resource pack zip. A new tiny mod under
`extras/` needs no change to the workflow while its mod id starts with `yolt-`: the `gather the release files` step
and the release job pick up every `yolt-*` jar by pattern. A mod in a folder of its own, as `hardcore/` and
`coordinates/` are, needs that folder added to the gather step and to the steps that store the test results and
reports. Real skins aren't checked: test players have no skins, and the tests never ask Mojang. These tests haven't
replaced a hands-on session in a real client, which I haven't done yet.

Two client tests record everything the client is sent, from inside its own connection (`PacketRecorder`):
`PresenceLeakClientTest` searches every byte for the names and UUIDs of players the client mustn't know about, and
`CoordinateLeakClientTest` puts every packet it decodes through the coordinate leak scanner the server sweep uses.

The GameTests share one world, which keeps the switches that ship on but most tests were written without off
(`TestServerSetup.pinOldDefaults`). `ShippedDefaultsGameTests` checks them as they ship.

## Changing a default

Every server's config file holds every key, so a new default never reaches a server that already has a file. A default
only changes through a step that flips a value still at its old default:

1. Raise `ConfigLoader.CURRENT_VERSION` by one.
2. Add a step from the old version that sets the key to its new default only where the file still holds the old one,
   exactly, and adds a line saying how to go back. `ConfigLoader.turnOnUntouched` does this for switches.
3. Say so in the release notes.

Maps and lists are read whole, so a new entry in a default map, such as a food in `hardcore.nutrients.foods`, doesn't
reach a server either. Its step replaces the map only where it still equals the old default map, entry for entry.
A key that is new in a release needs no step: a file without it takes its default.

These steps are in YOLT's loader only. YOLT Hardcore's, the tiny mods' and YOLT Coordinates' own files are written
with every key too, and none has upgrade steps (`HardcoreFile`, `SettingsFile`), so a changed default doesn't reach a
server that runs one of those mods without YOLT and already has its file.

## On every Minecraft update

The coordinate offset reads every packet, so a new Minecraft version can add a position it doesn't know about. Before
a release on a new version:

- Run the coverage and leak tests (`CoordinateOffsetCoverageGameTests` and `LeakSweepGameTests`). Every new packet,
  item component, entity data type, particle, block entity, position source and waypoint type has to be sorted into
  "shifted" or "holds no position" first. A new debug subscription needs nothing: a player with an offset is only
  ever sent the server tick times one, so any new one is held back from them.
- Compare the packet classes that changed between the two versions, and look in them for new positions, items, NBT
  and particles.
- Check that every method the hooks sit on still exists with the same arguments: sending and receiving on the
  connection, the respawn, the teleport, the parsing of a typed command, the container click, the coordinate
  arguments, the debug subscriptions and the collision shape.
- Check that the order of things still holds: on a dimension change the respawn comes before the old chunks are
  forgotten, a dead player is sent no forgets, the client keeps its chunks on a respawn in the same dimension, and
  accepting a teleport ends it.
- Read the update commit for that version in CoordinateOffset (the Paper plugin that does the same job). It lists
  what moved.

[All pages](README.md)
