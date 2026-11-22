package bobbynooby.dev.coords;

import com.mojang.authlib.GameProfile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// On its own the mod runs only where no player owns the server. Fabric's GameTest server is a dedicated one, so the
// rule and what the mod says where it doesn't run are tried here.
class SinglePlayerTest {
    private static final CoordinateSettings OFF = new CoordinateSettings(false);
    private static final String NOTHING = "YOLT Coordinates does nothing in a single-player world";

    @Test
    void itRunsOnlyWhereNoPlayerOwnsTheServer() {
        assertTrue(YoltCoordinates.runsOn(null), "a dedicated server");
        assertFalse(YoltCoordinates.runsOn(new GameProfile(UUID.randomUUID(), "Owner")), "a single-player world");
    }

    // Whatever the file says, the start-up line and /yolt-coordinates never call it on where it does nothing
    @Test
    void inASinglePlayerWorldNothingSaysItIsOn() {
        for (CoordinateSettings settings : List.of(CoordinateSettings.DEFAULTS, OFF)) {
            assertFalse(YoltCoordinates.on(false, settings), settings.toString());
            assertEquals("nobody is shifted", YoltCoordinates.summary(false, settings), settings.toString());
            assertEquals(NOTHING, YoltCoordinates.describe(false, settings), settings.toString());
        }
    }

    @Test
    void onADedicatedServerTheLinesFollowTheSwitch() {
        assertTrue(YoltCoordinates.on(true, CoordinateSettings.DEFAULTS));
        assertEquals("each player's X and Z are shifted", YoltCoordinates.summary(true, CoordinateSettings.DEFAULTS));
        assertEquals("Coordinate offset on: each player's X and Z are shifted",
                YoltCoordinates.describe(true, CoordinateSettings.DEFAULTS));
        assertFalse(YoltCoordinates.on(true, OFF));
        assertEquals("nobody is shifted", YoltCoordinates.summary(true, OFF));
        assertEquals("The coordinate offset is off", YoltCoordinates.describe(true, OFF));
    }

    // A single-player world comes first: there nobody has coordinates to hide, the console included
    @Test
    void realIsRefusedInASinglePlayerWorldAndFromTheConsole() {
        assertEquals(NOTHING + ".", CoordinateCommands.refusal(false, true), "a player in a single-player world");
        assertEquals(NOTHING + ".", CoordinateCommands.refusal(false, false), "no player in a single-player world");
        assertEquals("Only a player has coordinates to show.", CoordinateCommands.refusal(true, false), "the console");
        assertNull(CoordinateCommands.refusal(true, true), "a player on a dedicated server");
    }
}
