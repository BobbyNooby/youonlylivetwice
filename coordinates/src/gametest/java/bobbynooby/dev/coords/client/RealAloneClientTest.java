package bobbynooby.dev.coords.client;

import bobbynooby.dev.coords.ClientBridge;
import bobbynooby.dev.coords.Offset;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.world.phys.Vec3;

import java.util.List;

import static bobbynooby.dev.coords.client.ClientKit.check;

/**
 * /yolt-coordinates real as an op types it. Before they are made an op the client has no /yolt-coordinates; once they
 * are, /yolt-coordinates says the offset is on, and /yolt-coordinates real says real coordinates come at the next
 * join, respawn or dimension change. The client stays shifted until it dies and respawns, and then its position is the
 * server's own, far from 0, 0, as its F3 screen shows. Typed again, it is shifted again from the respawn after that.
 */
public class RealAloneClientTest implements FabricClientGameTest {
    private static final String COMMAND = "yolt-coordinates";
    private static final String OFFSET_ON = "Coordinate offset on: each player's X and Z are shifted";
    private static final String REAL_ON = "You see real coordinates from your next join, respawn or dimension change.";
    private static final String REAL_OFF = "You see shifted coordinates again from your next join, respawn or dimension change.";
    // Real numbers at the moved spawn are this far from 0 at least, where shifted ones are within a chunk of it
    private static final double FAR = 500.0;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        ClientKit.withServer(ctx, (server, connection) -> {
            try {
                Offset joined = ClientKit.offset(server, connection);
                check(!joined.isZero(), () -> "the join's stretch has no shift");
                ClientKit.checkShifted(ctx, server, connection, joined, "after the join");
                makeOp(ctx, server, connection);

                ClientKit.clearChat(ctx);
                int mark = ClientKit.mark();
                ClientKit.type(ctx, connection, "/" + COMMAND);
                ClientKit.waitForChat(ctx, mark, OFFSET_ON);
                ClientKit.type(ctx, connection, "/" + COMMAND + " real");
                ClientKit.waitForChat(ctx, mark, REAL_ON);
                check(server.computeOnServer(s -> ClientBridge.seesReal(connection.getServerPlayer())),
                        () -> "/" + COMMAND + " real gave the op no real coordinates");
                // Not before the next stretch
                check(ClientKit.offset(server, connection).equals(joined),
                        () -> "the command changed the shift at once, from " + joined + " to " + ClientKit.offset(server, connection));
                ClientKit.checkShifted(ctx, server, connection, joined, "after /" + COMMAND + " real");

                ClientKit.die(ctx, server, connection);
                ClientKit.respawn(ctx, connection);
                Offset real = ClientKit.offset(server, connection);
                check(real.isZero(), () -> "the op respawned with the shift " + real + " after /" + COMMAND + " real");
                Vec3 shown = ClientKit.checkShifted(ctx, server, connection, Offset.ZERO, "after the respawn with real coordinates");
                check(Math.abs(shown.x) > FAR && Math.abs(shown.z) > FAR,
                        () -> "the op's client is at " + shown + ", not at the real numbers of the moved spawn");
                ClientKit.showF3(ctx);
                ClientKit.checkF3Position(ctx, shown, "with real coordinates");
                ClientKit.screenshotWithChat(ctx, "coordinates-alone-real");
                ClientKit.hideF3(ctx);

                ClientKit.clearChat(ctx);
                int again = ClientKit.mark();
                ClientKit.type(ctx, connection, "/" + COMMAND + " real");
                ClientKit.waitForChat(ctx, again, REAL_OFF);
                check(!server.computeOnServer(s -> ClientBridge.seesReal(connection.getServerPlayer())),
                        () -> "/" + COMMAND + " real typed again left real coordinates on");
                check(ClientKit.offset(server, connection).isZero(),
                        () -> "typed again, the command shifted the op at once, by " + ClientKit.offset(server, connection));
                ClientKit.checkShifted(ctx, server, connection, Offset.ZERO, "after /" + COMMAND + " real typed again");

                ClientKit.die(ctx, server, connection);
                ClientKit.respawn(ctx, connection);
                Offset shiftedAgain = ClientKit.offset(server, connection);
                check(!shiftedAgain.isZero(), () -> "the op respawned with no shift after turning real coordinates off");
                Vec3 shownAgain = ClientKit.checkShifted(ctx, server, connection, shiftedAgain, "after the next respawn");
                ClientKit.checkArrivalChunk(ctx, "at the next respawn");
                ClientKit.showF3(ctx);
                ClientKit.checkF3Position(ctx, shownAgain, "shifted again");
                ClientKit.screenshotWithChat(ctx, "coordinates-alone-shifted-again");
            } finally {
                ClientKit.runAll(List.of(
                        () -> ClientKit.hideF3(ctx),
                        // The op list is a file in the client's run folder, which every later server reads
                        () -> server.runOnServer(s -> ClientKit.deop(connection.getServerPlayer())),
                        () -> server.runOnServer(s -> ClientBridge.forgetReal(connection.getServerPlayer())),
                        () -> ClientKit.clearChat(ctx)));
            }
        });
    }

    // The test server's player isn't an op, and its client has no /yolt-coordinates until it is made one
    private static void makeOp(ClientGameTestContext ctx, TestDedicatedServerContext server,
                               TestDedicatedServerConnection connection) {
        check(!server.computeOnServer(s -> s.getPlayerList().isOp(connection.getServerPlayer().nameAndId())),
                () -> "the client's player is an op before the test made it one");
        check(!ctx.computeOnClient(mc -> ClientKit.clientHasCommand(mc, COMMAND)),
                () -> "a non-op's command tree has /" + COMMAND);
        server.runOnServer(s -> ClientKit.op(connection.getServerPlayer()));
        // Making an op sends the player their new command tree
        ClientKit.waitOnClient(ctx, ClientKit.LONG_TIMEOUT, mc -> ClientKit.clientHasCommand(mc, COMMAND),
                mc -> "an op's command tree has no /" + COMMAND);
    }
}
