package bobbynooby.dev.anvils.client;

import bobbynooby.dev.anvils.YoltAnvils;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;

import java.util.List;

import static bobbynooby.dev.anvils.client.ClientKit.check;

// A real client joins a server with only YOLT Anvils, as a player who isn't an op. The server has /yolt-anvils, but
// leaves it out of the player's command tree and answers it typed as a command that doesn't exist.
public class CommandAloneClientTest implements FabricClientGameTest {
    private static final String COMMAND = "yolt-anvils";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        // The client and its in-process server share one loader, so these are the mods the server runs
        FabricLoader loader = FabricLoader.getInstance();
        check(loader.isModLoaded(YoltAnvils.MOD_ID), () -> "yolt-anvils isn't loaded");
        check(!loader.isModLoaded("you-only-live-twice"), () -> "you-only-live-twice is loaded");
        check(!loader.isModLoaded("yolt-hardcore"), () -> "yolt-hardcore is loaded");
        ClientKit.withServer(ctx, (server, connection) -> {
            try {
                check(!YoltAnvils.hosted(), () -> "YOLT Anvils is hosted, but nothing here hosts it");
                check(server.computeOnServer(s -> ClientKit.serverHasCommand(s, COMMAND)),
                        () -> "the server has no /" + COMMAND);
                check(!server.computeOnServer(s -> s.getPlayerList().isOp(connection.getServerPlayer().nameAndId())),
                        () -> "the client's player is an op");
                check(!ctx.computeOnClient(mc -> ClientKit.clientHasCommand(mc, COMMAND)),
                        () -> "a non-op's command tree has /" + COMMAND);
                ClientKit.clearChat(ctx);
                int mark = ClientKit.mark();
                ClientKit.type(ctx, connection, "/" + COMMAND);
                ClientKit.waitForChat(ctx, mark, ClientKit.unknownCommand());
                List<String> shown = ClientKit.chat(mark);
                check(!shown.contains(ClientKit.PRICES), () -> "a non-op was told " + shown);
                ClientKit.screenshotWithChat(ctx, "anvils-alone-refused");
            } finally {
                ClientKit.runAll(List.of(() -> ClientKit.clearChat(ctx)));
            }
        });
    }
}
