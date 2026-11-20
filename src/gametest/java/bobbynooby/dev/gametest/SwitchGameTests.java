package bobbynooby.dev.gametest;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.world.GameRuleSwitches;
import bobbynooby.dev.world.WorldBorders;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.SleepStatus;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.BorderStatus;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;

import java.lang.reflect.RecordComponent;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
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

    // At 1:1 the Overworld side looks for a portal to link to only 16 blocks around the exit, not vanilla's 128. Two
    // lone portal blocks well above the test, out of the way of other tests: one 8 blocks from the first search, one
    // 40 from the second. A new portal block reaches the search a task later, so the checks wait for both. The switch
    // is set and put back inside the one tick that runs the checks.
    @GameTest(maxTicks = 40)
    public void portalSearchIsNarrowAtOneToOne(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos near = helper.absolutePos(BlockPos.ZERO).above(40);
        BlockPos far = near.east(60);
        BlockPos nearSearch = near.east(8);
        BlockPos farSearch = near.east(100);
        BlockState portal = Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS, Direction.Axis.X);
        Runnable cleanUp = BodyTestKit.once(() -> {
            level.setBlock(near, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            level.setBlock(far, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        });
        helper.runBeforeTestEnd(cleanUp);
        // No neighbour updates, so the frameless portal blocks stay
        level.setBlock(near, portal, Block.UPDATE_CLIENTS);
        level.setBlock(far, portal, Block.UPDATE_CLIENTS);
        helper.succeedWhen(() -> {
            helper.assertTrue(level.getPoiManager().existsAtPosition(PoiTypes.NETHER_PORTAL, near)
                    && level.getPoiManager().existsAtPosition(PoiTypes.NETHER_PORTAL, far), "the portals are not registered yet");
            YoltConfig config = Settings.get();
            boolean before = config.netherScaleOneToOne;
            try {
                config.netherScaleOneToOne = true;
                helper.assertValueEqual(findPortal(level, nearSearch), Optional.of(near), "1:1, a portal 8 blocks off");
                helper.assertFalse(findPortal(level, farSearch).equals(Optional.of(far)), "1:1 found a portal 40 blocks off");

                config.netherScaleOneToOne = false;
                helper.assertValueEqual(findPortal(level, farSearch), Optional.of(far), "vanilla scale, a portal 40 blocks off");
            } finally {
                config.netherScaleOneToOne = before;
            }
            cleanUp.run();
        });
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

    // With the feature off YOLT sizes no border, so each level keeps the size it has, whether the apply is a startup's,
    // a reload's or a border command's. Each level gets a size of its own, so one copied onto another shows.
    @GameTest
    public void borderLeftAloneWithSwitchOff(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.WorldBorderSection border = Settings.get().worldBorder;
        boolean enabledBefore = border.enabled;
        Map<ServerLevel, Double> sizesBefore = new HashMap<>();
        Map<ServerLevel, Double> odd = new HashMap<>();
        double size = 1234;
        for (ServerLevel level : server.getAllLevels()) {
            sizesBefore.put(level, level.getWorldBorder().getSize());
            odd.put(level, size);
            size += 1111;
        }

        border.enabled = false;
        try {
            odd.forEach((level, oddSize) -> level.getWorldBorder().setSize(oddSize));
            WorldBorders.apply(server);
            WorldBorders.apply(server, true);
            for (ServerLevel level : server.getAllLevels()) {
                helper.assertValueEqual(level.getWorldBorder().getSize(), odd.get(level), level.dimension().identifier() + " border size");
            }
        } finally {
            border.enabled = enabledBefore;
            sizesBefore.forEach((level, before) -> level.getWorldBorder().setSize(before));
        }
        helper.succeed();
    }

    // What a portal trip multiplies the position by, from the Overworld to the Nether. A real trip would build a portal in
    // the Nether, which the shared world would keep, so this is the scale vanilla's portal asks for: a portal at x 8000
    // leads to 8000 at 1:1, and to 1000 with the switch off.
    @GameTest
    public void portalTripLandsAtTheSameCoordinatesAtOneToOne(GameTestHelper helper) {
        DimensionType overworld = helper.getLevel().getServer().overworld().dimensionType();
        DimensionType nether = nether(helper).dimensionType();
        YoltConfig config = Settings.get();
        boolean before = config.netherScaleOneToOne;
        try {
            config.netherScaleOneToOne = true;
            helper.assertValueEqual(8000 * DimensionType.getTeleportationScale(overworld, nether), 8000.0, "the Nether x at 1:1");
            helper.assertValueEqual(8000 * DimensionType.getTeleportationScale(nether, overworld), 8000.0, "the way back at 1:1");
            config.netherScaleOneToOne = false;
            helper.assertValueEqual(8000 * DimensionType.getTeleportationScale(overworld, nether), 1000.0, "the Nether x at vanilla scale");
        } finally {
            config.netherScaleOneToOne = before;
        }
        helper.succeed();
    }

    // A modded dimension's own scale gives way too: a copy of the Nether's type with a scale of 4
    @GameTest
    public void moddedDimensionTypeScaleIsOne(GameTestHelper helper) throws ReflectiveOperationException {
        DimensionType modded = withScale(nether(helper).dimensionType(), 4.0);
        YoltConfig config = Settings.get();
        boolean before = config.netherScaleOneToOne;
        try {
            config.netherScaleOneToOne = true;
            helper.assertValueEqual(modded.coordinateScale(), 1.0, "the modded scale with the switch on");
            config.netherScaleOneToOne = false;
            helper.assertValueEqual(modded.coordinateScale(), 4.0, "the modded scale with the switch off");
        } finally {
            config.netherScaleOneToOne = before;
        }
        helper.succeed();
    }

    // The same dimension type with another coordinate scale, as a mod's data pack would make one
    private static DimensionType withScale(DimensionType type, double scale) throws ReflectiveOperationException {
        RecordComponent[] parts = DimensionType.class.getRecordComponents();
        Object[] values = new Object[parts.length];
        Class<?>[] types = new Class<?>[parts.length];
        for (int i = 0; i < parts.length; i++) {
            types[i] = parts[i].getType();
            values[i] = parts[i].getName().equals("coordinateScale") ? scale : parts[i].getAccessor().invoke(type);
        }
        return DimensionType.class.getDeclaredConstructor(types).newInstance(values);
    }

    // The rule is turned on by the switch at every apply, as a startup and a reload run it. Off, the rule is left as the
    // admin set it, here off.
    @GameTest
    public void immediateRespawnForcedOnlyWithSwitch(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        GameRules rules = server.getGameRules();
        YoltConfig config = Settings.get();
        boolean switchBefore = config.immediateRespawn;
        boolean ruleBefore = rules.get(GameRules.IMMEDIATE_RESPAWN);
        try {
            rules.set(GameRules.IMMEDIATE_RESPAWN, false, server);
            config.immediateRespawn = true;
            GameRuleSwitches.applyImmediateRespawn(server);
            helper.assertTrue(rules.get(GameRules.IMMEDIATE_RESPAWN), "switch on: immediate_respawn is still off");

            rules.set(GameRules.IMMEDIATE_RESPAWN, false, server);
            config.immediateRespawn = false;
            GameRuleSwitches.applyImmediateRespawn(server);
            helper.assertFalse(rules.get(GameRules.IMMEDIATE_RESPAWN), "switch off: immediate_respawn was turned on");
        } finally {
            config.immediateRespawn = switchBefore;
            rules.set(GameRules.IMMEDIATE_RESPAWN, ruleBefore, server);
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

    // The search vanilla runs for an exit in the Overworld
    private static Optional<BlockPos> findPortal(ServerLevel overworld, BlockPos exit) {
        return overworld.getPortalForcer().findClosestPortalPosition(exit, false, overworld.getWorldBorder());
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
