package bobbynooby.dev.coords.client;

import bobbynooby.dev.coords.ClientBridge;
import bobbynooby.dev.coords.Offset;
import bobbynooby.dev.coords.YoltCoordinates;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.world.phys.Vec3;

import java.util.List;

import static bobbynooby.dev.coords.client.ClientKit.check;

// A real client joins a server with only YOLT Coordinates, at a world spawn far from the real chunk at 0, 0. Its own
// position is the server's shifted by the join's offset, which isn't zero, it stands in its chunk 0, 0, and the full F3
// screen shows those shifted numbers, not the real ones.
public class JoinAloneClientTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        // The client and its in-process server share one loader, so these are the mods the server runs
        FabricLoader loader = FabricLoader.getInstance();
        check(loader.isModLoaded(YoltCoordinates.MOD_ID), () -> "yolt-coordinates isn't loaded");
        check(!loader.isModLoaded("you-only-live-twice"), () -> "you-only-live-twice is loaded");
        check(!loader.isModLoaded("yolt-hardcore"), () -> "yolt-hardcore is loaded");
        ClientKit.withServer(ctx, (server, connection) -> {
            try {
                check(server.computeOnServer(s -> ClientBridge.active()),
                        () -> "YOLT Coordinates doesn't run on the dedicated server");
                check(!YoltCoordinates.hosted(), () -> "YOLT Coordinates is hosted, but nothing here hosts it");
                Offset offset = ClientKit.offset(server, connection);
                check(!offset.isZero(), () -> "the join's stretch has no shift");
                Vec3 shown = ClientKit.checkShifted(ctx, server, connection, offset, "after the join");
                Vec3 real = server.computeOnServer(s -> connection.getServerPlayer().position());
                ClientKit.checkArrivalChunk(ctx, "at the join");

                ClientKit.showF3(ctx);
                ClientKit.checkF3Position(ctx, shown, "after the join");
                List<String> realLines = ClientKit.positionLines(real);
                List<String> f3 = ctx.computeOnClient(mc -> ClientKit.f3Lines(mc, DebugScreenEntries.PLAYER_POSITION));
                check(f3.stream().noneMatch(realLines::contains),
                        () -> "the F3 screen shows a real line, of " + realLines + ": " + f3);
                ClientKit.screenshot(ctx, "coordinates-alone-f3");
            } finally {
                ClientKit.runAll(List.of(() -> ClientKit.hideF3(ctx)));
            }
        });
    }
}
