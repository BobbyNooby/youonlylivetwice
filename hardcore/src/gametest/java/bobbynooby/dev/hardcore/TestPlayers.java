package bobbynooby.dev.hardcore;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.StringUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * For the mod's own GameTests: players that join through PlayerList.placeNewPlayer, as a client's login does, whose far
 * end is a channel the test holds, so what the server tells them can be read; the console as a source whose replies can
 * be read; and the server's own dispatcher to run commands through.
 */
final class TestPlayers {
    // Tests share one server, so each name ends in a count no other player has
    private static final AtomicInteger COUNTER = new AtomicInteger();
    // The longest base that leaves room for a four-digit count within vanilla's 16 characters
    private static final int BASE_LIMIT = 12;
    private static final Map<UUID, EmbeddedChannel> CHANNELS = new ConcurrentHashMap<>();

    private TestPlayers() {}

    /**
     * A player who joins at a spot of the test's, then is put in the game mode asked for. Until then they are in the
     * test world's own game mode, creative, so anything that runs at the join sees a player the hardcore rules leave
     * out.
     */
    static ServerPlayer join(GameTestHelper helper, String base, GameType mode, BlockPos relativePos) {
        return join(helper, profile(base), mode, relativePos, Optional.empty());
    }

    /** A name and id no other test player has, for a player who joins, leaves and joins again as the same one. */
    static GameProfile profile(String base) {
        if (base.length() > BASE_LIMIT) {
            throw new IllegalArgumentException("The base " + base + " is longer than " + BASE_LIMIT);
        }
        String name = base + COUNTER.incrementAndGet();
        if (!StringUtil.isValidPlayerName(name)) {
            throw new IllegalStateException("No valid player name from the base " + base + ": " + name);
        }
        return new GameProfile(UUID.randomUUID(), name);
    }

    /** The player with this name and id joins, at a spot of the test's, as join with a base does. */
    static ServerPlayer join(GameTestHelper helper, GameProfile profile, GameType mode, BlockPos relativePos) {
        return join(helper, profile, mode, relativePos, Optional.empty());
    }

    /**
     * A returning player: their save is read before they are placed in the world, as a client's login reads it, so they
     * join in the game mode they left in and the join sees them as they are.
     */
    static ServerPlayer rejoin(GameTestHelper helper, GameProfile profile, BlockPos relativePos) {
        MinecraftServer server = helper.getLevel().getServer();
        Optional<ValueInput> saved = server.getPlayerList().loadPlayerData(new NameAndId(profile.id(), profile.name()))
                .map(tag -> TagValueInput.create(ProblemReporter.DISCARDING, server.registryAccess(), tag));
        if (saved.isEmpty()) {
            throw helper.assertionException("no save for " + profile.name() + " to join again with");
        }
        return join(helper, profile, null, relativePos, saved);
    }

    private static ServerPlayer join(GameTestHelper helper, GameProfile profile, @Nullable GameType mode,
                                     BlockPos relativePos, Optional<ValueInput> saved) {
        MinecraftServer server = helper.getLevel().getServer();
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
        ServerPlayer player = new ServerPlayer(server, helper.getLevel(), profile, cookie.clientInformation());
        saved.ifPresent(player::load);
        player.snapTo(helper.absolutePos(relativePos), 0.0F, 0.0F);
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        CHANNELS.put(profile.id(), new EmbeddedChannel(connection));
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        // setGameMode sends a packet, so it waits for the connection placeNewPlayer sets up
        if (mode != null) {
            player.setGameMode(mode);
        }
        return player;
    }

    /**
     * The player goes, off the op list too, whatever the test gave them. Closing the fake client's end, as a real
     * client's going does, ends Fabric's networking session for the connection. Nothing for a player who never joined.
     */
    static void leave(@Nullable ServerPlayer player) {
        if (player == null) {
            return;
        }
        try {
            deop(player);
        } finally {
            player.level().getServer().getPlayerList().remove(player);
            EmbeddedChannel channel = CHANNELS.remove(player.getUUID());
            if (channel != null) {
                // What was sent since the last read is delivered first, as a real client gets it before it goes, so
                // closing fails none of it: a failed chat line would be logged with vanilla's fallback warning
                channel.flushOutbound();
                channel.close();
            }
        }
    }

    /** The player with this id goes, whichever object a respawn made them. Nothing if they are away. */
    static void leave(MinecraftServer server, UUID id) {
        leave(server.getPlayerList().getPlayer(id));
    }

    // Vanilla keeps a player from being hurt until their client says the world is in, which a test player's fake client
    // never does
    static void markLoaded(ServerPlayer player) {
        player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
    }

    /**
     * A dead player comes back, straight through the player list, as the death screen's button ends up doing. The new
     * player goes on the connection and its position is reset, as vanilla's own callers do, so the connection never
     * goes on with the one who died.
     */
    static ServerPlayer respawn(ServerPlayer dead) {
        ServerPlayer back = dead.level().getServer().getPlayerList().respawn(dead, false, Entity.RemovalReason.KILLED);
        back.connection.player = back;
        back.connection.resetPosition();
        return back;
    }

    // The test server gives ops level 0 unless told otherwise, below the level the commands need
    static void op(ServerPlayer player) {
        player.level().getServer().getPlayerList().op(player.nameAndId(), Optional.of(LevelBasedPermissionSet.GAMEMASTER),
                Optional.empty());
    }

    static void deop(ServerPlayer player) {
        player.level().getServer().getPlayerList().deop(player.nameAndId());
    }

    /** How many packets the server has sent this player so far, to read only what comes after. */
    static int mark(ServerPlayer player) {
        return sent(player).size();
    }

    /** The text of each chat line the server has sent this player from packet "from" on, the action bar left out. */
    static List<String> said(ServerPlayer player, int from) {
        return lines(player, from, false);
    }

    /** The text of each line the server has put over this player's hotbar from packet "from" on. */
    static List<String> actionBars(ServerPlayer player, int from) {
        return lines(player, from, true);
    }

    /** What /status tells the player, run as they would type it. */
    static List<String> status(GameTestHelper helper, ServerPlayer player) {
        int from = mark(player);
        helper.assertValueEqual(run(helper, player.createCommandSourceStack(), "status"), 1, "what /status returned");
        return said(player, from);
    }

    private static List<String> lines(ServerPlayer player, int from, boolean overlay) {
        List<Object> sent = sent(player);
        List<String> lines = new ArrayList<>();
        for (Object message : sent.subList(from, sent.size())) {
            if (message instanceof ClientboundSystemChatPacket chat && chat.overlay() == overlay) {
                lines.add(chat.content().getString());
            }
        }
        return lines;
    }

    private static List<Object> sent(ServerPlayer player) {
        EmbeddedChannel channel = CHANNELS.get(player.getUUID());
        // Sends during a tick wait in the channel until the end of the tick
        channel.flushOutbound();
        return new ArrayList<>(channel.outboundMessages());
    }

    /**
     * The server's own source, as the console types, with what it is told caught in a list: only its failures, or only
     * its other replies, so a test can tell the two apart.
     */
    static CommandSourceStack console(MinecraftServer server, List<String> said, boolean failures) {
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

    /** What the console is told when it runs the command: its replies, or with failures true, its failures. */
    static List<String> console(GameTestHelper helper, String command, boolean failures, int expectedResult) {
        List<String> said = new ArrayList<>();
        int result = run(helper, console(helper.getLevel().getServer(), said, failures), command);
        helper.assertValueEqual(result, expectedResult, "what /" + command + " returned");
        return said;
    }

    /** Through the server's own dispatcher, as a typed command runs. */
    static int run(GameTestHelper helper, CommandSourceStack source, String command) {
        try {
            return helper.getLevel().getServer().getCommands().getDispatcher().execute(command, source);
        } catch (CommandSyntaxException e) {
            throw helper.assertionException("/" + command + " failed: " + e.getMessage());
        }
    }

    /**
     * The dispatcher skips a node the source may not use, so the command is unknown to it, as vanilla answers any
     * command a player may not use.
     */
    static void refused(GameTestHelper helper, CommandSourceStack source, String command) {
        try {
            int result = helper.getLevel().getServer().getCommands().getDispatcher().execute(command, source);
            throw helper.assertionException("/" + command + " ran without op permission and returned " + result);
        } catch (CommandSyntaxException e) {
            helper.assertTrue(e.getType() == CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand(),
                    "/" + command + " was refused as " + e.getMessage() + ", not as an unknown command");
        }
    }
}
