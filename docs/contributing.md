# Contributing

Contents:

- [Testing](#testing)
- [On every Minecraft update](#on-every-minecraft-update)

## Testing

Everything builds and tests on GitHub Actions, nothing needs to run locally. Every run does the build, the JUnit
tests and the server GameTests. Their results are in the `test-results` artifact, kept for 14 days. The client
GameTests, which save screenshots in the `client-screenshots` artifact, run on pull requests, on pushes to `release`
and on manual runs with the `client` option. The jar and the revive paper's resource pack zip are in the `Artifacts`
download. Real skins aren't checked in CI: test players have no skins, and CI never asks
Mojang. These tests haven't replaced a hands-on session in a real client, which I haven't done yet.

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
