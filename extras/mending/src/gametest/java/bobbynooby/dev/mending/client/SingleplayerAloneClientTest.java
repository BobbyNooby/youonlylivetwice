package bobbynooby.dev.mending.client;

import bobbynooby.dev.mending.YoltMending;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.world.level.GameType;

import java.util.List;

import static bobbynooby.dev.mending.client.ClientKit.check;

/**
 * The mod in a client's own single-player world, as a player who put the jar in their mods folder meets it. The world's
 * start reads the client's config/yolt-mending.json, which the run's first server start wrote with the defaults, Loom
 * having emptied the run folder before the run. In a survival world the same pickups as on a server come to the same
 * pickaxe and XP bar.
 */
public class SingleplayerAloneClientTest implements FabricClientGameTest {
    private static final String START_LINE = "YOLT Mending on: 0.4 durability per XP point";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        TestSingleplayerContext[] opened = new TestSingleplayerContext[1];
        List<String> logged = ClientKit.logged(() -> opened[0] = ctx.worldBuilder().adjustSettings(world -> {
            world.setGameMode(WorldCreationUiState.SelectedGameMode.SURVIVAL);
            world.setAllowCommands(false);
        }).create());
        try (TestSingleplayerContext world = opened[0]) {
            check(logged.contains(START_LINE), () -> "the world's start logged " + logged + ", not \"" + START_LINE + "\"");
            check(!YoltMending.hosted(), () -> "YOLT Mending is hosted, but nothing here hosts it");
            check(world.getServer().computeOnServer(s -> world.getConnection().getServerPlayer().gameMode()) == GameType.SURVIVAL,
                    () -> "the owner isn't in survival, where the nerf counts");
            ClientKit.checkPickups(ctx, world.getServer(), world.getConnection(), "mending-alone-sp");
        }
    }
}
