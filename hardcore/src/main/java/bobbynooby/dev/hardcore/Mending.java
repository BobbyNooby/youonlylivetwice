package bobbynooby.dev.hardcore;

import bobbynooby.dev.mending.MendingSettings;
import bobbynooby.dev.mending.YoltMending;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * The Mending nerf is the YOLT Mending mod, shipped inside YOLT Hardcore's jar. YOLT Hardcore hosts it: it reads
 * hardcore.mending from the settings in use, live, so under YOLT a reload or /yolt config change applies at the next
 * pickup, follows the host's switch, such as YOLT's for single-player, and leaves out stand-ins such as player bodies,
 * and the players every hardcore feature leaves out. Its start-up line names the mod those settings come from. Its
 * rate goes in the /status rules.
 */
public final class Mending {
    private Mending() {}

    static void init() {
        YoltMending.hostedBy(Host.name(), Mending::settings,
                player -> Host.isStandIn(player) || Hardcore.exempt(player));
        Status.rule(Mending::statusPart);
    }

    private static MendingSettings settings() {
        return new MendingSettings(Host.on(c -> c.mending.enabled),
                Host.settings().mending.durabilityPerXp);
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
