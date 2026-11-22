package bobbynooby.dev.coords.client;

import bobbynooby.dev.coords.ClientBridge;
import bobbynooby.dev.coords.CoordinateOffsets;
import bobbynooby.dev.coords.Offset;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static bobbynooby.dev.coords.client.ClientKit.check;

/**
 * The mod in a client's own single-player world, as a player who put the jar in their mods folder meets it: it does
 * nothing there and says so. The world's start logs that line and still writes config/yolt-coordinates.json, which the
 * test takes away first. The client's position is the server's own, at the join and after a respawn at a world spawn
 * moved far from 0, 0, where a stretch would have had a shift, and its F3 screen shows those real numbers. The owner,
 * with cheats on, may type /yolt-coordinates and /yolt-coordinates real, and each says the mod does nothing here.
 */
public class SingleplayerAloneClientTest implements FabricClientGameTest {
    private static final String NOTHING_HERE = "YOLT Coordinates does nothing in a single-player world";
    private static final String START_LINE = "YOLT Coordinates off: nobody is shifted";
    // /yolt-coordinates real refuses with the same words as a sentence
    private static final String REAL_REFUSED = NOTHING_HERE + ".";
    private static final double FAR = 500.0;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        Path file = ClientBridge.configFile();
        String before = read(file);
        try {
            // The run's first server start wrote it, and only this world's start can write it again
            deleteFile(file);
            TestSingleplayerContext[] opened = new TestSingleplayerContext[1];
            List<String> logged = ClientKit.logged(() -> opened[0] = ctx.worldBuilder().adjustSettings(world -> {
                world.setGameMode(WorldCreationUiState.SelectedGameMode.SURVIVAL);
                world.setAllowCommands(true);
            }).create());
            try (TestSingleplayerContext world = opened[0]) {
                TestServerContext server = world.getServer();
                TestServerConnection connection = world.getConnection();
                check(logged.contains(NOTHING_HERE), () -> "the world's start logged " + logged + ", not \"" + NOTHING_HERE + "\"");
                check(logged.contains(START_LINE), () -> "the world's start logged " + logged + ", not \"" + START_LINE + "\"");
                check(Files.isRegularFile(file), () -> "the world's start wrote no " + file);
                check(!server.computeOnServer(s -> ClientBridge.active()), () -> "YOLT Coordinates runs in a single-player world");

                checkReal(ctx, server, connection, "after the join");
                server.runOnServer(s -> ClientKit.moveSpawn(s, ClientKit.SPAWN_X, ClientKit.SPAWN_Z));
                ClientKit.die(ctx, server, connection);
                ClientKit.respawn(ctx, connection);
                Vec3 shown = checkReal(ctx, server, connection, "after a respawn at the moved spawn");
                check(Math.abs(shown.x) > FAR && Math.abs(shown.z) > FAR,
                        () -> "the client respawned at " + shown + ", not at the moved spawn's real numbers");

                ClientKit.clearChat(ctx);
                int mark = ClientKit.mark();
                // The owner of a world with cheats on may use every command
                check(ctx.computeOnClient(mc -> ClientKit.clientHasCommand(mc, "yolt-coordinates")),
                        () -> "the owner's command tree has no /yolt-coordinates with cheats on");
                ClientKit.type(ctx, connection, "/yolt-coordinates");
                ClientKit.waitForChat(ctx, mark, NOTHING_HERE);
                ClientKit.type(ctx, connection, "/yolt-coordinates real");
                ClientKit.waitForChat(ctx, mark, REAL_REFUSED);
                check(!server.computeOnServer(s -> ClientBridge.seesReal(connection.getServerPlayer())),
                        () -> "/yolt-coordinates real turned real coordinates on in a single-player world");
                ClientKit.showF3(ctx);
                ClientKit.checkF3Position(ctx, shown, "in single-player");
                ClientKit.screenshotWithChat(ctx, "coordinates-alone-singleplayer");
            } finally {
                ClientKit.runAll(List.of(() -> ClientKit.hideF3(ctx), () -> ClientKit.clearChat(ctx)));
            }
        } finally {
            // A world that never started would leave the file gone for anything after this test
            if (before != null && !Files.exists(file)) {
                write(file, before);
            }
        }
    }

    // The client stands where the server's player stands, to the digit, and the mod gave it no shift
    private static Vec3 checkReal(ClientGameTestContext ctx, TestServerContext server, TestServerConnection connection,
                                  String when) {
        Offset offset = server.computeOnServer(s -> CoordinateOffsets.offset(connection.getServerPlayer()));
        check(offset.isZero(), () -> "the client was given the shift " + offset + " " + when);
        Vec3 shown = ClientKit.checkShifted(ctx, server, connection, Offset.ZERO, when);
        Vec3 real = server.computeOnServer(s -> connection.getServerPlayer().position());
        check(shown.equals(real), () -> "the client is at " + shown + " " + when + ", the server's player at " + real);
        return shown;
    }

    private static String read(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file) : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteFile(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path file, String text) {
        try {
            Files.writeString(file, text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
