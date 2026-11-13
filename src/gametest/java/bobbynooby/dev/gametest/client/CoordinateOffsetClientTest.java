package bobbynooby.dev.gametest.client;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.coords.CoordinateOffsets;
import bobbynooby.dev.coords.Offset;
import bobbynooby.dev.world.RandomSpawn;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;

/**
 * What a real client shows with the coordinate offset on: its own position, shifted by its frame's offset, on the full
 * F3 screen, before and after a teleport, with no border wall in sight; then after a death's respawn, and on a trip to
 * the nether and back, each of which starts a frame of its own.
 */
public class CoordinateOffsetClientTest implements FabricClientGameTest {
    // Where the world spawn goes, so the join's frame has an offset that isn't zero
    private static final int SPAWN_X = 1_000;
    private static final int SPAWN_Z = 2_000;
    // Where the server teleports the player, in real coordinates
    private static final double TELEPORT_X = 1_300.5;
    private static final double TELEPORT_Z = 1_800.5;
    // The border a client is sent with no wall near it: the whole world, centred on 0, 0
    private static final double WHOLE_WORLD = 59_999_968.0;
    // Long enough for a landed player's last move to reach the server
    private static final int SETTLE_TICKS = 20;
    // Where the world spawn moves before the death, so the respawn's frame is anchored away from the join's
    private static final int RESPAWN_X = 600;
    private static final int RESPAWN_Z = 2_400;
    // Where the trip to the nether lands, on a platform made there, and where the trip back lands
    private static final BlockPos NETHER_SPOT = new BlockPos(150, 100, 250);
    private static final int BACK_X = 1_400;
    private static final int BACK_Z = 1_700;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        // The config outlives each test's server, and between servers nothing else reads it
        YoltConfig config = Settings.get();
        boolean coordinatesWas = config.hide.coordinates;
        boolean debugScreenWas = config.hide.debugScreen;
        boolean randomSpawnWas = config.randomSpawn;
        // The reduced debug screen would hide the position this test shows, and a random spawn the world spawn it sets
        config.hide.coordinates = true;
        config.hide.debugScreen = false;
        config.randomSpawn = false;
        try {
            ClientTests.withServer(ctx, server -> server.runOnServer(s -> {
                // The world is new for each test, so its spawn needs no setting back
                ServerLevel overworld = s.overworld();
                s.setRespawnData(LevelData.RespawnData.of(Level.OVERWORLD, standingSpot(overworld, SPAWN_X, SPAWN_Z), 0.0F, 0.0F));
            }), (server, connection) -> {
                try {
                    Offset offset = server.computeOnServer(s -> CoordinateOffsets.offset(connection.getServerPlayer()));
                    if (offset.isZero()) {
                        throw new AssertionError("the join's frame has no offset");
                    }
                    checkShiftedPosition(ctx, server, connection, offset);
                    checkNoWallInSight(ctx);
                    showDebugScreen(ctx);
                    showTeleport(ctx, server, connection, offset);
                    Offset respawned = checkRespawn(ctx, server, connection, offset);
                    Offset inNether = checkNetherTrip(ctx, server, connection, respawned);
                    checkTripBack(ctx, server, connection, inNether);
                } finally {
                    // The client keeps the overlay up into the tests after this one otherwise
                    ClientTests.runAll(List.of(() -> ctx.runOnClient(mc -> mc.debugEntries.setOverlayVisible(false))));
                }
            });
        } finally {
            // Only once the server is gone, so a join that failed can't leave them on for the tests after this one
            config.hide.coordinates = coordinatesWas;
            config.hide.debugScreen = debugScreenWas;
            config.randomSpawn = randomSpawnWas;
        }
    }

    // The client's X and Z are the server's shifted by the offset, exactly, and its Y is the server's
    private static void checkShiftedPosition(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                             TestDedicatedServerConnection connection, Offset offset) {
        ClientTests.waitOnClient(ctx, ClientGameTestContext.DEFAULT_TIMEOUT, mc -> mc.player.onGround(),
                mc -> "the client's player never landed, it is at " + mc.player.position());
        ctx.waitTicks(SETTLE_TICKS);
        Vec3 real = server.computeOnServer(s -> connection.getServerPlayer().position());
        Vec3 shown = ctx.computeOnClient(mc -> mc.player.position());
        if (Math.abs(shown.x - (real.x + offset.x())) > 1.0E-6 || Math.abs(shown.z - (real.z + offset.z())) > 1.0E-6
                || shown.y != real.y) {
            throw new AssertionError("the client is at " + shown + ", not at the server's " + real + " shifted by " + offset);
        }
    }

    private static void checkNoWallInSight(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            WorldBorder border = mc.level.getWorldBorder();
            if (border.getCenterX() != 0.0 || border.getCenterZ() != 0.0 || border.getSize() != WHOLE_WORLD) {
                throw new AssertionError("the client's border is " + border.getSize() + " wide round " + border.getCenterX() + ", "
                        + border.getCenterZ() + ", not the whole world round 0, 0");
            }
        });
    }

    private static void showDebugScreen(ClientGameTestContext ctx) {
        ClientTests.waitOnClient(ctx, ClientGameTestContext.DEFAULT_TIMEOUT, mc -> !mc.showOnlyReducedInfo(),
                mc -> "the client has the reduced debug screen");
        ctx.runOnClient(mc -> {
            mc.debugEntries.setOverlayVisible(true);
            if (!mc.debugEntries.isCurrentlyEnabled(DebugScreenEntries.PLAYER_POSITION)) {
                throw new AssertionError("the debug screen doesn't show the player's position");
            }
        });
        ClientTests.screenshot(ctx, "12-offset-f3");
    }

    // A teleport in the same dimension starts no frame, so the client lands at the real spot shifted by the same offset
    private static void showTeleport(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                     TestDedicatedServerConnection connection, Offset offset) {
        server.runOnServer(s -> {
            ServerLevel overworld = s.overworld();
            BlockPos spot = standingSpot(overworld, (int) Math.floor(TELEPORT_X), (int) Math.floor(TELEPORT_Z));
            ServerPlayer player = connection.getServerPlayer();
            player.teleportTo(overworld, TELEPORT_X, spot.getY(), TELEPORT_Z, Set.of(), player.getYRot(), player.getXRot(), true);
        });
        double x = TELEPORT_X + offset.x();
        double z = TELEPORT_Z + offset.z();
        ClientTests.waitOnClient(ctx, ClientGameTestContext.DEFAULT_TIMEOUT, mc -> mc.player.getX() == x && mc.player.getZ() == z,
                mc -> "the client is at " + mc.player.getX() + ", " + mc.player.getZ() + ", not at " + x + ", " + z);
        Offset after = server.computeOnServer(s -> CoordinateOffsets.offset(connection.getServerPlayer()));
        if (!after.equals(offset)) {
            throw new AssertionError("the teleport changed the offset from " + offset + " to " + after);
        }
        connection.waitForChunksRender();
        ClientTests.screenshot(ctx, "12-offset-teleport");
    }

    // A death respawns the client at the world spawn, moved first, in a new frame: the client's position is the server's
    // shifted by that frame's offset. The lockout is off for it, so the death doesn't kick the client.
    private static Offset checkRespawn(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                       TestDedicatedServerConnection connection, Offset joined) {
        LocalPlayer before = ctx.computeOnClient(mc -> mc.player);
        boolean lockoutWas = server.computeOnServer(s -> Settings.get().deathLockout.enabled);
        try {
            server.runOnServer(s -> {
                Settings.get().deathLockout.enabled = false;
                ServerLevel overworld = s.overworld();
                s.setRespawnData(LevelData.RespawnData.of(Level.OVERWORLD, standingSpot(overworld, RESPAWN_X, RESPAWN_Z), 0.0F, 0.0F));
                ServerPlayer player = connection.getServerPlayer();
                player.kill(player.level());
                if (!player.isDeadOrDying()) {
                    throw new AssertionError("the client's player did not die");
                }
            });
            // Immediate respawn is on, so the client respawns by itself
            ctx.waitFor(mc -> mc.player != before && mc.gui.screen() == null, TestServerConnection.DEFAULT_CHUNK_LOAD_TIMEOUT);
            Offset offset = frameOffset(server, connection);
            if (offset.isZero() || offset.equals(joined)) {
                throw new AssertionError("the respawn's frame has the offset " + offset + ", the join's was " + joined);
            }
            checkShiftedPosition(ctx, server, connection, offset);
            return offset;
        } finally {
            server.runOnServer(s -> Settings.get().deathLockout.enabled = lockoutWas);
        }
    }

    // The nether starts a frame at the arrival. A platform is made there first, with room above it, so the client lands.
    private static Offset checkNetherTrip(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                          TestDedicatedServerConnection connection, Offset before) {
        server.runOnServer(s -> {
            ServerLevel nether = s.getLevel(Level.NETHER);
            if (nether == null) {
                throw new AssertionError("no nether to go to");
            }
            for (BlockPos pos : BlockPos.betweenClosed(NETHER_SPOT.offset(-1, -1, -1), NETHER_SPOT.offset(1, 2, 1))) {
                nether.setBlockAndUpdate(pos, pos.getY() < NETHER_SPOT.getY() ? Blocks.OBSIDIAN.defaultBlockState()
                        : Blocks.AIR.defaultBlockState());
            }
            ServerPlayer player = connection.getServerPlayer();
            player.teleportTo(nether, NETHER_SPOT.getX() + 0.5, NETHER_SPOT.getY(), NETHER_SPOT.getZ() + 0.5, Set.of(),
                    player.getYRot(), player.getXRot(), true);
        });
        return checkArrival(ctx, server, connection, Level.NETHER, before);
    }

    // And the trip back starts another
    private static void checkTripBack(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                      TestDedicatedServerConnection connection, Offset before) {
        server.runOnServer(s -> {
            ServerLevel overworld = s.overworld();
            BlockPos spot = standingSpot(overworld, BACK_X, BACK_Z);
            ServerPlayer player = connection.getServerPlayer();
            player.teleportTo(overworld, spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, Set.of(), player.getYRot(),
                    player.getXRot(), true);
        });
        checkArrival(ctx, server, connection, Level.OVERWORLD, before);
    }

    private static Offset checkArrival(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                       TestDedicatedServerConnection connection, ResourceKey<Level> dimension, Offset before) {
        ctx.waitFor(mc -> mc.level != null && mc.level.dimension() == dimension && mc.gui.screen() == null,
                TestServerConnection.DEFAULT_CHUNK_LOAD_TIMEOUT);
        Offset offset = frameOffset(server, connection);
        if (offset.isZero() || offset.equals(before)) {
            throw new AssertionError("the frame in " + dimension.identifier() + " has the offset " + offset + ", the last one's was "
                    + before);
        }
        checkShiftedPosition(ctx, server, connection, offset);
        return offset;
    }

    private static Offset frameOffset(TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
        return server.computeOnServer(s -> CoordinateOffsets.offset(connection.getServerPlayer()));
    }

    // The block above the ground in that column, where a player stands
    private static BlockPos standingSpot(ServerLevel overworld, int x, int z) {
        BlockPos spot = RandomSpawn.spotAt(overworld, x, z);
        if (spot == null) {
            throw new AssertionError("nowhere to stand at " + x + ", " + z);
        }
        return spot;
    }
}
