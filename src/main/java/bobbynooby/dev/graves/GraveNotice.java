package bobbynooby.dev.graves;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.core.Yolt;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * Tells a dead player, and nobody else, where their grave is, as graves.tellLocation says: in the lockout's kick and
 * login refusal, or in chat after the respawn when the death kicks nobody.
 */
public final class GraveNotice {
    private GraveNotice() {
    }

    static void init() {
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            // alive is a way back from the End, not a death. A death that kicks tells in the kick and at login instead.
            if (alive || GraveDeath.deathKicks()) {
                return;
            }
            String line = line(newPlayer.level().getServer(), newPlayer.getUUID());
            if (line != null) {
                newPlayer.sendSystemMessage(Component.literal(line));
            }
        });
    }

    /**
     * "Your grave is at x, y, z in the Overworld." for the owner's newest grave, or null when they have none or the
     * setting says not to tell. Never throws, since the login check calls it.
     */
    public static @Nullable String line(MinecraftServer server, UUID owner) {
        if (!Settings.active()) {
            return null;
        }
        YoltConfig.GravesSection config = Settings.get().graves;
        try {
            if (config.tellLocation == YoltConfig.TellLocation.NEVER) {
                return null;
            }
            Grave grave = newest(server, owner);
            if (grave == null || (config.tellLocation == YoltConfig.TellLocation.WHEN_MOVED
                    && !movedFar(grave, config.tellLocationDistance))) {
                return null;
            }
            BlockPos pos = grave.pos();
            return "Your grave is at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + " in "
                    + dimensionName(grave.dimension()) + ".";
        } catch (RuntimeException e) {
            Yolt.LOGGER.error("Could not look up where the grave of {} is, so they are not told", owner, e);
            return null;
        }
    }

    // The first grave of the newest death: one death's graves share a time, and the store keeps them in the order made
    private static @Nullable Grave newest(MinecraftServer server, UUID owner) {
        Grave newest = null;
        for (Grave grave : GraveStore.get(server).all()) {
            if (grave.owner().equals(owner) && (newest == null || grave.createdAt() > newest.createdAt())) {
                newest = grave;
            }
        }
        return newest;
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
