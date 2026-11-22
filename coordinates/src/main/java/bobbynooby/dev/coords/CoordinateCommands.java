package bobbynooby.dev.coords;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * /yolt-coordinates real, which turns real coordinates on or off for the op who runs it. {@link YoltCoordinates#MOD}
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

    // An offset only changes when a frame starts, so the op's coordinates follow at their next one, and the reply says
    // so
    private static int real(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        String refusal = refusal(YoltCoordinates.active(), player != null);
        if (refusal != null) {
            source.sendFailure(Component.literal(refusal));
            return 0;
        }
        boolean on = YoltCoordinates.toggleReal(player);
        YoltCoordinates.LOGGER.info("{} turned real coordinates {}", source.getTextName(), on ? "on" : "off");
        source.sendSuccess(() -> Component.literal(on
                ? "You see real coordinates from your next join, respawn or dimension change."
                : "You see shifted coordinates again from your next join, respawn or dimension change."), false);
        return 1;
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
