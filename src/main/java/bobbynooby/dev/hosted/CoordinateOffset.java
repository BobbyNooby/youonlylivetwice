package bobbynooby.dev.hosted;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.coords.CoordinateSettings;
import bobbynooby.dev.coords.YoltCoordinates;
import bobbynooby.dev.core.StandIn;
import bobbynooby.dev.visibility.AdminView;
import bobbynooby.dev.visibility.DebugPrivacy;
import bobbynooby.dev.visibility.NorthCompass;

/**
 * The coordinate offset is the YOLT Coordinates mod, shipped inside YOLT's jar. YOLT hosts it: it reads
 * hide.coordinates from YOLT's own config, live, follows YOLT's single-player switch, and gets admin view, so an
 * admin's next frame can be real, the north compass's target, whose Z is the same for everyone, debug privacy, which
 * keeps the spawn protection line from naming a block, and stand-ins such as player bodies, which have no client.
 */
public final class CoordinateOffset {
    // Made once: the mod reads the settings on the block collision path, which runs for every block an entity touches
    private static final CoordinateSettings ON = new CoordinateSettings(true);
    private static final CoordinateSettings OFF = new CoordinateSettings(false);

    private CoordinateOffset() {}

    public static void init() {
        YoltCoordinates.hostedBy("You Only Live Twice",
                () -> Settings.on(c -> c.hide.coordinates) ? ON : OFF,
                new YoltCoordinates.Hooks(Settings::active, AdminView::isOn, NorthCompass::isNorthTarget,
                        DebugPrivacy::hidesPosition, StandIn::is));
    }
}
