package bobbynooby.dev.commands;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.Yolt;
import bobbynooby.dev.hardcore.BloodMoon;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import java.util.OptionalDouble;

/** /yolt bloodmoon, for testing a server's blood moon: starts or ends one now, or says whether one is up. */
final class BloodMoonCommands {
    private BloodMoonCommands() {}

    static void register(LiteralArgumentBuilder<CommandSourceStack> yolt) {
        yolt.then(Commands.literal("bloodmoon")
                .requires(YoltCommands.GAMEMASTER)
                .then(Commands.literal("start").executes(c -> start(c.getSource())))
                .then(Commands.literal("stop").executes(c -> stop(c.getSource())))
                .then(Commands.literal("status").executes(c -> status(c.getSource()))));
    }

    private static int start(CommandSourceStack source) {
        if (!on()) {
            return off(source);
        }
        if (BloodMoon.active()) {
            return YoltCommands.fail(source, "A blood moon is up already.");
        }
        BloodMoon.start(source.getServer());
        Yolt.LOGGER.info("{} started a blood moon", source.getTextName());
        return YoltCommands.done(source, "Started a blood moon.");
    }

    private static int stop(CommandSourceStack source) {
        if (!on()) {
            return off(source);
        }
        if (!BloodMoon.active()) {
            return YoltCommands.fail(source, "No blood moon is up.");
        }
        BloodMoon.stop(source.getServer());
        Yolt.LOGGER.info("{} stopped the blood moon", source.getTextName());
        return YoltCommands.done(source, "Stopped the blood moon.");
    }

    private static int status(CommandSourceStack source) {
        if (!on()) {
            return off(source);
        }
        OptionalDouble left = BloodMoon.left();
        if (left.isPresent()) {
            return YoltCommands.done(source, "A blood moon is up, with " + Math.round(left.getAsDouble() * 100.0)
                    + "% of it left.");
        }
        if (BloodMoon.tonight()) {
            return YoltCommands.done(source, "A blood moon rises tonight.");
        }
        return YoltCommands.done(source, "No blood moon is up.");
    }

    private static boolean on() {
        return Settings.on(c -> c.hardcore.bloodMoon.enabled);
    }

    private static int off(CommandSourceStack source) {
        return YoltCommands.fail(source, "The blood moon is turned off.");
    }
}
