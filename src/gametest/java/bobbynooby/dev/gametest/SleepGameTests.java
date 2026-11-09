package bobbynooby.dev.gametest;

import bobbynooby.dev.config.Settings;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelData;

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
}
