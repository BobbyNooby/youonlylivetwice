package bobbynooby.dev.graves;

import bobbynooby.dev.config.Settings;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Where each online player last stood on solid ground, and each body for its owner, so a grave for a death over the
 * void goes back there. A body left at logout keeps its owner's, and it goes with the body (see Bodies). Memory only:
 * an offline death with no body starts from the save instead.
 */
public final class LastGround {
    // Owner to the block they last stood in. Server thread only.
    private static final Map<UUID, GlobalPos> LAST = new HashMap<>();

    private LastGround() {
    }

    static void init() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                update(player.getUUID(), player);
            }
        });
        // Static, so it would outlive the server in a JVM that starts another one
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> LAST.clear());
    }

    /** Notes where the entity stands, for its owner, when it stands on something and is out of any fluid. */
    public static void update(UUID owner, Entity entity) {
        if (Settings.on(c -> c.graves.enabled) && entity.onGround() && !entity.isInLiquid()) {
            // Above the block it stands on, so a player half over the edge of a ledge counts the ledge
            LAST.put(owner, GlobalPos.of(entity.level().dimension(), entity.getOnPos().above()));
        }
    }

    /** Where the owner last stood in this dimension, if they have stood anywhere in it since they joined. */
    public static Optional<BlockPos> in(UUID owner, ResourceKey<Level> dimension) {
        GlobalPos last = LAST.get(owner);
        return last != null && last.dimension() == dimension ? Optional.of(last.pos()) : Optional.empty();
    }

    public static void forget(UUID owner) {
        LAST.remove(owner);
    }
}
