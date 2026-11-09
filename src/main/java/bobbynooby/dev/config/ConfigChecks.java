package bobbynooby.dev.config;

import java.util.List;
import java.util.Locale;

/** Range checks on a loaded config. A value that fails is replaced by the fallback's. */
final class ConfigChecks {
    private ConfigChecks() {}

    static void validate(YoltConfig c, YoltConfig fallback, List<String> warnings) {
        if (c.worldBorder.diameter < 1 || c.worldBorder.diameter > ConfigLoader.MAX_BORDER_DIAMETER) {
            c.worldBorder.diameter = fallback.worldBorder.diameter;
            warnings.add("worldBorder.diameter: must be 1 to " + ConfigLoader.MAX_BORDER_DIAMETER + ", using " + c.worldBorder.diameter);
        }
        if (!Double.isFinite(c.worldBorder.endScale) || c.worldBorder.endScale <= 0) {
            c.worldBorder.endScale = fallback.worldBorder.endScale;
            warnings.add("worldBorder.endScale: must be above 0, using " + c.worldBorder.endScale);
        }
        // The End border is the overworld's times endScale, and vanilla's border can't be larger than its maximum
        double maxEndScale = (double) ConfigLoader.MAX_BORDER_DIAMETER / c.worldBorder.diameter;
        if (c.worldBorder.endScale > maxEndScale) {
            c.worldBorder.endScale = maxEndScale;
            warnings.add("worldBorder.endScale: worldBorder.diameter times endScale can't pass " + ConfigLoader.MAX_BORDER_DIAMETER
                    + ", using " + maxEndScale);
        }
        if (c.deathLockout.cooldownDays < 0) {
            c.deathLockout.cooldownDays = fallback.deathLockout.cooldownDays;
            warnings.add("deathLockout.cooldownDays: must be 0 or more, using " + c.deathLockout.cooldownDays);
        }
        if (c.chat.radius < 1) {
            c.chat.radius = fallback.chat.radius;
            warnings.add("chat.radius: must be 1 or more, using " + c.chat.radius);
        }
        if (c.graves.enderChestOnDeath == null) {
            c.graves.enderChestOnDeath = fallback.graves.enderChestOnDeath;
            warnings.add("graves.enderChestOnDeath: unknown value, using " + name(c.graves.enderChestOnDeath));
        }
        if (c.chat.mode == null) {
            c.chat.mode = fallback.chat.mode;
            warnings.add("chat.mode: unknown value, using " + name(c.chat.mode));
        }
        if (c.bodies.type == null) {
            c.bodies.type = fallback.bodies.type;
            warnings.add("bodies.type: unknown value, using " + name(c.bodies.type));
        }
        if (c.bodies.pose == null) {
            c.bodies.pose = fallback.bodies.pose;
            warnings.add("bodies.pose: unknown value, using " + name(c.bodies.pose));
        }
        if (c.bodies.duration == null) {
            c.bodies.duration = fallback.bodies.duration;
            warnings.add("bodies.duration: expected \"offline\" or minutes of 1 or more, using "
                    + (c.bodies.duration.isOffline() ? "\"offline\"" : c.bodies.duration.minutes()));
        }
    }

    private static String name(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
