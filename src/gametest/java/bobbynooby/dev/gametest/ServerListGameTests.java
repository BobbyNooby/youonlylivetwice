package bobbynooby.dev.gametest;

import bobbynooby.dev.config.Settings;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.server.players.NameAndId;

import java.util.List;
import java.util.UUID;

public class ServerListGameTests {
    // The switch is flipped and restored inside one tick, so no other test sees it
    @GameTest
    public void statusHidesPlayerCount(GameTestHelper helper) {
        ServerStatus.Players players = new ServerStatus.Players(20, 5, List.of(new NameAndId(UUID.randomUUID(), "Listed")));
        boolean before = Settings.get().hide.serverPlayerCount;
        try {
            Settings.get().hide.serverPlayerCount = true;
            helper.assertValueEqual(players.max(), 1, "max players with the count hidden");
            helper.assertValueEqual(players.online(), 0, "online players with the count hidden");
            helper.assertTrue(players.sample().isEmpty(), "player sample is not empty with the count hidden");

            Settings.get().hide.serverPlayerCount = false;
            helper.assertValueEqual(players.max(), 20, "max players with the count shown");
            helper.assertValueEqual(players.online(), 5, "online players with the count shown");
            helper.assertValueEqual(players.sample().size(), 1, "player sample size with the count shown");
        } finally {
            Settings.get().hide.serverPlayerCount = before;
        }
        helper.succeed();
    }
}
