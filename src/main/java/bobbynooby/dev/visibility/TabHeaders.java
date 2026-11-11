package bobbynooby.dev.visibility;

import bobbynooby.dev.config.Settings;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Gives each player a tab-list header picked at random from hide.tabHeaderLines, sent on join and whenever the live
 * settings are applied. Server thread only.
 */
public final class TabHeaders {
    // Over the picked line and as the footer. Decoration, not one of the lines.
    private static final String HEADER_FOOTER = ChatFormatting.RESET.toString() + ChatFormatting.DARK_GRAY.toString()
            + "--------------";

    private static final Map<UUID, HeaderFooter> PLAYER_MAP = new HashMap<>();
    private static final Random RANDOM = new Random();

    private TabHeaders() {}

    // A line starts from plain text, so the dashes' grey doesn't run into it
    private static HeaderFooter pick() {
        List<String> lines = Settings.get().hide.tabHeaderLines;
        if (lines.isEmpty()) {
            return HeaderFooter.NONE;
        }
        String line = lines.get(RANDOM.nextInt(lines.size()));
        return new HeaderFooter(line, HEADER_FOOTER + "\n" + ChatFormatting.RESET + line, HEADER_FOOTER);
    }

    // The client keeps a header until it disconnects, so one send per join is enough.
    // Entries are dropped on leave, otherwise every player who ever joined stays in the map.
    public static void init() {
        ServerPlayerEvents.JOIN.register(TabHeaders::send);
        ServerPlayerEvents.LEAVE.register(player -> remove(player.getUUID()));
    }

    /** Sends the player their header. One is picked with the switch off too, so turning it on later covers them. */
    public static void send(ServerPlayer player) {
        if (!Settings.active()) {
            return;
        }
        HeaderFooter headerFooter = PLAYER_MAP.computeIfAbsent(player.getUUID(), id -> pick());
        // A client starts with no header, so with no line there is nothing to send
        if (Settings.get().hide.tabHeader && headerFooter.line() != null) {
            player.connection.send(packet(headerFooter));
        }
    }

    /**
     * At startup and on /yolt reload: each player's header with the switch on, and none with it off. A player whose line
     * is no longer in hide.tabHeaderLines gets a new one first, so a changed list shows at once.
     */
    public static void applyAll(MinecraftServer server) {
        if (!Settings.active()) {
            return;
        }
        List<String> lines = Settings.get().hide.tabHeaderLines;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            HeaderFooter headerFooter = PLAYER_MAP.compute(player.getUUID(),
                    (id, had) -> had != null && had.pickedFrom(lines) ? had : pick());
            player.connection.send(packet(Settings.get().hide.tabHeader ? headerFooter : HeaderFooter.NONE));
        }
    }

    // Empty text is how the client is told to show no header
    private static ClientboundTabListPacket packet(HeaderFooter headerFooter) {
        if (headerFooter.line() == null) {
            return new ClientboundTabListPacket(Component.empty(), Component.empty());
        }
        return new ClientboundTabListPacket(Component.literal(headerFooter.header()), Component.literal(headerFooter.footer()));
    }

    public static boolean has(UUID uuid) {
        return PLAYER_MAP.containsKey(uuid);
    }

    public static void remove(UUID uuid) {
        PLAYER_MAP.remove(uuid);
    }

    /** A player's header: the configured line it shows, or null for none, and the texts sent for it. */
    public record HeaderFooter(@Nullable String line, String header, String footer) {
        static final HeaderFooter NONE = new HeaderFooter(null, "", "");

        // Still one of the lines, or still no line while the list is empty
        boolean pickedFrom(List<String> lines) {
            return line == null ? lines.isEmpty() : lines.contains(line);
        }
    }
}
