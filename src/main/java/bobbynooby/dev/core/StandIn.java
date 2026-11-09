package bobbynooby.dev.core;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.List;

/**
 * A player entity that stands in for a player who isn't online, such as a player body. It has no client. Code outside
 * the bodies package checks this, so it doesn't need to import the body class.
 */
public interface StandIn {
    static boolean is(Entity entity) {
        return entity instanceof StandIn;
    }

    /** The players that aren't stand-ins. The same list when there are none, which is nearly always. */
    static List<ServerPlayer> without(List<ServerPlayer> players) {
        for (ServerPlayer player : players) {
            if (is(player)) {
                return players.stream().filter(p -> !is(p)).toList();
            }
        }
        return players;
    }
}
