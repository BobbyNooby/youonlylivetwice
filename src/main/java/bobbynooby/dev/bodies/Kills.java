package bobbynooby.dev.bodies;

import bobbynooby.dev.core.Profiles;
import bobbynooby.dev.lockout.UncountedDeaths;
import bobbynooby.dev.offline.OfflineDeath;
import bobbynooby.dev.offline.OfflinePlayerData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import java.util.Optional;

/** Kills a player whether they are online or not, so both end in the same state. */
public final class Kills {
    // KILLED_UNCOUNTED: dead, but in creative or spectator, so the death doesn't count
    public enum Result { KILLED_ONLINE, KILLED_OFFLINE, KILLED_UNCOUNTED, NOT_FOUND, ALREADY_DEAD, NOT_KILLED }

    /** A failure the body's death path has logged already, so the caller only reports it. */
    public static final class LoggedFailure extends IllegalStateException {
        LoggedFailure(String message) {
            super(message);
        }
    }

    private Kills() {}

    /**
     * A player who is already dead is left exactly as they are, so a second kill can't restart their lockout.
     * An offline player whose body still stands dies through that body.
     */
    public static Result kill(MinecraftServer server, NameAndId who) {
        ServerPlayer online = server.getPlayerList().getPlayer(who.id());
        if (online != null) {
            if (online.isDeadOrDying()) {
                return Result.ALREADY_DEAD;
            }
            // Same as vanilla /kill: the normal death runs, and its death event starts the lockout
            online.kill(online.level());
            // Vanilla ignores the kill while the player is still loading in or changing dimension
            if (!online.isDeadOrDying()) {
                return Result.NOT_KILLED;
            }
            return UncountedDeaths.uncounted(online) ? Result.KILLED_UNCOUNTED : Result.KILLED_ONLINE;
        }
        // No save file means they never played here
        Optional<CompoundTag> tag = OfflinePlayerData.load(server, who);
        if (tag.isEmpty()) {
            return Result.NOT_FOUND;
        }
        // Logged out dead, or killed offline before. A save with no health loads at full health.
        if (OfflinePlayerData.isDead(tag.get())) {
            return Result.ALREADY_DEAD;
        }
        // A pending record was taken over already, so the save is the player's real state
        Optional<BodyRecord> body = BodyStore.get(server).byOwner(who.id()).filter(record -> !record.pendingRemoval());
        if (body.isPresent()) {
            return BodyDeath.killBody(server, body.get());
        }
        // Read before the death, which changes the tag
        boolean uncounted = UncountedDeaths.uncounted(OfflinePlayerData.gameType(tag.get()));
        // No player or body to copy a skin from, so the grave head gets the one last known for them
        OfflineDeath.applyOfflineDeath(server, who, tag.get(), OfflinePlayerData.lastPosition(tag.get()),
                Profiles.of(server, who), null);
        return uncounted ? Result.KILLED_UNCOUNTED : Result.KILLED_OFFLINE;
    }
}
