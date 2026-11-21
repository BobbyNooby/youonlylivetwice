package bobbynooby.dev.config;

import bobbynooby.dev.anvils.AnvilSettings;
import bobbynooby.dev.mending.MendingSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

// YOLT's config and each tiny mod's own file ship the same numbers, so a server that moves from one to the other plays
// the same
class TinyModDefaultsTest {
    @Test
    void anvilNumbersMatchTheMod() {
        HardcoreConfig.AnvilSection anvil = new YoltConfig().hardcore.anvil;
        assertEquals(AnvilSettings.DEFAULTS, new AnvilSettings(anvil.enabled, anvil.enchantCost, anvil.repairCost,
                anvil.renameCost, anvil.breakChance));
    }

    @Test
    void mendingNumbersMatchTheMod() {
        HardcoreConfig.MendingSection mending = new YoltConfig().hardcore.mending;
        assertEquals(MendingSettings.DEFAULTS, new MendingSettings(mending.enabled, mending.durabilityPerXp));
    }
}
