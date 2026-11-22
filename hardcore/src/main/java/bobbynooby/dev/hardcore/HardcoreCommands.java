package bobbynooby.dev.hardcore;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.OptionalDouble;

/**
 * The mod's commands on its own: /status for everyone, and for ops /yolt-hardcore, which names the features that are
 * on, with reload, status, bloodmoon and curse under it. The replies are the ones YOLT's /status and /yolt commands
 * give. A host has commands of its own, so these are only registered while nothing hosts the mod. A player named is
 * an online one: the players who are away are a host's to know.
 */
final class HardcoreCommands {
    private HardcoreCommands() {}

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // No argument: a player can only ever read their own body
        dispatcher.register(Commands.literal("status").executes(c -> own(c.getSource())));
        dispatcher.register(Commands.literal(YoltHardcore.MOD_ID)
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(c -> done(c.getSource(), features(Host.settings())))
                .then(Commands.literal("reload").executes(c -> reload(c.getSource())))
                .then(Commands.literal("status")
                        .then(Commands.argument("player", StringArgumentType.string())
                                .executes(c -> other(c.getSource(), StringArgumentType.getString(c, "player")))))
                .then(Commands.literal("bloodmoon")
                        .then(Commands.literal("start").executes(c -> startBloodMoon(c.getSource())))
                        .then(Commands.literal("stop").executes(c -> stopBloodMoon(c.getSource())))
                        .then(Commands.literal("status").executes(c -> bloodMoonStatus(c.getSource()))))
                .then(Commands.literal("curse")
                        .then(Commands.argument("player", StringArgumentType.string())
                                // The top is the cap in force, checked on each use, since a reload can change it
                                .then(Commands.literal("set")
                                        .then(Commands.argument("hearts", IntegerArgumentType.integer(0))
                                                .executes(c -> setCurse(c.getSource(),
                                                        StringArgumentType.getString(c, "player"),
                                                        IntegerArgumentType.getInteger(c, "hearts")))))
                                .then(Commands.literal("clear")
                                        .executes(c -> setCurse(c.getSource(), StringArgumentType.getString(c, "player"),
                                                0))))));
    }

    /** What /yolt-hardcore says, and the start-up line after the file is read: the features that are on. */
    static String features(HardcoreConfig.HardcoreSection settings) {
        List<String> on = Hardcore.featuresOn(settings);
        return on.isEmpty() ? "Every hardcore feature is off." : "Hardcore features on: " + String.join(", ", on) + ".";
    }

    private static int own(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return fail(source, "Only a player has a body to report on.");
        }
        // Straight to the player, so the report shows even with command feedback turned off
        for (Component line : Status.report(player)) {
            player.sendSystemMessage(line);
        }
        return 1;
    }

    // Notes first, then the outcome. The file is the admin's own, so a reload never writes it.
    private static int reload(CommandSourceStack source) {
        HardcoreFile.Result result = YoltHardcore.reload();
        for (String note : result.notes()) {
            reply(source, Component.literal(note));
        }
        if (result.failed()) {
            return fail(source, "Nothing changed: " + HardcoreFile.NAME + " couldn't be read");
        }
        source.sendSuccess(() -> Component.literal("Reloaded " + HardcoreFile.NAME + "."), true);
        return 1;
    }

    // Every line reads the player as they are now
    private static int other(CommandSourceStack source, String name) {
        ServerPlayer player = source.getServer().getPlayerList().getPlayerByName(name);
        if (player == null) {
            return fail(source, name + " is not online.");
        }
        reply(source, Component.literal(player.nameAndId().name() + "'s body"));
        List<Component> lines = Status.lines(player);
        if (lines.isEmpty()) {
            reply(source, Status.nothingToReport());
        }
        for (Component line : lines) {
            reply(source, line);
        }
        return 1;
    }

    private static int startBloodMoon(CommandSourceStack source) {
        if (!bloodMoonOn()) {
            return bloodMoonOff(source);
        }
        if (BloodMoon.active()) {
            return fail(source, "A blood moon is up already.");
        }
        BloodMoon.start(source.getServer());
        YoltHardcore.LOGGER.info("{} started a blood moon", source.getTextName());
        return done(source, "Started a blood moon.");
    }

    private static int stopBloodMoon(CommandSourceStack source) {
        if (!bloodMoonOn()) {
            return bloodMoonOff(source);
        }
        if (!BloodMoon.active()) {
            return fail(source, "No blood moon is up.");
        }
        BloodMoon.stop(source.getServer());
        YoltHardcore.LOGGER.info("{} stopped the blood moon", source.getTextName());
        return done(source, "Stopped the blood moon.");
    }

    private static int bloodMoonStatus(CommandSourceStack source) {
        if (!bloodMoonOn()) {
            return bloodMoonOff(source);
        }
        OptionalDouble left = BloodMoon.left();
        if (left.isPresent()) {
            return done(source, "A blood moon is up, with " + Math.round(left.getAsDouble() * 100.0) + "% of it left.");
        }
        if (BloodMoon.tonight()) {
            return done(source, "A blood moon rises tonight.");
        }
        return done(source, "No blood moon is up.");
    }

    private static boolean bloodMoonOn() {
        return Host.on(c -> c.bloodMoon.enabled);
    }

    private static int bloodMoonOff(CommandSourceStack source) {
        return fail(source, "The blood moon is turned off.");
    }

    // For the cases a death can't undo and for testing: sets or clears an online player's Curse of the Undead
    private static int setCurse(CommandSourceStack source, String name, int hearts) {
        if (!Host.on(c -> c.curse.enabled)) {
            return fail(source, "The Curse of the Undead is turned off.");
        }
        int max = Host.settings().curse.maxLostHearts;
        if (hearts > max) {
            return fail(source, "The curse takes at most " + max + (max == 1 ? " heart." : " hearts."));
        }
        ServerPlayer player = online(source.getServer(), name);
        if (player == null) {
            return fail(source, name + " is not online.");
        }
        String found = player.nameAndId().name();
        Curse.set(player.getUUID(), hearts);
        // The heart bar changes now rather than at the player's next tick
        Curse.apply(player);
        YoltHardcore.LOGGER.info("{} set {}'s Curse of the Undead to {} hearts", source.getTextName(), found, hearts);
        if (hearts == 0) {
            return done(source, "Cleared " + found + "'s Curse of the Undead.");
        }
        return done(source, found + " has lost " + hearts + (hearts == 1 ? " max heart" : " max hearts")
                + " to the Curse of the Undead.");
    }

    // The player in the same case as typed first, since an offline-mode server gives "Steve" and "steve" different
    // ids, then one in any case
    private static @Nullable ServerPlayer online(MinecraftServer server, String name) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getGameProfile().name().equals(name)) {
                return player;
            }
        }
        return server.getPlayerList().getPlayerByName(name);
    }

    private static int done(CommandSourceStack source, String text) {
        reply(source, Component.literal(text));
        return 1;
    }

    private static int fail(CommandSourceStack source, String text) {
        source.sendFailure(Component.literal(text));
        return 0;
    }

    private static void reply(CommandSourceStack source, Component line) {
        source.sendSuccess(() -> line, false);
    }
}
