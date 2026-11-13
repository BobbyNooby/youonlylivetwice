package bobbynooby.dev.commands;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.lockout.DeathLockout;
import bobbynooby.dev.skins.SkinOwners;
import bobbynooby.dev.skins.SkinStore;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** The /yolt admin commands. Every reply and log line works from the console too, which has no player. */
public final class YoltCommands {
    // Each subcommand needs it, apart from /yolt skin for a player's own skin
    static final Predicate<CommandSourceStack> GAMEMASTER = Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);

    private YoltCommands() {}

    public static void register() {
        SkinCommands.init();
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> {
            LiteralArgumentBuilder<CommandSourceStack> yolt = Commands.literal("yolt")
                    // Commands are registered before any server exists, so single-player is checked on each use. A
                    // non-op may use it for /yolt skin. Vanilla sends a player only the nodes they can use, so they
                    // see nothing else of it.
                    .requires(source -> Settings.active() && (GAMEMASTER.test(source) || SkinCommands.playersMayPick()));
            // The order the subcommands are sent to clients and listed by /help in
            BorderCommands.register(yolt);
            LockoutCommands.register(yolt);
            GraveCommands.register(yolt);
            AdminCommands.register(yolt);
            ConfigCommands.register(yolt);
            SkinCommands.register(yolt);
            dispatcher.register(yolt);
        });
    }

    /** Run when a server has started, before the border is applied: holds a saved border that would strand what can't move. */
    public static void holdStrandingBorder(MinecraftServer server) {
        BorderCommands.holdIfStranding(server);
    }

    static int fail(CommandSourceStack source, String text) {
        source.sendFailure(Component.literal(text));
        return 0;
    }

    static int done(CommandSourceStack source, String reply) {
        reply(source, reply);
        return 1;
    }

    // The player a command names, from what this server knows, in any case. Mojang is never asked: vanilla's own
    // lookup would on a miss, on the server thread, and on an offline-mode server could give the account's online id,
    // which an offline login never has. Online players first, so the exact player is used. Then the ids vanilla's
    // cache holds, the death log, which keeps a locked-out player who can't join, and the id each player last joined
    // with, which a forwarding proxy may have given. A player who never joined has no save to act on, so is not found.
    // The first one in the same case as typed wins over any before it: an offline-mode server gives "Steve" and "steve"
    // different ids, and every source but the last ignores case.
    static Optional<NameAndId> lookup(MinecraftServer server, String name) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getGameProfile().name().equals(name)) {
                return Optional.of(player.nameAndId());
            }
        }
        List<Supplier<Optional<NameAndId>>> sources = List.of(
                () -> Optional.ofNullable(server.getPlayerList().getPlayerByName(name)).map(ServerPlayer::nameAndId),
                // Only an id that played here: vanilla's own commands can leave an account's online id in an offline
                // server's cache
                () -> SkinOwners.cached(server.services().nameToIdCache(), name)
                        .filter(found -> SkinOwners.playedHere(server, found.id())),
                () -> DeathLockout.latestByName(name),
                () -> SkinStore.get(server).joinedUnder(name));
        NameAndId otherCase = null;
        for (Supplier<Optional<NameAndId>> source : sources) {
            Optional<NameAndId> found = source.get();
            if (found.isPresent() && found.get().name().equals(name)) {
                return found;
            }
            if (otherCase == null && found.isPresent()) {
                otherCase = found.get();
            }
        }
        return Optional.ofNullable(otherCase);
    }

    /** The reply when lookup finds no one. */
    static String notJoined(String name) {
        return "No player named " + name + " has joined this server.";
    }

    static void reply(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), false);
    }
}
