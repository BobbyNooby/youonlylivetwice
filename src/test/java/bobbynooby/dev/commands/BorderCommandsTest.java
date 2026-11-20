package bobbynooby.dev.commands;

import bobbynooby.dev.config.BodyDuration;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BorderCommandsTest {
    @Test
    void shortListIsShownWhole() {
        assertEquals("Alex, Steve's body, Bob's grave", BorderCommands.listed(List.of("Alex", "Steve's body", "Bob's grave")));
    }

    @Test
    void longListShowsTenAndHowManyMore() {
        List<String> names = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            names.add("P" + i);
        }
        assertEquals("P1, P2, P3, P4, P5, P6, P7, P8, P9, P10 and 5 more", BorderCommands.listed(names));
    }

    // An offline player is only checked through their body, so the reply says when some may have none: bodies off, or
    // bodies that go after a time. With the border feature off no border moved, so there is nothing to say.
    @Test
    void offlineNoteFollowsBodiesAndDuration() {
        YoltConfig live = Settings.get();
        boolean borderBefore = live.worldBorder.enabled;
        boolean bodiesBefore = live.bodies.enabled;
        BodyDuration durationBefore = live.bodies.duration;
        String note = " Offline players without a body were not checked.";
        try {
            live.worldBorder.enabled = true;
            live.bodies.enabled = true;
            live.bodies.duration = BodyDuration.OFFLINE;
            assertEquals("", BorderCommands.offlineNote());

            live.bodies.enabled = false;
            assertEquals(note, BorderCommands.offlineNote());

            live.bodies.enabled = true;
            live.bodies.duration = new BodyDuration(30);
            assertEquals(note, BorderCommands.offlineNote());

            live.worldBorder.enabled = false;
            assertEquals("", BorderCommands.offlineNote());
        } finally {
            live.worldBorder.enabled = borderBefore;
            live.bodies.enabled = bodiesBefore;
            live.bodies.duration = durationBefore;
        }
    }

    // Only names left outside and no confirm refuse a border command, and the refusal names them and says how to go on
    @Test
    void refusalNeedsNamesAndNoConfirm() {
        assertNull(BorderCommands.refusal(BorderCommands.Outside.NONE, false));
        assertNull(BorderCommands.refusal(new BorderCommands.Outside(List.of("Alex"), ""), true));
        assertEquals("This would leave Alex outside the world border. Add confirm to the command to do it anyway.",
                BorderCommands.refusal(new BorderCommands.Outside(List.of("Alex"), ""), false));
    }
}
