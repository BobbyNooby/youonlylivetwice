package bobbynooby.dev.gametest.client;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;

import java.util.function.BiConsumer;

public final class ClientTests {
    private ClientTests() {
    }

    // The server runs inside the client process, but it is a real dedicated server, so YOLT is active on it
    public static void withServer(ClientGameTestContext ctx, BiConsumer<TestDedicatedServerContext, TestDedicatedServerConnection> body) {
        try (TestDedicatedServerContext server = ctx.worldBuilder().createServer();
             TestDedicatedServerConnection connection = server.connect()) {
            connection.waitForChunksRender();
            // Join toasts (offline chat warning, social tutorial) would cover the top right of every screenshot
            ctx.runOnClient(mc -> mc.gui.toastManager().clear());
            body.accept(server, connection);
        }
    }
}
