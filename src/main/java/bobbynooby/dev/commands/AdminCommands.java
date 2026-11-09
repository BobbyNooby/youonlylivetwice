package bobbynooby.dev.commands;

import bobbynooby.dev.config.ConfigLoader;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.visibility.AdminView;
import bobbynooby.dev.world.LiveSettings;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.border.WorldBorder;

import java.util.ArrayList;
import java.util.List;

import static bobbynooby.dev.core.Yolt.LOGGER;

/** /yolt adminview and reload. */
final class AdminCommands {
    private AdminCommands() {}

    static void register(LiteralArgumentBuilder<CommandSourceStack> yolt) {
        yolt.then(Commands.literal("adminview")
                        .executes(c -> adminView(c.getSource())))
                .then(Commands.literal("reload")
                        .executes(c -> reload(c.getSource())));
    }

    // Admin view is about what a client is sent, so the console gets vanilla's "a player is required" error
    private static int adminView(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        boolean on = AdminView.toggle(player);
        LOGGER.info("{} turned admin view {}", source.getTextName(), on ? "on" : "off");
        return YoltCommands.done(source, on ? "Admin view on" : "Admin view off");
    }

    private static int reload(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        YoltConfig.WorldBorderSection liveBorder = Settings.get().worldBorder;
        ConfigLoader.Result result = Settings.reload();
        if (result.unparseable()) {
            String problem = String.join(", ", result.warnings());
            LOGGER.warn("{} tried to reload the config: {}", source.getTextName(), problem);
            return YoltCommands.fail(source, "Config not reloaded, nothing changed: " + problem);
        }
        List<String> warnings = new ArrayList<>(result.warnings());
        // A reload can't be confirmed, so a border that would leave someone outside waits for /yolt border and the others
        YoltConfig.WorldBorderSection reloaded = Settings.get().worldBorder;
        WorldBorder overworld = server.overworld().getWorldBorder();
        List<String> outside = BorderCommands.stranded(server, reloaded.diameter, reloaded.endScale,
                overworld.getCenterX(), overworld.getCenterZ());
        if (!outside.isEmpty()) {
            Settings.get().worldBorder = liveBorder;
            warnings.add("World border kept as it was, since the new one would leave outside it: " + String.join(", ", outside) + ".");
        }
        LiveSettings.applyAll(server);

        LOGGER.info("{} reloaded the config", source.getTextName());
        YoltCommands.reply(source, "Config reloaded");
        for (String warning : warnings) {
            LOGGER.warn("Config: {}", warning);
            YoltCommands.reply(source, warning);
        }
        return 1;
    }
}
