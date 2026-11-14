# Contributing

Contents:

- [Testing](#testing)
- [Changing a default](#changing-a-default)
- [On every Minecraft update](#on-every-minecraft-update)

## Testing

Everything builds and tests on GitHub Actions, nothing needs to run locally. Every run does the build, the JUnit
tests and the server GameTests. Their results are in the `test-results` artifact, kept for 14 days. The client
GameTests, which save screenshots in the `client-screenshots` artifact, run on pull requests, on pushes to `release`
and on manual runs with the `client` option. The jar and the revive paper's resource pack zip are in the `Artifacts`
download. Real skins aren't checked in CI: test players have no skins, and CI never asks Mojang. These tests haven't
replaced a hands-on session in a real client, which I haven't done yet.

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
