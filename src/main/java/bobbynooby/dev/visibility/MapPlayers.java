package bobbynooby.dev.visibility;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.StandIn;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapDecorationType;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Hides other players' markers on shared paper maps. Every copy of a map shows every other holder at any distance, so
 * each holder's client only gets the markers of players in its sight. The map itself keeps them all. Server thread only.
 */
public final class MapPlayers {
    // Each holder's near-drop count when its markers last went out
    private static final Map<MapItemSavedData.HoldingPlayer, Integer> SENT = new WeakHashMap<>();

    private MapPlayers() {}

    /** The markers sent to this holder: all of them, less the players it isn't tracking. */
    public static Collection<MapDecoration> visibleTo(MapItemSavedData.HoldingPlayer holder, Map<String, MapDecoration> markers,
                                                      Collection<MapDecoration> all) {
        // A player body holding a copy has no client to tell
        if (!Settings.active() || !(holder.player instanceof ServerPlayer viewer) || StandIn.is(viewer)) {
            return all;
        }
        SENT.put(holder, PlayerVisibility.nearDrops(viewer));
        if (!Settings.get().hide.mapPlayers || AdminView.isOn(viewer)) {
            return all;
        }
        Set<String> near = null;
        List<MapDecoration> shown = new ArrayList<>(markers.size());
        for (Map.Entry<String, MapDecoration> marker : markers.entrySet()) {
            // Vanilla keys a player's marker by their name, player bodies included
            if (isPlayer(marker.getValue()) && !marker.getKey().equals(viewer.getPlainTextName())) {
                if (near == null) {
                    near = nearNames(viewer);
                }
                if (!near.contains(marker.getKey())) {
                    continue;
                }
            }
            shown.add(marker.getValue());
        }
        return shown.size() == markers.size() ? all : shown;
    }

    /**
     * True when the holder may have stopped seeing someone since its markers last went out. Vanilla only sends them again
     * when one of them changes, so a player who just left its sight would otherwise stay on its map, live.
     */
    public static boolean resendDue(MapItemSavedData.HoldingPlayer holder) {
        if (!Settings.on(c -> c.hide.mapPlayers) || !(holder.player instanceof ServerPlayer viewer)) {
            return false;
        }
        Integer sent = SENT.get(holder);
        return sent != null && sent.intValue() != PlayerVisibility.nearDrops(viewer);
    }

    // Frames, banners and an explorer map's targets aren't players
    private static boolean isPlayer(MapDecoration marker) {
        Holder<MapDecorationType> type = marker.type();
        return type.is(MapDecorationTypes.PLAYER) || type.is(MapDecorationTypes.PLAYER_OFF_MAP)
                || type.is(MapDecorationTypes.PLAYER_OFF_LIMITS);
    }

    // The near set, not the client's entries: with hide.distantPlayers off a client has everyone's entry, which says
    // who is online but not where they are
    private static Set<String> nearNames(ServerPlayer viewer) {
        Set<String> names = new HashSet<>();
        for (ServerPlayer player : PlayerVisibility.nearPlayers(viewer)) {
            names.add(player.getPlainTextName());
        }
        return names;
    }
}
