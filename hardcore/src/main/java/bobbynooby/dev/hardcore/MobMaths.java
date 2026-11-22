package bobbynooby.dev.hardcore;

import java.util.Map;
import java.util.Set;

/** The multipliers a mob gets, worked out from the config's numbers alone. */
public final class MobMaths {
    private MobMaths() {}

    /** Health, damage and speed multipliers; 1 is vanilla. */
    public record Boost(double health, double damage, double speed) {
        public static final Boost NONE = new Boost(1, 1, 1);

        /** Both boosts at once, as they stack on a mob that has both. */
        public Boost times(Boost other) {
            return new Boost(health * other.health, damage * other.damage, speed * other.speed);
        }
    }

    /** The tougher-mobs boost for one mob type. An excluded mob gets none; an override replaces only the values it names. */
    public static Boost tougher(String mobId, double health, double damage, double speed, Set<String> exclude,
                                Map<String, Map<String, Double>> overrides) {
        if (exclude.contains(mobId)) {
            return Boost.NONE;
        }
        Map<String, Double> own = overrides.get(mobId);
        if (own == null) {
            return new Boost(health, damage, speed);
        }
        return new Boost(own.getOrDefault("health", health), own.getOrDefault("damage", damage),
                own.getOrDefault("speed", speed));
    }

    /** The blood moon's own boost, which goes on top of the tougher-mobs one. */
    public static Boost bloodMoon(double health, double damage, double speed) {
        return new Boost(health, damage, speed);
    }
}
