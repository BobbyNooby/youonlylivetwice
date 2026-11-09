package bobbynooby.dev.commands;

import bobbynooby.dev.bodies.BodyRecord;
import bobbynooby.dev.bodies.BodySnapshot;
import bobbynooby.dev.bodies.BodyStore;
import bobbynooby.dev.config.ConfigLoader;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.graves.Grave;
import bobbynooby.dev.graves.GraveStore;
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
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static bobbynooby.dev.core.Yolt.LOGGER;

/** /yolt border, center and endscaling. */
final class BorderCommands {
    private BorderCommands() {}

    // Each takes an optional "confirm", needed only when the change would leave someone outside the border
    static void register(LiteralArgumentBuilder<CommandSourceStack> yolt) {
        yolt.then(Commands.literal("border")
                        .then(Commands.argument("diameter", IntegerArgumentType.integer(1, ConfigLoader.MAX_BORDER_DIAMETER))
                                .executes(c -> border(c.getSource(), IntegerArgumentType.getInteger(c, "diameter"), false))
                                .then(Commands.literal("confirm")
                                        .executes(c -> border(c.getSource(), IntegerArgumentType.getInteger(c, "diameter"), true)))))
                .then(Commands.literal("center")
                        // false: a typed 100 stays 100 instead of moving to the block centre, so decimals are kept as typed
                        .then(Commands.argument("pos", Vec2Argument.vec2(false))
                                .executes(c -> center(c.getSource(), centerArgument(c), false))
                                .then(Commands.literal("confirm")
                                        .executes(c -> center(c.getSource(), centerArgument(c), true)))))
                .then(Commands.literal("endscaling")
                        .then(Commands.argument("scaling", DoubleArgumentType.doubleArg())
                                .executes(c -> endScaling(c.getSource(), DoubleArgumentType.getDouble(c, "scaling"), false))
                                .then(Commands.literal("confirm")
                                        .executes(c -> endScaling(c.getSource(), DoubleArgumentType.getDouble(c, "scaling"), true)))));
    }

    /**
     * The online players, bodies and graves a border would leave outside, where they take border damage or can't be
     * reached: the overworld and nether border around this centre, the end's around 0, 0, where /yolt center keeps it.
     * Empty while the border feature is off, since then YOLT changes no border. The switch is read from the live config,
     * so a change to it is made before asking.
     */
    static List<String> stranded(MinecraftServer server, int diameter, double endScale, double centerX, double centerZ) {
        if (!Settings.on(c -> c.worldBorder.enabled)) {
            return List.of();
        }
        // Vanilla's own border answers "inside", so the edge is the one border damage and block use go by
        Map<ResourceKey<Level>, WorldBorder> proposed = new HashMap<>();
        WorldBorders.forEachBorder(server, (level, isEnd) -> {
            WorldBorder border = new WorldBorder();
            if (isEnd) {
                border.setSize(diameter * endScale);
            } else {
                border.setCenter(centerX, centerZ);
                border.setSize(diameter);
            }
            proposed.put(level.dimension(), border);
        });

        // A set, since one player can have several graves
        Set<String> names = new LinkedHashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (outside(proposed, player.level().dimension(), player.getX(), player.getZ())) {
                names.add(player.nameAndId().name());
            }
        }
        for (BodyRecord body : BodyStore.get(server).all()) {
            BodySnapshot snapshot = body.snapshot();
            if (outside(proposed, snapshot.dimension(), snapshot.pos().x, snapshot.pos().z)) {
                names.add(body.ownerName() + "'s body");
            }
        }
        for (Grave grave : GraveStore.get(server).all()) {
            if (outside(proposed, grave.dimension(), grave.pos().getX(), grave.pos().getZ())) {
                names.add(grave.ownerName() + "'s grave");
            }
        }
        return List.copyOf(names);
    }

    private static boolean outside(Map<ResourceKey<Level>, WorldBorder> proposed, ResourceKey<Level> dimension, double x, double z) {
        WorldBorder border = proposed.get(dimension);
        return border != null && !border.isWithinBounds(x, z);
    }

    // Null when the change may go ahead
    private static @Nullable String strandedRefusal(MinecraftServer server, boolean confirmed, int diameter, double endScale,
                                                    double centerX, double centerZ) {
        if (confirmed) {
            return null;
        }
        List<String> outside = stranded(server, diameter, endScale, centerX, centerZ);
        return outside.isEmpty()
                ? null
                : "This would leave outside the world border: " + String.join(", ", outside) + ". Add confirm to the command to do it anyway.";
    }

    private static int border(CommandSourceStack source, int diameter, boolean confirmed) {
        MinecraftServer server = source.getServer();
        WorldBorder live = server.overworld().getWorldBorder();
        String refusal = strandedRefusal(server, confirmed, diameter, Settings.get().worldBorder.endScale, live.getCenterX(), live.getCenterZ());
        if (refusal != null) {
            return YoltCommands.fail(source, refusal);
        }
        Settings.get().worldBorder.diameter = diameter;
        Settings.save();
        WorldBorders.apply(source.getServer(), true);
        LOGGER.info("{} set the world border diameter to {}", source.getTextName(), diameter);
        return YoltCommands.done(source, "World border diameter set to: " + diameter + borderOffNote());
    }

    // The centre isn't in the config: vanilla saves it with the world
    private static int center(CommandSourceStack source, Vec3 pos, boolean confirmed) {
        double x = pos.x;
        double z = pos.z;
        // Vanilla's limit: the saved border can't hold a centre further out, so the world would fail to save
        if (Math.abs(x) > WorldBorder.MAX_CENTER_COORDINATE || Math.abs(z) > WorldBorder.MAX_CENTER_COORDINATE) {
            return YoltCommands.fail(source, "Center must be within ±" + (long) WorldBorder.MAX_CENTER_COORDINATE + ".");
        }
        MinecraftServer server = source.getServer();
        YoltConfig.WorldBorderSection config = Settings.get().worldBorder;
        String refusal = strandedRefusal(server, confirmed, config.diameter, config.endScale, x, z);
        if (refusal != null) {
            return YoltCommands.fail(source, refusal);
        }
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

    private static int endScaling(CommandSourceStack source, double scaling, boolean confirmed) {
        // Checked here because brigadier's minimum includes the bound itself, and 0 must be refused
        if (!(scaling > 0)) {
            return YoltCommands.fail(source, "End world border scaling factor must be above 0.");
        }
        MinecraftServer server = source.getServer();
        WorldBorder live = server.overworld().getWorldBorder();
        String refusal = strandedRefusal(server, confirmed, Settings.get().worldBorder.diameter, scaling, live.getCenterX(), live.getCenterZ());
        if (refusal != null) {
            return YoltCommands.fail(source, refusal);
        }
        Settings.get().worldBorder.endScale = scaling;
        Settings.save();
        WorldBorders.apply(source.getServer(), true);
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
