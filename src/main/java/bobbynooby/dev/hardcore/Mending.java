package bobbynooby.dev.hardcore;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.StandIn;
import bobbynooby.dev.mending.MendingSettings;
import bobbynooby.dev.mending.YoltMending;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * The Mending nerf is the YOLT Mending mod, shipped inside YOLT's jar. YOLT hosts it: it reads hardcore.mending from
 * YOLT's own config, live, so a reload or /yolt config change applies at the next pickup, follows YOLT's single-player
 * switch, and leaves out stand-ins such as player bodies, and the players every hardcore feature leaves out. Its rate
 * goes in the /status rules.
 */
public final class Mending {
    private Mending() {}

    static void init() {
        YoltMending.hostedBy("You Only Live Twice", Mending::settings,
                player -> StandIn.is(player) || Hardcore.exempt(player));
        Status.rule(Mending::statusPart);
    }

    private static MendingSettings settings() {
        return new MendingSettings(Settings.on(c -> c.hardcore.mending.enabled),
                Settings.get().hardcore.mending.durabilityPerXp);
    }

    private static Optional<String> statusPart(ServerPlayer player) {
        MendingSettings settings = settings();
        if (!settings.enabled()) {
            return Optional.empty();
        }
        return Optional.of("Mending " + MendingSettings.number(settings.durabilityPerXp()) + "/XP (vanilla "
                + MendingSettings.number(MendingSettings.VANILLA_RATE) + ")");
    }
}
