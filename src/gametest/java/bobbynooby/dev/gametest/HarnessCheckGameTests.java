package bobbynooby.dev.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

import java.util.List;

public class HarnessCheckGameTests {
    @GameTest
    public void deliberatelyFails(GameTestHelper helper) {
        helper.fail("deliberate failure to check that a failing gametest fails the build");
    }

    @GameTest(environment = "you-only-live-twice-gametest:solo")
    public void soloBatchRunsAndMixinTargetsLoad(GameTestHelper helper) {
        // Loading each target applies its mixin, so a wrong target fails here
        List<String> targets = List.of(
                "net.minecraft.commands.Commands",
                "net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket",
                "net.minecraft.network.protocol.status.ServerStatus$Players",
                "net.minecraft.server.level.PlayerSpawnFinder",
                "net.minecraft.server.level.ServerPlayer",
                "net.minecraft.server.players.PlayerList",
                "net.minecraft.server.players.SleepStatus",
                "net.minecraft.world.level.dimension.DimensionType");
        for (String target : targets) {
            try {
                Class.forName(target);
            } catch (Throwable e) {
                helper.fail("could not load " + target + ": " + e);
            }
        }
        helper.succeed();
    }
}
