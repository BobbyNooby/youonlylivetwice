package bobbynooby.dev.coords;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The mod on its own, with You Only Live Twice nowhere on the server: its own switch, file and commands. YOLT's GameTests
 * cover the same coordinate offset hosted inside YOLT.
 * <p>
 * Each test joins players of its own, whose packets it reads, and before it ends puts back what it changed: the
 * settings it swapped in, the file it wrote, the players it joined and opped, and who sees real coordinates. Respawns go
 * to a spot beside the test, so no spot is searched for. Vanilla puts the test area at a random X and Z up to 15
 * million blocks out, so a frame there has an offset: only a frame that starts in the real chunk at 0, 0 has none. The
 * GameTest server is a dedicated one, so the rule that keeps the mod out of a single-player world isn't tried here.
 */
public class CoordinateGameTests {
    private static final BlockPos SPOT = new BlockPos(1, 1, 1);
    private static final BlockPos RESPAWN = new BlockPos(2, 1, 2);
    private static final CoordinateSettings OFF = new CoordinateSettings(false);
    private static final String REAL_ON = "You see real coordinates from your next join, respawn or dimension change.";
    private static final String REAL_OFF = "You see shifted coordinates again from your next join, respawn or dimension change.";
    private static final String OFF_NOTE = " The coordinate offset is off, so this changes nothing until it is on.";

    @GameTest
    public void theModRunsWithoutYolt(GameTestHelper helper) {
        FabricLoader loader = FabricLoader.getInstance();
        helper.assertTrue(loader.isModLoaded("yolt-coordinates"), "yolt-coordinates isn't loaded");
        helper.assertFalse(loader.isModLoaded("you-only-live-twice"), "you-only-live-twice is loaded");
        helper.assertFalse(YoltCoordinates.hosted(), "the mod is hosted, but nothing here hosts it");
        helper.succeed();
    }

    @GameTest
    public void aJoiningPlayerIsShifted(GameTestHelper helper) {
        CoordinateSettings before = YoltCoordinates.swapForTesting(CoordinateSettings.DEFAULTS);
        ServerPlayer player = null;
        try {
            player = TestPlayers.join(helper, "joins", GameType.SURVIVAL, SPOT);
            assertSentShifted(helper, player, 0, "the join");
        } finally {
            try {
                leave(player);
            } finally {
                YoltCoordinates.swapForTesting(before);
            }
        }
        helper.succeed();
    }

    // Untranslated, the accept would be a long way from the real spot and the step after it too quick, so the server
    // would send the player back
    @GameTest
    public void whatTheClientSendsIsReadBack(GameTestHelper helper) {
        CoordinateSettings before = YoltCoordinates.swapForTesting(CoordinateSettings.DEFAULTS);
        ServerPlayer player = null;
        try {
            player = TestPlayers.join(helper, "moves", GameType.SURVIVAL, SPOT);
            helper.assertFalse(CoordinateOffsets.offset(player).isZero(), "the join got no offset");
            TestPlayers.markLoaded(player);
            // What each tick does first, so the move check measures from where the player is
            player.connection.resetPosition();
            Vec3 real = player.position();
            ClientboundPlayerPositionPacket teleport = TestPlayers.last(helper, player, 0, ClientboundPlayerPositionPacket.class);
            Vec3 client = teleport.change().position();
            TestPlayers.receive(player,
                    new ServerboundAcceptTeleportationPacket(teleport.id(), client.x, client.y, client.z, 0.0F, 0.0F));
            helper.assertValueEqual(player.position(), real, "the position after the client accepted the join's teleport");
            TestPlayers.receive(player, new ServerboundMovePlayerPacket.Pos(client.x + 1, client.y, client.z + 1, false, false));
            helper.assertValueEqual(player.position(), new Vec3(real.x + 1, real.y, real.z + 1), "the position after a step");
        } finally {
            try {
                leave(player);
            } finally {
                YoltCoordinates.swapForTesting(before);
            }
        }
        helper.succeed();
    }

    @GameTest
    public void offShiftsNobody(GameTestHelper helper) {
        CoordinateSettings before = YoltCoordinates.swapForTesting(OFF);
        ServerPlayer player = null;
        try {
            player = TestPlayers.join(helper, "unshifted", GameType.SURVIVAL, SPOT);
            assertSentReal(helper, player, "a join while off");
        } finally {
            try {
                leave(player);
            } finally {
                YoltCoordinates.swapForTesting(before);
            }
        }
        helper.succeed();
    }

    // The switch is read as a frame starts, so a player who joined while it was on keeps their offset to the end of the
    // frame, and what the server sends them goes on in it, as their client still reads everything in it
    @GameTest
    public void aReloadWaitsForTheNextFrame(GameTestHelper helper) {
        CoordinateSettings before = YoltCoordinates.swapForTesting(CoordinateSettings.DEFAULTS);
        Path file = YoltCoordinates.configFile();
        String original = read(file);
        ServerPlayer player = null;
        try {
            player = TestPlayers.join(helper, "waits", GameType.SURVIVAL, SPOT);
            Offset offset = CoordinateOffsets.offset(player);
            helper.assertFalse(offset.isZero(), "the join got no offset");

            write(file, CoordinateConfig.FORMAT.json(OFF));
            helper.assertFalse(YoltCoordinates.reload().failed(), "the reload failed");
            helper.assertFalse(YoltCoordinates.settings().enabled(), "still on after reloading a file that turns it off");
            helper.assertValueEqual(CoordinateOffsets.offset(player), offset, "the offset right after the reload");
            int mark = TestPlayers.sent(player).size();
            player.connection.teleport(PositionMoveRotation.of(player), Set.of());
            Vec3 sent = TestPlayers.last(helper, player, mark, ClientboundPlayerPositionPacket.class).change().position();
            helper.assertValueEqual(sent, new Vec3(player.getX() + offset.x(), player.getY(), player.getZ() + offset.z()),
                    "a teleport sent after the reload");

            player = TestPlayers.respawn(helper, player, RESPAWN);
            helper.assertValueEqual(CoordinateOffsets.offset(player), Offset.ZERO, "the offset after the next respawn");
        } finally {
            try {
                leave(player);
            } finally {
                restore(file, original, before);
            }
        }
        helper.succeed();
    }

    @GameTest
    public void realGivesAnOpRealCoordinatesFromTheirNextFrame(GameTestHelper helper) {
        CoordinateSettings before = YoltCoordinates.swapForTesting(CoordinateSettings.DEFAULTS);
        ServerPlayer player = null;
        try {
            player = TestPlayers.join(helper, "realop", GameType.SURVIVAL, SPOT);
            TestPlayers.op(player);
            Offset offset = CoordinateOffsets.offset(player);
            helper.assertFalse(offset.isZero(), "the join got no offset");

            int mark = TestPlayers.sent(player).size();
            helper.assertValueEqual(run(helper, player.createCommandSourceStack(), "yolt-coordinates real"), 1, "/yolt-coordinates real");
            helper.assertValueEqual(said(player, mark), List.of(REAL_ON), "the reply");
            helper.assertTrue(YoltCoordinates.seesReal(player), "the command gave no real coordinates");
            helper.assertValueEqual(CoordinateOffsets.offset(player), offset, "the offset right after the command");
            player = TestPlayers.respawn(helper, player, RESPAWN);
            helper.assertValueEqual(CoordinateOffsets.offset(player), Offset.ZERO, "the offset after the next respawn");

            mark = TestPlayers.sent(player).size();
            helper.assertValueEqual(run(helper, player.createCommandSourceStack(), "yolt-coordinates real"), 1,
                    "/yolt-coordinates real again");
            helper.assertValueEqual(said(player, mark), List.of(REAL_OFF), "the second reply");
            helper.assertFalse(YoltCoordinates.seesReal(player), "the second command left real coordinates on");
            helper.assertValueEqual(CoordinateOffsets.offset(player), Offset.ZERO, "the offset right after the second command");
            player = TestPlayers.respawn(helper, player, RESPAWN);
            helper.assertFalse(CoordinateOffsets.offset(player).isZero(), "the respawn after the second command got no offset");
        } finally {
            try {
                leave(player);
            } finally {
                YoltCoordinates.swapForTesting(before);
            }
        }
        helper.succeed();
    }

    // Through /execute as, the command turns another player's real coordinates on or off, while the reply still goes to
    // whoever ran it, so the reply names that player. The log names both: here an op, then the console.
    @GameTest
    public void realRunAsAnotherPlayerNamesThem(GameTestHelper helper) {
        CoordinateSettings before = YoltCoordinates.swapForTesting(CoordinateSettings.DEFAULTS);
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer runner = null;
        ServerPlayer target = null;
        try {
            runner = TestPlayers.join(helper, "realrunner", GameType.SURVIVAL, SPOT);
            target = TestPlayers.join(helper, "realtarget", GameType.SURVIVAL, SPOT);
            TestPlayers.op(runner);
            TestPlayers.op(target);
            String runnerName = runner.getPlainTextName();
            String targetName = target.getPlainTextName();
            String command = "execute as " + targetName + " run yolt-coordinates real";
            CommandSourceStack asRunner = runner.createCommandSourceStack();
            int runnerMark = TestPlayers.sent(runner).size();
            int targetMark = TestPlayers.sent(target).size();
            List<String> logged = logged(() -> helper.assertValueEqual(run(helper, asRunner, command), 1, "/" + command));
            helper.assertTrue(YoltCoordinates.seesReal(target), "the player it ran as was given no real coordinates");
            helper.assertFalse(YoltCoordinates.seesReal(runner), "the op who ran it was given real coordinates");
            helper.assertValueEqual(said(runner, runnerMark),
                    List.of(targetName + " sees real coordinates from their next join, respawn or dimension change."),
                    "the reply to the op who ran it");
            helper.assertValueEqual(said(target, targetMark), List.of(), "what the player it ran as was told");
            helper.assertValueEqual(logged, List.of(runnerName + " turned real coordinates on for " + targetName), "the log");

            List<String> fromConsole = logged(() ->
                    helper.assertValueEqual(run(helper, server.createCommandSourceStack(), command), 1, "/" + command + " from the console"));
            helper.assertFalse(YoltCoordinates.seesReal(target), "the console's run left real coordinates on");
            helper.assertValueEqual(fromConsole, List.of("Server turned real coordinates off for " + targetName),
                    "the log of the console's run");
        } finally {
            try {
                leave(runner);
            } finally {
                try {
                    leave(target);
                } finally {
                    YoltCoordinates.swapForTesting(before);
                }
            }
        }
        helper.succeed();
    }

    // Only an op is ever given real coordinates, so through /execute as the command refuses a player who isn't one and
    // turns nothing on: opped afterwards, they still see shifted coordinates
    @GameTest
    public void realRunAsAPlayerWhoIsNotAnOpIsRefused(GameTestHelper helper) {
        CoordinateSettings before = YoltCoordinates.swapForTesting(CoordinateSettings.DEFAULTS);
        ServerPlayer runner = null;
        ServerPlayer target = null;
        try {
            runner = TestPlayers.join(helper, "realasker", GameType.SURVIVAL, SPOT);
            target = TestPlayers.join(helper, "realnotop", GameType.SURVIVAL, SPOT);
            TestPlayers.op(runner);
            String targetName = target.getPlainTextName();
            String command = "execute as " + targetName + " run yolt-coordinates real";
            int runnerMark = TestPlayers.sent(runner).size();
            int targetMark = TestPlayers.sent(target).size();
            // Brigadier answers a forked command such as /execute as with the number of forks it ran, whatever each
            // returned, so the refusal shows in the reply and in what was turned on
            run(helper, runner.createCommandSourceStack(), command);
            helper.assertValueEqual(said(runner, runnerMark),
                    List.of(targetName + " is not an op, and only an op sees real coordinates."), "what the op who ran it was told");
            helper.assertValueEqual(said(target, targetMark), List.of(), "what the player it ran as was told");
            TestPlayers.op(target);
            helper.assertFalse(YoltCoordinates.seesReal(target), "the refused command turned real coordinates on");
        } finally {
            try {
                leave(runner);
            } finally {
                try {
                    leave(target);
                } finally {
                    YoltCoordinates.swapForTesting(before);
                }
            }
        }
        helper.succeed();
    }

    // With the offset off nobody is shifted, whatever the command turns, so the reply says the offset is off as well
    @GameTest
    public void realWithTheOffsetOffSaysItIsOff(GameTestHelper helper) {
        CoordinateSettings before = YoltCoordinates.swapForTesting(OFF);
        ServerPlayer player = null;
        try {
            player = TestPlayers.join(helper, "realoff", GameType.SURVIVAL, SPOT);
            TestPlayers.op(player);
            int mark = TestPlayers.sent(player).size();
            helper.assertValueEqual(run(helper, player.createCommandSourceStack(), "yolt-coordinates real"), 1, "/yolt-coordinates real");
            helper.assertValueEqual(said(player, mark), List.of(REAL_ON + OFF_NOTE), "the reply");
            helper.assertTrue(YoltCoordinates.seesReal(player), "the command gave no real coordinates");

            mark = TestPlayers.sent(player).size();
            helper.assertValueEqual(run(helper, player.createCommandSourceStack(), "yolt-coordinates real"), 1,
                    "/yolt-coordinates real again");
            helper.assertValueEqual(said(player, mark), List.of(REAL_OFF + OFF_NOTE), "the second reply");
        } finally {
            try {
                leave(player);
            } finally {
                YoltCoordinates.swapForTesting(before);
            }
        }
        helper.succeed();
    }

    // Real coordinates are an op's: once de-opped, a player can't run the command to turn them off, so they stop at
    // the next frame by themselves
    @GameTest
    public void aDeoppedPlayerIsShiftedAgainAtTheirNextFrame(GameTestHelper helper) {
        CoordinateSettings before = YoltCoordinates.swapForTesting(CoordinateSettings.DEFAULTS);
        ServerPlayer player = null;
        try {
            player = TestPlayers.join(helper, "deopped", GameType.SURVIVAL, SPOT);
            TestPlayers.op(player);
            helper.assertValueEqual(run(helper, player.createCommandSourceStack(), "yolt-coordinates real"), 1, "/yolt-coordinates real");
            player = TestPlayers.respawn(helper, player, RESPAWN);
            helper.assertValueEqual(CoordinateOffsets.offset(player), Offset.ZERO, "the offset after a respawn with real on");

            TestPlayers.deop(player);
            helper.assertValueEqual(CoordinateOffsets.offset(player), Offset.ZERO, "the offset right after the deop");
            player = TestPlayers.respawn(helper, player, RESPAWN);
            helper.assertFalse(CoordinateOffsets.offset(player).isZero(), "the respawn after the deop got no offset");
        } finally {
            try {
                leave(player);
            } finally {
                YoltCoordinates.swapForTesting(before);
            }
        }
        helper.succeed();
    }

    // Creative and spectator players are shifted too: on its own the mod has no one it leaves out
    @GameTest
    public void aCreativePlayerIsShifted(GameTestHelper helper) {
        assertShiftedIn(helper, "creative", GameType.CREATIVE);
    }

    @GameTest
    public void aSpectatorIsShifted(GameTestHelper helper) {
        assertShiftedIn(helper, "spectator", GameType.SPECTATOR);
    }

    // Each one refused, then each one run once the player is an op, so the refusals were for the permission. The file
    // that would turn the mod off proves the refused reload read nothing, and the op's reload of it proves it would
    // have.
    @GameTest
    public void theCommandsAreForOpsOnly(GameTestHelper helper) {
        CoordinateSettings before = YoltCoordinates.swapForTesting(CoordinateSettings.DEFAULTS);
        Path file = YoltCoordinates.configFile();
        String original = read(file);
        ServerPlayer player = null;
        try {
            player = TestPlayers.join(helper, "notop", GameType.SURVIVAL, SPOT);
            String off = CoordinateConfig.FORMAT.json(OFF);
            write(file, off);
            int mark = TestPlayers.sent(player).size();
            CommandSourceStack notOp = player.createCommandSourceStack();
            for (String command : List.of("yolt-coordinates", "yolt-coordinates reload", "yolt-coordinates real")) {
                refused(helper, notOp, command);
            }
            helper.assertValueEqual(said(player, mark), List.of(), "what the player was told");
            helper.assertValueEqual(YoltCoordinates.settings(), CoordinateSettings.DEFAULTS, "the settings after a refused reload");
            helper.assertValueEqual(read(file), off, "the file after a refused reload");

            TestPlayers.op(player);
            CommandSourceStack op = player.createCommandSourceStack();
            helper.assertValueEqual(run(helper, op, "yolt-coordinates"), 1, "/yolt-coordinates as an op");
            helper.assertValueEqual(run(helper, op, "yolt-coordinates reload"), 1, "/yolt-coordinates reload as an op");
            helper.assertFalse(YoltCoordinates.settings().enabled(), "still on after an op reloaded a file that turns it off");
            helper.assertValueEqual(run(helper, op, "yolt-coordinates real"), 1, "/yolt-coordinates real as an op");
            helper.assertTrue(YoltCoordinates.seesReal(player), "an op's real gave no real coordinates");
        } finally {
            try {
                leave(player);
            } finally {
                restore(file, original, before);
            }
        }
        helper.succeed();
    }

    // The console always works in real coordinates, so it is told why there is nothing to turn on, not given an error
    @GameTest
    public void theConsoleHasNoCoordinatesToShow(GameTestHelper helper) {
        List<String> said = new ArrayList<>();
        CommandSourceStack console = console(helper.getLevel().getServer(), said, true);
        helper.assertValueEqual(run(helper, console, "yolt-coordinates real"), 0, "/yolt-coordinates real from the console");
        helper.assertValueEqual(said, List.of("Only a player has coordinates to show."), "the failures the console was told");
        helper.succeed();
    }

    // Through the command, as an op at the console would
    @GameTest
    public void aReloadAppliesAChangedFile(GameTestHelper helper) {
        CoordinateSettings before = YoltCoordinates.swapForTesting(CoordinateSettings.DEFAULTS);
        Path file = YoltCoordinates.configFile();
        String original = read(file);
        ServerPlayer player = null;
        try {
            write(file, CoordinateConfig.FORMAT.json(OFF));
            List<String> said = new ArrayList<>();
            helper.assertValueEqual(run(helper, console(helper.getLevel().getServer(), said, false), "yolt-coordinates reload"), 1,
                    "/yolt-coordinates reload");
            helper.assertValueEqual(said, List.of("Reloaded yolt-coordinates.json. The coordinate offset is off"), "the reply");
            player = TestPlayers.join(helper, "reloaded", GameType.SURVIVAL, SPOT);
            assertSentReal(helper, player, "a join after the reload");
        } finally {
            try {
                leave(player);
            } finally {
                restore(file, original, before);
            }
        }
        helper.succeed();
    }

    // A join's frame starts in the server's default game mode, before TestPlayers switches it, so this reads the frame
    // of a respawn, which starts from the player in the mode they were given
    private static void assertShiftedIn(GameTestHelper helper, String base, GameType mode) {
        CoordinateSettings before = YoltCoordinates.swapForTesting(CoordinateSettings.DEFAULTS);
        ServerPlayer player = null;
        try {
            player = TestPlayers.join(helper, base, mode, SPOT);
            helper.assertValueEqual(player.gameMode(), mode, "the game mode the respawn's frame starts in");
            int mark = TestPlayers.sent(player).size();
            player = TestPlayers.respawn(helper, player, RESPAWN);
            helper.assertValueEqual(player.gameMode(), mode, "the game mode after the respawn");
            assertSentShifted(helper, player, mark, "the " + mode.getName() + " respawn");
        } finally {
            try {
                leave(player);
            } finally {
                YoltCoordinates.swapForTesting(before);
            }
        }
        helper.succeed();
    }

    // The teleport that starts a frame, which puts the client where the player is, goes out moved by the player's offset
    // on X and Z
    private static void assertSentShifted(GameTestHelper helper, ServerPlayer player, int from, String what) {
        Offset offset = CoordinateOffsets.offset(player);
        helper.assertFalse(offset.isZero(), what + " got no offset");
        Vec3 sent = TestPlayers.last(helper, player, from, ClientboundPlayerPositionPacket.class).change().position();
        helper.assertValueEqual(sent, new Vec3(player.getX() + offset.x(), player.getY(), player.getZ() + offset.z()),
                "the position " + what + " sent");
    }

    private static void assertSentReal(GameTestHelper helper, ServerPlayer player, String what) {
        helper.assertValueEqual(CoordinateOffsets.offset(player), Offset.ZERO, "the offset of " + what);
        Vec3 sent = TestPlayers.last(helper, player, 0, ClientboundPlayerPositionPacket.class).change().position();
        helper.assertValueEqual(sent, player.position(), "the position " + what + " sent");
    }

    // The player goes, off the list of who sees real coordinates and off the op list too, whatever the test gave them.
    // Each step runs even when one before it fails.
    private static void leave(@Nullable ServerPlayer player) {
        if (player == null) {
            return;
        }
        try {
            YoltCoordinates.forgetRealForTesting(player);
        } finally {
            try {
                TestPlayers.deop(player);
            } finally {
                TestPlayers.leave(player);
            }
        }
    }

    // The file as it was, and the settings, which go back even when the file can't be written
    private static void restore(Path file, String original, CoordinateSettings before) {
        try {
            write(file, original);
        } finally {
            YoltCoordinates.swapForTesting(before);
        }
    }

    // Each line the mod's own logger logs while the task runs, which is within the tick. The appender joins the config
    // the logger already uses, which other loggers may use too, so it keeps the mod's lines only.
    private static List<String> logged(Runnable task) {
        String name = YoltCoordinates.LOGGER.getName();
        LoggerConfig config = ((Logger) LogManager.getLogger(name)).get();
        List<String> lines = new CopyOnWriteArrayList<>();
        AbstractAppender appender = new AbstractAppender("yolt-coordinates-gametest", null, null, false, Property.EMPTY_ARRAY) {
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

    // The text of each system message the player was sent from packet "from" on
    private static List<String> said(ServerPlayer player, int from) {
        return TestPlayers.all(player, from, ClientboundSystemChatPacket.class).stream()
                .map(packet -> packet.content().getString()).toList();
    }

    // The server's own source, as the console types, with what it is told caught in a list: only its failures, or only
    // its other replies, so a test can tell the two apart
    private static CommandSourceStack console(MinecraftServer server, List<String> said, boolean failures) {
        return server.createCommandSourceStack().withSource(new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                said.add(message.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return !failures;
            }

            @Override
            public boolean acceptsFailure() {
                return failures;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        });
    }

    // Through the server's own dispatcher, as a typed command runs
    private static int run(GameTestHelper helper, CommandSourceStack source, String command) {
        try {
            return helper.getLevel().getServer().getCommands().getDispatcher().execute(command, source);
        } catch (CommandSyntaxException e) {
            throw helper.assertionException("/" + command + " failed: " + e.getMessage());
        }
    }

    // The dispatcher skips a node the source may not use, so the command is unknown to it, as vanilla answers any
    // command a player may not use
    private static void refused(GameTestHelper helper, CommandSourceStack source, String command) {
        try {
            int result = helper.getLevel().getServer().getCommands().getDispatcher().execute(command, source);
            throw helper.assertionException("/" + command + " ran without op permission and returned " + result);
        } catch (CommandSyntaxException e) {
            helper.assertTrue(e.getType() == CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand(),
                    "/" + command + " was refused as " + e.getMessage() + ", not as an unknown command");
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path file, String text) {
        try {
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
