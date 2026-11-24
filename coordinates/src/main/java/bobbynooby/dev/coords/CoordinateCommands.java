package bobbynooby.dev.coords;

import bobbynooby.dev.coords.mixin.CommandSourceStackAccessor;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.rcon.RconConsoleSource;
import net.minecraft.world.level.BaseCommandBlock;
import org.jspecify.annotations.Nullable;

/**
 * /yolt-coordinates real, which turns real coordinates on or off for the player it runs as. {@link YoltCoordinates#MOD}
 * registers /yolt-coordinates and its reload; Brigadier merges a literal registered again under the same name into the
 * one there, so this adds one more branch under it. Only while the mod runs on its own, as a host has its own way.
 */
final class CoordinateCommands {
    private CoordinateCommands() {}

    static void init() {
        // Commands are built after every onInitialize, so a host is known by then
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> {
            if (!YoltCoordinates.hosted()) {
                register(dispatcher);
            }
        });
    }

    // The merged node keeps the permission of whichever literal is registered first, so both carry the same one
    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(YoltCoordinates.MOD_ID)
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("real").executes(context -> real(context.getSource()))));
    }

    // An offset only changes when a frame starts, so the player's coordinates follow at their next one, and the reply
    // says so. Run through /execute as, the command is another player's, while its reply still goes to whoever ran it,
    // so the reply names that player, and the log names both.
    private static int real(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        String refusal = refusal(YoltCoordinates.active(), player != null);
        if (refusal != null) {
            source.sendFailure(Component.literal(refusal));
            return 0;
        }
        String name = player.getPlainTextName();
        // Only an op is ever given real coordinates, by the same check as the mod's own hook, so for anyone else the
        // entry would do nothing until they were opped, and then hand them real coordinates unasked. The command itself
        // needs that level, so this is a source with more than the player has, such as /execute as run by an op.
        if (!Commands.LEVEL_GAMEMASTERS.check(player.permissions())) {
            source.sendFailure(Component.literal(name + " is not an op, and only an op sees real coordinates."));
            return 0;
        }
        boolean on = YoltCoordinates.toggleReal(player);
        CommandSource caller = ((CommandSourceStackAccessor) source).yoltCoordinatesSource();
        boolean own = caller == player.commandSource();
        if (own) {
            YoltCoordinates.LOGGER.info("{} turned real coordinates {}", name, on ? "on" : "off");
        } else {
            YoltCoordinates.LOGGER.info("{} turned real coordinates {} for {}", callerName(source.getServer(), caller),
                    on ? "on" : "off", name);
        }
        String reply = reply(own ? null : name, on, YoltCoordinates.settings().enabled());
        source.sendSuccess(() -> Component.literal(reply), false);
        return 1;
    }

    /**
     * What /yolt-coordinates real says: to the player it is for, or, when someone else ran it as them, naming them.
     * With the offset off nobody is shifted at all, so then it says the command changes nothing until the offset is
     * on.
     */
    static String reply(@Nullable String someoneElse, boolean on, boolean offsetOn) {
        String sees = on ? " real coordinates" : " shifted coordinates again";
        String text = someoneElse == null
                ? "You see" + sees + " from your next join, respawn or dimension change."
                : someoneElse + " sees" + sees + " from their next join, respawn or dimension change.";
        return offsetOn ? text : text + " The coordinate offset is off, so this changes nothing until it is on.";
    }

    // For the log: a player by name, the console and RCON as vanilla names them, and a command block as one. A command
    // block's source class is protected, so it is known by the class it is declared in. Anything else, such as a
    // function, has no name to go by here.
    private static String callerName(MinecraftServer server, CommandSource caller) {
        if (caller instanceof MinecraftServer) {
            return "Server";
        }
        if (caller instanceof RconConsoleSource) {
            return "Rcon";
        }
        if (caller.getClass().getEnclosingClass() == BaseCommandBlock.class) {
            return "A command block";
        }
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            if (online.commandSource() == caller) {
                return online.getPlainTextName();
            }
        }
        return "Another source";
    }

    /**
     * Why /yolt-coordinates real can't be run, or null when it can. In a single-player world nothing is hidden. The
     * console, a command block or any other entity that isn't a player has no client to show coordinates to.
     */
    static @Nullable String refusal(boolean active, boolean player) {
        if (!active) {
            return YoltCoordinates.SINGLE_PLAYER + ".";
        }
        return player ? null : "Only a player has coordinates to show.";
    }
}
