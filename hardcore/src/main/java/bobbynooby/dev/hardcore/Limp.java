package bobbynooby.dev.hardcore;

import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The limp: a player low on health is slowed until they heal back up. It starts below one line and stops only at a
 * higher one, so health creeping up in small steps doesn't switch it on and off. It lives in memory only: a player
 * who comes back starts from whole and limps again at once if their health is still low.
 */
public final class Limp {
    // By player, online only. Server thread only.
    private static final Map<UUID, Boolean> LIMPING = new HashMap<>();

    private Limp() {}

    static void init() {
        Slowdowns.register("limp", Limp::slowShare);
        Hardcore.onTick(Limp::tick);
        ServerPlayerEvents.LEAVE.register(player -> LIMPING.remove(player.getUUID()));
    }

    /**
     * Whether the player limps after this tick: from health in points (2 a heart) against the two lines in hearts.
     * Under the lower line they limp, at the upper one or more they don't, and in between nothing changes.
     */
    public static boolean next(boolean limping, double health, double belowHearts, double recoverAtHearts) {
        if (health < belowHearts * 2.0) {
            return true;
        }
        if (health >= recoverAtHearts * 2.0) {
            return false;
        }
        return limping;
    }

    /** True while the player limps; never for a creative or spectator player, or with the limp off. */
    public static boolean limping(ServerPlayer player) {
        return on() && !Hardcore.exempt(player) && !Host.isStandIn(player) && LIMPING.getOrDefault(player.getUUID(), false);
    }

    private static void tick(ServerPlayer player) {
        // A dead player starts the next life whole, so the respawn mustn't put a limp on them for a tick
        if (!on() || Host.isStandIn(player) || player.isDeadOrDying()) {
            LIMPING.remove(player.getUUID());
            return;
        }
        HardcoreConfig.LimpSection cfg = Host.settings().limp;
        double health = player.getHealth() + (cfg.countAbsorption ? player.getAbsorptionAmount() : 0.0F);
        boolean now = next(LIMPING.getOrDefault(player.getUUID(), false), health, cfg.belowHearts, cfg.recoverAtHearts);
        LIMPING.put(player.getUUID(), now);
    }

    private static boolean on() {
        return Host.on(c -> c.limp.enabled);
    }

    private static double slowShare(ServerPlayer player) {
        return limping(player) ? Host.settings().limp.slow : 0.0;
    }
}
