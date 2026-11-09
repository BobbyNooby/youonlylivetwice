package bobbynooby.dev.commands;

import bobbynooby.dev.config.ConfigLoader;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.world.WorldBorders;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.commands.arguments.coordinates.Vec2Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.Vec3;

import java.math.BigDecimal;

import static bobbynooby.dev.core.Yolt.LOGGER;

/** /yolt border, center and endscaling. */
final class BorderCommands {
    private BorderCommands() {}

    static void register(LiteralArgumentBuilder<CommandSourceStack> yolt) {
        yolt.then(Commands.literal("border")
                        .then(Commands.argument("diameter", IntegerArgumentType.integer(1, ConfigLoader.MAX_BORDER_DIAMETER))
                                .executes(c -> border(c.getSource(), IntegerArgumentType.getInteger(c, "diameter")))))
                .then(Commands.literal("center")
                        // false: a typed 100 stays 100 instead of moving to the block centre, so decimals are kept as typed
                        .then(Commands.argument("pos", Vec2Argument.vec2(false))
                                .executes(c -> center(c.getSource(), centerArgument(c)))))
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
    private static int center(CommandSourceStack source, Vec3 pos) {
        double x = pos.x;
        double z = pos.z;
        // Vanilla's limit: the saved border can't hold a centre further out, so the world would fail to save
        if (Math.abs(x) > WorldBorder.MAX_CENTER_COORDINATE || Math.abs(z) > WorldBorder.MAX_CENTER_COORDINATE) {
            return YoltCommands.fail(source, "Center must be within ±" + (long) WorldBorder.MAX_CENTER_COORDINATE + ".");
        }
        MinecraftServer server = source.getServer();
        WorldBorders.forEachBorder(server, (level, isEnd) -> {
            if (isEnd) {
                // The end island sits at 0, 0
                level.getWorldBorder().setCenter(0, 0);
            } else {
                level.getWorldBorder().setCenter(x, z);
            }
        });
        LOGGER.info("{} set the world border center to {}, {}", source.getTextName(), number(x), number(z));
        return YoltCommands.done(source, "World border center set to: " + number(x) + ", " + number(z)
                + borderOffNote() + spawnOutsideNote(server));
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

    // Read as doubles: Vec2Argument.getVec2 rounds to floats, which moves a centre past about 16 million by a block
    private static Vec3 centerArgument(CommandContext<CommandSourceStack> context) {
        return context.getArgument("pos", Coordinates.class).getPosition(context.getSource());
    }

    // 100 is shown as "100" and 100.5 as "100.5"
    private static String number(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    // With the feature off YOLT sizes no border: border and endscaling only save their value, center only moves vanilla's
    private static String borderOffNote() {
        return Settings.get().worldBorder.enabled ? "" : " (world border feature is off)";
    }

    // With random spawn off, a first join starts at the world spawn itself, not at a spot vanilla keeps inside the border
    private static String spawnOutsideNote(MinecraftServer server) {
        if (Settings.get().randomSpawn) {
            return "";
        }
        BlockPos spawn = server.getWorldData().overworldData().getRespawnData().pos();
        return server.findRespawnDimension().getWorldBorder().isWithinBounds(spawn)
                ? ""
                : " The world spawn is outside the new border; move it with /setworldspawn.";
    }
}
