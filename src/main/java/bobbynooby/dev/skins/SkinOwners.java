package bobbynooby.dev.skins;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.Guard;
import bobbynooby.dev.core.Yolt;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
     * True when the account is someone else's here, so a non-op can't pick it; a player's own name is always theirs.
     * Someone else's means another player picked it, or a player other than this one has joined as it: under its name
     * or its id in YOLT's record of joins, or with a save in players/data. A save is tried under the account's own id,
     * for online mode, and under the id an offline-mode server gives the name as asked and as Mojang spells it. Only a
     * join writes a save, so a name vanilla merely looked up never counts, and every player from before the record is
     * covered.
     */
    public static boolean taken(MinecraftServer server, CachedSkin skin, String asked, NameAndId by) {
        if (skin.name().equalsIgnoreCase(by.name())) {
            return false;
        }
        SkinStore store = SkinStore.get(server);
        if (store.joinedAs(skin.name(), by.id()) || !store.pickedBy(skin.name(), by.id()).isEmpty()) {
            return true;
        }
        Set<UUID> ids = new LinkedHashSet<>();
        if (skin.id() != null) {
            ids.add(skin.id());
        }
        ids.add(UUIDUtil.createOfflinePlayerUUID(asked));
        ids.add(UUIDUtil.createOfflinePlayerUUID(skin.name()));
        Path saves = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
        for (UUID id : ids) {
            if (!id.equals(by.id()) && (store.joined(id) || Files.exists(saves.resolve(id + ".dat")))) {
                return true;
            }
        }
        return false;
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
            // Told at their next join, when the skin really changes. Told now, an online borrower would learn that the
            // owner just came online.
            store.noteReset(borrower);
        }
        Yolt.LOGGER.info("{} joined, so {} skin {} of their name went back to the default", name, borrowers.size(),
                borrowers.size() == 1 ? "pick" : "picks");
        // On disk at once, as a pick from the command is, so a crash can't bring back a pick that was reset
        try {
            store.saveNow(server);
        } catch (RuntimeException e) {
            Yolt.LOGGER.error("Could not save the skin picks reset for {} at once, so they are saved with the world", name, e);
        }
    }
}
