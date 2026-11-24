package bobbynooby.dev.elytra.client;

import bobbynooby.dev.elytra.YoltElytra;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.util.List;

import static bobbynooby.dev.elytra.client.ClientKit.check;

/**
 * The mod in a client's own single-player world, as a player who put the jar in their mods folder meets it. The world's
 * start reads the client's config/yolt-elytra.json, which the run's first server start wrote with the defaults, Loom
 * having emptied the run folder before the run. In a survival world the same glides as on a server come to the same
 * gravity and speeds.
 */
public class SingleplayerAloneClientTest implements FabricClientGameTest {
    private static final String START_LINE = "YOLT Elytra on: glide gravity 0.6, rocket cut-off 20 m/s";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        TestSingleplayerContext[] opened = new TestSingleplayerContext[1];
        List<String> logged = ClientKit.logged(() -> opened[0] = ctx.worldBuilder().adjustSettings(world -> {
            world.setGameMode(WorldCreationUiState.SelectedGameMode.SURVIVAL);
            world.setAllowCommands(false);
        }).create());
        try (TestSingleplayerContext world = opened[0]) {
            TestServerContext server = world.getServer();
            TestServerConnection connection = world.getConnection();
            Vec3 home = server.computeOnServer(s -> connection.getServerPlayer().position());
            try {
                check(logged.contains(START_LINE), () -> "the world's start logged " + logged + ", not \"" + START_LINE + "\"");
                check(!YoltElytra.hosted(), () -> "YOLT Elytra is hosted, but nothing here hosts it");
                check(server.computeOnServer(s -> connection.getServerPlayer().gameMode()) == GameType.SURVIVAL,
                        () -> "the owner isn't in survival, where the nerf counts");
                ClientKit.checkGlide(ctx, server, connection, home, "elytra-alone-sp-glide");
                ClientKit.checkRockets(ctx, server, connection, home, "elytra-alone-sp-cutoff");
            } finally {
                ClientKit.runAll(List.of(() -> ClientKit.takeBack(ctx, server, connection, home)));
            }
        }
    }
}
