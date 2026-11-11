package bobbynooby.dev.gametest.client;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.Yolt;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Hud;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.clock.ClockTimeMarker;
import net.minecraft.world.clock.ClockTimeMarkers;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;

// A night in bed on a real client: nobody is told how many players sleep, and the night doesn't skip, so the clock keeps
// going past the point vanilla would have moved it on to the morning and woken the client
public class SleepClientTest implements FabricClientGameTest {
    // The action-bar lines vanilla sends everyone in the dimension when the sleeper count changes
    private static final Set<String> SLEEP_LINES = Set.of("sleep.players_sleeping", "sleep.skipping_night");
    // Two blocks ahead puts the bed in reach, as vanilla counts it for a bed
    private static final int BED_DISTANCE = 2;
    // How far the clock has to run on once the client has slept long enough for a skip, so a skip would have come
    private static final int RUN_ON_TICKS = 20;
    // A skip moves midnight on to the morning, some 6000 ticks; the night only runs a few hundred while the client sleeps
    private static final long NO_SKIP_TICKS = 1000;
    // Vanilla needs everyone asleep for 100 ticks before it skips
    private static final int SLEEP_TIMEOUT = 300;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        // Before the server, so a rename fails with its own message rather than as a wait that never ended
        Field actionBar = actionBarField();
        ClientTests.withServer(ctx, (server, connection) -> {
            boolean hideWas = server.computeOnServer(s -> Settings.get().sleep.hideSleepers);
            boolean noSkipWas = server.computeOnServer(s -> Settings.get().sleep.noNightSkip);
            boolean timeWas = server.computeOnServer(s -> s.getGameRules().get(GameRules.ADVANCE_TIME));
            try {
                server.runOnServer(s -> {
                    Settings.get().sleep.hideSleepers = true;
                    Settings.get().sleep.noNightSkip = true;
                    // The test world's clock stands still, which would hide a skip as well as a night going on
                    s.getGameRules().set(GameRules.ADVANCE_TIME, true, s);
                });
                BlockPos bed = placeBed(server, connection);
                Component refused = refusedByDay(ctx, server, connection, actionBar, bed);
                showSleepingThroughNight(ctx, server, connection, actionBar, bed, refused);
            } finally {
                ClientTests.runAll(List.of(
                        () -> server.runOnServer(s -> {
                            ServerPlayer player = connection.getServerPlayer();
                            if (player.isSleeping()) {
                                player.stopSleepInBed(true, true);
                            }
                        }),
                        () -> server.runOnServer(s -> s.getGameRules().set(GameRules.ADVANCE_TIME, timeWas, s)),
                        // The switches are static, so later client tests in this JVM would see them
                        () -> server.runOnServer(s -> Settings.get().sleep.hideSleepers = hideWas),
                        () -> server.runOnServer(s -> Settings.get().sleep.noNightSkip = noSkipWas)));
            }
        });
    }

    // On the flat world's ground ahead of the client, its foot nearer
    private static BlockPos placeBed(TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
        return server.computeOnServer(s -> {
            ServerPlayer self = connection.getServerPlayer();
            Direction facing = self.getDirection();
            BlockPos foot = self.blockPosition().relative(facing, BED_DISTANCE);
            BlockState bed = Blocks.BED.pick(DyeColor.RED).defaultBlockState().setValue(BedBlock.FACING, facing);
            ServerLevel level = self.level();
            level.setBlock(foot, bed.setValue(BedBlock.PART, BedPart.FOOT), Block.UPDATE_ALL);
            level.setBlock(foot.relative(facing), bed.setValue(BedBlock.PART, BedPart.HEAD), Block.UPDATE_ALL);
            return foot;
        });
    }

    // By day the bed says no on the action bar, which shows the action bar is read right. Returns that line.
    private static Component refusedByDay(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                          TestDedicatedServerConnection connection, Field actionBar, BlockPos bed) {
        moveClock(server, connection, ClockTimeMarkers.DAY, true);
        Component before = ctx.computeOnClient(mc -> actionBarLine(actionBar, mc));
        useBed(ctx, bed);
        ClientTests.waitOnClient(ctx, ClientGameTestContext.DEFAULT_TIMEOUT, mc -> {
                    Component shown = actionBarLine(actionBar, mc);
                    return shown != null && shown != before;
                }, mc -> "the bed by day put nothing on the client's action bar");
        if (ctx.computeOnClient(mc -> mc.player.isSleeping())) {
            throw new AssertionError("the client went to sleep by day");
        }
        Component refused = ctx.computeOnClient(mc -> actionBarLine(actionBar, mc));
        Yolt.LOGGER.info("By day the bed told the client \"{}\"", refused.getString());
        return refused;
    }

    private static void showSleepingThroughNight(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                                 TestDedicatedServerConnection connection, Field actionBar, BlockPos bed,
                                                 Component refused) {
        moveClock(server, connection, ClockTimeMarkers.MIDNIGHT, false);
        useBed(ctx, bed);
        ClientTests.waitOnClient(ctx, ClientGameTestContext.DEFAULT_TIMEOUT, mc -> mc.player.isSleeping(),
                mc -> "the client did not get into the bed at midnight; its action bar says " + describe(actionBarLine(actionBar, mc)));
        long asleepAt = ctx.computeOnClient(mc -> mc.level.getDefaultClockTime());
        // Where vanilla would move the clock on and wake everyone
        ClientTests.waitOnServer(server, SLEEP_TIMEOUT, s -> connection.getServerPlayer().isSleepingLongEnough(),
                s -> "the client's player never slept long enough for a night to skip");
        long deepAt = ctx.computeOnClient(mc -> mc.level.getDefaultClockTime());
        ClientTests.waitOnClient(ctx, ClientGameTestContext.DEFAULT_TIMEOUT, mc -> mc.level.getDefaultClockTime() >= deepAt + RUN_ON_TICKS,
                mc -> "the client's clock stopped at " + mc.level.getDefaultClockTime() + " while it slept");
        // A skip's new time, had there been one, has reached the client by now
        connection.waitForClientboundPackets();
        ctx.runOnClient(mc -> {
            if (!mc.player.isSleeping()) {
                throw new AssertionError("the client was woken up, as a skipped night does");
            }
            long ran = mc.level.getDefaultClockTime() - asleepAt;
            if (ran >= NO_SKIP_TICKS) {
                throw new AssertionError("the client's clock moved " + ran + " ticks while it slept, so the night skipped");
            }
            Component shown = actionBarLine(actionBar, mc);
            if (isSleepLine(shown)) {
                throw new AssertionError("the client's action bar shows the sleepers: " + shown.getString());
            }
            Yolt.LOGGER.info("The client slept for {} ticks of the clock; its action bar still holds \"{}\"{}", ran,
                    describe(shown), shown == refused ? ", the bed's line from the day" : "");
        });
        ClientTests.screenshot(ctx, "09-sleep-night");
    }

    // A real right-click on the bed
    private static void useBed(ClientGameTestContext ctx, BlockPos bed) {
        ctx.getInput().lookAt(bed);
        ClientTests.waitOnClient(ctx, ClientGameTestContext.DEFAULT_TIMEOUT,
                mc -> mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                        && mc.level.getBlockState(hit.getBlockPos()).getBlock() instanceof AbstractBedBlock,
                mc -> "the client looks at " + mc.hitResult + ", not the bed");
        ctx.getInput().pressKey(options -> options.keyUse);
    }

    // Beds only work in the dark, and the sky brightness is cached until the next level tick
    private static void moveClock(TestDedicatedServerContext server, TestDedicatedServerConnection connection,
                                  ResourceKey<ClockTimeMarker> marker, boolean bright) {
        server.runOnServer(s -> {
            ServerLevel level = connection.getServerPlayer().level();
            Holder<WorldClock> clock = level.dimensionType().defaultClock().orElseThrow();
            s.clockManager().moveToTimeMarker(clock, marker);
            level.updateSkyBrightness();
            if (level.isBrightOutside() != bright) {
                throw new AssertionError("it is not " + (bright ? "light" : "dark") + " after moving the clock to " + marker.identifier());
            }
        });
    }

    private static boolean isSleepLine(@Nullable Component line) {
        return line != null && line.getContents() instanceof TranslatableContents text && SLEEP_LINES.contains(text.getKey());
    }

    private static String describe(@Nullable Component line) {
        return line == null ? "nothing" : line.getString();
    }

    // The action bar's last line, which the HUD keeps after it fades. Private, so it is read by name.
    private static Field actionBarField() {
        try {
            Field field = Hud.class.getDeclaredField("overlayMessageString");
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException e) {
            throw new AssertionError("the HUD has no overlayMessageString to read the action bar from", e);
        }
    }

    private static @Nullable Component actionBarLine(Field actionBar, Minecraft mc) {
        try {
            return (Component) actionBar.get(mc.gui.hud);
        } catch (IllegalAccessException e) {
            throw new AssertionError("could not read the client's action bar", e);
        }
    }
}
