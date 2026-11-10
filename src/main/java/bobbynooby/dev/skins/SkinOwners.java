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
import org.jspecify.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Skins that belong to someone here: the skin of each player who has joined this server, and the account each player
 * picked. With skins.refuseKnownPlayers on, a non-op can't pick one (SkinCommands), and a pick of an account that has
 * become someone else's goes at its holder's own next login, though not a pick an op made. The owner's join changes
 * nothing for anyone else, so nothing a borrower sees lines up with it.
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
     * True when the account is someone else's here, so a non-op can't pick it: another player picked it, or it is
     * owned (see owned). A player's own name is always theirs.
     */
    public static boolean taken(MinecraftServer server, CachedSkin skin, String asked, NameAndId by) {
        if (skin.name().equalsIgnoreCase(by.name())) {
            return false;
        }
        return !SkinStore.get(server).pickedBy(skin.name(), by.id()).isEmpty()
                || owned(server, skin.name(), skin.id(), asked, by);
    }

    /**
     * True when a player other than this one has joined as the account: under its name or an id of it in YOLT's record
     * of joins, or with a save in players/data. The ids tried are the account's own, for online mode, and the one an
     * offline-mode server gives the name as asked and as Mojang spells it. Only a join writes a save, so a name vanilla
     * merely looked up never counts, and every player from before the record is covered.
     */
    static boolean owned(MinecraftServer server, String name, @Nullable UUID accountId, String asked, NameAndId by) {
        if (name.equalsIgnoreCase(by.name())) {
            return false;
        }
        SkinStore store = SkinStore.get(server);
        if (store.joinedAs(name, by.id())) {
            return true;
        }
        Set<UUID> ids = new LinkedHashSet<>();
        if (accountId != null) {
            ids.add(accountId);
        }
        ids.add(UUIDUtil.createOfflinePlayerUUID(asked));
        ids.add(UUIDUtil.createOfflinePlayerUUID(name));
        Path saves = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
        for (UUID id : ids) {
            if (!id.equals(by.id()) && (store.joined(id) || Files.exists(saves.resolve(id + ".dat")))) {
                return true;
            }
        }
        return false;
    }

    /**
     * At a player's own login, before their skin is worked out: their pick of an account that is now owned goes, and
     * they are told once they have joined. Never a pick an op made. The saved answer stands in for Mojang, who isn't
     * asked.
     */
    static void dropOwnedPick(MinecraftServer server, NameAndId who) {
        if (!Settings.get().skins.refuseKnownPlayers) {
            return;
        }
        SkinStore store = SkinStore.get(server);
        String chosen = store.choice(who.id());
        if (chosen == null || store.pickedByOp(who.id())) {
            return;
        }
        CachedSkin saved = store.skin(chosen);
        if (!owned(server, chosen, saved != null ? saved.id() : null, chosen, who)) {
            return;
        }
        store.clearChoice(who.id());
        store.noteReset(who.id());
        Yolt.LOGGER.info("{} logged in with the skin of {}, who has played here, so it went back to the default", who.name(),
                chosen);
        // On disk at once, as a pick from the command is, so a crash can't bring back a pick that was reset
        try {
            store.saveNow(server);
        } catch (RuntimeException e) {
            Yolt.LOGGER.error("Could not save the reset of {}'s skin pick at once, so it is saved with the world", who.name(), e);
        }
    }

    private static void onJoin(ServerPlayer player) {
        if (!Settings.active()) {
            return;
        }
        SkinStore store = SkinStore.get(player.level().getServer());
        // Whatever the switches say, so the record is whole once they are turned on
        store.noteJoin(player.getUUID(), player.nameAndId().name());
        if (store.takeReset(player.getUUID())) {
            player.sendSystemMessage(Component.literal(RESET));
        }
    }
}
