package bobbynooby.dev.gametest;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import net.minecraft.world.attribute.BedRule;
import net.minecraft.world.clock.ClockTimeMarkers;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

public class PhantomGameTests {
    // Phantoms stay vanilla, so our sleep changes must not stop a bed from resetting the rest timer
    @GameTest(skyAccess = true)
    public void enteringBedResetsTimeSinceRest(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerClockManager clocks = level.getServer().clockManager();
        Holder<WorldClock> clock = level.dimensionType().defaultClock().orElseThrow();
        long savedTicks = clocks.getInstance(clock).totalTicks();

        BlockPos foot = new BlockPos(1, 1, 1);
        BlockPos head = foot.relative(Direction.SOUTH);
        BlockState bed = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH);
        helper.setBlock(foot, bed.setValue(BedBlock.PART, BedPart.FOOT));
        helper.setBlock(head, bed.setValue(BedBlock.PART, BedPart.HEAD));

        ServerPlayer player = TestPlayers.join(helper, "Sleeper", GameType.SURVIVAL, new BlockPos(1, 1, 0));
        try {
            // Beds only work in the dark, and the sky brightness is cached until the next level tick
            clocks.moveToTimeMarker(clock, ClockTimeMarkers.MIDNIGHT);
            level.updateSkyBrightness();
            helper.assertTrue(level.isDarkOutside(), "it is not dark after moving the clock to midnight");

            Stat<?> timeSinceRest = Stats.CUSTOM.get(Stats.TIME_SINCE_REST);
            player.getStats().setValue(player, timeSinceRest, 80000);
            helper.assertValueEqual(player.getStats().getValue(timeSinceRest), 80000, "time since rest before sleeping");

            boolean slept = player.startSleepInBed((BedBlock) Blocks.RED_BED, level.getBlockState(helper.absolutePos(head)),
                    BedRule.CAN_SLEEP_WHEN_DARK, helper.absolutePos(head)).right().isPresent();
            helper.assertTrue(slept, "the player could not get into the bed");
            helper.assertTrue(player.isSleeping(), "the player is not sleeping");
            helper.assertValueEqual(player.getStats().getValue(timeSinceRest), 0, "time since rest after getting into bed");

            YoltConfig.SleepSection sleep = Settings.get().sleep;
            helper.assertTrue(sleep.hideSleepers && sleep.noNightSkip, "the sleep switches are not both on");
            helper.assertTrue(level.isDarkOutside(), "the night was skipped");
        } finally {
            clocks.setTotalTicks(clock, savedTicks);
            level.updateSkyBrightness();
            TestPlayers.leave(player);
        }
        helper.succeed();
    }
}
