package bobbynooby.dev.features;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/*

    Some classes to look at that helped in making this include

    Client :
    PlayerListHud
    ClientPlayNetworkHandler

    Server:
    PlayerListHeaderS2CPacket
    PlayerManager


 */
public class CustomTabHeaders {

    private static final String HEADER_FOOTER = Formatting.RESET.toString() + Formatting.DARK_GRAY.toString() + "--------------";

    private static final Map<ServerPlayerEntity, HeaderFooter> PLAYER_MAP = new HashMap<>();
    private static final Random RANDOM = new Random();
    private static final String[] TAB_HEADERS = {
            Formatting.RESET.toString() + Formatting.BLUE.toString() + "The cold atmosphere of a new land... it fills you with determination.",
            Formatting.RESET.toString() + Formatting.RED.toString() + "You scream out for help, but no one answers.",
            Formatting.RESET.toString() + Formatting.DARK_GRAY.toString() + "You are surrounded by darkness. You cannot see anything.",
            Formatting.RESET.toString() + Formatting.AQUA.toString() + "The horizon stretches far ahead, and you wonder what awaits.",
            Formatting.RESET.toString() + Formatting.GREEN.toString() + "The map is incomplete. Where will you go next?",
            Formatting.RESET.toString() + Formatting.YELLOW.toString() + "The path ahead is unclear, but your journey must continue.",
            Formatting.RESET.toString() + Formatting.LIGHT_PURPLE.toString() + "Every step into the unknown feels like a new discovery.",
            Formatting.RESET.toString() + Formatting.GOLD.toString() + "A new land lies before you. What secrets will it reveal?",
            Formatting.RESET.toString() + Formatting.RED.toString() + "The world is vast and uncharted. Adventure calls.",
            Formatting.RESET.toString() + Formatting.GRAY.toString() + "Who knows what lies beyond the next ridge?",
            Formatting.RESET.toString() + Formatting.WHITE.toString() + "With every sunrise, the unknown becomes a little clearer.",
            Formatting.RESET.toString() + Formatting.DARK_GREEN.toString() + "You're not sure what you'll find, but you're ready to explore.",
            Formatting.RESET.toString() + Formatting.DARK_AQUA.toString() + "The world is filled with possibilities... and dangers."
    };


    private static String getRandomHeader() {
        return HEADER_FOOTER + "\n" + TAB_HEADERS[RANDOM.nextInt(TAB_HEADERS.length)];
    }


    public static Text getFooter() {
        return Text.literal(HEADER_FOOTER);
    }

    public static void onPlayerJoin(ServerPlayerEntity player) {
        PLAYER_MAP.put(player, new HeaderFooter(getRandomHeader(), HEADER_FOOTER));
    }

    public static HeaderFooter getPlayerTab(ServerPlayerEntity player) {
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

