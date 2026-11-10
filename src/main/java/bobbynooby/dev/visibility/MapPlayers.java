package bobbynooby.dev.visibility;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.StandIn;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapDecorationType;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hides other players' markers on shared paper maps. Every copy of a map shows every other holder at any distance, so
 * each holder's client only gets the markers of players it knows about. The map itself keeps them all. Server thread only.
 */
public final class MapPlayers {
    private MapPlayers() {}

    /** The markers sent to this holder: all of them, less the players its client doesn't know. */
    public static Collection<MapDecoration> visibleTo(Player holder, Map<String, MapDecoration> markers,
                                                      Collection<MapDecoration> all) {
        // A player body holding a copy has no client to tell
        if (!Settings.on(c -> c.hide.mapPlayers) || !(holder instanceof ServerPlayer viewer) || StandIn.is(viewer)
                || AdminView.isOn(viewer)) {
            return all;
        }
        Set<String> known = null;
        List<MapDecoration> shown = new ArrayList<>(markers.size());
        for (Map.Entry<String, MapDecoration> marker : markers.entrySet()) {
            // Vanilla keys a player's marker by their name, player bodies included
            if (isPlayer(marker.getValue()) && !marker.getKey().equals(viewer.getPlainTextName())) {
                if (known == null) {
                    known = knownNames(viewer);
                }
                if (!known.contains(marker.getKey())) {
                    continue;
                }
            }
            shown.add(marker.getValue());
        }
        return shown.size() == markers.size() ? all : shown;
    }

    // Frames, banners and an explorer map's targets aren't players
    private static boolean isPlayer(MapDecoration marker) {
        Holder<MapDecorationType> type = marker.type();
        return type.is(MapDecorationTypes.PLAYER) || type.is(MapDecorationTypes.PLAYER_OFF_MAP)
                || type.is(MapDecorationTypes.PLAYER_OFF_LIMITS);
    }

    private static Set<String> knownNames(ServerPlayer viewer) {
        Set<String> names = new HashSet<>();
        for (ServerPlayer player : PlayerVisibility.knownPlayers(viewer)) {
            names.add(player.getPlainTextName());
        }
        return names;
    }
}
