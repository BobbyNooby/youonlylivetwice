package bobbynooby.dev.coords.client;

import bobbynooby.dev.coords.CoordinateOffsets;
import bobbynooby.dev.coords.Offset;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static bobbynooby.dev.coords.client.ClientKit.check;

/**
 * A second player, a test player with no client of their own, joins beside a real client on a server with only YOLT
 * Coordinates, a chunk east of the one the client's stretch started in, so their own amount isn't the client's. The
 * client sees them at their real spot shifted by the client's own amount; then they step two blocks north, a move the
 * server sends as a step from where they were, and then twenty more, too far for a step, so the server sends where they
 * now are; the client sees them at each new real spot, shifted by the same amount.
 */
public class SecondPlayerAloneClientTest implements FabricClientGameTest {
    // From the spawn at 1000, 2000 into the chunk east of it, which starts at 1008
    private static final int EAST = 12;
    private static final int STEP = 2;
    // Past the 8 blocks a step from where an entity was can carry
    private static final int JUMP = 20;
    // The shot aims this far above the second player's feet, at their chest
    private static final double CHEST = 1.2;
    private static final double TICKS_PER_SECOND = 20.0;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        ClientKit.withServer(ctx, (server, connection) -> {
            // Known before the join, so the clean-up finds the second player even if the join fails part-way
            UUID id = UUID.randomUUID();
            EmbeddedChannel[] channel = new EmbeddedChannel[1];
            try {
                Offset offset = ClientKit.offset(server, connection);
                check(!offset.isZero(), () -> "the join's stretch has no shift");
                // The client stands still, so its own stretch is settled before the second player comes
                ClientKit.checkShifted(ctx, server, connection, offset, "before the second player joins");
                BlockPos spot = server.computeOnServer(s -> connection.getServerPlayer().blockPosition().east(EAST));
                ServerPlayer other = server.computeOnServer(s -> join(s, id, spot, channel));
                Offset theirs = server.computeOnServer(s -> CoordinateOffsets.offset(other));
                check(!theirs.equals(offset), () -> "the second player's own shift " + theirs + " is the client's too");

                checkSeen(ctx, server, other, offset, theirs, "after they joined");
                moveNorth(server, other, STEP);
                checkSeen(ctx, server, other, offset, theirs, "after a step of " + STEP + " blocks");
                moveNorth(server, other, JUMP);
                checkSeen(ctx, server, other, offset, theirs, "after a move of " + JUMP + " blocks");

                showSecondPlayer(ctx, id);
            } finally {
                ClientKit.runAll(List.of(
                        () -> ClientKit.hideF3(ctx),
                        () -> server.runOnServer(s -> leave(s, id, channel[0])),
                        () -> ClientKit.waitOnClient(ctx, ClientKit.LONG_TIMEOUT, mc -> seen(mc, id).isEmpty(),
                                mc -> "the client still sees the second player after they left")));
            }
        });
    }

    // Where the client sees the second player is where the server has them, shifted by the client's own amount, not by
    // theirs and not left real. The client eases a player it sees to where it was told over a few ticks, so this waits.
    private static void checkSeen(ClientGameTestContext ctx, TestDedicatedServerContext server, ServerPlayer other,
                                  Offset offset, Offset theirs, String when) {
        Vec3 real = server.computeOnServer(s -> other.position());
        Vec3 expected = new Vec3(real.x + offset.x(), real.y, real.z + offset.z());
        ClientKit.waitOnClient(ctx, ClientKit.LONG_TIMEOUT, mc -> seen(mc, other.getUUID())
                        .map(player -> player.position().distanceTo(expected) < ClientKit.CLOSE).orElse(false),
                mc -> seen(mc, other.getUUID()).map(player -> "the client sees the second player at " + player.position()
                        + " " + when + ", not at the real " + real + " shifted by its own " + offset + " (shifted by theirs, "
                        + theirs + ", it would be " + new Vec3(real.x + theirs.x(), real.y, real.z + theirs.z()) + ")")
                        .orElse("the client doesn't see the second player " + when));
    }

    // The client turned to face the second player, with F3 open. The client works out again which sections are in view
    // over the frames after its camera turns, and draws an entity only in a section it draws, so the shot waits for the
    // second player's section and for its fade-in.
    private static void showSecondPlayer(ClientGameTestContext ctx, UUID id) {
        Vec3 shown = ctx.computeOnClient(mc -> seen(mc, id).orElseThrow().position());
        ClientKit.lookAt(ctx, shown.add(0.0, CHEST, 0.0));
        ClientKit.waitOnClient(ctx, ClientKit.LONG_TIMEOUT, mc -> seen(mc, id)
                        .map(player -> mc.levelRenderer.isSectionCompiledAndVisible(player.blockPosition(), 0L)).orElse(false),
                mc -> "the client never draws the section the second player stands in");
        int fadeTicks = ctx.computeOnClient(mc -> (int) Math.ceil(mc.options.chunkSectionFadeInTime().get() * TICKS_PER_SECOND));
        ctx.waitTicks(fadeTicks + 1);
        ClientKit.showF3(ctx);
        ClientKit.screenshot(ctx, "coordinates-alone-second-player");
    }

    // Moved on the server as a teleport moves a player, which the server then sends the players who see them
    private static void moveNorth(TestDedicatedServerContext server, ServerPlayer other, int blocks) {
        server.runOnServer(s -> {
            Vec3 at = other.position();
            other.teleportTo(other.level(), at.x, at.y, at.z - blocks, Set.of(), other.getYRot(), other.getXRot(), true);
        });
    }

    private static Optional<Player> seen(Minecraft mc, UUID id) {
        return mc.level.players().stream().filter(player -> player.getUUID().equals(id)).findFirst().map(Player.class::cast);
    }

    // A test player who joins through PlayerList.placeNewPlayer, as a client's login places one, at this real spot, so
    // a stretch starts for them as for a client; the far end of their connection is a channel the test holds. The same
    // steps as the mod's own TestPlayers, which only reach a GameTest's world.
    private static ServerPlayer join(MinecraftServer server, UUID id, BlockPos spot, EmbeddedChannel[] channel) {
        GameProfile profile = new GameProfile(id, "SecondPlayer");
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
        ServerPlayer player = new ServerPlayer(server, server.overworld(), profile, cookie.clientInformation());
        player.snapTo(spot, 0.0F, 0.0F);
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        channel[0] = new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        // setGameMode sends a packet, so it waits for the connection placeNewPlayer sets up
        player.setGameMode(GameType.SURVIVAL);
        return player;
    }

    // The second player goes, found by id, as far as their join got: off the player list if they reached it, and the
    // test's end of their connection closed if it was made. Closing it, as a real client's going does, ends Fabric's
    // networking session for the connection.
    private static void leave(MinecraftServer server, UUID id, EmbeddedChannel channel) {
        ServerPlayer player = server.getPlayerList().getPlayer(id);
        if (player != null) {
            server.getPlayerList().remove(player);
        }
        if (channel != null) {
            channel.close();
        }
    }
}
