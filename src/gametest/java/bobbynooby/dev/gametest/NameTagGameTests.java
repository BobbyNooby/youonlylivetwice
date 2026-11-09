package bobbynooby.dev.gametest;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.visibility.PlayerVisibility;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.scores.PlayerTeam;

import java.util.ArrayList;
import java.util.List;

import static bobbynooby.dev.YouOnlyLiveTwice.TEAM_NAME;

// Other tests share the server, so these check who is in a team, never the whole team
public class NameTagGameTests {
    @GameTest
    public void farPlayerNameNotInAnyTeamPacket(GameTestHelper helper) {
        YoltConfig.HideSection hide = Settings.get().hide;
        boolean distantBefore = hide.distantPlayers;
        boolean nametagsBefore = hide.nametags;
        List<ServerPlayer> joined = new ArrayList<>();
        try {
            hide.distantPlayers = true;
            hide.nametags = true;
            // Online first, so vanilla's team packet to the viewer at join would list it
            ServerPlayer far = join(helper, joined, "tagfar", new BlockPos(1, 0, 0));
            far.teleportTo(far.getX() + 1000, far.getY(), far.getZ());
            ServerPlayer viewer = join(helper, joined, "tagview", BlockPos.ZERO);
            // A reload or admin view re-sends the whole team
            PlayerVisibility.refresh(viewer);
            PlayerVisibility.refresh(far);

            // Both stay in the server's team, so it is the per-viewer packets that keep the names apart
            PlayerTeam team = helper.getLevel().getServer().getScoreboard().getPlayerTeam(TEAM_NAME);
            helper.assertTrue(team != null && team.getPlayers().contains(far.getScoreboardName())
                    && team.getPlayers().contains(viewer.getScoreboardName()), "the server's team lost a player");
            helper.assertFalse(namedInTeam(viewer, far), "viewer's client got the far player's name in a team packet");
            helper.assertFalse(namedInTeam(far, viewer), "far player's client got the viewer's name in a team packet");
            helper.assertTrue(addedToTeam(viewer, viewer), "viewer's client does not have the viewer in the team");
        } finally {
            hide.distantPlayers = distantBefore;
            hide.nametags = nametagsBefore;
            leaveAll(joined);
        }
        helper.succeed();
    }

    // Relies on hide.distantPlayers and hide.nametags being on, as they are by default
    @GameTest(maxTicks = 20)
    public void nearPlayerAddedToViewersTeam(GameTestHelper helper) {
        ServerPlayer a = TestPlayers.join(helper, "tagneara", GameType.SURVIVAL, BlockPos.ZERO);
        ServerPlayer b = TestPlayers.join(helper, "tagnearb", GameType.SURVIVAL, new BlockPos(5, 0, 0));
        boolean waiting = false;
        try {
            helper.onEachTick(() -> {
                TestPlayers.driveTracking(a);
                TestPlayers.driveTracking(b);
            });
            helper.runAfterDelay(5, () -> {
                try {
                    assertAddedBetweenEntryAndSpawn(helper, a, b);
                    assertAddedBetweenEntryAndSpawn(helper, b, a);
                } finally {
                    TestPlayers.leave(a);
                    TestPlayers.leave(b);
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                TestPlayers.leave(a);
                TestPlayers.leave(b);
            }
        }
    }

    @GameTest
    public void switchOffKeepsSharedTeam(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.HideSection hide = Settings.get().hide;
        boolean distantBefore = hide.distantPlayers;
        boolean nametagsBefore = hide.nametags;
        List<ServerPlayer> joined = new ArrayList<>();
        try {
            hide.distantPlayers = false;
            hide.nametags = true;
            ServerPlayer far = join(helper, joined, "tagofffar", new BlockPos(1, 0, 0));
            far.teleportTo(far.getX() + 1000, far.getY(), far.getZ());
            ServerPlayer viewer = join(helper, joined, "tagoff", BlockPos.ZERO);

            PlayerTeam team = server.getScoreboard().getPlayerTeam(TEAM_NAME);
            helper.assertTrue(team != null, "there is no " + TEAM_NAME + " team");
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                helper.assertTrue(team.getPlayers().contains(player.getScoreboardName()),
                        "switch off: " + player.getPlainTextName() + " is not in the server's team");
            }
            helper.assertTrue(addedToTeam(viewer, far), "switch off: the viewer's client did not get the far player in the team");
            PlayerVisibility.refresh(viewer);
            helper.assertTrue(lastCreateLists(viewer, far), "switch off: a re-sent team left out the far player");
        } finally {
            hide.distantPlayers = distantBefore;
            hide.nametags = nametagsBefore;
            leaveAll(joined);
        }
        helper.succeed();
    }

    // The name must not reach the client before the entry, and the nametag must be hidden from the first frame
    private static void assertAddedBetweenEntryAndSpawn(GameTestHelper helper, ServerPlayer viewer, ServerPlayer seen) {
        List<Packet<?>> packets = TestPlayers.sentPackets(viewer);
        int entry = -1;
        int named = -1;
        int spawn = -1;
        for (int i = 0; i < packets.size(); i++) {
            Packet<?> packet = packets.get(i);
            if (entry < 0 && VisibilityGameTests.addedEntry(packet, seen) != null) {
                entry = i;
            }
            if (named < 0 && packet instanceof ClientboundSetPlayerTeamPacket team && names(team, seen)) {
                named = i;
            }
            if (spawn < 0 && VisibilityGameTests.spawns(packet, seen)) {
                spawn = i;
            }
        }
        String who = viewer.getPlainTextName() + " seeing " + seen.getPlainTextName();
        helper.assertTrue(entry >= 0, who + ": no entry sent");
        helper.assertTrue(spawn >= 0, who + ": no spawn sent");
        helper.assertTrue(named >= 0, who + ": never added to the team");
        helper.assertTrue(adds((ClientboundSetPlayerTeamPacket) packets.get(named), seen), who + ": first named in a team packet that isn't an add");
        helper.assertTrue(entry < named, who + ": named in a team packet before the entry");
        helper.assertTrue(named < spawn, who + ": spawned before joining the team");
    }

    private static boolean namedInTeam(ServerPlayer viewer, ServerPlayer seen) {
        return TestPlayers.sentPackets(viewer).stream()
                .anyMatch(p -> p instanceof ClientboundSetPlayerTeamPacket team && names(team, seen));
    }

    private static boolean addedToTeam(ServerPlayer viewer, ServerPlayer seen) {
        return TestPlayers.sentPackets(viewer).stream()
                .anyMatch(p -> p instanceof ClientboundSetPlayerTeamPacket team && adds(team, seen));
    }

    // The newest packet that (re)creates our team on this client
    private static boolean lastCreateLists(ServerPlayer viewer, ServerPlayer seen) {
        ClientboundSetPlayerTeamPacket last = null;
        for (Packet<?> packet : TestPlayers.sentPackets(viewer)) {
            if (packet instanceof ClientboundSetPlayerTeamPacket team && team.getName().equals(TEAM_NAME)
                    && team.getTeamAction() == ClientboundSetPlayerTeamPacket.Action.ADD) {
                last = team;
            }
        }
        return last != null && last.getPlayers().contains(seen.getScoreboardName());
    }

    // Any team, since a name in any team packet reaches the client
    private static boolean names(ClientboundSetPlayerTeamPacket team, ServerPlayer seen) {
        return team.getPlayers().contains(seen.getScoreboardName());
    }

    private static boolean adds(ClientboundSetPlayerTeamPacket team, ServerPlayer seen) {
        return team.getName().equals(TEAM_NAME) && team.getPlayerAction() == ClientboundSetPlayerTeamPacket.Action.ADD
                && team.getPlayers().contains(seen.getScoreboardName());
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
