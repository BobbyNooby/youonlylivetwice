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
import net.minecraft.world.level.border.BorderStatus;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

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

    // A timed /worldborder shrink must survive a restart or reload. The border is turned on for the call, since apply
    // does nothing while it's off.
    @GameTest
    public void movingBorderLeftAlone(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.WorldBorderSection border = Settings.get().worldBorder;
        boolean enabledBefore = border.enabled;
        Map<ServerLevel, Double> sizesBefore = new HashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            sizesBefore.put(level, level.getWorldBorder().getSize());
        }
        WorldBorder overworld = server.overworld().getWorldBorder();

        border.enabled = true;
        try {
            double size = overworld.getSize();
            overworld.lerpSizeBetween(size, size - 1000, 1_000_000L, server.overworld().getGameTime());
            WorldBorders.apply(server);
            helper.assertValueEqual(overworld.getStatus(), BorderStatus.SHRINKING, "overworld border status");
            helper.assertValueEqual(overworld.getLerpTarget(), size - 1000, "overworld border target");
        } finally {
            border.enabled = enabledBefore;
            // setSize also ends the lerp
            sizesBefore.forEach((level, size) -> level.getWorldBorder().setSize(size));
        }
        helper.succeed();
    }

    @GameTest
    public void locatorBarOffOnlyWithSwitch(GameTestHelper helper) {
        ruleOffOnlyWithSwitch(helper, GameRules.LOCATOR_BAR, hide -> hide.locatorBar, (hide, on) -> hide.locatorBar = on,
                GameRuleSwitches::applyLocatorBar);
    }

    @GameTest
    public void globalSoundsOffOnlyWithSwitch(GameTestHelper helper) {
        ruleOffOnlyWithSwitch(helper, GameRules.GLOBAL_SOUND_EVENTS, hide -> hide.globalSounds, (hide, on) -> hide.globalSounds = on,
                GameRuleSwitches::applyGlobalSounds);
    }

    // Each case starts with the rule on, as in a new world
    private static void ruleOffOnlyWithSwitch(GameTestHelper helper, GameRule<Boolean> rule,
                                              Predicate<YoltConfig.HideSection> getSwitch,
                                              BiConsumer<YoltConfig.HideSection, Boolean> setSwitch,
                                              Consumer<MinecraftServer> apply) {
        MinecraftServer server = helper.getLevel().getServer();
        GameRules rules = server.getGameRules();
        YoltConfig.HideSection hide = Settings.get().hide;
        boolean switchBefore = getSwitch.test(hide);
        boolean ruleBefore = rules.get(rule);
        try {
            rules.set(rule, true, server);
            setSwitch.accept(hide, true);
            apply.accept(server);
            helper.assertFalse(rules.get(rule), "switch on: " + rule.id() + " is still on");

            rules.set(rule, true, server);
            setSwitch.accept(hide, false);
            apply.accept(server);
            helper.assertTrue(rules.get(rule), "switch off: " + rule.id() + " was changed");
        } finally {
            setSwitch.accept(hide, switchBefore);
            rules.set(rule, ruleBefore, server);
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
