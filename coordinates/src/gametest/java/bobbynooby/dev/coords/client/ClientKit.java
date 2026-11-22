package bobbynooby.dev.coords.client;

import bobbynooby.dev.coords.ClientBridge;
import bobbynooby.dev.coords.CoordinateOffsets;
import bobbynooby.dev.coords.CoordinateSettings;
import bobbynooby.dev.coords.Offset;
import bobbynooby.dev.coords.YoltCoordinates;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * What the mod's client tests share: a real dedicated server in the client's process, its world spawn moved away from
 * 0, 0 before the client joins, so the join's stretch has a shift; commands typed into the chat as a player types them,
 * and the lines the client was shown; the death screen's buttons; the client's position against the server's; the F3
 * screen's lines as vanilla writes them; and screenshots. Nothing here comes from You Only Live Twice, which isn't
 * loaded.
 */
final class ClientKit {
    static final int LONG_TIMEOUT = 200;
    // More than the 20 ticks within which a standing client sends its position again, so the server has its last one
    static final int SETTLE_TICKS = 25;
    // Shifted positions are rounded to a grid far finer than this
    static final double CLOSE = 1.0E-6;
    // Where the world spawn goes before the client joins: far enough from the real chunk at 0, 0 that a stretch which
    // starts there has a shift, and a client shown real numbers there is shown numbers far from 0
    static final int SPAWN_X = 1_000;
    static final int SPAWN_Z = 2_000;
    // A death screen's buttons wake 20 ticks after it opens
    private static final int BUTTON_TIMEOUT = 100;
    private static final String RESPAWN = "deathScreen.respawn";

    // Not the mod's own logger, whose lines a test reads
    static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger("YOLT Coordinates client tests");

    // Every system line the client was shown in its chat. A listener can't be taken off an event, so it is added once
    // for the run, and a check reads only what came after its mark.
    private static final List<String> CHAT = new CopyOnWriteArrayList<>();

    static {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                CHAT.add(message.getString());
            }
        });
    }

    private ClientKit() {}

    /**
     * The server runs inside the client process, but it is a real dedicated server, with this mod and nothing of
     * YOLT's. Once it is up its world spawn moves to a spot at SPAWN_X, SPAWN_Z and the offset is on as it ships,
     * before the client joins, as a stretch takes its shift when it starts. The client then joins.
     */
    static void withServer(ClientGameTestContext ctx, BiConsumer<TestDedicatedServerContext, TestDedicatedServerConnection> body) {
        try (TestDedicatedServerContext server = ctx.worldBuilder().createServer()) {
            Runnable putBack = server.computeOnServer(s -> {
                moveSpawn(s, SPAWN_X, SPAWN_Z);
                return ClientBridge.use(CoordinateSettings.DEFAULTS);
            });
            try (TestDedicatedServerConnection connection = server.connect()) {
                connection.waitForChunksRender();
                clearToasts(ctx);
                body.accept(server, connection);
            } finally {
                server.runOnServer(s -> putBack.run());
            }
        }
    }

    /** Moves the world spawn to the block above the ground at this X and Z. Called on the server thread. */
    static BlockPos moveSpawn(MinecraftServer server, int x, int z) {
        ServerLevel overworld = server.overworld();
        BlockPos column = new BlockPos(x, 0, z);
        // The heightmap of a chunk that isn't loaded gives the bottom of the world, so the chunk is loaded first
        overworld.getChunkAt(column);
        BlockPos spot = overworld.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, column);
        server.setRespawnData(LevelData.RespawnData.of(Level.OVERWORLD, spot, 0.0F, 0.0F));
        return spot;
    }

    /** The offset the player's client is sent positions in now. Called off the server thread. */
    static Offset offset(TestServerContext server, TestServerConnection connection) {
        return server.computeOnServer(s -> CoordinateOffsets.offset(connection.getServerPlayer()));
    }

    /**
     * Waits for the client to stand on the ground, and for its last move to reach the server, then checks its X and Z
     * are the server's shifted by the offset and its Y is the server's. Returns where the client is, in its numbers.
     */
    static Vec3 checkShifted(ClientGameTestContext ctx, TestServerContext server, TestServerConnection connection,
                             Offset offset, String when) {
        waitOnClient(ctx, LONG_TIMEOUT, mc -> mc.player.onGround() && still(mc),
                mc -> "the client never stood still on the ground " + when + ": it is at " + mc.player.position()
                        + ", moving " + mc.player.getDeltaMovement());
        ctx.waitTicks(SETTLE_TICKS);
        Vec3 shown = ctx.computeOnClient(mc -> mc.player.position());
        Vec3 real = server.computeOnServer(s -> connection.getServerPlayer().position());
        check(Math.abs(shown.x - (real.x + offset.x())) < CLOSE && Math.abs(shown.z - (real.z + offset.z())) < CLOSE
                        && shown.y == real.y,
                () -> "the client is at " + shown + " " + when + ", not at the server's " + real + " shifted by " + offset);
        return shown;
    }

    /** The chunk of the block the client's player stands in is 0, 0: where every stretch starts. */
    static void checkArrivalChunk(ClientGameTestContext ctx, String when) {
        ChunkPos chunk = ctx.computeOnClient(mc -> ChunkPos.containing(mc.player.blockPosition()));
        check(chunk.x() == 0 && chunk.z() == 0, () -> "the client arrived in its chunk " + chunk + " " + when + ", not 0, 0");
    }

    /**
     * Turns the client's player to look from its eyes at this point, in the client's own numbers, as a player aims with
     * the mouse. Fabric's lookAt for a block aims at its centre, which for a block in the ground a few blocks off is
     * behind the top of a nearer one.
     */
    static void lookAt(ClientGameTestContext ctx, Vec3 point) {
        Vec3 eye = ctx.computeOnClient(mc -> mc.player.getEyePosition());
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        ctx.getInput().lookAt(yaw, pitch);
    }

    private static boolean still(Minecraft mc) {
        return mc.player.getDeltaMovement().horizontalDistanceSqr() < CLOSE * CLOSE;
    }

    /**
     * The lines one entry of the F3 screen shows, as vanilla's own entry writes them from what the client holds, with
     * the formatting codes taken out. Only for an entry the screen shows: it is enabled and allowed with the debug info
     * the server lets this client have.
     */
    static List<String> f3Lines(Minecraft mc, Identifier id) {
        DebugScreenEntry entry = DebugScreenEntries.getEntry(id);
        check(entry != null, () -> "there is no F3 entry " + id);
        check(mc.debugEntries.isCurrentlyEnabled(id), () -> "the F3 screen doesn't show " + id);
        check(entry.isAllowed(mc.showOnlyReducedInfo()), () -> "the F3 screen leaves " + id + " out with reduced debug info");
        List<String> lines = new ArrayList<>();
        DebugScreenDisplayer collect = new DebugScreenDisplayer() {
            @Override
            public void addPriorityLine(String line) {
                lines.add(line);
            }

            @Override
            public void addLine(String line) {
                lines.add(line);
            }

            @Override
            public void addToGroup(Identifier group, Collection<String> groupLines) {
                lines.addAll(groupLines);
            }

            @Override
            public void addToGroup(Identifier group, String line) {
                lines.add(line);
            }
        };
        // As the screen does for a server in another process: the client's level and chunk, and no server chunk
        entry.display(collect, mc.level, mc.level.getChunkAt(mc.player.blockPosition()), null);
        return lines.stream().map(ChatFormatting::stripFormatting).toList();
    }

    /**
     * The F3 screen's position lines for a player at this position, in the numbers the screen is to show: its XYZ,
     * Block and Chunk lines as vanilla formats them.
     */
    static List<String> positionLines(Vec3 position) {
        BlockPos block = BlockPos.containing(position);
        ChunkPos chunk = ChunkPos.containing(block);
        return List.of(
                String.format(Locale.ROOT, "XYZ: %.3f / %.5f / %.3f", position.x, position.y, position.z),
                String.format(Locale.ROOT, "Block: %d %d %d", block.getX(), block.getY(), block.getZ()),
                String.format(Locale.ROOT, "Chunk: %d %d %d [%d %d in r.%d.%d.mca]", chunk.x(),
                        SectionPos.blockToSectionCoord(block.getY()), chunk.z(), chunk.getRegionLocalX(),
                        chunk.getRegionLocalZ(), chunk.getRegionX(), chunk.getRegionZ()));
    }

    /**
     * The F3 screen's position lines start with these, for a player at this position in the screen's numbers. The lines
     * are worked out from the position the client holds, so this only shows the screen draws what the client holds: it
     * fails if something changes vanilla's position entry, never for a shift gone wrong. That the client holds the
     * server's position shifted is checkShifted's to prove.
     */
    static void checkF3Position(ClientGameTestContext ctx, Vec3 position, String when) {
        List<String> expected = positionLines(position);
        List<String> shown = ctx.computeOnClient(mc -> f3Lines(mc, DebugScreenEntries.PLAYER_POSITION));
        check(shown.size() >= expected.size() && shown.subList(0, expected.size()).equals(expected),
                () -> "the F3 screen shows " + shown + " " + when + ", not " + expected);
    }

    /** The full F3 screen, as a player opens it, for the shots. The overlay is taken off again by hideF3. */
    static void showF3(ClientGameTestContext ctx) {
        waitOnClient(ctx, LONG_TIMEOUT, mc -> !mc.showOnlyReducedInfo(), mc -> "the client has the reduced debug screen");
        ctx.runOnClient(mc -> mc.debugEntries.setOverlayVisible(true));
    }

    // The client keeps the overlay up into the tests after this one otherwise
    static void hideF3(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> mc.debugEntries.setOverlayVisible(false));
    }

    /** Where the chat lines the client has been shown end now, so a check reads only what comes after. */
    static int mark() {
        return CHAT.size();
    }

    /** The chat lines the client was shown from the mark on. */
    private static List<String> chat(int from) {
        List<String> all = List.copyOf(CHAT);
        return all.subList(Math.min(from, all.size()), all.size());
    }

    /** Waits until the client was shown this chat line after the mark. */
    static void waitForChat(ClientGameTestContext ctx, int from, String line) {
        waitOnClient(ctx, LONG_TIMEOUT, mc -> chat(from).contains(line),
                mc -> "the client's chat never showed \"" + line + "\", only " + chat(from));
    }

    /** Typed into the chat box and sent with Enter, as a player would, and the server's answer back. */
    static void type(ClientGameTestContext ctx, TestServerConnection connection, String line) {
        ctx.getInput().pressKey(options -> options.keyChat);
        ctx.waitForScreen(ChatScreen.class);
        ctx.getInput().typeChars(line);
        ctx.getInput().pressKey(InputConstants.KEY_RETURN);
        ctx.waitForScreen(null);
        connection.waitForServerboundPackets();
        connection.waitForClientboundPackets();
    }

    /** Empties the chat on screen, so a shot of it shows only what comes next. The record of what was shown stays. */
    static void clearChat(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> mc.gui.hud.getChat().clearMessages(false));
    }

    /** A shot with the chat open, as a player looks back over it. */
    static void screenshotWithChat(ClientGameTestContext ctx, String name) {
        ctx.getInput().pressKey(options -> options.keyChat);
        ctx.waitForScreen(ChatScreen.class);
        try {
            screenshot(ctx, name);
        } finally {
            ctx.setScreen(() -> null);
        }
    }

    static void screenshot(ClientGameTestContext ctx, String name) {
        clearToasts(ctx);
        // A death leaves a puff of smoke on the spot, and a broken block its pieces, right in front of the camera
        ctx.runOnClient(mc -> mc.particleEngine.clearParticles());
        // No counter prefix, so each file keeps its plain name from run to run
        ctx.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix());
    }

    // Join toasts, such as the offline chat warning and the tutorial, would cover the top right of every shot
    private static void clearToasts(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> mc.gui.toastManager().clear());
    }

    /**
     * Clicks the button a player would, once it can be clicked: Fabric's click presses a button whether or not it is
     * active, and a death screen's are asleep for its first second.
     */
    static void click(ClientGameTestContext ctx, String translationKey) {
        String label = Component.translatable(translationKey).getString();
        waitOnClient(ctx, BUTTON_TIMEOUT, mc -> button(mc, label).map(Button::isActive).orElse(false),
                mc -> button(mc, label).isPresent() ? "the \"" + label + "\" button on " + screenName(mc) + " never woke"
                        : "there is no \"" + label + "\" button on " + screenName(mc));
        ctx.clickScreenButton(translationKey);
    }

    /** Killed on the server, as any death is, and the client on its death screen. */
    static void die(ClientGameTestContext ctx, TestServerContext server, TestServerConnection connection) {
        server.runOnServer(s -> {
            ServerPlayer player = connection.getServerPlayer();
            player.kill(player.level());
            check(player.isDeadOrDying(), () -> "the client's player did not die");
        });
        waitOnClient(ctx, LONG_TIMEOUT, mc -> mc.gui.screen() instanceof DeathScreen,
                mc -> "the client is on " + screenName(mc) + ", not the death screen");
    }

    /**
     * The death screen's Respawn, clicked once it wakes, and the client back in the world alive, with what it sees around
     * it drawn. The respawn starts a new stretch.
     */
    static void respawn(ClientGameTestContext ctx, TestServerConnection connection) {
        click(ctx, RESPAWN);
        waitOnClient(ctx, LONG_TIMEOUT, mc -> mc.gui.screen() == null && mc.player != null && !mc.player.isDeadOrDying(),
                mc -> "the client is on " + screenName(mc) + " after Respawn"
                        + (mc.player == null ? ", with no player" : ", with " + mc.player.getHealth() + " health"));
        connection.waitForChunksRender();
    }

    private static Optional<Button> button(Minecraft mc, String label) {
        Screen screen = mc.gui.screen();
        if (screen == null) {
            return Optional.empty();
        }
        return screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> button.getMessage().getString().equals(label)).findFirst();
    }

    private static String screenName(Minecraft mc) {
        Screen screen = mc.gui.screen();
        return screen == null ? "no screen" : screen.getClass().getSimpleName();
    }

    // The test server gives an op level 0 unless told otherwise, below the level /yolt-coordinates needs. The op list is
    // a file in the client's run folder, which every later server reads, so each op is taken off again.
    static void op(ServerPlayer player) {
        player.level().getServer().getPlayerList().op(player.nameAndId(), Optional.of(LevelBasedPermissionSet.GAMEMASTER),
                Optional.empty());
    }

    static void deop(ServerPlayer player) {
        player.level().getServer().getPlayerList().deop(player.nameAndId());
    }

    /** True when the client's own command tree, which the server sends it, has this command. */
    static boolean clientHasCommand(Minecraft mc, String name) {
        return mc.getConnection().getCommands().getRoot().getChild(name) != null;
    }

    /** Each line the mod's own logger logs while the task runs, which on a world's start is the server thread's. */
    static List<String> logged(Runnable task) {
        String name = YoltCoordinates.LOGGER.getName();
        LoggerConfig config = ((Logger) LogManager.getLogger(name)).get();
        List<String> lines = new CopyOnWriteArrayList<>();
        // The appender joins the config the logger already uses, which other loggers may use too, so it keeps the
        // mod's lines only
        AbstractAppender appender = new AbstractAppender("yolt-coordinates-client-test", null, null, false, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                if (name.equals(event.getLoggerName())) {
                    lines.add(event.getMessage().getFormattedMessage());
                }
            }
        };
        appender.start();
        config.addAppender(appender, null, null);
        try {
            task.run();
        } finally {
            config.removeAppender(appender.getName());
            appender.stop();
        }
        return List.copyOf(lines);
    }

    static void check(boolean condition, Supplier<String> failure) {
        if (!condition) {
            throw new AssertionError(failure.get());
        }
    }

    // Like waitFor, but a timeout says what was wrong at the end, read on the server
    static void waitOnServer(TestServerContext server, int timeout, Predicate<MinecraftServer> condition,
                             Function<MinecraftServer, String> failure) {
        try {
            server.waitFor(condition, timeout);
        } catch (AssertionError e) {
            throw explained(e, () -> server.computeOnServer(failure::apply));
        }
    }

    static void waitOnClient(ClientGameTestContext ctx, int timeout, Predicate<Minecraft> condition,
                             Function<Minecraft, String> failure) {
        try {
            ctx.waitFor(condition, timeout);
        } catch (AssertionError e) {
            throw explained(e, () -> ctx.computeOnClient(failure::apply));
        }
    }

    // Reading what was wrong can fail too, say when the player it reads has gone. The wait's own failure is kept.
    private static AssertionError explained(AssertionError failed, Supplier<String> message) {
        try {
            return new AssertionError(message.get(), failed);
        } catch (RuntimeException | Error e) {
            failed.addSuppressed(e);
            return failed;
        }
    }

    /**
     * Runs every step even when one fails, so one broken clean-up step can't leave the rest undone for the tests after
     * it in this JVM. The first failure is thrown at the end, with any later ones attached.
     */
    static void runAll(List<Runnable> steps) {
        Throwable first = null;
        for (Runnable step : steps) {
            try {
                step.run();
            } catch (RuntimeException | Error e) {
                LOGGER.error("A client test step failed", e);
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
        }
        if (first instanceof RuntimeException e) {
            throw e;
        }
        if (first instanceof Error e) {
            throw e;
        }
    }
}
