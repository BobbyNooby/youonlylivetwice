package bobbynooby.dev.graves;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleMenuProvider;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Opens grave menus, and keeps the one container all viewers of a grave share. Server thread only. */
public final class GraveMenus {
    // One container per grave while anyone has it open, keyed by grave id.
    static final Map<UUID, SharedGraveContainer> OPEN = new HashMap<>();

    private GraveMenus() {
    }

    static void init() {
        // A container must never outlive its server and save into the next one started in the same game
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> OPEN.clear());
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
    static Container sharedContainer(MinecraftServer server, Grave grave) {
        return OPEN.computeIfAbsent(grave.id(), id -> new SharedGraveContainer(server, id,
                GraveStore.get(server).byId(id).map(Grave::items).orElse(List.of())));
    }

    /** Once the stored record is empty: the head goes, then the record, then every menu still showing it. */
    static void removeIfEmpty(MinecraftServer server, Grave grave) {
        GraveStore store = GraveStore.get(server);
        if (store.byId(grave.id()).filter(stored -> stored.items().isEmpty()).isEmpty()) {
            return;
        }
        ServerLevel level = server.getLevel(grave.dimension());
        if (level != null) {
            GraveBlocks.clearHead(level, grave.pos());
        }
        store.remove(grave.id());
        SharedGraveContainer container = OPEN.remove(grave.id());
        if (container != null) {
            container.closeMenus();
        }
    }
}
