package bobbynooby.dev.bodies;

import bobbynooby.dev.graves.GraveItems;
import bobbynooby.dev.offline.OfflineDeath;
import bobbynooby.dev.offline.OfflinePlayerData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import java.util.Optional;

/** Kills a player whether they are online or not, so both end in the same state. */
public final class Kills {
    public enum Result { KILLED_ONLINE, KILLED_OFFLINE, NOT_FOUND, ALREADY_DEAD, NOT_KILLED }

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
            return online.isDeadOrDying() ? Result.KILLED_ONLINE : Result.NOT_KILLED;
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
            return Bodies.killBody(server, body.get());
        }
        OfflineDeath.applyOfflineDeath(server, who, tag.get(), OfflinePlayerData.lastPosition(tag.get()),
                GraveItems.profileOf(who));
        return Result.KILLED_OFFLINE;
    }
}
