package bobbynooby.dev.graves;

import bobbynooby.dev.core.Guard;
import bobbynooby.dev.core.Later;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleMenuProvider;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Opens grave menus, and keeps the one container all viewers of a grave share. Server thread only. */
public final class GraveMenus {
    // One container per grave while anyone has it open, keyed by grave id. Only this class writes it.
    private static final Map<UUID, SharedGraveContainer> OPEN = new HashMap<>();
    // Players who had a grave open while it changed. Looting only marks the grave file to save with the world, so their
    // logout saves it, or a crash after it could hand the items back while the player's own save keeps them.
    private static final Set<UUID> CHANGED_BY = new HashSet<>();
    private static boolean saveQueued;

    private GraveMenus() {}

    static void init() {
        // A container must never outlive its server and save into the next one started in the same game
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            OPEN.clear();
            CHANGED_BY.clear();
            saveQueued = false;
        });
        ServerPlayerEvents.LEAVE.register(GraveMenus::onLeave);
    }

    static void changedBy(UUID player) {
        CHANGED_BY.add(player);
    }

    // A grave menu still open counts too, since closing it as the player goes can put items back. The save waits for the
    // end of the tick, so that has happened, and several players leaving at once share it.
    private static void onLeave(ServerPlayer player) {
        boolean changed = CHANGED_BY.remove(player.getUUID()) | player.containerMenu instanceof GraveMenu;
        if (!changed || saveQueued) {
            return;
        }
        saveQueued = true;
        MinecraftServer server = player.level().getServer();
        String name = player.nameAndId().name();
        Later.afterTick(server, () -> {
            saveQueued = false;
            Guard.run(() -> GraveStore.get(server).saveNow(server), "Could not save the graves after {} left", name);
        });
    }

    /** Opens the grave's take-only menu. Everyone looking at one grave shares the same items. */
    public static void open(ServerPlayer player, Grave grave) {
        if (GraveStore.get(player.level().getServer()).byId(grave.id()).isEmpty()) {
            return;
        }
        player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new GraveMenu(id, inventory, grave),
                Component.literal(grave.ownerName() + "'s Grave")));
    }

    /** The container every open menu on this grave shares, or null when nobody has the grave open. */
    public static @Nullable Container containerFor(Grave grave) {
        return OPEN.get(grave.id());
    }

    // Filled from the stored record, never the caller's copy, which may still list items someone has taken
    static SharedGraveContainer sharedContainer(MinecraftServer server, Grave grave) {
        return OPEN.computeIfAbsent(grave.id(), id -> new SharedGraveContainer(server, id,
                GraveStore.get(server).byId(id).map(Grave::items).orElse(List.of())));
    }

    // True while this is the container the grave's menus share, the only one that may write the record
    static boolean isShared(UUID graveId, SharedGraveContainer container) {
        return OPEN.get(graveId) == container;
    }

    // Its last viewer closed, so it lets go, unless a newer container has taken its place
    static void letGo(UUID graveId, SharedGraveContainer container) {
        OPEN.remove(graveId, container);
    }

    // Takes the grave's container out of the shared ones, so it can't write the record again. Null when nobody has the
    // grave open.
    static @Nullable SharedGraveContainer release(UUID graveId) {
        return OPEN.remove(graveId);
    }

    /**
     * Once the stored record is empty: the head goes, then the record, then every menu still showing it. The head is
     * looked for where the record says, since the re-check may have moved the grave while it was open.
     */
    static void removeIfEmpty(MinecraftServer server, Grave grave) {
        GraveStore store = GraveStore.get(server);
        Optional<Grave> emptied = store.byId(grave.id()).filter(stored -> stored.items().isEmpty());
        if (emptied.isEmpty()) {
            return;
        }
        ServerLevel level = server.getLevel(emptied.get().dimension());
        if (level != null) {
            GraveBlocks.clearHead(level, emptied.get());
        }
        store.remove(grave.id());
        SharedGraveContainer container = release(grave.id());
        if (container != null) {
            container.closeMenus();
        }
    }
}
