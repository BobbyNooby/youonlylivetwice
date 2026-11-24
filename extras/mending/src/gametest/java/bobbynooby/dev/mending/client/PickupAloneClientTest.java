package bobbynooby.dev.mending.client;

import bobbynooby.dev.mending.ClientBridge;
import bobbynooby.dev.mending.MendingSettings;
import bobbynooby.dev.mending.YoltMending;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.GameType;

import java.util.List;

import static bobbynooby.dev.mending.client.ClientKit.check;

// A real client on a server with only YOLT Mending, with the rate as shipped, picks up XP with a Mending pickaxe in
// hand: what the pickaxe and the XP bar come to is in ClientKit.checkPickups
public class PickupAloneClientTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        // The client and its in-process server share one loader, so these are the mods the server runs
        FabricLoader loader = FabricLoader.getInstance();
        check(loader.isModLoaded(YoltMending.MOD_ID), () -> "yolt-mending isn't loaded");
        check(!loader.isModLoaded("you-only-live-twice"), () -> "you-only-live-twice is loaded");
        check(!loader.isModLoaded("yolt-hardcore"), () -> "yolt-hardcore is loaded");
        ClientKit.withServer(ctx, (server, connection) -> {
            // Last before the try, so nothing can fail between the swap and what puts it back
            Runnable putBack = server.computeOnServer(s -> ClientBridge.use(MendingSettings.DEFAULTS));
            try {
                check(!YoltMending.hosted(), () -> "YOLT Mending is hosted, but nothing here hosts it");
                check(server.computeOnServer(s -> connection.getServerPlayer().gameMode()) == GameType.SURVIVAL,
                        () -> "the client's player isn't in survival, where the nerf counts");
                ClientKit.checkPickups(ctx, server, connection, "mending-alone");
            } finally {
                // The part of a point the mod keeps goes when the player leaves, as the server stops
                ClientKit.runAll(List.of(
                        () -> server.runOnServer(s -> ClientKit.takeBack(connection.getServerPlayer())),
                        () -> server.runOnServer(s -> putBack.run())));
            }
        });
    }
}
