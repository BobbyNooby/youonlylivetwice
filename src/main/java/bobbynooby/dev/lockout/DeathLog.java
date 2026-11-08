package bobbynooby.dev.lockout;

import java.util.UUID;

public record DeathLog(UUID uuid, String name, long deathTimeMillis, int cooldownDays, boolean bypass) {
}
