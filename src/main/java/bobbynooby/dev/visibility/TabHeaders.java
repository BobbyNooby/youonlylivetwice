package bobbynooby.dev.visibility;

import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/** Gives each player a random tab-list header and footer. Server thread only. */
public final class TabHeaders {
    private static final String HEADER_FOOTER = ChatFormatting.RESET.toString() + ChatFormatting.DARK_GRAY.toString() + "--------------";

    private static final Map<UUID, HeaderFooter> PLAYER_MAP = new HashMap<>();
    private static final Random RANDOM = new Random();
    private static final String[] TAB_HEADERS = {
            ChatFormatting.RESET.toString() + ChatFormatting.BLUE.toString() + "The cold atmosphere of a new land... it fills you with determination.",
            ChatFormatting.RESET.toString() + ChatFormatting.RED.toString() + "You scream out for help, but no one answers.",
            ChatFormatting.RESET.toString() + ChatFormatting.DARK_GRAY.toString() + "You are surrounded by darkness. You cannot see anything.",
            ChatFormatting.RESET.toString() + ChatFormatting.AQUA.toString() + "The horizon stretches far ahead, and you wonder what awaits.",
            ChatFormatting.RESET.toString() + ChatFormatting.GREEN.toString() + "The map is incomplete. Where will you go next?",
            ChatFormatting.RESET.toString() + ChatFormatting.YELLOW.toString() + "The path ahead is unclear, but your journey must continue.",
            ChatFormatting.RESET.toString() + ChatFormatting.LIGHT_PURPLE.toString() + "Every step into the unknown feels like a new discovery.",
            ChatFormatting.RESET.toString() + ChatFormatting.GOLD.toString() + "A new land lies before you. What secrets will it reveal?",
            ChatFormatting.RESET.toString() + ChatFormatting.RED.toString() + "The world is vast and uncharted. Adventure calls.",
            ChatFormatting.RESET.toString() + ChatFormatting.GRAY.toString() + "Who knows what lies beyond the next ridge?",
            ChatFormatting.RESET.toString() + ChatFormatting.WHITE.toString() + "With every sunrise, the unknown becomes a little clearer.",
            ChatFormatting.RESET.toString() + ChatFormatting.DARK_GREEN.toString() + "You're not sure what you'll find, but you're ready to explore.",
            ChatFormatting.RESET.toString() + ChatFormatting.DARK_AQUA.toString() + "The world is filled with possibilities... and dangers."
    };

    private static String getRandomHeader() {
        return HEADER_FOOTER + "\n" + TAB_HEADERS[RANDOM.nextInt(TAB_HEADERS.length)];
    }

    // Entries are dropped on leave, otherwise every player who ever joined stays in the map
    public static void init() {
        ServerPlayerEvents.LEAVE.register(player -> remove(player.getUUID()));
    }

    public static void onJoin(ServerPlayer player) {
        PLAYER_MAP.put(player.getUUID(), new HeaderFooter(getRandomHeader(), HEADER_FOOTER));
    }

    public static void sendAll(PlayerList list) {
        for (ServerPlayer player : list.getPlayers()) {
            HeaderFooter headerFooter = PLAYER_MAP.get(player.getUUID());
            if (headerFooter == null) {
                continue;
            }
            player.connection.send(new ClientboundTabListPacket(Component.literal(headerFooter.header()), Component.literal(headerFooter.footer())));
        }
    }

    public static boolean has(UUID uuid) {
        return PLAYER_MAP.containsKey(uuid);
    }

    public static void remove(UUID uuid) {
        PLAYER_MAP.remove(uuid);
    }

    public record HeaderFooter(String header, String footer) {
    }
}
