package bobbynooby.dev.lockout;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

/** One death: who, when, how long the lockout is, how they died, and who revived them and when, if anyone did. */
public record DeathLog(UUID uuid, String name, long deathTimeMillis, int cooldownDays, boolean bypass,
                       @Nullable String deathMessage, @Nullable Long revivedAt, @Nullable String revivedBy) {
    /** A revived death keeps no one out. Revives from before the database kept who and when only set bypass. */
    public boolean revived() {
        return bypass || revivedAt != null;
    }
}
