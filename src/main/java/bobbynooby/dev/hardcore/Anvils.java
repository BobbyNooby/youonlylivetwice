package bobbynooby.dev.hardcore;

import bobbynooby.dev.anvils.AnvilSettings;
import bobbynooby.dev.anvils.YoltAnvils;
import bobbynooby.dev.config.Settings;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * The flat anvil price is the YOLT Anvils mod, shipped inside YOLT's jar. YOLT hosts it: it reads hardcore.anvil from
 * YOLT's own config, live, so a reload or /yolt config change applies at the next use, follows YOLT's single-player
 * switch, and leaves out the players every hardcore feature leaves out. Its prices go in the /status rules.
 */
public final class Anvils {
    private Anvils() {}

    static void init() {
        YoltAnvils.hostedBy("You Only Live Twice", Anvils::settings, Hardcore::exempt);
        Status.rule(Anvils::statusPart);
    }

    private static AnvilSettings settings() {
        HardcoreConfig.AnvilSection anvil = Settings.get().hardcore.anvil;
        return new AnvilSettings(Settings.on(c -> c.hardcore.anvil.enabled), anvil.enchantCost, anvil.repairCost,
                anvil.renameCost, anvil.breakChance);
    }

    private static Optional<String> statusPart(ServerPlayer player) {
        AnvilSettings settings = settings();
        return settings.enabled() ? Optional.of("anvil: " + settings.prices()) : Optional.empty();
    }
}
