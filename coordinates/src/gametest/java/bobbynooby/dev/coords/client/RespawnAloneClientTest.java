package bobbynooby.dev.coords.client;

import bobbynooby.dev.coords.ClientBridge;
import bobbynooby.dev.coords.Offset;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.List;

import static bobbynooby.dev.coords.client.ClientKit.check;

// A death and the death screen's own Respawn start a new stretch: the world spawn is moved first, so the respawn lands
// in another real chunk than the join, and the client's position there is the server's shifted by a new offset, in its
// chunk 0, 0 again, as its F3 screen shows
public class RespawnAloneClientTest implements FabricClientGameTest {
    // In another chunk than the join's spawn on both axes, and at another place inside its chunk, since only whole
    // chunks are shifted: the client is then shown other numbers than at the join, 13.5 and 11.5 against 8.5 and 0.5
    private static final int RESPAWN_X = 605;
    private static final int RESPAWN_Z = 2_411;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        ClientKit.withServer(ctx, (server, connection) -> {
            try {
                Offset joined = ClientKit.offset(server, connection);
                check(!joined.isZero(), () -> "the join's stretch has no shift");
                ClientKit.checkShifted(ctx, server, connection, joined, "after the join");

                BlockPos respawnAt = server.computeOnServer(s -> ClientKit.moveSpawn(s, RESPAWN_X, RESPAWN_Z));
                ClientKit.die(ctx, server, connection);
                // Nothing changes on the death screen: the stretch is the join's until the respawn
                check(ClientKit.offset(server, connection).equals(joined),
                        () -> "the death changed the shift from " + joined + " to " + ClientKit.offset(server, connection));
                ClientKit.respawn(ctx, connection);

                Offset respawned = ClientKit.offset(server, connection);
                check(!respawned.isZero() && !respawned.equals(joined),
                        () -> "the respawn's stretch has the shift " + respawned + ", the join's was " + joined);
                Vec3 real = server.computeOnServer(s -> connection.getServerPlayer().position());
                check(BlockPos.containing(real).equals(respawnAt),
                        () -> "the player respawned at the real " + real + ", not at the moved spawn " + respawnAt.toShortString());
                Vec3 shown = ClientKit.checkShifted(ctx, server, connection, respawned, "after the respawn");
                ClientKit.checkArrivalChunk(ctx, "at the respawn");
                // The client sends its moves in the new shift once it has accepted the respawn's teleport
                check(server.computeOnServer(s -> ClientBridge.incomingOffset(connection.getServerPlayer())).equals(respawned),
                        () -> "what the client sends after the respawn is read in "
                                + server.computeOnServer(s -> ClientBridge.incomingOffset(connection.getServerPlayer()))
                                + ", not the respawn's " + respawned);

                ClientKit.showF3(ctx);
                ClientKit.checkF3Position(ctx, shown, "after the respawn");
                ClientKit.screenshot(ctx, "coordinates-alone-respawn");
            } finally {
                ClientKit.runAll(List.of(() -> ClientKit.hideF3(ctx), () -> ClientKit.clearChat(ctx)));
            }
        });
    }
}
