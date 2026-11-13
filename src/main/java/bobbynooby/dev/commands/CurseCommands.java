package bobbynooby.dev.commands;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.Yolt;
import bobbynooby.dev.hardcore.Curse;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import java.util.Optional;

/** /yolt curse, for the cases an unkill can't undo and for testing: sets or clears anyone's Curse of the Undead. */
final class CurseCommands {
    private CurseCommands() {}

    static void register(LiteralArgumentBuilder<CommandSourceStack> yolt) {
        yolt.then(Commands.literal("curse")
                .requires(YoltCommands.GAMEMASTER)
                .then(Commands.argument("player", StringArgumentType.string())
                        // The top is the cap in force, checked on each use, since it can change live
                        .then(Commands.literal("set")
                                .then(Commands.argument("hearts", IntegerArgumentType.integer(0))
                                        .executes(c -> set(c.getSource(), StringArgumentType.getString(c, "player"),
                                                IntegerArgumentType.getInteger(c, "hearts")))))
                        .then(Commands.literal("clear")
                                .executes(c -> set(c.getSource(), StringArgumentType.getString(c, "player"), 0)))));
    }

    private static int set(CommandSourceStack source, String name, int hearts) {
        if (!Settings.on(c -> c.hardcore.curse.enabled)) {
            return YoltCommands.fail(source, "The Curse of the Undead is turned off.");
        }
        int max = Settings.get().hardcore.curse.maxLostHearts;
        if (hearts > max) {
            return YoltCommands.fail(source, "The curse takes at most " + max + (max == 1 ? " heart." : " hearts."));
        }
        Optional<NameAndId> who = YoltCommands.lookup(source.getServer(), name);
        if (who.isEmpty()) {
            return YoltCommands.fail(source, YoltCommands.notJoined(name));
        }
        String found = who.get().name();
        Curse.set(who.get().id(), hearts);
        // An online player's heart bar changes now rather than at their next tick
        ServerPlayer online = source.getServer().getPlayerList().getPlayer(who.get().id());
        if (online != null) {
            Curse.apply(online);
        }
        Yolt.LOGGER.info("{} set {}'s Curse of the Undead to {} hearts", source.getTextName(), found, hearts);
        if (hearts == 0) {
            return YoltCommands.done(source, "Cleared " + found + "'s Curse of the Undead.");
        }
        return YoltCommands.done(source, found + " has lost " + hearts + (hearts == 1 ? " max heart" : " max hearts")
                + " to the Curse of the Undead.");
    }
}
