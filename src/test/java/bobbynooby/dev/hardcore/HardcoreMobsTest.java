package bobbynooby.dev.hardcore;

import bobbynooby.dev.config.HardcoreConfig;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HardcoreMobsTest {
    private static final double CLOSE = 1e-9;
    // The shipped boss list and per-mob overrides
    private static final HardcoreConfig.TougherMobsSection DEFAULTS = new HardcoreConfig.TougherMobsSection();
    private static final Set<String> BOSSES = new HashSet<>(DEFAULTS.exclude);
    private static final Map<String, Map<String, Double>> OVERRIDES = DEFAULTS.overrides;

    private static MobMaths.Boost tougher(String mobId) {
        return MobMaths.tougher(mobId, 1.5, 1.25, 1.1, BOSSES, OVERRIDES);
    }

    private static void assertBoost(double health, double damage, double speed, MobMaths.Boost boost) {
        assertEquals(health, boost.health(), CLOSE, "health");
        assertEquals(damage, boost.damage(), CLOSE, "damage");
        assertEquals(speed, boost.speed(), CLOSE, "speed");
    }

    // An override replaces only the values it names, and an excluded mob gets nothing at all
    @Test
    void mobs1DefaultsAndOverrides() {
        assertEquals(new MobMaths.Boost(1.5, 1.25, 1.1), tougher("minecraft:zombie"));
        assertBoost(2.0, 1.25, 1.1, tougher("minecraft:ghast"));
        assertBoost(1.0, 1.0, 1.0, tougher("minecraft:phantom"));
        assertBoost(1.5, 1.0, 1.1, tougher("minecraft:creeper"));
        assertEquals(MobMaths.Boost.NONE, tougher("minecraft:wither"));
        assertEquals(MobMaths.Boost.NONE, tougher("minecraft:warden"));
    }

    @Test
    void mobs1TougherTimesBloodMoon() {
        MobMaths.Boost bloodMoon = MobMaths.bloodMoon(1.25, 1.0, 1.1);
        assertBoost(1.875, 1.25, 1.21, new MobMaths.Boost(1.5, 1.25, 1.1).times(bloodMoon));
        assertBoost(1.25, 1.0, 1.1, MobMaths.Boost.NONE.times(bloodMoon));
    }
}
