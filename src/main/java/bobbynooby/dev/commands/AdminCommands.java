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

import java.util.ArrayList;
import java.util.List;

import static bobbynooby.dev.core.Yolt.LOGGER;

/** /yolt adminview and reload. */
final class AdminCommands {
    private AdminCommands() {}

    static void register(LiteralArgumentBuilder<CommandSourceStack> yolt) {
        yolt.then(Commands.literal("adminview")
                        .requires(YoltCommands.GAMEMASTER)
                        .executes(c -> adminView(c.getSource())))
                .then(Commands.literal("reload")
                        .requires(YoltCommands.GAMEMASTER)
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
        boolean applyBorder = true;
        if (Settings.borderHeld() && result.borderProblem() != null) {
            // A bad border key keeps the live value, and while the border is held that is a startup default, not the world's
            String held = "World border still not applied, since " + result.borderProblem() + ".";
            if (result.rewritten()) {
                // The reload has already written that value over the typo, and the startup may have kept the admin's file
                String backup = Settings.startupBackup();
                warnings.add(held + " The reload saved the value in use over it, so set it again in the file, then reload."
                        + (backup == null ? "" : " The file as it was at startup is in " + backup + "."));
            } else {
                // A newer config is never rewritten, so the typo is still in the file
                warnings.add(held + " Fix it, then reload again.");
            }
        } else {
            // A reload can't be confirmed, so a border that would leave someone outside waits for /yolt border and the others.
            // An unchanged file is checked too: what counts is the world's own border, which a timed /worldborder can have
            // moved away from the config.
            YoltConfig.WorldBorderSection reloaded = Settings.get().worldBorder;
            BorderCommands.Outside outside = BorderCommands.newlyStranded(server, reloaded.diameter, reloaded.endScale);
            if (!outside.names().isEmpty()) {
                // The world's borders stay exactly as they are, and the live values as they were
                Settings.get().worldBorder = liveBorder;
                applyBorder = false;
                // A restart checks it again for the world spawn, the End platform, bodies and graves, but not for players,
                // who are all offline then: a border kept only for online players is applied at the next restart
                warnings.add("World border kept as it was, since the config's border would leave " + BorderCommands.listed(outside.names())
                        + " outside it. Use /yolt border or endscaling with confirm to apply it now, or change the file, then run"
                        + " /yolt reload.");
            } else {
                Settings.releaseBorder();
                // A world spawn left outside only blocks while new players start on it, so with random spawn it is just said
                if (!outside.spawnNote().isEmpty()) {
                    warnings.add(outside.spawnNote().strip());
                }
            }
        }
        LiveSettings.applyAll(server, applyBorder);

        LOGGER.info("{} reloaded the config", source.getTextName());
        YoltCommands.reply(source, "Config reloaded.");
        for (String warning : warnings) {
            LOGGER.warn("Config: {}", warning);
            YoltCommands.reply(source, warning);
        }
        return 1;
    }
}
