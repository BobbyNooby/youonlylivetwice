package bobbynooby.dev.hardcore.client;

import bobbynooby.dev.hardcore.ClientBridge;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;

import java.util.List;

import static bobbynooby.dev.hardcore.client.ClientKit.check;

// /yolt-hardcore typed by the same player before and after they are made an op: the server leaves it out of a non-op's
// command tree and answers it as an unknown command, and once they are an op it is in their tree and answers
public class CommandAloneClientTest implements FabricClientGameTest {
    private static final String COMMAND = "yolt-hardcore";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        ClientKit.withServer(ctx, (server, connection) -> {
            Runnable putBack = server.computeOnServer(s -> ClientBridge.use(c -> {}));
            try {
                showRefused(ctx, server, connection);
                showAnswered(ctx, server, connection);
            } finally {
                ClientKit.runAll(List.of(
                        () -> server.runOnServer(s -> ClientBridge.deop(connection.getServerPlayer())),
                        () -> server.runOnServer(s -> putBack.run()),
                        () -> ClientKit.clearChat(ctx)));
            }
        });
    }

    private static void showRefused(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                    TestDedicatedServerConnection connection) {
        check(!server.computeOnServer(s -> s.getPlayerList().isOp(connection.getServerPlayer().nameAndId())),
                () -> "the client's player is an op before the test made it one");
        check(ctx.computeOnClient(mc -> ClientKit.clientHasCommand(mc, "status")), () -> "the client's command tree has no /status");
        check(!ctx.computeOnClient(mc -> ClientKit.clientHasCommand(mc, COMMAND)),
                () -> "a non-op's command tree has /" + COMMAND);
        ClientKit.clearChat(ctx);
        int mark = ClientKit.mark();
        ClientKit.type(ctx, connection, "/" + COMMAND);
        ClientKit.waitForChat(ctx, mark, ClientKit.unknownCommand());
        List<String> shown = ClientKit.chat(mark);
        check(!shown.contains(ClientKit.FEATURES), () -> "a non-op was told " + shown);
        ClientKit.screenshotWithChat(ctx, "hardcore-alone-refused");
    }

    private static void showAnswered(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                     TestDedicatedServerConnection connection) {
        server.runOnServer(s -> ClientBridge.op(connection.getServerPlayer()));
        // Making an op sends the player their new command tree
        ClientKit.waitOnClient(ctx, ClientKit.LONG_TIMEOUT, mc -> ClientKit.clientHasCommand(mc, COMMAND),
                mc -> "an op's command tree has no /" + COMMAND);
        ClientKit.clearChat(ctx);
        int mark = ClientKit.mark();
        ClientKit.type(ctx, connection, "/" + COMMAND);
        ClientKit.waitForChat(ctx, mark, ClientKit.FEATURES);
        List<String> shown = ClientKit.chat(mark);
        check(!shown.contains(ClientKit.unknownCommand()), () -> "an op was told " + shown);
        ClientKit.screenshotWithChat(ctx, "hardcore-alone-op");
    }
}
