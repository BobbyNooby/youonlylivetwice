package bobbynooby.dev.hardcore;

import bobbynooby.dev.config.HardcoreConfig;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.Yolt;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * The /status report: each feature's line about the player's own body, in a fixed order. A line never holds a position,
 * a height, a biome, a distance or another player, so the report gives nothing away about anyone else.
 */
public final class Status {
    private static final int TOTAL_SLOWDOWN = 30;
    private static final int RULES = 80;
    private static final String RULE_SEPARATOR = " · ";

    private record Line(int order, Function<ServerPlayer, Optional<Component>> line) {}

    // Copy on write, since a test can take its line away while a report reads the list
    private static final List<Line> LINES = new CopyOnWriteArrayList<>();
    private static final List<Function<ServerPlayer, Optional<String>>> RULE_PARTS = new CopyOnWriteArrayList<>();

    static {
        register(TOTAL_SLOWDOWN, Status::totalSlowdown);
        register(RULES, Status::rules);
    }

    private Status() {}

    /** Adds a line at its place in the report. Each feature registers once, at init, and gives no line while it is off. */
    public static void register(int order, Function<ServerPlayer, Optional<Component>> line) {
        LINES.add(new Line(order, line));
    }

    /** The same, for a test, which runs the returned task to take the line away again. */
    public static Runnable registerForTesting(int order, Function<ServerPlayer, Optional<Component>> line) {
        Line added = new Line(order, line);
        LINES.add(added);
        return () -> LINES.remove(added);
    }

    /** Adds one part of the single "Rules" line; the parts are joined in the order they were added. */
    public static void rule(Function<ServerPlayer, Optional<String>> part) {
        RULE_PARTS.add(part);
    }

    /** "Your body" and then each line, or the single line "Nothing to report". */
    public static List<Component> report(ServerPlayer player) {
        List<Component> lines = lines(player);
        if (lines.isEmpty()) {
            return List.of(nothingToReport());
        }
        List<Component> report = new ArrayList<>();
        report.add(Component.literal("Your body"));
        report.addAll(lines);
        return report;
    }

    /** Only the feature lines, empty while the layer is off or nothing has a line to show. */
    public static List<Component> lines(ServerPlayer player) {
        // Off means off, whatever a line would say
        if (!Hardcore.anyOn()) {
            return List.of();
        }
        List<Line> ordered = new ArrayList<>(LINES);
        // A stable sort, so lines with the same order keep the order they were added in
        ordered.sort(Comparator.comparingInt(Line::order));
        List<Component> lines = new ArrayList<>();
        for (Line line : ordered) {
            try {
                line.line().apply(player).ifPresent(lines::add);
            } catch (RuntimeException e) {
                // One broken line mustn't cost the player the rest of the report
                Yolt.LOGGER.error("A status line failed for {}", player.nameAndId().name(), e);
            }
        }
        return lines;
    }

    public static Component nothingToReport() {
        return Component.literal("Nothing to report");
    }

    /** A line with a plain explanation shown when the text is hovered. */
    public static Component line(String text, String hover) {
        return Component.literal(text).withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }

    private static Optional<Component> totalSlowdown(ServerPlayer player) {
        // A player outside the rules carries no slowdown, whatever the parts add up to
        double raw = Slowdowns.raw(player);
        if (Hardcore.exempt(player) || !(raw > 0.0)) {
            return Optional.empty();
        }
        HardcoreConfig.SlowSection slow = Settings.get().hardcore.slow;
        // Capped but not stepped, so the lines above it add up to it
        double total = Math.min(raw, Math.max(slow.cap, 0.0));
        // The limp has no line of its own, so the total says when it is part of it
        String hover = "Every slowdown added together, up to the cap. Potions such as Slowness come on top of it."
                + (Limp.limping(player) ? " It includes a limp from low health." : "");
        return Optional.of(line("Total slowdown " + percent(total) + "% (cap " + percent(slow.cap) + "%; potions not included)", hover));
    }

    private static Optional<Component> rules(ServerPlayer player) {
        List<String> parts = new ArrayList<>();
        for (Function<ServerPlayer, Optional<String>> part : RULE_PARTS) {
            try {
                part.apply(player).ifPresent(parts::add);
            } catch (RuntimeException e) {
                // The other rules still show
                Yolt.LOGGER.error("A status rule failed for {}", player.nameAndId().name(), e);
            }
        }
        if (parts.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(line("Rules  " + String.join(RULE_SEPARATOR, parts), "How this server's rules differ from vanilla."));
    }

    private static long percent(double share) {
        return Math.round(share * 100.0);
    }
}
