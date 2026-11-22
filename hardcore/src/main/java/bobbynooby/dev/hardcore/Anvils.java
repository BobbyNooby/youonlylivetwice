package bobbynooby.dev.hardcore;

import bobbynooby.dev.anvils.AnvilSettings;
import bobbynooby.dev.anvils.YoltAnvils;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * The flat anvil price is the YOLT Anvils mod, shipped inside YOLT Hardcore's jar. YOLT Hardcore hosts it: it reads
 * hardcore.anvil from the settings in use, live, so under YOLT a reload or /yolt config change applies at the next use,
 * follows the host's switch, such as YOLT's for single-player, and leaves out the players every hardcore feature leaves
 * out. Its start-up line names the mod those settings come from. Its prices go in the /status rules.
 */
public final class Anvils {
    private Anvils() {}

    static void init() {
        YoltAnvils.hostedBy(Host.name(), Anvils::settings, Hardcore::exempt);
        Status.rule(Anvils::statusPart);
    }

    private static AnvilSettings settings() {
        HardcoreConfig.AnvilSection anvil = Host.settings().anvil;
        return new AnvilSettings(Host.on(c -> c.anvil.enabled), anvil.enchantCost, anvil.repairCost,
                anvil.renameCost, anvil.breakChance);
    }

    private static Optional<String> statusPart(ServerPlayer player) {
        AnvilSettings settings = settings();
        return settings.enabled() ? Optional.of("anvil: " + settings.prices()) : Optional.empty();
    }
}
