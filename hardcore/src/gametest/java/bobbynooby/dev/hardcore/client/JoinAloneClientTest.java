package bobbynooby.dev.hardcore.client;

import bobbynooby.dev.hardcore.ClientBridge;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;

import java.util.List;

import static bobbynooby.dev.hardcore.client.ClientKit.check;

// A real client joins a server with only YOLT Hardcore, and the report a typed /status sends reaches its chat. The
// settings in use are the defaults, so the report is the one a new player on a server as shipped gets.
public class JoinAloneClientTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        // The client and its in-process server share one loader, so these are the mods the server runs
        FabricLoader loader = FabricLoader.getInstance();
        check(loader.isModLoaded("yolt-hardcore"), () -> "yolt-hardcore isn't loaded");
        check(!loader.isModLoaded("you-only-live-twice"), () -> "you-only-live-twice is loaded");
        for (String tinyMod : List.of("yolt-anvils", "yolt-mending", "yolt-elytra")) {
            check(loader.isModLoaded(tinyMod), () -> tinyMod + " isn't loaded");
        }
        ClientKit.withServer(ctx, (server, connection) -> {
            check(server.computeOnServer(s -> ClientBridge.active()), () -> "YOLT Hardcore isn't active on the dedicated server");
            Runnable putBack = server.computeOnServer(s -> ClientBridge.use(c -> {}));
            try {
                check(ctx.computeOnClient(mc -> ClientKit.clientHasCommand(mc, "status")),
                        () -> "the client's command tree has no /status");
                ClientKit.clearChat(ctx);
                int mark = ClientKit.mark();
                ClientKit.type(ctx, connection, "/status");
                ClientKit.waitOnClient(ctx, ClientKit.LONG_TIMEOUT,
                        mc -> ClientKit.chat(mark).size() >= ClientKit.STATUS_REPORT.size(),
                        mc -> "the client's chat showed " + ClientKit.chat(mark) + " after /status");
                List<String> shown = ClientKit.chat(mark);
                check(shown.equals(ClientKit.STATUS_REPORT),
                        () -> "the client's chat showed " + shown + " for /status, not " + ClientKit.STATUS_REPORT);
                ClientKit.screenshotWithChat(ctx, "hardcore-alone-status");
            } finally {
                ClientKit.runAll(List.of(
                        () -> server.runOnServer(s -> putBack.run()),
                        () -> ClientKit.clearChat(ctx)));
            }
        });
    }
}
