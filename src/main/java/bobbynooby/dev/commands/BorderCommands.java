package bobbynooby.dev.commands;

import bobbynooby.dev.bodies.BodyRecord;
import bobbynooby.dev.bodies.BodySnapshot;
import bobbynooby.dev.bodies.BodyStore;
import bobbynooby.dev.config.ConfigKeys;
import bobbynooby.dev.config.ConfigLoader;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.graves.Grave;
import bobbynooby.dev.graves.GraveStore;
import bobbynooby.dev.world.RandomSpawn;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.BorderStatus;
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
    private static final int LISTED = 10;

    private BorderCommands() {}

    // Each takes an optional "confirm", needed only when the change would leave someone outside the border
    static void register(LiteralArgumentBuilder<CommandSourceStack> yolt) {
        yolt.then(Commands.literal("border")
                        .requires(YoltCommands.GAMEMASTER)
                        .then(Commands.argument("diameter", IntegerArgumentType.integer(1, ConfigLoader.MAX_BORDER_DIAMETER))
                                .executes(c -> border(c.getSource(), IntegerArgumentType.getInteger(c, "diameter"), false))
                                .then(Commands.literal("confirm")
                                        .executes(c -> border(c.getSource(), IntegerArgumentType.getInteger(c, "diameter"), true)))))
                .then(Commands.literal("center")
                        .requires(YoltCommands.GAMEMASTER)
                        // false: a typed 100 stays 100 instead of moving to the block centre, so decimals are kept as typed
                        .then(Commands.argument("pos", Vec2Argument.vec2(false))
                                .executes(c -> center(c.getSource(), centerArgument(c), false))
                                .then(Commands.literal("confirm")
                                        .executes(c -> center(c.getSource(), centerArgument(c), true)))))
                .then(Commands.literal("endscaling")
                        .requires(YoltCommands.GAMEMASTER)
                        .then(Commands.argument("scaling", DoubleArgumentType.doubleArg())
                                .executes(c -> endScaling(c.getSource(), DoubleArgumentType.getDouble(c, "scaling"), false))
                                .then(Commands.literal("confirm")
                                        .executes(c -> endScaling(c.getSource(), DoubleArgumentType.getDouble(c, "scaling"), true)))));
    }

    /**
     * What a border change would leave outside, where players take border damage and things can't be reached. names
     * need confirm: the world spawn while new players start right on it, where players arrive in the end, and the online
     * players, bodies and graves. spawnNote is for the reply when the world spawn ends up outside, and "" when it doesn't.
     */
    record Outside(List<String> names, String spawnNote) {
        static final Outside NONE = new Outside(List.of(), "");
    }

    /**
     * What a border command would leave outside. The command sets every size now, a moving border's too, and each level
     * keeps its own centre. Nothing while the border feature is off, since then YOLT changes no border. The switch is
     * read from the live config, so a change to it is made before asking.
     */
    static Outside stranded(MinecraftServer server, int diameter, double endScale) {
        return Settings.on(c -> c.worldBorder.enabled) ? stranded(server, sized(diameter, endScale, false), false) : Outside.NONE;
    }

    /** The same for a border section not saved yet, so its own switch counts: /yolt config can turn the feature on. */
    static Outside stranded(MinecraftServer server, YoltConfig.WorldBorderSection border) {
        return Settings.active() && border.enabled
                ? stranded(server, sized(border.diameter, border.endScale, false), false)
                : Outside.NONE;
    }

    /**
     * The same for a reload. A moving border is left to finish, so it isn't checked, and what is already outside a
     * level's border is left out, so a bigger border is never refused for it.
     */
    static Outside newlyStranded(MinecraftServer server, int diameter, double endScale) {
        return Settings.on(c -> c.worldBorder.enabled) ? stranded(server, sized(diameter, endScale, true), true) : Outside.NONE;
    }

    /**
     * At startup, before the config's border is applied: one that would leave the world spawn, the End's arrival platform,
     * a body or a grave outside is held, as a startup border that fell back to defaults is, until a reload or a border
     * command checks it again. Nobody is online yet. A border saved while held, or edited into the file while the server
     * was down, gets no other check.
     */
    static void holdIfStranding(MinecraftServer server) {
        if (Settings.borderHeld()) {
            return;
        }
        YoltConfig.WorldBorderSection border = Settings.get().worldBorder;
        Outside outside = newlyStranded(server, border.diameter, border.endScale);
        if (!outside.names().isEmpty()) {
            Settings.holdBorder();
            LOGGER.warn("Config: the config's border would leave {} outside it, so YOLT left the world border as the world saved it."
                    + " Change the config, then run /yolt reload, or use /yolt border or endscaling with confirm.", listed(outside.names()));
        }
    }

    // The border one level would have after a change, or null when the change leaves that level's border alone
    private interface Proposal {
        @Nullable WorldBorder of(WorldBorder live, boolean isEnd);
    }

    // Only the sizes change: the overworld and nether take the diameter, the end that times endScale
    private static Proposal sized(int diameter, double endScale, boolean leaveMoving) {
        return (live, isEnd) -> leaveMoving && live.getStatus() != BorderStatus.STATIONARY
                ? null
                : borderAt(live.getCenterX(), live.getCenterZ(), isEnd ? diameter * endScale : diameter);
    }

    // /yolt center moves the overworld and nether to the new centre and the end to 0, 0. Each keeps the size the world
    // has, which can differ from the config's: a held border, or a vanilla /worldborder change.
    private static Proposal centred(double centerX, double centerZ) {
        return (live, isEnd) -> isEnd ? borderAt(0, 0, live.getSize()) : borderAt(centerX, centerZ, live.getSize());
    }

    private static WorldBorder borderAt(double centerX, double centerZ, double size) {
        WorldBorder border = new WorldBorder();
        border.setCenter(centerX, centerZ);
        border.setSize(size);
        return border;
    }

    // The caller has checked the border feature is on
    private static Outside stranded(MinecraftServer server, Proposal proposal, boolean newlyOnly) {
        // Each level is checked against its own border as it is now. Vanilla's border answers "inside", so the edge is
        // the one border damage and block use go by.
        Map<ResourceKey<Level>, Edges> borders = new HashMap<>();
        WorldBorders.forEachBorder(server, (level, isEnd) -> {
            WorldBorder proposed = proposal.of(level.getWorldBorder(), isEnd);
            if (proposed != null) {
                borders.put(level.dimension(), new Edges(level.getWorldBorder(), proposed, newlyOnly));
            }
        });

        // The spawn points first, so the cap on listed names never hides them. A set, since one player can have several graves.
        Set<String> names = new LinkedHashSet<>();
        String spawnNote = "";
        // A first join starts at the world spawn as it is, not moved inside the border, unless random spawn picks the spot
        ServerLevel spawnLevel = server.findRespawnDimension();
        BlockPos spawn = server.getWorldData().overworldData().getRespawnData().pos();
        if (strands(borders, spawnLevel.dimension(), spawn.getX(), spawn.getZ())) {
            spawnNote = " The world spawn is outside the new border; move it with /setworldspawn.";
            if (!RandomSpawn.placesFirstJoins(server, spawnLevel)) {
                names.add("the world spawn");
            }
        }
        // An End portal always puts a player on the platform vanilla builds here, whatever the spawn settings
        Vec3 endArrival = Vec3.atBottomCenterOf(ServerLevel.END_SPAWN_POINT);
        if (strands(borders, Level.END, endArrival.x, endArrival.z)) {
            names.add("the End's arrival platform");
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (strands(borders, player.level().dimension(), player.getX(), player.getZ())) {
                names.add(player.nameAndId().name());
            }
        }
        for (BodyRecord body : BodyStore.get(server).all()) {
            BodySnapshot snapshot = body.snapshot();
            if (strands(borders, snapshot.dimension(), snapshot.pos().x, snapshot.pos().z)) {
                names.add(body.ownerName() + "'s body");
            }
        }
        for (Grave grave : GraveStore.get(server).all()) {
            if (strands(borders, grave.dimension(), grave.pos().getX(), grave.pos().getZ())) {
                names.add(grave.ownerName() + "'s grave");
            }
        }
        return new Outside(List.copyOf(names), spawnNote);
    }

    // One dimension's border as it is and as it would be
    private record Edges(WorldBorder live, WorldBorder proposed, boolean newlyOnly) {
        boolean strands(double x, double z) {
            return !proposed.isWithinBounds(x, z) && (!newlyOnly || live.isWithinBounds(x, z));
        }
    }

    private static boolean strands(Map<ResourceKey<Level>, Edges> borders, ResourceKey<Level> dimension, double x, double z) {
        Edges edges = borders.get(dimension);
        return edges != null && edges.strands(x, z);
    }

    // Null when the change may go ahead
    static @Nullable String refusal(Outside outside, boolean confirmed) {
        return confirmed || outside.names().isEmpty()
                ? null
                : "This would leave " + listed(outside.names()) + " outside the world border. Add confirm to the command to do it anyway.";
    }

    /** The first ten names and how many more, so a long list doesn't flood the chat. */
    static String listed(List<String> names) {
        if (names.size() <= LISTED) {
            return String.join(", ", names);
        }
        return String.join(", ", names.subList(0, LISTED)) + " and " + (names.size() - LISTED) + " more";
    }

    private static int border(CommandSourceStack source, int diameter, boolean confirmed) {
        Outside outside = stranded(source.getServer(), diameter, Settings.get().worldBorder.endScale);
        String refusal = refusal(outside, confirmed);
        if (refusal != null) {
            return YoltCommands.fail(source, refusal);
        }
        // Only this key is written, so an edit to the file not yet reloaded stays. The value already set still applies the
        // border, which may have been held or changed with vanilla's /worldborder since.
        ConfigLoader.Change change = Settings.set(ConfigKeys.find("worldBorder.diameter").orElseThrow(), Integer.toString(diameter));
        if (change.problem() != null) {
            return ConfigCommands.failed(source, change);
        }
        WorldBorders.apply(source.getServer(), true);
        LOGGER.info("{} set the world border diameter to {}", source.getTextName(), diameter);
        YoltCommands.reply(source, "World border diameter set to " + diameter + "." + borderOffNote() + outside.spawnNote());
        ConfigCommands.tellBackup(source, change);
        return 1;
    }

    // The centre isn't in the config: vanilla saves it with the world
    private static int center(CommandSourceStack source, Vec3 pos, boolean confirmed) {
        double x = pos.x;
        double z = pos.z;
        // Vanilla's limit: the saved border can't hold a centre further out, so the world would fail to save
        if (Math.abs(x) > WorldBorder.MAX_CENTER_COORDINATE || Math.abs(z) > WorldBorder.MAX_CENTER_COORDINATE) {
            return YoltCommands.fail(source, "Center must be within ±" + (long) WorldBorder.MAX_CENTER_COORDINATE + ".");
        }
        // Off, YOLT leaves the border alone: /yolt border then only saves its value, and the centre has none to save
        if (!Settings.on(c -> c.worldBorder.enabled)) {
            return YoltCommands.fail(source, "The world border feature is off, so YOLT moves no border.");
        }
        MinecraftServer server = source.getServer();
        Outside outside = stranded(server, centred(x, z), false);
        String refusal = refusal(outside, confirmed);
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
        return YoltCommands.done(source, "World border center set to " + number(x) + ", " + number(z) + "." + outside.spawnNote());
    }

    private static int endScaling(CommandSourceStack source, double scaling, boolean confirmed) {
        // Checked here because brigadier's minimum includes the bound itself, and 0 must be refused
        if (!(scaling > 0)) {
            return YoltCommands.fail(source, "End world border scaling factor must be above 0.");
        }
        Outside outside = stranded(source.getServer(), Settings.get().worldBorder.diameter, scaling);
        String refusal = refusal(outside, confirmed);
        if (refusal != null) {
            return YoltCommands.fail(source, refusal);
        }
        // The config's End cap can still refuse it, as a reload would
        ConfigLoader.Change change = Settings.set(ConfigKeys.find("worldBorder.endScale").orElseThrow(), Double.toString(scaling));
        if (change.problem() != null) {
            return ConfigCommands.failed(source, change);
        }
        WorldBorders.apply(source.getServer(), true);
        LOGGER.info("{} set the end world border scaling factor to {}", source.getTextName(), scaling);
        YoltCommands.reply(source, "End world border scaling factor set to " + scaling + "." + borderOffNote() + outside.spawnNote());
        ConfigCommands.tellBackup(source, change);
        return 1;
    }

    // Read as doubles: Vec2Argument.getVec2 rounds to floats, which moves a centre past about 16 million by a block
    private static Vec3 centerArgument(CommandContext<CommandSourceStack> context) {
        return context.getArgument("pos", Coordinates.class).getPosition(context.getSource());
    }

    // 100 is shown as "100" and 100.5 as "100.5"
    private static String number(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    // With the feature off YOLT sizes no border: border and endscaling only save their value, and center is refused
    private static String borderOffNote() {
        return Settings.get().worldBorder.enabled ? "" : " The world border feature is off, so YOLT sizes no border.";
    }
}
