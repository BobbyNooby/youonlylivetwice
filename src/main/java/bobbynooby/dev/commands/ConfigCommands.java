package bobbynooby.dev.commands;

import bobbynooby.dev.config.ConfigKey;
import bobbynooby.dev.config.ConfigKeys;
import bobbynooby.dev.config.ConfigLoader;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.world.LiveSettings;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static bobbynooby.dev.core.Yolt.LOGGER;

/** /yolt config: shows one config key, or changes it in the file and the live config at once. */
final class ConfigCommands {
    private static final String BORDER_KEYS = "worldBorder.";
    private static final String CONFIRM = " confirm";
    private static final Pattern LOG_FORM = Pattern.compile("([\\w.]+): (.+)");
    private static final Pattern STARTS_WITH_KEY = Pattern.compile("\\w+\\.[\\w.]+ .*");
    private static final String HELD = "The world border is still held as the world saved it, since the config's border couldn't"
            + " be used at startup. Run /yolt reload to apply the file's border, or /yolt border to set one now.";

    private ConfigCommands() {}

    static void register(LiteralArgumentBuilder<CommandSourceStack> yolt) {
        yolt.then(Commands.literal("config")
                .requires(YoltCommands.GAMEMASTER)
                // One word, which may hold the dots of a key's path
                .then(Commands.argument("key", StringArgumentType.word())
                        .suggests((c, builder) -> SharedSuggestionProvider.suggest(ConfigKeys.all().stream().map(ConfigKey::path),
                                builder))
                        .executes(c -> show(c.getSource(), StringArgumentType.getString(c, "key")))
                        // The rest of the line, so a border value can end in confirm
                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                .suggests((c, builder) -> SharedSuggestionProvider.suggest(choices(StringArgumentType.getString(c, "key")),
                                        builder))
                                .executes(c -> set(c.getSource(), StringArgumentType.getString(c, "key"),
                                        StringArgumentType.getString(c, "value"))))));
    }

    // true and false, an enum's names or offline; nothing for a number
    private static List<String> choices(String name) {
        return ConfigKeys.find(name).map(ConfigKey::choices).orElse(List.of());
    }

    private static int show(CommandSourceStack source, String name) {
        Optional<ConfigKey> key = ConfigKeys.find(name);
        if (key.isEmpty()) {
            return notAKey(source, name);
        }
        return YoltCommands.done(source, name + " is " + key.get().show(Settings.get()) + ".");
    }

    private static int set(CommandSourceStack source, String name, String typed) {
        Optional<ConfigKey> found = ConfigKeys.find(name);
        if (found.isEmpty()) {
            return notAKey(source, name);
        }
        ConfigKey key = found.get();
        MinecraftServer server = source.getServer();
        // Only a border change can leave someone outside, so only its value may end in confirm
        boolean border = name.startsWith(BORDER_KEYS);
        boolean confirmed = border && typed.endsWith(CONFIRM);
        String text = confirmed ? typed.substring(0, typed.length() - CONFIRM.length()) : typed;

        // Set by the check, which runs once the value has passed the config's own checks and before anything is saved
        BorderCommands.Outside[] outside = {BorderCommands.Outside.NONE};
        ConfigLoader.Change change = Settings.set(key, text, next -> {
            // A held border isn't applied, and a reload or border command checks it before it is. A value already live,
            // only missing from the file, moves no border.
            if (!border || Settings.borderHeld() || key.show(next).equals(key.show(Settings.get()))) {
                return null;
            }
            outside[0] = BorderCommands.stranded(server, next.worldBorder);
            return BorderCommands.refusal(outside[0], confirmed);
        });
        if (change.problem() != null) {
            return failed(source, change);
        }
        if (change.config() == null) {
            return YoltCommands.done(source, name + " is already " + change.newValue() + ".");
        }
        // The live config had it already and only the file was behind, so there is nothing to apply
        if (change.oldValue().equals(change.newValue())) {
            LOGGER.info("{} set config {} from {} to {}", source.getTextName(), name, change.oldValue(), change.newValue());
            YoltCommands.reply(source, name + " is already " + change.newValue() + ", and the config file now says so too.");
            tellBackup(source, change);
            return 1;
        }

        // Only a border key moves the border. Applying it for any other key could put back a border a reload refused.
        LiveSettings.applyAll(server, border);
        LOGGER.info("{} set config {} from {} to {}", source.getTextName(), name, change.oldValue(), change.newValue());
        YoltCommands.reply(source, name + " changed from " + change.oldValue() + " to " + change.newValue() + key.when().note() + ".");
        tellBackup(source, change);
        if (border && change.config().worldBorder.enabled) {
            if (Settings.borderHeld()) {
                YoltCommands.reply(source, HELD);
            } else if (!outside[0].spawnNote().isEmpty()) {
                YoltCommands.reply(source, outside[0].spawnNote().strip());
            }
        }
        return 1;
    }

    private static int notAKey(CommandSourceStack source, String name) {
        if (ConfigKeys.fileOnly(name)) {
            return YoltCommands.fail(source, name + " can only be changed in the config file. Change it there, then run /yolt reload.");
        }
        return YoltCommands.fail(source, "No config key is named " + name + ".");
    }

    /** Fails a command with the change's problem, saying where the admin's file went if it couldn't be read. */
    static int failed(CommandSourceStack source, ConfigLoader.Change change) {
        YoltCommands.fail(source, sentence(change.problem()));
        tellBackup(source, change);
        return 0;
    }

    // The config's own problems are in its log form, "<key>: <problem>". A reply reads "<key> <problem>.", or the problem
    // alone when another key is its subject, as in the End cap's. A plain word at the start gets a capital; a key keeps its case.
    private static String sentence(String problem) {
        String text = problem;
        Matcher logForm = LOG_FORM.matcher(problem);
        if (logForm.matches()) {
            String rest = logForm.group(2);
            text = STARTS_WITH_KEY.matcher(rest).matches() ? rest : logForm.group(1) + " " + rest;
        }
        if (Character.isLowerCase(text.charAt(0)) && !text.split(" ", 2)[0].contains(".")) {
            text = Character.toUpperCase(text.charAt(0)) + text.substring(1);
        }
        return text.endsWith(".") ? text : text + ".";
    }

    /** Says where the admin's file went when it couldn't be read, whether or not the rewrite then worked. */
    static void tellBackup(CommandSourceStack source, ConfigLoader.Change change) {
        if (change.backup() != null) {
            YoltCommands.reply(source, "Your config file couldn't be read, so it was kept as " + change.backup()
                    + (change.config() != null ? " and rewritten." : "."));
        }
    }
}
