package bobbynooby.dev.bodies;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.Guard;
import bobbynooby.dev.core.Profiles;
import bobbynooby.dev.lockout.DeathMessages;
import bobbynooby.dev.offline.OfflineDeath;
import bobbynooby.dev.offline.OfflinePlayerData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Optional;

/** Kills a body's owner when the body dies, and kills a standing body for an offline /yolt kill. */
final class BodyDeath {
    private BodyDeath() {
    }

    /**
     * A body's death is its owner's: their saved items go into a grave where the body fell, and the lockout starts.
     * With bodies switched off this still runs, since a body left from before still stands for its owner.
     */
    static void onBodyDeath(LivingEntity body) {
        if (!Settings.active() || !body.entityTags().contains(Bodies.TAG) || !(body.level() instanceof ServerLevel level)) {
            return;
        }
        MinecraftServer server = level.getServer();
        Optional<BodyRecord> found = BodyStore.get(server).byEntity(body.getUUID());
        // A pending record was taken over already, so its body is a stale copy. An owner who is online isn't away.
        if (found.isEmpty() || found.get().pendingRemoval() || server.getPlayerList().getPlayer(found.get().owner()) != null) {
            return;
        }
        BodyRecord record = found.get();
        // No retry: a failed death leaves the save as it was, so nothing is lost, and the owner's next login
        // takes over the body's last saved state instead. A retry would also start from the wiped tag.
        Guard.run(() -> killOwner(server, record, body), "The body of {} died, but they could not be killed",
                record.ownerName());
    }

    /**
     * Who a dying entity is in the death log: for a body its owner, from the body's record, since a mannequin's own name
     * is just "Mannequin"; for anything else its uuid. Worked out only when the line is written, as every death passes here.
     */
    static Object logName(LivingEntity entity) {
        return new Object() {
            @Override
            public String toString() {
                try {
                    if (entity.level() instanceof ServerLevel level) {
                        Optional<BodyRecord> record = BodyStore.get(level.getServer()).byEntity(entity.getUUID());
                        if (record.isPresent()) {
                            return record.get().ownerName();
                        }
                    }
                } catch (RuntimeException e) {
                    // The line already reports a failure, maybe this same one, so the uuid will do
                }
                return entity.getUUID().toString();
            }
        };
    }

    /**
     * Offline /yolt kill of a player whose body still stands. A loaded body is killed and its death does the rest;
     * one in an unloaded chunk dies at its last saved spot, and goes when its chunk next loads.
     * Throws if the owner could not be killed.
     */
    static Kills.Result killBody(MinecraftServer server, BodyRecord record) {
        // A body still dying from a death that failed to kill its owner counts as gone
        if (Bodies.entityOf(server, record) instanceof LivingEntity body
                && !body.isDeadOrDying() && body.level() instanceof ServerLevel level) {
            body.kill(level);
            Optional<BodyRecord> left = BodyStore.get(server).byOwner(record.owner());
            if (left.isEmpty()) {
                return Kills.Result.KILLED_OFFLINE;
            }
            // Taken over meanwhile: the death handler skips a stale body and logs nothing
            if (left.get().pendingRemoval()) {
                return Kills.Result.NOT_KILLED;
            }
            if (!body.isDeadOrDying()) {
                // Another mod stopped the death
                return Kills.Result.NOT_KILLED;
            }
            throw new Kills.LoggedFailure("The body of " + record.ownerName() + " died, but they could not be killed");
        }
        BodySnapshot snapshot = record.snapshot();
        // No entity, so no skin to keep: the grave head is built from the name. Nothing died, so no death message.
        killOwner(server, record, snapshot.gear(), GlobalPos.of(snapshot.dimension(), BlockPos.containing(snapshot.pos())),
                Profiles.of(record.who()), null);
        return Kills.Result.KILLED_OFFLINE;
    }

    // The body's gear as it is now, where it fell, its skin for the grave head, and how it died. A player body's own
    // profile has the body's id, and a revive head needs the owner's.
    private static void killOwner(MinecraftServer server, BodyRecord record, LivingEntity body) {
        ResolvableProfile profile = body instanceof PlayerBody playerBody ? playerBody.ownerProfile()
                : body instanceof Avatar avatar ? avatar.getProfile()
                : Profiles.of(record.who());
        killOwner(server, record, BodySnapshot.of(body).gear(), GlobalPos.of(body.level().dimension(), body.blockPosition()),
                profile, DeathMessages.ofBody(body, record.ownerName()));
    }

    // Through the one offline-death routine. The record only goes once that has worked.
    private static void killOwner(MinecraftServer server, BodyRecord record, Map<EquipmentSlot, ItemStack> bodyGear,
                                  GlobalPos where, ResolvableProfile profile, @Nullable String bodyDeathMessage) {
        NameAndId who = record.who();
        CompoundTag tag = OfflinePlayerData.loadOrThrow(server, who);
        // Already dead, say after a crash before the record's removal was saved: a second death would restart the lockout
        if (!OfflinePlayerData.isDead(tag)) {
            // What happened to the body's gear goes onto the save first, so the grave holds what the body really wore
            BodySaves.applyBodyGear(tag, bodyGear, server.registryAccess());
            OfflineDeath.applyOfflineDeath(server, who, tag, where, profile, bodyDeathMessage);
        }
        BodyStore.get(server).remove(record.owner());
    }
}
