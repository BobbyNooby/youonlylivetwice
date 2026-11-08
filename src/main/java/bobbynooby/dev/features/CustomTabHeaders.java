package bobbynooby.dev.features;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/*

    Some classes to look at that helped in making this include

    Client :
    PlayerTabOverlay
    ClientPacketListener

    Server:
    ClientboundTabListPacket
    PlayerList


 */
public class CustomTabHeaders {

    private static final String HEADER_FOOTER = ChatFormatting.RESET.toString() + ChatFormatting.DARK_GRAY.toString() + "--------------";

    private static final Map<ServerPlayer, HeaderFooter> PLAYER_MAP = new HashMap<>();
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


    public static Component getFooter() {
        return Component.literal(HEADER_FOOTER);
    }

    public static void onPlayerJoin(ServerPlayer player) {
        PLAYER_MAP.put(player, new HeaderFooter(getRandomHeader(), HEADER_FOOTER));
    }

    public static HeaderFooter getPlayerTab(ServerPlayer player) {
        return PLAYER_MAP.get(player);
    }

    public static class HeaderFooter {
        public String HEADER_STRING;
        public String FOOTER_STRING;

        public HeaderFooter(String HEADER_STRING, String FOOTER_STRING) {
            this.HEADER_STRING = HEADER_STRING;
            this.FOOTER_STRING = FOOTER_STRING;
        }

        public void set(String HEADER_STRING, String FOOTER_STRING) {
            this.HEADER_STRING = HEADER_STRING;
            this.FOOTER_STRING = FOOTER_STRING;
        }
    }
}

