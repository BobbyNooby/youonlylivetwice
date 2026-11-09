package bobbynooby.dev.gametest;

import bobbynooby.dev.config.Settings;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.SleepStatus;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelData;

import java.util.List;

public class SleepGameTests {
    // The switch is flipped and restored inside one tick, so no other test sees it
    @GameTest
    public void noBedRespawnHidesRespawnConfig(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "Bed", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        boolean before = Settings.get().sleep.noBedRespawn;
        try {
            LevelData.RespawnData data = new LevelData.RespawnData(GlobalPos.of(Level.OVERWORLD, helper.absolutePos(new BlockPos(2, 1, 2))), 0.0F, 0.0F);
            player.setRespawnPosition(new ServerPlayer.RespawnConfig(data, false), false);

            Settings.get().sleep.noBedRespawn = true;
            helper.assertTrue(player.getRespawnConfig() == null, "respawn config still visible with no-bed-respawn on");

            Settings.get().sleep.noBedRespawn = false;
            helper.assertTrue(player.getRespawnConfig() != null, "respawn config lost with no-bed-respawn off");
        } finally {
            Settings.get().sleep.noBedRespawn = before;
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // Same one-tick flip as above. The status is fed directly, so no clock or bed is involved.
    @GameTest
    public void hideSleepersReportsNoSleepers(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "Sleepy", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        boolean before = Settings.get().sleep.hideSleepers;
        try {
            player.startSleeping(helper.absolutePos(new BlockPos(1, 1, 1)));
            helper.assertTrue(player.isSleeping(), "the test player is not sleeping");
            SleepStatus status = new SleepStatus();
            status.update(List.of(player));

            Settings.get().sleep.hideSleepers = true;
            helper.assertValueEqual(status.amountSleeping(), 0, "sleepers counted with hide sleepers on");

            Settings.get().sleep.hideSleepers = false;
            helper.assertValueEqual(status.amountSleeping(), 1, "sleepers counted with hide sleepers off");
        } finally {
            Settings.get().sleep.hideSleepers = before;
            player.stopSleeping();
            TestPlayers.leave(player);
        }
        helper.succeed();
    }
}
