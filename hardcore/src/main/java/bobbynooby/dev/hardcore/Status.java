package bobbynooby.dev.hardcore;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * The /status report: each feature's lines about the player's own body, in a fixed order. A line never holds a position,
 * a height, a biome, a distance or another player, so the report gives nothing away about anyone else.
 *
 * <p>The colours live here, so every feature's lines look alike: a yellow label, white values, red for what costs the
 * player, green for what helps, aqua for the cold and gray for side notes and detail lines. The words always say the
 * same as the colour.
 */
public final class Status {
    /** The longest line, in characters, that still fits a default chat window without wrapping. */
    public static final int WIDTH = 48;
    private static final int TOTAL_SLOWDOWN = 30;
    private static final int RULES = 80;
    private static final String DETAIL = "  ";
    private static final String SEPARATOR = " · ";

    private record Line(int order, Function<ServerPlayer, List<Component>> lines) {}

    // Copy on write, since a test can take its line away while a report reads the list
    private static final List<Line> LINES = new CopyOnWriteArrayList<>();
    private static final List<Function<ServerPlayer, Optional<String>>> RULE_PARTS = new CopyOnWriteArrayList<>();

    static {
        register(TOTAL_SLOWDOWN, Status::totalSlowdown);
        register(RULES, Status::rules);
    }

    private Status() {}

    /**
     * Adds a feature's lines at their place in the report: a main line and any detail lines under it. Each feature
     * registers once, at init, and gives no lines while it is off.
     */
    public static void register(int order, Function<ServerPlayer, List<Component>> lines) {
        LINES.add(new Line(order, lines));
    }

    /** The same, for a test, which runs the returned task to take the lines away again. */
    public static Runnable registerForTesting(int order, Function<ServerPlayer, List<Component>> lines) {
        Line added = new Line(order, lines);
        LINES.add(added);
        return () -> LINES.remove(added);
    }

    /** Adds one rule to the "Server rules" block; the rules show in the order they were added. */
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
        report.add(Component.literal("Your body").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        report.addAll(lines);
        return report;
    }

    /** Only the feature lines, one chat line each, empty while the layer is off or nothing has a line to show. */
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
                lines.addAll(line.lines().apply(player));
            } catch (RuntimeException e) {
                // One broken feature mustn't cost the player the rest of the report
                YoltHardcore.LOGGER.error("A status line failed for {}", player.nameAndId().name(), e);
            }
        }
        return lines;
    }

    public static Component nothingToReport() {
        return Component.literal("Nothing to report").withStyle(ChatFormatting.GRAY);
    }

    /**
     * One chat line made of parts, with a plain explanation shown when any of it is hovered. The hover sits on the line
     * itself, and the parts take it from there, so it covers the whole line.
     */
    public static Component line(String hover, Component... parts) {
        MutableComponent line = Component.empty().withStyle(style -> style.withHoverEvent(hoverText(hover)));
        for (Component part : parts) {
            line.append(part);
        }
        return line;
    }

    /** An indented gray line under a main line, with the main line's hover. */
    public static Component detail(String hover, Component... parts) {
        MutableComponent line = Component.literal(DETAIL)
                .withStyle(style -> style.withColor(ChatFormatting.GRAY).withHoverEvent(hoverText(hover)));
        for (Component part : parts) {
            line.append(part);
        }
        return line;
    }

    /** The same, for a plain gray text. */
    public static Component detail(String hover, String text) {
        return detail(hover, Component.literal(text));
    }

    /** A line's first word, followed by its space. */
    public static MutableComponent label(String text) {
        return Component.literal(text + " ").withStyle(ChatFormatting.YELLOW);
    }

    /** A plain value. */
    public static MutableComponent value(String text) {
        return Component.literal(text).withStyle(ChatFormatting.WHITE);
    }

    /** Something that costs the player: a slowdown, lost hearts, a broken bone, a low nutrient. */
    public static MutableComponent bad(String text) {
        return Component.literal(text).withStyle(ChatFormatting.RED);
    }

    /** Something that helps: healing, warming, resting. */
    public static MutableComponent good(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GREEN);
    }

    /** How cold the player is. */
    public static MutableComponent cold(String text) {
        return Component.literal(text).withStyle(ChatFormatting.AQUA);
    }

    /** A danger to everyone out tonight, such as a blood moon. */
    public static MutableComponent danger(String text) {
        return Component.literal(text).withStyle(ChatFormatting.DARK_RED);
    }

    /** A side note. */
    public static MutableComponent note(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GRAY);
    }

    /** The gray " · " between two parts of a line. */
    public static MutableComponent separator() {
        return note(SEPARATOR);
    }

    /** The text cut to fit the room, ending in "…" when it had to be cut. */
    public static String fit(String text, int room) {
        if (text.length() <= room) {
            return text;
        }
        return text.substring(0, Math.max(room - 1, 0)) + "…";
    }

    private static HoverEvent hoverText(String hover) {
        return new HoverEvent.ShowText(Component.literal(hover));
    }

    private static List<Component> totalSlowdown(ServerPlayer player) {
        // A player outside the rules carries no slowdown, whatever the parts add up to
        double raw = Slowdowns.raw(player);
        if (Hardcore.exempt(player) || !(raw > 0.0)) {
            return List.of();
        }
        HardcoreConfig.SlowSection slow = Host.settings().slow;
        // Capped but not stepped, so the lines above it add up to it
        double total = Math.min(raw, Math.max(slow.cap, 0.0));
        // The limp has no line of its own, so the total says when it is part of it
        String hover = "Every slowdown added together, up to the cap. Potions such as Slowness come on top of it."
                + (Limp.limping(player) ? " It includes a limp from low health." : "");
        return List.of(line(hover, label("Total"), bad(percent(total) + "% slower"), separator(),
                note("cap " + percent(slow.cap) + "%")));
    }

    private static List<Component> rules(ServerPlayer player) {
        List<String> parts = new ArrayList<>();
        for (Function<ServerPlayer, Optional<String>> part : RULE_PARTS) {
            try {
                part.apply(player).ifPresent(parts::add);
            } catch (RuntimeException e) {
                // The other rules still show
                YoltHardcore.LOGGER.error("A status rule failed for {}", player.nameAndId().name(), e);
            }
        }
        if (parts.isEmpty()) {
            return List.of();
        }
        String hover = "How this server's rules differ from vanilla.";
        List<Component> lines = new ArrayList<>();
        lines.add(line(hover, Component.literal("Server rules").withStyle(ChatFormatting.GOLD)));
        for (String part : parts) {
            lines.add(detail(hover, part));
        }
        return lines;
    }

    private static long percent(double share) {
        return Math.round(share * 100.0);
    }
}
