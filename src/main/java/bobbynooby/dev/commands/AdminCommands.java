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
        return YoltCommands.done(source, on ? "Admin view on." : "Admin view off.");
    }

    private static int reload(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        YoltConfig live = Settings.get();
        YoltConfig.WorldBorderSection liveBorder = live.worldBorder;
        ConfigLoader.Result result = Settings.reload();
        // With no file the loader hands back the live config itself: nothing was read, so it is refused like a broken file
        if (result.unparseable() || result.config() == live) {
            String problem = String.join(", ", result.warnings());
            LOGGER.warn("{} tried to reload the config: {}", source.getTextName(), problem);
            return YoltCommands.fail(source, "Config not reloaded, nothing changed: " + problem);
        }
        List<String> warnings = new ArrayList<>(result.warnings());
        YoltConfig.WorldBorderSection reloaded = Settings.get().worldBorder;
        if (Settings.borderHeld() && result.borderProblem() != null) {
            // A bad border key keeps the live value, and while the border is held that is a startup default, not the world's
            warnings.add("World border still not applied, since " + result.borderProblem() + ". Fix it, then reload again.");
        } else {
            // A reload can't be confirmed, so a border that would leave someone outside waits for /yolt border and the others.
            // An unchanged border strands nobody new, so it isn't checked; a held border was never applied, so it always is.
            boolean borderChanged = Settings.borderHeld() || reloaded.enabled != liveBorder.enabled
                    || reloaded.diameter != liveBorder.diameter || Double.compare(reloaded.endScale, liveBorder.endScale) != 0;
            WorldBorder overworld = server.overworld().getWorldBorder();
            List<String> outside = borderChanged
                    ? BorderCommands.newlyStranded(server, reloaded.diameter, reloaded.endScale, overworld.getCenterX(), overworld.getCenterZ())
                    : List.of();
            if (!outside.isEmpty()) {
                Settings.get().worldBorder = liveBorder;
                warnings.add("World border kept as it was, since the new one would leave " + BorderCommands.listed(outside)
                        + " outside it. The file still holds the new border and a restart will apply it; use /yolt border or endscaling"
                        + " with confirm to apply it now, or change the file back.");
            } else {
                Settings.releaseBorder();
            }
        }
        LiveSettings.applyAll(server);

        LOGGER.info("{} reloaded the config", source.getTextName());
        YoltCommands.reply(source, "Config reloaded.");
        for (String warning : warnings) {
            LOGGER.warn("Config: {}", warning);
            YoltCommands.reply(source, warning);
        }
        return 1;
    }
}
