package bobbynooby.dev.gametest;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

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

    // How many packets the player has been sent so far, to read only what comes after
    static int mark(ServerPlayer player) {
        return TestPlayers.sentPackets(player).size();
    }

    // Every packet of the type sent from packet "from" on, oldest first, with a bundle's parts in their place after it
    static <T> List<T> sent(ServerPlayer player, int from, Class<T> type) {
        List<Packet<?>> packets = TestPlayers.sentPackets(player);
        List<T> found = new ArrayList<>();
        for (Packet<?> packet : packets.subList(from, packets.size())) {
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

    static <T> T last(GameTestHelper helper, ServerPlayer player, int from, Class<T> type) {
        List<T> found = sent(player, from, type);
        helper.assertFalse(found.isEmpty(), "no " + type.getSimpleName() + " was sent");
        return found.getLast();
    }
}
