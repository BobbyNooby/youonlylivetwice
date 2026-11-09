package bobbynooby.dev.commands;

import bobbynooby.dev.bodies.Kills;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.lockout.DeathLockout;
import bobbynooby.dev.lockout.DeathLog;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import java.util.Optional;

import static bobbynooby.dev.core.Yolt.LOGGER;

/** /yolt respawncooldown, kill and unkill. */
final class LockoutCommands {
    private LockoutCommands() {}

    static void register(LiteralArgumentBuilder<CommandSourceStack> yolt) {
        yolt.then(Commands.literal("respawncooldown")
                        .then(Commands.argument("days", IntegerArgumentType.integer(0))
                                .executes(c -> respawnCooldown(c.getSource(), IntegerArgumentType.getInteger(c, "days")))))
                .then(Commands.literal("kill")
                        .then(Commands.argument("player", StringArgumentType.string())
                                .executes(c -> kill(c.getSource(), StringArgumentType.getString(c, "player")))))
                .then(Commands.literal("unkill")
                        .then(Commands.argument("player", StringArgumentType.string())
                                .executes(c -> unkill(c.getSource(), StringArgumentType.getString(c, "player")))));
    }

    private static int respawnCooldown(CommandSourceStack source, int days) {
        Settings.get().deathLockout.cooldownDays = days;
        Settings.save();
        LOGGER.info("{} set the respawn cooldown to {} days", source.getTextName(), days);
        return YoltCommands.done(source, "Respawn cooldown set to: " + days);
    }

    private static int kill(CommandSourceStack source, String name) {
        MinecraftServer server = source.getServer();
        Optional<NameAndId> who = YoltCommands.lookup(server, name);
        // The kill's own death row is a new one, so the newest row now is what it must differ from
        DeathLog before = who.map(found -> DeathLockout.newestDeath(found.id())).orElse(null);
        Kills.Result result;
        try {
            result = who.map(found -> Kills.kill(server, found)).orElse(Kills.Result.NOT_FOUND);
        } catch (Kills.LoggedFailure e) {
            // The body death logged it already
            return YoltCommands.fail(source, "Could not kill " + name + ", see the server log.");
        } catch (RuntimeException e) {
            LOGGER.error("{} could not kill {}", source.getTextName(), name, e);
            return YoltCommands.fail(source, "Could not kill " + name + ", see the server log.");
        }
        String failure = switch (result) {
            case NOT_FOUND -> "Player " + name + " not found.";
            case ALREADY_DEAD -> who.get().name() + " is already dead.";
            case NOT_KILLED -> "Could not kill " + who.get().name() + " right now.";
            case KILLED_ONLINE, KILLED_OFFLINE -> null;
        };
        if (failure != null) {
            return YoltCommands.fail(source, failure);
        }
        LOGGER.info("{} killed {} ({})", source.getTextName(), who.get().name(), result == Kills.Result.KILLED_ONLINE ? "online" : "offline");
        // The player is dead either way; without the record they are not locked out, so the admin has to know
        if (!DeathLockout.deathRecordedAfter(who.get().id(), who.get().name(), before)) {
            return YoltCommands.fail(source, "Killed " + who.get().name() + ", but the death could not be recorded; see the server log.");
        }
        return YoltCommands.done(source, "Killed " + who.get().name());
    }

    private static int unkill(CommandSourceStack source, String name) {
        Optional<NameAndId> who = YoltCommands.lookup(source.getServer(), name);
        if (who.isEmpty()) {
            return YoltCommands.fail(source, "Player " + name + " not found.");
        }
        String found = who.get().name();
        return switch (DeathLockout.revive(who.get().id(), found, reviver(source))) {
            case REVIVED -> {
                LOGGER.info("{} revived {}", source.getTextName(), found);
                yield YoltCommands.done(source, "Revived " + found);
            }
            case ALREADY_ENDED -> YoltCommands.fail(source, found + "'s lockout had already ended.");
            case NO_DEATH -> YoltCommands.fail(source, found + " has no death to undo.");
            case FAILED -> YoltCommands.fail(source, "Could not revive " + found + ", see the server log.");
        };
    }

    // Kept with the death. Anything that isn't a player, a command block too, counts as the console.
    private static String reviver(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        return player != null ? player.nameAndId().name() : "console";
    }
}
