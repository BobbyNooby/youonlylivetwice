package bobbynooby.dev.gametest;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.world.WorldBorders;
import bobbynooby.dev.world.GameRuleSwitches;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.SleepStatus;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;

import java.util.HashMap;
import java.util.Map;

// Each test restores what it flips in a finally block, in the same tick, so other tests never see it
public class SwitchGameTests {
    @GameTest
    public void nightSkipBlockedWhenOn(GameTestHelper helper) {
        YoltConfig.SleepSection sleep = Settings.get().sleep;
        boolean before = sleep.noNightSkip;
        sleep.noNightSkip = true;
        try {
            helper.assertValueEqual(new SleepStatus().sleepersNeeded(100), 727, "sleepers needed");
        } finally {
            sleep.noNightSkip = before;
        }
        helper.succeed();
    }

    // Vanilla never needs fewer than 1 sleeper, even with nobody online
    @GameTest
    public void nightSkipVanillaWhenOff(GameTestHelper helper) {
        YoltConfig.SleepSection sleep = Settings.get().sleep;
        boolean before = sleep.noNightSkip;
        sleep.noNightSkip = false;
        try {
            helper.assertValueEqual(new SleepStatus().sleepersNeeded(100), 1, "sleepers needed");
        } finally {
            sleep.noNightSkip = before;
        }
        helper.succeed();
    }

    @GameTest
    public void netherScaleOneWhenOn(GameTestHelper helper) {
        YoltConfig config = Settings.get();
        boolean before = config.netherScaleOneToOne;
        config.netherScaleOneToOne = true;
        try {
            helper.assertValueEqual(nether(helper).dimensionType().coordinateScale(), 1.0, "nether coordinate scale");
        } finally {
            config.netherScaleOneToOne = before;
        }
        helper.succeed();
    }

    @GameTest
    public void netherScaleVanillaWhenOff(GameTestHelper helper) {
        YoltConfig config = Settings.get();
        boolean before = config.netherScaleOneToOne;
        config.netherScaleOneToOne = false;
        try {
            helper.assertValueEqual(nether(helper).dimensionType().coordinateScale(), 8.0, "nether coordinate scale");
        } finally {
            config.netherScaleOneToOne = before;
        }
        helper.succeed();
    }

    // The test server keeps YOLT's border off, so this turns it on just for the call
    @GameTest
    public void borderAppliedFromConfig(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.WorldBorderSection border = Settings.get().worldBorder;
        boolean enabledBefore = border.enabled;
        int diameterBefore = border.diameter;
        Map<ServerLevel, Double> sizesBefore = new HashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            sizesBefore.put(level, level.getWorldBorder().getSize());
        }

        border.enabled = true;
        border.diameter = 4000;
        try {
            WorldBorders.apply(server);
            helper.assertValueEqual(server.overworld().getWorldBorder().getSize(), 4000.0, "overworld border size");
            helper.assertValueEqual(nether(helper).getWorldBorder().getSize(), 4000.0, "nether border size");
            helper.assertValueEqual(level(helper, Level.END).getWorldBorder().getSize(), 4000 * border.endScale, "end border size");
        } finally {
            border.enabled = enabledBefore;
            border.diameter = diameterBefore;
            sizesBefore.forEach((level, size) -> level.getWorldBorder().setSize(size));
        }
        helper.succeed();
    }

    // Each case starts with the rule on, as in a new world
    @GameTest
    public void locatorBarOffOnlyWithSwitch(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        GameRules rules = server.getGameRules();
        YoltConfig.HideSection hide = Settings.get().hide;
        boolean switchBefore = hide.locatorBar;
        boolean ruleBefore = rules.get(GameRules.LOCATOR_BAR);
        try {
            rules.set(GameRules.LOCATOR_BAR, true, server);
            hide.locatorBar = true;
            GameRuleSwitches.applyLocatorBar(server);
            helper.assertFalse(rules.get(GameRules.LOCATOR_BAR), "switch on: the locator bar rule is still on");

            rules.set(GameRules.LOCATOR_BAR, true, server);
            hide.locatorBar = false;
            GameRuleSwitches.applyLocatorBar(server);
            helper.assertTrue(rules.get(GameRules.LOCATOR_BAR), "switch off: the locator bar rule was changed");
        } finally {
            hide.locatorBar = switchBefore;
            rules.set(GameRules.LOCATOR_BAR, ruleBefore, server);
        }
        helper.succeed();
    }

    @GameTest
    public void activeOnTestServer(GameTestHelper helper) {
        helper.assertTrue(Settings.active(), "YOLT is not active on the test server");
        helper.succeed();
    }

    private static ServerLevel nether(GameTestHelper helper) {
        return level(helper, Level.NETHER);
    }

    private static ServerLevel level(GameTestHelper helper, ResourceKey<Level> dimension) {
        ServerLevel level = helper.getLevel().getServer().getLevel(dimension);
        helper.assertTrue(level != null, "test server has no level " + dimension);
        return level;
    }
}
