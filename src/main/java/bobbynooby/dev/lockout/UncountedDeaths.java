package bobbynooby.dev.lockout;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.Guard;
import bobbynooby.dev.core.StandIn;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * Deaths in creative or spectator don't count unless deathLockout.countCreativeAndSpectatorDeaths says so: no lockout,
 * no grave and no death record, and the player keeps their items, XP and score, as with keepInventory. The game mode
 * at the moment of death decides.
 */
public final class UncountedDeaths {
    /**
     * The entity tag a death that didn't count leaves on the dead player, until the respawn hands everything back. It is
     * saved with the player, so a rejoin after a restart still has it, and an op changing the game mode meanwhile
     * changes nothing.
     */
    public static final String MARK = "yolt_uncounted_death";

    private UncountedDeaths() {
    }

    static void init() {
        // Every such death, a spectator's too, whose drops vanilla skips. A death that counts takes off a mark from
        // anywhere else, such as /tag, so the respawn can't hand back the XP this death dropped.
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer player && !StandIn.is(player)) {
                if (uncounted(player)) {
                    player.addTag(MARK);
                } else {
                    player.removeTag(MARK);
                }
            }
        });
        ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) -> Guard.run(
                () -> handBack(oldPlayer, newPlayer, alive),
                "Could not hand {} back what they had at a death that didn't count", newPlayer.nameAndId().name()));
    }

    /** True while YOLT runs and a death in this game mode doesn't count. */
    public static boolean uncounted(GameType mode) {
        return !mode.isSurvival() && Settings.on(c -> !c.deathLockout.countCreativeAndSpectatorDeaths);
    }

    public static boolean uncounted(ServerPlayer player) {
        return uncounted(player.gameMode());
    }

    /**
     * At a death's drops: true when the death doesn't count, and then it drops no XP either, the way keepInventory does.
     * Vanilla drops the XP right after the items.
     */
    public static boolean keepsDrops(ServerPlayer player) {
        if (!uncounted(player)) {
            return false;
        }
        player.skipDropExperience();
        return true;
    }

    // What keepInventory hands over: vanilla does it already with keepInventory on and for a spectator
    private static void handBack(ServerPlayer oldPlayer, ServerPlayer newPlayer, boolean alive) {
        if (alive || !oldPlayer.entityTags().contains(MARK)) {
            return;
        }
        // Off the old player first, or the respawn would copy it onto the new one with the other tags
        oldPlayer.removeTag(MARK);
        if (oldPlayer.isSpectator() || newPlayer.level().getGameRules().get(GameRules.KEEP_INVENTORY)) {
            return;
        }
        newPlayer.getInventory().replaceWith(oldPlayer.getInventory());
        newPlayer.experienceLevel = oldPlayer.experienceLevel;
        newPlayer.totalExperience = oldPlayer.totalExperience;
        newPlayer.experienceProgress = oldPlayer.experienceProgress;
        newPlayer.setScore(oldPlayer.getScore());
    }
}
