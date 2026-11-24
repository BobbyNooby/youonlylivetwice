package bobbynooby.dev.elytra.client;

import bobbynooby.dev.elytra.ClientBridge;
import bobbynooby.dev.elytra.ElytraSettings;
import bobbynooby.dev.elytra.YoltElytra;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.util.List;

import static bobbynooby.dev.elytra.client.ClientKit.check;

// A real client on a server with only YOLT Elytra, with the settings as shipped, glides and fires rockets: what its own
// gravity and speed come to is in ClientKit.checkGlide and checkRockets
public class GlideAloneClientTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        // The client and its in-process server share one loader, so these are the mods the server runs
        FabricLoader loader = FabricLoader.getInstance();
        check(loader.isModLoaded(YoltElytra.MOD_ID), () -> "yolt-elytra isn't loaded");
        check(!loader.isModLoaded("you-only-live-twice"), () -> "you-only-live-twice is loaded");
        check(!loader.isModLoaded("yolt-hardcore"), () -> "yolt-hardcore is loaded");
        ClientKit.withServer(ctx, (server, connection) -> {
            Vec3 home = server.computeOnServer(s -> connection.getServerPlayer().position());
            // Last before the try, so nothing can fail between the swap and what puts it back
            Runnable putBack = server.computeOnServer(s -> ClientBridge.use(ElytraSettings.DEFAULTS));
            try {
                check(!YoltElytra.hosted(), () -> "YOLT Elytra is hosted, but nothing here hosts it");
                check(server.computeOnServer(s -> connection.getServerPlayer().gameMode()) == GameType.SURVIVAL,
                        () -> "the client's player isn't in survival, where the nerf counts");
                ClientKit.checkGlide(ctx, server, connection, home, "elytra-alone-glide");
                ClientKit.checkRockets(ctx, server, connection, home, "elytra-alone-cutoff");
            } finally {
                ClientKit.runAll(List.of(
                        () -> ClientKit.takeBack(ctx, server, connection, home),
                        () -> server.runOnServer(s -> putBack.run())));
            }
        });
    }
}
