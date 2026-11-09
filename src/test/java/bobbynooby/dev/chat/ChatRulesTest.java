package bobbynooby.dev.chat;

import bobbynooby.dev.chat.ChatRules.Listener;
import bobbynooby.dev.config.YoltConfig.ChatMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ChatRulesTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    private static Listener at(String dim, double x, boolean admin) {
        return new Listener(UUID.randomUUID(), dim, x, 64, 0, admin);
    }

    private static Set<UUID> hear(Listener sender, ChatMode mode, Listener... others) {
        return ChatRules.chatRecipients(sender, List.of(others), mode, 48);
    }

    @Test
    void globalReachesEveryone() {
        Listener sender = at(OVERWORLD, 0, false);
        Listener far = at(NETHER, 9000, false);
        Set<UUID> heard = hear(sender, ChatMode.GLOBAL, sender, far);
        assertTrue(heard.contains(sender.id()));
        assertTrue(heard.contains(far.id()));
    }

    @Test
    void selfOnlyReachesSenderAndAdmins() {
        Listener sender = at(OVERWORLD, 0, false);
        Listener near = at(OVERWORLD, 1, false);
        Listener admin = at(NETHER, 9000, true);
        Set<UUID> heard = hear(sender, ChatMode.SELF_ONLY, near, admin);
        assertTrue(heard.contains(sender.id()));
        assertTrue(heard.contains(admin.id()));
        assertFalse(heard.contains(near.id()));
    }

    @Test
    void proximityReachesNearbySameDimension() {
        Listener sender = at(OVERWORLD, 0, false);
        Listener near = at(OVERWORLD, 10, false);
        Listener far = at(OVERWORLD, 100, false);
        Set<UUID> heard = hear(sender, ChatMode.PROXIMITY, near, far);
        assertTrue(heard.contains(sender.id()));
        assertTrue(heard.contains(near.id()));
        assertFalse(heard.contains(far.id()));
    }

    @Test
    void radiusIsInclusive() {
        Listener sender = at(OVERWORLD, 0, false);
        Listener edge = at(OVERWORLD, 48.0, false);
        Listener past = at(OVERWORLD, 48.01, false);
        Set<UUID> heard = hear(sender, ChatMode.PROXIMITY, edge, past);
        assertTrue(heard.contains(edge.id()));
        assertFalse(heard.contains(past.id()));
    }

    @Test
    void otherDimensionNeverHears() {
        Listener sender = at(OVERWORLD, 0, false);
        Listener nether = at(NETHER, 0, false);
        assertFalse(hear(sender, ChatMode.PROXIMITY, nether).contains(nether.id()));
    }

    @Test
    void adminViewHearsEverything() {
        Listener sender = at(OVERWORLD, 0, false);
        Listener admin = at(NETHER, 5000, true);
        assertTrue(hear(sender, ChatMode.PROXIMITY, admin).contains(admin.id()));
    }

    @Test
    void whisperNeedsRangeAndDimension() {
        Listener sender = at(OVERWORLD, 0, false);
        assertTrue(ChatRules.whisperArrives(sender, at(OVERWORLD, 20, false), 48));
        assertFalse(ChatRules.whisperArrives(sender, at(OVERWORLD, 49, false), 48));
        assertFalse(ChatRules.whisperArrives(sender, at(NETHER, 20, false), 48));
    }
}
