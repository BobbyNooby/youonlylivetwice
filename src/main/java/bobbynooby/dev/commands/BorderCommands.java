package bobbynooby.dev.commands;

import bobbynooby.dev.config.ConfigLoader;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.world.WorldBorders;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.Vec2Argument;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec2;

import static bobbynooby.dev.core.Yolt.LOGGER;

/** /yolt border, center and endscaling. */
final class BorderCommands {
    private BorderCommands() {}

    static void register(LiteralArgumentBuilder<CommandSourceStack> yolt) {
        yolt.then(Commands.literal("border")
                        .then(Commands.argument("diameter", IntegerArgumentType.integer(1, ConfigLoader.MAX_BORDER_DIAMETER))
                                .executes(c -> border(c.getSource(), IntegerArgumentType.getInteger(c, "diameter")))))
                .then(Commands.literal("center")
                        .then(Commands.argument("pos", Vec2Argument.vec2())
                                .executes(c -> center(c.getSource(), Vec2Argument.getVec2(c, "pos")))))
                .then(Commands.literal("endscaling")
                        .then(Commands.argument("scaling", DoubleArgumentType.doubleArg())
                                .executes(c -> endScaling(c.getSource(), DoubleArgumentType.getDouble(c, "scaling")))));
    }

    private static int border(CommandSourceStack source, int diameter) {
        Settings.get().worldBorder.diameter = diameter;
        Settings.save();
        WorldBorders.apply(source.getServer());
        LOGGER.info("{} set the world border diameter to {}", source.getTextName(), diameter);
        return YoltCommands.done(source, "World border diameter set to: " + diameter + borderOffNote());
    }

    // The centre isn't in the config: vanilla saves it with the world
    private static int center(CommandSourceStack source, Vec2 pos) {
        // The argument puts whole numbers on the block centre (+0.5), so floor them back
        int x = Mth.floor(pos.x);
        int z = Mth.floor(pos.y);
        WorldBorders.forEachBorder(source.getServer(), (level, isEnd) -> {
            if (isEnd) {
                // The end island sits at 0, 0
                level.getWorldBorder().setCenter(0, 0);
            } else {
                level.getWorldBorder().setCenter(x, z);
            }
        });
        LOGGER.info("{} set the world border center to {}, {}", source.getTextName(), x, z);
        return YoltCommands.done(source, "World border center set to: " + x + ", " + z);
    }

    private static int endScaling(CommandSourceStack source, double scaling) {
        // Checked here because brigadier's minimum includes the bound itself, and 0 must be refused
        if (!(scaling > 0)) {
            return YoltCommands.fail(source, "End world border scaling factor must be above 0.");
        }
        Settings.get().worldBorder.endScale = scaling;
        Settings.save();
        WorldBorders.apply(source.getServer());
        LOGGER.info("{} set the end world border scaling factor to {}", source.getTextName(), scaling);
        return YoltCommands.done(source, "End world border scaling factor set to: " + scaling + borderOffNote());
    }

    // The value is still saved with the feature off, but nothing in the world changes
    private static String borderOffNote() {
        return Settings.get().worldBorder.enabled ? "" : " (world border feature is off)";
    }
}
