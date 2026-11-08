package bobbynooby.dev.gametest.client;

import bobbynooby.dev.config.Settings;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;

public class JoinClientTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        ClientTests.withServer(ctx, (server, connection) -> {
            if (!server.computeOnServer(s -> Settings.active())) {
                throw new AssertionError("YOLT is not active on the dedicated server");
            }
            // The server sends every player unlisted, so a real client shows nobody, not even itself
            if (!ctx.computeOnClient(mc -> mc.getConnection().getListedOnlinePlayers().isEmpty())) {
                throw new AssertionError("the client lists players in the tab list");
            }

            // No counter prefix, so the CI artifact keeps the plain file names
            ctx.getInput().holdKey(options -> options.keyPlayerList);
            ctx.takeScreenshot(TestScreenshotOptions.of("01-join-tablist").disableCounterPrefix());
            ctx.getInput().releaseKey(options -> options.keyPlayerList);

            ctx.takeScreenshot(TestScreenshotOptions.of("01-join-world").disableCounterPrefix());
        });
    }
}
