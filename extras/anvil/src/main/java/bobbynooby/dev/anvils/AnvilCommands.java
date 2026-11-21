package bobbynooby.dev.anvils;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * /yolt-anvils shows the settings in use, and /yolt-anvils reload reads the file again. Ops only, and only while the
 * mod runs on its own: a host's settings come from its own config.
 */
final class AnvilCommands {
    private AnvilCommands() {}

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("yolt-anvils")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal(describe(YoltAnvils.settings())), false);
                    return 1;
                })
                .then(Commands.literal("reload").executes(context -> {
                    CommandSourceStack source = context.getSource();
                    AnvilConfig.Result result = YoltAnvils.reload();
                    for (String note : result.notes()) {
                        source.sendSuccess(() -> Component.literal(note), false);
                    }
                    if (result.failed()) {
                        source.sendFailure(Component.literal("Nothing changed: " + AnvilConfig.FILE + " couldn't be read"));
                        return 0;
                    }
                    source.sendSuccess(() -> Component.literal("Reloaded " + AnvilConfig.FILE + ". "
                            + describe(result.settings())), true);
                    return 1;
                })));
    }

    static String describe(AnvilSettings settings) {
        if (!settings.enabled()) {
            return "Flat anvil prices are off";
        }
        return "Anvil prices: " + settings.prices() + "; break chance " + settings.breakChance();
    }
}
