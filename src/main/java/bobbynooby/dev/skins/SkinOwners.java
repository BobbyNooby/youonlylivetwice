package bobbynooby.dev.skins;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.Guard;
import bobbynooby.dev.core.Yolt;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;

/**
 * Skins that belong to someone here: the skin of each player who has joined this server, and the account each player
 * picked. With skins.refuseKnownPlayers on, a non-op can't pick one (SkinCommands), and a player who joins takes their
 * own skin back from whoever picked it.
 */
public final class SkinOwners {
    static final String RESET = "Your skin was reset because its owner joined this server.";

    private SkinOwners() {
    }

    static void init() {
        // A throw here would leave the player half joined
        ServerPlayerEvents.JOIN.register(player -> Guard.run(() -> onJoin(player),
                "Could not check whose skin {} has as they joined", player.nameAndId().name()));
    }

    /**
     * True when the account with this name is the skin of a player other than this one who has joined, or one another
     * player picked. Only players who really joined count: vanilla's name cache also holds names that were only looked up.
     */
    public static boolean taken(MinecraftServer server, String name, UUID by) {
        SkinStore store = SkinStore.get(server);
        return store.joinedAs(name, by) || !store.pickedBy(name, by).isEmpty();
    }

    private static void onJoin(ServerPlayer player) {
        if (!Settings.active()) {
            return;
        }
        MinecraftServer server = player.level().getServer();
        SkinStore store = SkinStore.get(server);
        String name = player.nameAndId().name();
        // Whatever the switches say, so the record is whole once they are turned on
        store.noteJoin(player.getUUID(), name);
        if (store.takeReset(player.getUUID())) {
            player.sendSystemMessage(Component.literal(RESET));
        }
        if (!Settings.get().skins.refuseKnownPlayers) {
            return;
        }
        List<UUID> borrowers = store.pickedBy(name, player.getUUID());
        if (borrowers.isEmpty()) {
            return;
        }
        for (UUID borrower : borrowers) {
            store.clearChoice(borrower);
            // The skin they wear now stays until they rejoin, like any other change of skin
            ServerPlayer online = server.getPlayerList().getPlayer(borrower);
            if (online != null) {
                online.sendSystemMessage(Component.literal(RESET));
            } else {
                store.noteReset(borrower);
            }
        }
        Yolt.LOGGER.info("{} joined, so {} skin {} of their name went back to the default", name, borrowers.size(),
                borrowers.size() == 1 ? "pick" : "picks");
        // On disk at once, as a pick from the command is, so a crash can't bring back a pick a player was told is gone
        try {
            store.saveNow(server);
        } catch (RuntimeException e) {
            Yolt.LOGGER.error("Could not save the skin picks reset for {} at once, so they are saved with the world", name, e);
        }
    }
}
