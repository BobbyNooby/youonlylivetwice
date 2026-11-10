package bobbynooby.dev.lockout;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.Guard;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * Deaths in creative or spectator don't count unless deathLockout.countCreativeAndSpectatorDeaths says so: no lockout,
 * no grave and no death record, and the player keeps their items, as with keepInventory. The game mode at the moment
 * of death decides.
 */
public final class UncountedDeaths {
    private UncountedDeaths() {
    }

    static void init() {
        ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) -> Guard.run(
                () -> handBackItems(oldPlayer, newPlayer, alive),
                "Could not hand {} back the items of a death that didn't count", newPlayer.nameAndId().name()));
    }

    /** True while YOLT runs and a death in this game mode doesn't count. */
    public static boolean uncounted(GameType mode) {
        return !mode.isSurvival() && Settings.on(c -> !c.deathLockout.countCreativeAndSpectatorDeaths);
    }

    public static boolean uncounted(ServerPlayer player) {
        return uncounted(player.gameMode());
    }

    // A death that didn't count dropped nothing, so the items are still on the dead player, and vanilla hands them over
    // only with keepInventory or to a spectator. Told apart by what is left on the dead player rather than by the game
    // mode, which an op can change before the respawn. Every other death has emptied the inventory, so this moves
    // nothing then.
    private static void handBackItems(ServerPlayer oldPlayer, ServerPlayer newPlayer, boolean alive) {
        if (alive || !Settings.active() || oldPlayer.isSpectator()
                || newPlayer.level().getGameRules().get(GameRules.KEEP_INVENTORY) || oldPlayer.getInventory().isEmpty()) {
            return;
        }
        newPlayer.getInventory().replaceWith(oldPlayer.getInventory());
    }
}
