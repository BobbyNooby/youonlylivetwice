package bobbynooby.dev.gametest;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.mixin.ServerCommonPacketListenerAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

// Joins with hide.coordinates on and reads back what the player's client was sent, for the coordinate offset tests
final class OffsetTestKit {
    private static final BlockPos SPOT = new BlockPos(1, 1, 1);

    private OffsetTestKit() {}

    // The switch is read as the frame starts, at the login, so it is on for the join only
    static ServerPlayer join(GameTestHelper helper, String name, GameType mode) {
        return withCoordinates(() -> TestPlayers.join(helper, name, mode, SPOT));
    }

    static TestPlayers.Recording joinRecording(GameTestHelper helper, String name, GameType mode) {
        return withCoordinates(() -> TestPlayers.joinRecording(helper, name, mode, SPOT));
    }

    // Puts the switch back in the same call, so a test sharing the server never sees it on
    static <T> T withCoordinates(Supplier<T> body) {
        YoltConfig.HideSection hide = Settings.get().hide;
        boolean before = hide.coordinates;
        hide.coordinates = true;
        try {
            return body.get();
        } finally {
            hide.coordinates = before;
        }
    }

    // As the player's client sends a typed command, which is all that is read in their frame
    static void type(ServerPlayer player, String command) {
        player.connection.handleChatCommand(new ServerboundChatCommandPacket(command));
    }

    // How many packets the player has been sent so far, to read only what comes after
    static int mark(ServerPlayer player) {
        return TestPlayers.sentPackets(player).size();
    }

    // At a fixed spot near the world's centre, as the test area is so far out that any spot near it clamps to the same
    // offset as every other: a frame anchored here differs from one anchored by the test
    static ServerPlayer joinNearTheCentre(GameTestHelper helper, String name, GameType mode, BlockPos spot) {
        return withCoordinates(() -> TestPlayers.join(helper.getLevel().getServer(), name, mode, spot));
    }

    // Through the server's own respawn with the switch on, at a spot of the test's, so no random spot is searched. From
    // the End it is the way back, which keeps everything.
    static ServerPlayer respawn(GameTestHelper helper, ServerPlayer player, BlockPos spot, boolean fromEnd) {
        player.setRespawnPosition(new ServerPlayer.RespawnConfig(
                LevelData.RespawnData.of(helper.getLevel().dimension(), spot, 0.0F, 0.0F), true), false);
        PlayerList players = helper.getLevel().getServer().getPlayerList();
        return withCoordinates(() -> fromEnd
                ? players.respawn(player, true, Entity.RemovalReason.CHANGED_DIMENSION)
                : players.respawn(player, false, Entity.RemovalReason.KILLED));
    }

    // What a real client does once it has joined: it says the world has loaded and accepts the join's teleport. Until it
    // accepts, the server refuses to place a block.
    static void settle(GameTestHelper helper, ServerPlayer player) {
        TestPlayers.markLoaded(player);
        // What each tick does first, so the accept's move is measured from where the player is
        player.connection.resetPosition();
        ClientboundPlayerPositionPacket teleport = last(helper, player, 0, ClientboundPlayerPositionPacket.class);
        Vec3 client = teleport.change().position();
        TestPlayers.receive(player,
                new ServerboundAcceptTeleportationPacket(teleport.id(), client.x, client.y, client.z, 0.0F, 0.0F));
    }

    // The connection holds the frames, and lives on across a respawn
    static Connection connection(ServerPlayer player) {
        return ((ServerCommonPacketListenerAccessor) player.connection).yoltConnection();
    }

    // Where the last packet of the type sent from packet "from" on is, to read what came before or after it
    static int lastIndex(GameTestHelper helper, ServerPlayer player, int from, Class<?> type) {
        List<Packet<?>> packets = TestPlayers.sentPackets(player);
        for (int i = packets.size() - 1; i >= from; i--) {
            if (type.isInstance(packets.get(i))) {
                return i;
            }
        }
        helper.fail("no " + type.getSimpleName() + " was sent");
        return -1;
    }

    // Every packet of the type sent from packet "from" on, oldest first, with a bundle's parts in their place after it
    static <T> List<T> sent(ServerPlayer player, int from, Class<T> type) {
        return sent(player, from, TestPlayers.sentPackets(player).size(), type);
    }

    // The same, up to packet "to"
    static <T> List<T> sent(ServerPlayer player, int from, int to, Class<T> type) {
        List<Packet<?>> packets = TestPlayers.sentPackets(player);
        List<T> found = new ArrayList<>();
        for (Packet<?> packet : packets.subList(from, to)) {
            if (type.isInstance(packet)) {
                found.add(type.cast(packet));
            }
            if (packet instanceof ClientboundBundlePacket bundle) {
                for (Packet<?> part : bundle.subPackets()) {
                    if (type.isInstance(part)) {
                        found.add(type.cast(part));
                    }
                }
            }
        }
        return found;
    }

    static <T> T first(GameTestHelper helper, ServerPlayer player, int from, Class<T> type) {
        List<T> found = sent(player, from, type);
        helper.assertFalse(found.isEmpty(), "no " + type.getSimpleName() + " was sent");
        return found.getFirst();
    }

    static <T> T last(GameTestHelper helper, ServerPlayer player, int from, Class<T> type) {
        List<T> found = sent(player, from, type);
        helper.assertFalse(found.isEmpty(), "no " + type.getSimpleName() + " was sent");
        return found.getLast();
    }
}
