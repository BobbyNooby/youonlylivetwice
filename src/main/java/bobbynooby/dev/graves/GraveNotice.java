package bobbynooby.dev.graves;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.core.Guard;
import bobbynooby.dev.core.Yolt;
import bobbynooby.dev.coords.CoordinateOffsets;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tells a dead player, and nobody else, where the grave of the death they are told about is, as graves.tellLocation
 * says: in the lockout's kick and login refusal, or in chat after the respawn when the death kicks nobody. Only that
 * death's grave: a death that made none, or whose grave is gone, says nothing.
 */
public final class GraveNotice {
    // A death's graves are made just before the death is recorded, in the same call; a minute covers a slow disk in
    // between. A grave made before that is an earlier death's, and one made after it a later one's.
    private static final long SAME_DEATH_MILLIS = 60_000L;
    // The first grave of each dead player's death, told at their respawn. Server thread only.
    private static final Map<UUID, UUID> AT_RESPAWN = new HashMap<>();

    private GraveNotice() {}

    static void init() {
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            // alive is a way back from the End, not a death
            if (alive) {
                return;
            }
            UUID graveId = AT_RESPAWN.remove(newPlayer.getUUID());
            if (graveId == null) {
                return;
            }
            Guard.run(() -> {
                String line = respawnLine(GraveStore.get(newPlayer.level().getServer()).byId(graveId).orElse(null), newPlayer);
                if (line != null) {
                    newPlayer.sendSystemMessage(Component.literal(line));
                }
            }, "Could not tell {} where their grave is", newPlayer.nameAndId().name());
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> AT_RESPAWN.clear());
    }

    /** At their respawn, the owner is told where this grave is, the first their death made. */
    public static void tellAtRespawn(UUID owner, UUID graveId) {
        AT_RESPAWN.put(owner, graveId);
    }

    /** At their respawn, the owner is told nothing: their death made no grave, or its kick told them. */
    public static void forget(UUID owner) {
        AT_RESPAWN.remove(owner);
    }

    /**
     * "Your grave is at x, y, z in the Overworld." for the owner's death at deathMillis, as the kick and the login
     * refusal tell it, or null when that death made no grave that is still there, or the setting says not to tell.
     * With coordinate offsets on, only the dimension: neither goes out in a frame, as the next one isn't known yet.
     * Never throws, since the login check calls it.
     */
    public static @Nullable String line(MinecraftServer server, UUID owner, long deathMillis) {
        try {
            return line(madeAt(server, owner, deathMillis));
        } catch (RuntimeException e) {
            Yolt.LOGGER.error("Could not look up where the grave of {} is, so they are not told", owner, e);
            return null;
        }
    }

    private static @Nullable String line(@Nullable Grave grave) {
        if (!tells(grave)) {
            return null;
        }
        return CoordinateOffsets.on() ? inLine(grave) : atLine(grave, grave.pos());
    }

    // In chat after the respawn, which goes out in the new frame: the spot the player's client shows, or only the
    // dimension when the grave is in another one
    private static @Nullable String respawnLine(@Nullable Grave grave, ServerPlayer player) {
        if (!tells(grave)) {
            return null;
        }
        return CoordinateOffsets.shownAt(player, GlobalPos.of(grave.dimension(), grave.pos()))
                .map(pos -> atLine(grave, pos))
                .orElseGet(() -> inLine(grave));
    }

    private static boolean tells(@Nullable Grave grave) {
        if (grave == null || !Settings.active()) {
            return false;
        }
        YoltConfig.GravesSection config = Settings.get().graves;
        return config.tellLocation != YoltConfig.TellLocation.NEVER && (config.tellLocation != YoltConfig.TellLocation.WHEN_MOVED
                || movedFar(grave, config.tellLocationDistance));
    }

    private static String atLine(Grave grave, BlockPos pos) {
        return "Your grave is at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + " in " + dimensionName(grave.dimension()) + ".";
    }

    private static String inLine(Grave grave) {
        return "Your grave is in " + dimensionName(grave.dimension()) + ".";
    }

    // The first grave of the death: one made within the minute before it, the newest such. One death's graves share a
    // time, and the store keeps them in the order made.
    private static @Nullable Grave madeAt(MinecraftServer server, UUID owner, long deathMillis) {
        Grave found = null;
        for (Grave grave : GraveStore.get(server).all()) {
            long made = grave.createdAt();
            if (grave.owner().equals(owner) && made <= deathMillis && made >= deathMillis - SAME_DEATH_MILLIS
                    && (found == null || made > found.createdAt())) {
                found = grave;
            }
        }
        return found;
    }

    // Further than the distance from where the owner died, or in another dimension. A grave made before graves kept the
    // death spot can't say, so it counts as not moved.
    private static boolean movedFar(Grave grave, int distance) {
        GlobalPos died = grave.deathPos();
        if (died == null) {
            return false;
        }
        return !died.dimension().equals(grave.dimension()) || died.pos().distSqr(grave.pos()) > (double) distance * distance;
    }

    private static String dimensionName(ResourceKey<Level> dimension) {
        if (dimension.equals(Level.OVERWORLD)) {
            return "the Overworld";
        }
        if (dimension.equals(Level.NETHER)) {
            return "the Nether";
        }
        if (dimension.equals(Level.END)) {
            return "the End";
        }
        return dimension.identifier().toString();
    }
}
