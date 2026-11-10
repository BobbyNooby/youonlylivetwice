package bobbynooby.dev.gametest;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.visibility.AdminView;
import bobbynooby.dev.visibility.PlayerVisibility;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapDecorationType;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.util.ArrayList;
import java.util.List;

// Each test makes a map of its own, so the markers on it are its own players'. The switches go back in the same tick.
public class MapGameTests {
    @GameTest
    public void farPlayerNotOnSharedMap(GameTestHelper helper) {
        YoltConfig.HideSection hide = Settings.get().hide;
        boolean distantBefore = hide.distantPlayers;
        boolean mapBefore = hide.mapPlayers;
        List<ServerPlayer> joined = new ArrayList<>();
        try {
            hide.distantPlayers = true;
            hide.mapPlayers = true;
            ServerPlayer viewer = join(helper, joined, "mapview", BlockPos.ZERO);
            ServerPlayer far = join(helper, joined, "mapfar", new BlockPos(1, 0, 0));
            far.teleportTo(far.getX() + 1000, far.getY(), far.getZ());
            ItemStack map = newMap(helper, viewer);
            carry(helper, map, List.of(viewer, far));

            helper.assertValueEqual(count(data(helper, map).getDecorations(), MapDecorationTypes.PLAYER_OFF_LIMITS), 1,
                    "far player's marker on the map itself");
            List<MapDecoration> viewerSees = sentTo(helper, map, viewer);
            helper.assertValueEqual(count(viewerSees, MapDecorationTypes.PLAYER), 1, "player markers sent to the viewer");
            helper.assertValueEqual(count(viewerSees, MapDecorationTypes.PLAYER_OFF_LIMITS), 0, "far player's marker sent to the viewer");
            List<MapDecoration> farSees = sentTo(helper, map, far);
            helper.assertValueEqual(count(farSees, MapDecorationTypes.PLAYER), 0, "viewer's marker sent to the far player");
            helper.assertValueEqual(count(farSees, MapDecorationTypes.PLAYER_OFF_LIMITS), 1, "far player's own marker");
        } finally {
            hide.distantPlayers = distantBefore;
            hide.mapPlayers = mapBefore;
            leaveAll(joined);
        }
        helper.succeed();
    }

    @GameTest
    public void nearPlayerOnSharedMap(GameTestHelper helper) {
        YoltConfig.HideSection hide = Settings.get().hide;
        boolean distantBefore = hide.distantPlayers;
        boolean mapBefore = hide.mapPlayers;
        List<ServerPlayer> joined = new ArrayList<>();
        try {
            hide.distantPlayers = true;
            hide.mapPlayers = true;
            ServerPlayer viewer = join(helper, joined, "mapnearv", BlockPos.ZERO);
            ServerPlayer near = join(helper, joined, "mapnear", new BlockPos(3, 0, 0));
            // As if the viewer had started tracking the near player, and not the other way round
            PlayerVisibility.onPairingStart(viewer, near);
            ItemStack map = newMap(helper, viewer);
            carry(helper, map, List.of(viewer, near));

            helper.assertValueEqual(count(sentTo(helper, map, viewer), MapDecorationTypes.PLAYER), 2,
                    "player markers sent to the viewer, who knows the near player");
            helper.assertValueEqual(count(sentTo(helper, map, near), MapDecorationTypes.PLAYER), 1,
                    "player markers sent to the near player, who doesn't know the viewer");
        } finally {
            hide.distantPlayers = distantBefore;
            hide.mapPlayers = mapBefore;
            leaveAll(joined);
        }
        helper.succeed();
    }

    @GameTest
    public void adminSeesFarPlayerOnSharedMap(GameTestHelper helper) {
        YoltConfig.HideSection hide = Settings.get().hide;
        boolean distantBefore = hide.distantPlayers;
        boolean mapBefore = hide.mapPlayers;
        List<ServerPlayer> joined = new ArrayList<>();
        ServerPlayer admin = null;
        try {
            hide.distantPlayers = true;
            hide.mapPlayers = true;
            admin = join(helper, joined, "mapadmin", BlockPos.ZERO);
            ServerPlayer far = join(helper, joined, "mapadminfar", new BlockPos(1, 0, 0));
            far.teleportTo(far.getX() + 1000, far.getY(), far.getZ());
            TestPlayers.op(admin);
            AdminView.set(admin, true);
            ItemStack map = newMap(helper, admin);
            carry(helper, map, List.of(admin, far));

            helper.assertValueEqual(count(sentTo(helper, map, admin), MapDecorationTypes.PLAYER_OFF_LIMITS), 1,
                    "far player's marker sent to an admin");
        } finally {
            hide.distantPlayers = distantBefore;
            hide.mapPlayers = mapBefore;
            if (admin != null) {
                AdminView.set(admin, false);
                TestPlayers.deop(admin);
            }
            leaveAll(joined);
        }
        helper.succeed();
    }

    @GameTest
    public void farPlayerOnSharedMapWithSwitchOff(GameTestHelper helper) {
        YoltConfig.HideSection hide = Settings.get().hide;
        boolean distantBefore = hide.distantPlayers;
        boolean mapBefore = hide.mapPlayers;
        List<ServerPlayer> joined = new ArrayList<>();
        try {
            hide.distantPlayers = true;
            hide.mapPlayers = false;
            ServerPlayer viewer = join(helper, joined, "mapoff", BlockPos.ZERO);
            ServerPlayer far = join(helper, joined, "mapofffar", new BlockPos(1, 0, 0));
            far.teleportTo(far.getX() + 1000, far.getY(), far.getZ());
            ItemStack map = newMap(helper, viewer);
            carry(helper, map, List.of(viewer, far));

            helper.assertValueEqual(count(sentTo(helper, map, viewer), MapDecorationTypes.PLAYER_OFF_LIMITS), 1,
                    "switch off: far player's marker sent to the viewer");
        } finally {
            hide.distantPlayers = distantBefore;
            hide.mapPlayers = mapBefore;
            leaveAll(joined);
        }
        helper.succeed();
    }

    // Unlimited tracking, like an explorer map, so even a far holder shows, pinned to the edge
    static ItemStack newMap(GameTestHelper helper, ServerPlayer centre) {
        return MapItem.create(helper.getLevel(), centre.getBlockX(), centre.getBlockZ(), (byte) 0, true, true);
    }

    // What each copy's inventory tick does; test players and bodies here get no living tick
    static void carry(GameTestHelper helper, ItemStack map, List<? extends ServerPlayer> holders) {
        MapItemSavedData data = data(helper, map);
        for (ServerPlayer holder : holders) {
            holder.getInventory().add(map.copy());
            data.tickCarriedBy(holder, map, null);
        }
    }

    // The markers in this holder's next update, which is its first, so it carries them
    static List<MapDecoration> sentTo(GameTestHelper helper, ItemStack map, ServerPlayer holder) {
        Packet<?> packet = data(helper, map).getUpdatePacket(map.get(DataComponents.MAP_ID), holder);
        helper.assertTrue(packet instanceof ClientboundMapItemDataPacket update && update.decorations().isPresent(),
                "no map markers sent to " + holder.getPlainTextName());
        return ((ClientboundMapItemDataPacket) packet).decorations().orElseThrow();
    }

    static int count(Iterable<MapDecoration> markers, Holder<MapDecorationType> type) {
        int count = 0;
        for (MapDecoration marker : markers) {
            if (marker.type().is(type)) {
                count++;
            }
        }
        return count;
    }

    static MapItemSavedData data(GameTestHelper helper, ItemStack map) {
        MapItemSavedData data = MapItem.getSavedData(map, helper.getLevel());
        helper.assertTrue(data != null, "the map has no data");
        return data;
    }

    private static ServerPlayer join(GameTestHelper helper, List<ServerPlayer> joined, String name, BlockPos pos) {
        ServerPlayer player = TestPlayers.join(helper, name, GameType.SURVIVAL, pos);
        joined.add(player);
        return player;
    }

    private static void leaveAll(List<ServerPlayer> joined) {
        for (ServerPlayer player : joined) {
            TestPlayers.leave(player);
        }
    }
}
