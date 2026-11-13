package bobbynooby.dev.commands;

import bobbynooby.dev.chat.CommandRestriction;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.hardcore.Status;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/** /status, a player's report on their own body, and /yolt status for an op to read anyone's who is online. */
public final class StatusCommands {
    private StatusCommands() {}

    // Run once, at startup
    static void init() {
        CommandRestriction.useStatusCommand(StatusCommands::command);
    }

    /** /status, the same node in the real tree and in a non-op's, so what the client is sent is what the server reads. */
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        // No argument: a player can only ever read their own body
        return Commands.literal("status")
                .requires(source -> Settings.active())
                .executes(c -> own(c.getSource()));
    }

    static void register(LiteralArgumentBuilder<CommandSourceStack> yolt) {
        yolt.then(Commands.literal("status")
                .requires(YoltCommands.GAMEMASTER)
                .then(Commands.argument("player", StringArgumentType.string())
                        .executes(c -> other(c.getSource(), StringArgumentType.getString(c, "player")))));
    }

    private static int own(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return YoltCommands.fail(source, "Only a player has a body to report on.");
        }
        // Straight to the player, so the report shows even with command feedback turned off
        for (Component line : Status.report(player)) {
            player.sendSystemMessage(line);
        }
        return 1;
    }

    // Online players only: every line reads the player as they are now
    private static int other(CommandSourceStack source, String name) {
        ServerPlayer player = source.getServer().getPlayerList().getPlayerByName(name);
        if (player == null) {
            return YoltCommands.fail(source, name + " is not online.");
        }
        reply(source, Component.literal(player.nameAndId().name() + "'s body"));
        List<Component> lines = Status.lines(player);
        if (lines.isEmpty()) {
            reply(source, Status.nothingToReport());
        }
        for (Component line : lines) {
            reply(source, line);
        }
        return 1;
    }

    private static void reply(CommandSourceStack source, Component line) {
        source.sendSuccess(() -> line, false);
    }
}
