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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
            assertSentShifted(helper, player, "survival");
        } finally {
            if (player != null) {
                TestPlayers.leave(player);
            }
            YoltCoordinates.swapForTesting(before);
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
            if (player != null) {
                TestPlayers.leave(player);
            }
            YoltCoordinates.swapForTesting(before);
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
            if (player != null) {
                TestPlayers.leave(player);
            }
            YoltCoordinates.swapForTesting(before);
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
            if (player != null) {
                TestPlayers.leave(player);
            }
            write(file, original);
            YoltCoordinates.swapForTesting(before);
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
            if (player != null) {
                leaveAsOp(helper, player);
            }
            YoltCoordinates.swapForTesting(before);
        }
        helper.succeed();
    }

    // Creative and spectator players are shifted too: on its own the mod has no one it leaves out
    @GameTest
    public void aCreativePlayerIsShifted(GameTestHelper helper) {
        CoordinateSettings before = YoltCoordinates.swapForTesting(CoordinateSettings.DEFAULTS);
        ServerPlayer player = null;
        try {
            player = TestPlayers.join(helper, "creative", GameType.CREATIVE, SPOT);
            assertSentShifted(helper, player, "creative");
        } finally {
            if (player != null) {
                TestPlayers.leave(player);
            }
            YoltCoordinates.swapForTesting(before);
        }
        helper.succeed();
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
            helper.assertFalse(YoltCoordinates.seesReal(player), "a refused real gave the player real coordinates");

            TestPlayers.op(player);
            CommandSourceStack op = player.createCommandSourceStack();
            helper.assertValueEqual(run(helper, op, "yolt-coordinates"), 1, "/yolt-coordinates as an op");
            helper.assertValueEqual(run(helper, op, "yolt-coordinates reload"), 1, "/yolt-coordinates reload as an op");
            helper.assertFalse(YoltCoordinates.settings().enabled(), "still on after an op reloaded a file that turns it off");
            helper.assertValueEqual(run(helper, op, "yolt-coordinates real"), 1, "/yolt-coordinates real as an op");
            helper.assertTrue(YoltCoordinates.seesReal(player), "an op's real gave no real coordinates");
        } finally {
            if (player != null) {
                leaveAsOp(helper, player);
            }
            write(file, original);
            YoltCoordinates.swapForTesting(before);
        }
        helper.succeed();
    }

    // The console always works in real coordinates, so it is told why there is nothing to turn on, not given an error
    @GameTest
    public void theConsoleHasNoCoordinatesToShow(GameTestHelper helper) {
        List<String> said = new ArrayList<>();
        CommandSourceStack console = console(helper.getLevel().getServer(), said);
        helper.assertValueEqual(run(helper, console, "yolt-coordinates real"), 0, "/yolt-coordinates real from the console");
        helper.assertValueEqual(said, List.of("Only a player has coordinates to show."), "what the console was told");
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
            helper.assertValueEqual(run(helper, console(helper.getLevel().getServer(), said), "yolt-coordinates reload"), 1,
                    "/yolt-coordinates reload");
            helper.assertValueEqual(said, List.of("Reloaded yolt-coordinates.json. The coordinate offset is off"), "the reply");
            player = TestPlayers.join(helper, "reloaded", GameType.SURVIVAL, SPOT);
            assertSentReal(helper, player, "a join after the reload");
        } finally {
            if (player != null) {
                TestPlayers.leave(player);
            }
            write(file, original);
            YoltCoordinates.swapForTesting(before);
        }
        helper.succeed();
    }

    // The join's teleport, which puts the client where the player is, goes out moved by the player's offset on X and Z
    private static void assertSentShifted(GameTestHelper helper, ServerPlayer player, String who) {
        Offset offset = CoordinateOffsets.offset(player);
        helper.assertFalse(offset.isZero(), "the " + who + " join got no offset");
        Vec3 sent = TestPlayers.last(helper, player, 0, ClientboundPlayerPositionPacket.class).change().position();
        helper.assertValueEqual(sent, new Vec3(player.getX() + offset.x(), player.getY(), player.getZ() + offset.z()),
                "the position the " + who + " join sent");
    }

    private static void assertSentReal(GameTestHelper helper, ServerPlayer player, String what) {
        helper.assertValueEqual(CoordinateOffsets.offset(player), Offset.ZERO, "the offset of " + what);
        Vec3 sent = TestPlayers.last(helper, player, 0, ClientboundPlayerPositionPacket.class).change().position();
        helper.assertValueEqual(sent, player.position(), "the position " + what + " sent");
    }

    // Real coordinates off through the command while the player is still an op, then the player goes. Each step runs
    // even when one before it fails.
    private static void leaveAsOp(GameTestHelper helper, ServerPlayer player) {
        try {
            if (YoltCoordinates.seesReal(player)) {
                run(helper, player.createCommandSourceStack(), "yolt-coordinates real");
            }
        } finally {
            try {
                TestPlayers.deop(player);
            } finally {
                TestPlayers.leave(player);
            }
        }
    }

    // The text of each system message the player was sent from packet "from" on
    private static List<String> said(ServerPlayer player, int from) {
        return TestPlayers.all(player, from, ClientboundSystemChatPacket.class).stream()
                .map(packet -> packet.content().getString()).toList();
    }

    // The server's own source, as the console types, with what it is told caught in a list
    private static CommandSourceStack console(MinecraftServer server, List<String> said) {
        return server.createCommandSourceStack().withSource(new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                said.add(message.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
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
