package bobbynooby.dev.config;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/** Range checks on a loaded config. A value that fails is replaced by the fallback's. */
final class ConfigChecks {
    // Vanilla's own sample never has more than 12 names. Past these, a long list could push the status reply over its size limit.
    static final int MAX_HOVER_LINES = 12;
    static final int MAX_HOVER_LINE_LENGTH = 256;
    // A player is sent only one line, so the count just keeps the list a sane size, with room over the 13 lines YOLT
    // ships with. A line is cut where a hover line is.
    static final int MAX_TAB_HEADER_LINES = 64;
    static final int MAX_TAB_HEADER_LINE_LENGTH = 256;
    // A login waits on a skin for at most this long. Vanilla ends a login 600 ticks after it began and a connection 30
    // seconds after the client's last packet, and online-mode checks and other mods' login steps come before the wait,
    // so this leaves two thirds of both for them.
    static final int MAX_SKIN_LOOKUP_SECONDS = 10;
    // A grave's first search already covers 3 blocks round the death, so the wide search starts 4 out, and a
    // searchRadius of 3 means no wide search. At 32 it checks up to 37,584 spots on the server thread (9 layers of the
    // rings 4 to 32 out), four times the 9,360 at the default 16.
    static final int MIN_GRAVE_SEARCH_RADIUS = 3;
    static final int MAX_GRAVE_SEARCH_RADIUS = 32;

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
        if (c.chat.whisperRadius < 0) {
            c.chat.whisperRadius = fallback.chat.whisperRadius;
            fellBack.accept("chat.whisperRadius", "must be 0 or more");
        }
        if (c.graves.enderChestOnDeath == null) {
            c.graves.enderChestOnDeath = fallback.graves.enderChestOnDeath;
            fellBack.accept("graves.enderChestOnDeath", "unknown value");
        }
        if (c.graves.recheckSeconds < 0) {
            c.graves.recheckSeconds = fallback.graves.recheckSeconds;
            fellBack.accept("graves.recheckSeconds", "must be 0 or more");
        }
        if (c.graves.tellLocation == null) {
            c.graves.tellLocation = fallback.graves.tellLocation;
            fellBack.accept("graves.tellLocation", "unknown value");
        }
        if (c.graves.tellLocationDistance < 0) {
            c.graves.tellLocationDistance = fallback.graves.tellLocationDistance;
            fellBack.accept("graves.tellLocationDistance", "must be 0 or more");
        }
        if (outsideGraveSearch(c.graves.searchRadius)) {
            c.graves.searchRadius = fallback.graves.searchRadius;
            fellBack.accept("graves.searchRadius", "must be " + MIN_GRAVE_SEARCH_RADIUS + " to " + MAX_GRAVE_SEARCH_RADIUS);
        }
        if (outsideGraveSearch(c.graves.voidSearchRadius)) {
            c.graves.voidSearchRadius = fallback.graves.voidSearchRadius;
            fellBack.accept("graves.voidSearchRadius", "must be " + MIN_GRAVE_SEARCH_RADIUS + " to " + MAX_GRAVE_SEARCH_RADIUS);
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
        if (c.skins.mode == null) {
            c.skins.mode = fallback.skins.mode;
            fellBack.accept("skins.mode", "unknown value");
        }
        if (c.skins.lookupTimeoutSeconds < 1 || c.skins.lookupTimeoutSeconds > MAX_SKIN_LOOKUP_SECONDS) {
            c.skins.lookupTimeoutSeconds = fallback.skins.lookupTimeoutSeconds;
            fellBack.accept("skins.lookupTimeoutSeconds", "must be 1 to " + MAX_SKIN_LOOKUP_SECONDS);
        }
        if (c.skins.refreshHours < 0) {
            c.skins.refreshHours = fallback.skins.refreshHours;
            fellBack.accept("skins.refreshHours", "must be 0 or more");
        }
        if (c.serverList.shownMaxPlayers < 0) {
            c.serverList.shownMaxPlayers = fallback.serverList.shownMaxPlayers;
            fellBack.accept("serverList.shownMaxPlayers", "must be 0 or more");
        }
        c.serverList.hoverLines = capped(c.serverList.hoverLines, MAX_HOVER_LINES, MAX_HOVER_LINE_LENGTH,
                "serverList.hoverLines", warnings);
        c.hide.tabHeaderLines = capped(c.hide.tabHeaderLines, MAX_TAB_HEADER_LINES, MAX_TAB_HEADER_LINE_LENGTH,
                "hide.tabHeaderLines", warnings);
        return reset;
    }

    // Like the End cap, what fits is kept rather than the whole list falling back. A list that fits comes back as it is.
    private static List<String> capped(List<String> lines, int maxLines, int maxLength, String path, List<String> warnings) {
        List<String> capped = new ArrayList<>();
        for (String line : lines.subList(0, Math.min(lines.size(), maxLines))) {
            capped.add(cut(line, maxLength));
        }
        if (capped.equals(lines)) {
            return lines;
        }
        warnings.add(path + ": at most " + maxLines + " lines of " + maxLength + " characters each, the rest was dropped");
        return capped;
    }

    private static boolean outsideGraveSearch(int radius) {
        return radius < MIN_GRAVE_SEARCH_RADIUS || radius > MAX_GRAVE_SEARCH_RADIUS;
    }

    // Never between the two halves of a character outside the basic range, such as an emoji
    private static String cut(String line, int maxLength) {
        if (line.length() <= maxLength) {
            return line;
        }
        boolean split = Character.isHighSurrogate(line.charAt(maxLength - 1));
        return line.substring(0, split ? maxLength - 1 : maxLength);
    }
}
