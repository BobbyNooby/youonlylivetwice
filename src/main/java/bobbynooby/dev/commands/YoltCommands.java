package bobbynooby.dev.commands;

import bobbynooby.dev.YouOnlyLiveTwice;
import bobbynooby.dev.config.ConfigLoader;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.features.NameTagHider;
import bobbynooby.dev.features.WorldBorders;
import bobbynooby.dev.lockout.DeathLockout;
import bobbynooby.dev.offline.OfflineKill;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.Vec2Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.Mth;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.phys.Vec2;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import static bobbynooby.dev.YouOnlyLiveTwice.LOGGER;

/** The /yolt admin commands. Every reply and log line works from the console too, which has no player. */
public final class YoltCommands {
    private static final Predicate<CommandSourceStack> GAMEMASTER = Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);

    private YoltCommands() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> dispatcher.register(
                Commands.literal("yolt")
                        // Commands are registered before any server exists, so single-player is checked on each use
                        .requires(source -> Settings.active() && GAMEMASTER.test(source))
                        .then(Commands.literal("border")
                                .then(Commands.argument("diameter", IntegerArgumentType.integer(1, ConfigLoader.MAX_BORDER_DIAMETER))
                                        .executes(c -> border(c.getSource(), IntegerArgumentType.getInteger(c, "diameter")))))
                        .then(Commands.literal("center")
                                .then(Commands.argument("pos", Vec2Argument.vec2())
                                        .executes(c -> center(c.getSource(), Vec2Argument.getVec2(c, "pos")))))
                        .then(Commands.literal("endscaling")
                                .then(Commands.argument("scaling", DoubleArgumentType.doubleArg())
                                        .executes(c -> endScaling(c.getSource(), DoubleArgumentType.getDouble(c, "scaling")))))
                        .then(Commands.literal("respawncooldown")
                                .then(Commands.argument("days", IntegerArgumentType.integer(0))
                                        .executes(c -> respawnCooldown(c.getSource(), IntegerArgumentType.getInteger(c, "days")))))
                        .then(Commands.literal("kill")
                                .then(Commands.argument("player", StringArgumentType.string())
                                        .executes(c -> kill(c.getSource(), StringArgumentType.getString(c, "player")))))
                        .then(Commands.literal("unkill")
                                .then(Commands.argument("player", StringArgumentType.string())
                                        .executes(c -> unkill(c.getSource(), StringArgumentType.getString(c, "player")))))
                        .then(Commands.literal("reload")
                                .executes(c -> reload(c.getSource())))));
    }

    private static int border(CommandSourceStack source, int diameter) {
        Settings.get().worldBorder.diameter = diameter;
        Settings.save();
        WorldBorders.apply(source.getServer());
        LOGGER.info("{} set the world border diameter to {}", source.getTextName(), diameter);
        reply(source, "World border diameter set to: " + diameter + borderOffNote());
        return 1;
    }

    // The centre isn't in the config: vanilla saves it with the world
    private static int center(CommandSourceStack source, Vec2 pos) {
        // The argument puts whole numbers on the block centre (+0.5), so floor them back
        int x = Mth.floor(pos.x);
        int z = Mth.floor(pos.y);
        for (ServerLevel level : source.getServer().getAllLevels()) {
            if (level.dimensionTypeRegistration().is(BuiltinDimensionTypes.OVERWORLD) || level.dimensionTypeRegistration().is(BuiltinDimensionTypes.NETHER)) {
                level.getWorldBorder().setCenter(x, z);
            } else if (level.dimensionTypeRegistration().is(BuiltinDimensionTypes.END)) {
                // The end island sits at 0, 0
                level.getWorldBorder().setCenter(0, 0);
            }
        }
        LOGGER.info("{} set the world border center to {}, {}", source.getTextName(), x, z);
        reply(source, "World border center set to: " + x + ", " + z);
        return 1;
    }

    private static int endScaling(CommandSourceStack source, double scaling) {
        // Checked here because brigadier's minimum includes the bound itself, and 0 must be refused
        if (!(scaling > 0)) {
            source.sendFailure(Component.literal("End world border scaling factor must be above 0."));
            return 0;
        }
        Settings.get().worldBorder.endScale = scaling;
        Settings.save();
        WorldBorders.apply(source.getServer());
        LOGGER.info("{} set the end world border scaling factor to {}", source.getTextName(), scaling);
        reply(source, "End world border scaling factor set to: " + scaling + borderOffNote());
        return 1;
    }

    private static int respawnCooldown(CommandSourceStack source, int days) {
        Settings.get().deathLockout.cooldownDays = days;
        Settings.save();
        LOGGER.info("{} set the respawn cooldown to {} days", source.getTextName(), days);
        reply(source, "Respawn cooldown set to: " + days);
        return 1;
    }

    private static int kill(CommandSourceStack source, String name) {
        MinecraftServer server = source.getServer();
        Optional<NameAndId> who = lookup(server, name);
        OfflineKill.KillResult result;
        try {
            result = who.map(found -> OfflineKill.kill(server, found)).orElse(OfflineKill.KillResult.NOT_FOUND);
        } catch (RuntimeException e) {
            LOGGER.error("{} could not kill {}", source.getTextName(), name, e);
            source.sendFailure(Component.literal("Could not kill " + name + ", see the server log."));
            return 0;
        }
        if (result == OfflineKill.KillResult.NOT_FOUND) {
            source.sendFailure(Component.literal("Player " + name + " not found."));
            return 0;
        }
        LOGGER.info("{} killed {} ({})", source.getTextName(), who.get().name(), result == OfflineKill.KillResult.KILLED_ONLINE ? "online" : "offline");
        reply(source, "Killed " + who.get().name());
        return 1;
    }

    private static int unkill(CommandSourceStack source, String name) {
        Optional<NameAndId> who = lookup(source.getServer(), name);
        if (who.isEmpty()) {
            source.sendFailure(Component.literal("Player " + name + " not found."));
            return 0;
        }
        if (!DeathLockout.revive(who.get().id())) {
            source.sendFailure(Component.literal(who.get().name() + " has no death to undo."));
            return 0;
        }
        LOGGER.info("{} revived {}", source.getTextName(), who.get().name());
        reply(source, "Revived " + who.get().name());
        return 1;
    }

    private static int reload(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        List<String> warnings = Settings.reload();
        // What startup applied; each of these checks its own switch
        WorldBorders.apply(server);
        NameTagHider.apply(server);
        YouOnlyLiveTwice.applyImmediateRespawn(server);

        LOGGER.info("{} reloaded the config", source.getTextName());
        reply(source, "Config reloaded");
        for (String warning : warnings) {
            LOGGER.warn("Config: {}", warning);
            reply(source, warning);
        }
        return 1;
    }

    // Online players first, so the exact player is used even if the name cache is out of date
    private static Optional<NameAndId> lookup(MinecraftServer server, String name) {
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) {
            return Optional.of(online.nameAndId());
        }
        return server.services().nameToIdCache().get(name);
    }

    // The value is still saved with the feature off, but nothing in the world changes
    private static String borderOffNote() {
        return Settings.get().worldBorder.enabled ? "" : " (world border feature is off)";
    }

    private static void reply(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), false);
    }
}
