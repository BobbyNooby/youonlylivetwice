package bobbynooby.dev.config;

import bobbynooby.dev.anvils.AnvilSettings;
import bobbynooby.dev.coords.CoordinateSettings;
import bobbynooby.dev.elytra.ElytraSettings;
import bobbynooby.dev.hardcore.HardcoreConfig;
import bobbynooby.dev.mending.MendingSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The tiny mods and YOLT Coordinates each ship the same numbers in their own file as YOLT's config, so a server that
// moves from one to the other plays the same. The switches match too, apart from the elytra nerf's, which YOLT ships
// off.
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

    // The mod on its own is on as soon as it is installed, while YOLT ships its elytra nerf off
    @Test
    void elytraNumbersMatchTheModAndItStaysOffHere() {
        HardcoreConfig.ElytraSection elytra = new YoltConfig().hardcore.elytra;
        assertFalse(elytra.enabled, "YOLT ships the elytra nerf on");
        assertEquals(ElytraSettings.DEFAULTS.glideGravityMultiplier(), elytra.glideGravityMultiplier,
                "glideGravityMultiplier");
        assertEquals(ElytraSettings.DEFAULTS.rocketCutoffSpeed(), elytra.rocketCutoffSpeed, "rocketCutoffSpeed");
    }

    // Both ship on, so a server that moves from YOLT to the mod on its own, or back, keeps hiding coordinates
    @Test
    void coordinateOffsetSwitchMatchesTheMod() {
        boolean coordinates = new YoltConfig().hide.coordinates;
        assertTrue(coordinates, "YOLT ships hide.coordinates off");
        assertEquals(CoordinateSettings.DEFAULTS.enabled(), coordinates,
                "the mod's enabled and hide.coordinates ship different values");
    }
}
