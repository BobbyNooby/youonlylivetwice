package bobbynooby.dev.commands;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.lockout.DeathLockout;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import java.util.Optional;
import java.util.function.Predicate;

/** The /yolt admin commands. Every reply and log line works from the console too, which has no player. */
public final class YoltCommands {
    private static final Predicate<CommandSourceStack> GAMEMASTER = Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);

    private YoltCommands() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> {
            LiteralArgumentBuilder<CommandSourceStack> yolt = Commands.literal("yolt")
                    // Commands are registered before any server exists, so single-player is checked on each use
                    .requires(source -> Settings.active() && GAMEMASTER.test(source));
            // The order the subcommands are sent to clients and listed by /help in
            BorderCommands.register(yolt);
            LockoutCommands.register(yolt);
            GraveCommands.register(yolt);
            AdminCommands.register(yolt);
            SkinCommands.register(yolt);
            dispatcher.register(yolt);
        });
    }

    static int fail(CommandSourceStack source, String text) {
        source.sendFailure(Component.literal(text));
        return 0;
    }

    static int done(CommandSourceStack source, String reply) {
        reply(source, reply);
        return 1;
    }

    // Online players first, so the exact player is used even if the name cache is out of date. Then the death log: a
    // locked-out player can't join, so the cache never refreshes them, and once their entry expires it may give
    // another id for the name.
    static Optional<NameAndId> lookup(MinecraftServer server, String name) {
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) {
            return Optional.of(online.nameAndId());
        }
        Optional<NameAndId> died = DeathLockout.latestByName(name);
        if (died.isPresent()) {
            return died;
        }
        return server.services().nameToIdCache().get(name);
    }

    static void reply(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), false);
    }
}
