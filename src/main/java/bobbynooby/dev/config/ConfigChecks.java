package bobbynooby.dev.config;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/** Range checks on a loaded config. A value that fails is replaced by the fallback's. */
final class ConfigChecks {
    private ConfigChecks() {}

    /**
     * fallbackName ends each warning the way the loader's own do: "default" at startup, "the current value" on a reload.
     * Returns the keys put back to the fallback. The End cap isn't one: it keeps the largest border vanilla allows
     * instead of swapping in a fallback.
     */
    static List<String> validate(YoltConfig c, YoltConfig fallback, List<String> warnings, String fallbackName) {
        List<String> reset = new ArrayList<>();
        BiConsumer<String, String> fellBack = (path, problem) -> {
            reset.add(path);
            warnings.add(path + ": " + problem + ", using " + fallbackName);
        };
        if (c.worldBorder.diameter < 1 || c.worldBorder.diameter > ConfigLoader.MAX_BORDER_DIAMETER) {
            c.worldBorder.diameter = fallback.worldBorder.diameter;
            fellBack.accept("worldBorder.diameter", "must be 1 to " + ConfigLoader.MAX_BORDER_DIAMETER);
        }
        if (!Double.isFinite(c.worldBorder.endScale) || c.worldBorder.endScale <= 0) {
            c.worldBorder.endScale = fallback.worldBorder.endScale;
            fellBack.accept("worldBorder.endScale", "must be above 0");
        }
        // The End border is the overworld's times endScale, and vanilla's border can't be larger than its maximum
        double maxEndScale = (double) ConfigLoader.MAX_BORDER_DIAMETER / c.worldBorder.diameter;
        // The division can round up, which would put the End border a hair past the maximum
        if (c.worldBorder.diameter * maxEndScale > ConfigLoader.MAX_BORDER_DIAMETER) {
            maxEndScale = Math.nextDown(maxEndScale);
        }
        if (c.worldBorder.endScale > maxEndScale) {
            c.worldBorder.endScale = maxEndScale;
            warnings.add("worldBorder.endScale: worldBorder.diameter times endScale can't pass " + ConfigLoader.MAX_BORDER_DIAMETER
                    + ", using " + maxEndScale);
        }
        if (c.deathLockout.cooldownDays < 0) {
            c.deathLockout.cooldownDays = fallback.deathLockout.cooldownDays;
            fellBack.accept("deathLockout.cooldownDays", "must be 0 or more");
        }
        if (c.chat.radius < 1) {
            c.chat.radius = fallback.chat.radius;
            fellBack.accept("chat.radius", "must be 1 or more");
        }
        if (c.graves.enderChestOnDeath == null) {
            c.graves.enderChestOnDeath = fallback.graves.enderChestOnDeath;
            fellBack.accept("graves.enderChestOnDeath", "unknown value");
        }
        if (c.chat.mode == null) {
            c.chat.mode = fallback.chat.mode;
            fellBack.accept("chat.mode", "unknown value");
        }
        if (c.bodies.type == null) {
            c.bodies.type = fallback.bodies.type;
            fellBack.accept("bodies.type", "unknown value");
        }
        if (c.bodies.pose == null) {
            c.bodies.pose = fallback.bodies.pose;
            fellBack.accept("bodies.pose", "unknown value");
        }
        if (c.bodies.duration == null) {
            c.bodies.duration = fallback.bodies.duration;
            fellBack.accept("bodies.duration", "expected \"offline\" or minutes of 1 or more");
        }
        return reset;
    }
}
