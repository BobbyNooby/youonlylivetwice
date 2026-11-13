package bobbynooby.dev.gametest;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.gametest.mixin.AttackStrengthAccessor;
import bobbynooby.dev.visibility.NameTagTeam;
import bobbynooby.dev.visibility.PlayerVisibility;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static bobbynooby.dev.visibility.NameTagTeam.TEAM_NAME;

// Other tests share the server, so these check who is in a team, never the whole team
public class NameTagGameTests {
    private static final String TEAM_REFILL = "you-only-live-twice-gametest:team_refill";
    // For the tests that keep two players online and read the team packets they got across ticks, which another test's
    // player or a reload would add to
    private static final String NAMETAG_NEAR = "you-only-live-twice-gametest:nametag_near";
    private static final String NAMETAG_FORGET = "you-only-live-twice-gametest:nametag_forget";

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
    @GameTest(maxTicks = 20, environment = NAMETAG_NEAR)
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

    // The client drops the connection when told to remove a name it doesn't have, so a forgotten name leaves exactly once
    @GameTest(maxTicks = 60, environment = NAMETAG_FORGET)
    public void forgottenPlayerLeavesTeamOnce(GameTestHelper helper) {
        ServerPlayer a = TestPlayers.join(helper, "tagonea", GameType.SURVIVAL, BlockPos.ZERO);
        ServerPlayer b = TestPlayers.join(helper, "tagoneb", GameType.SURVIVAL, new BlockPos(5, 0, 0));
        boolean waiting = false;
        try {
            // Leaving b undriven after the jump keeps the server from loading chunks out there
            AtomicBoolean jumped = new AtomicBoolean();
            helper.onEachTick(() -> {
                TestPlayers.driveTracking(a);
                if (!jumped.get()) {
                    TestPlayers.driveTracking(b);
                }
            });
            helper.runAfterDelay(5, () -> {
                jumped.set(true);
                b.teleportTo(b.getX() + 1000, b.getY(), b.getZ());
            });
            helper.runAfterDelay(45, () -> {
                try {
                    helper.assertValueEqual(moves(a, b, 0), List.of("join", "leave"), "a's team changes for b");
                    helper.assertValueEqual(moves(b, a, 0), List.of("join", "leave"), "b's team changes for a");
                    // Starting the team over is a remove and an add, never another LEAVE
                    PlayerVisibility.refresh(a);
                    helper.assertValueEqual(moves(a, b, 0), List.of("join", "leave"), "a's team changes for b after a refresh");
                    helper.assertFalse(lastCreateLists(a, b), "a's re-sent team still lists b");
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

    // What /team leave and /team join do, for a player the viewer knows and one it doesn't
    @GameTest
    public void teamMovesOnlyReachViewersWhoKnow(GameTestHelper helper) {
        ServerScoreboard scoreboard = helper.getLevel().getServer().getScoreboard();
        YoltConfig.HideSection hide = Settings.get().hide;
        boolean distantBefore = hide.distantPlayers;
        List<ServerPlayer> joined = new ArrayList<>();
        try {
            hide.distantPlayers = true;
            ServerPlayer viewer = join(helper, joined, "tagmovev", BlockPos.ZERO);
            ServerPlayer known = join(helper, joined, "tagmovek", new BlockPos(1, 0, 0));
            ServerPlayer unknown = join(helper, joined, "tagmoveu", new BlockPos(2, 0, 0));
            known.teleportTo(known.getX() + 1000, known.getY(), known.getZ());
            unknown.teleportTo(unknown.getX(), unknown.getY(), unknown.getZ() + 1000);
            // As if the viewer had started tracking the known player
            PlayerVisibility.onPairingStart(viewer, known);
            PlayerTeam team = scoreboard.getPlayerTeam(TEAM_NAME);
            helper.assertTrue(team != null, "there is no " + TEAM_NAME + " team");

            int viewerMark = TestPlayers.sentPackets(viewer).size();
            int knownMark = TestPlayers.sentPackets(known).size();
            int unknownMark = TestPlayers.sentPackets(unknown).size();
            for (ServerPlayer moved : List.of(known, unknown)) {
                scoreboard.removePlayerFromTeam(moved.getScoreboardName(), team);
                scoreboard.addPlayerToTeam(moved.getScoreboardName(), team);
            }

            helper.assertValueEqual(moves(viewer, known, viewerMark), List.of("leave", "join"), "viewer's team changes for a player it knows");
            helper.assertValueEqual(moves(viewer, unknown, viewerMark), List.of(), "viewer's team changes for a player it doesn't know");
            helper.assertValueEqual(moves(unknown, known, unknownMark), List.of(), "a far player's team changes for a player it doesn't know");
            helper.assertValueEqual(moves(known, known, knownMark), List.of("leave", "join"), "a player's team changes for itself");
            helper.assertValueEqual(moves(unknown, unknown, unknownMark), List.of("leave", "join"), "a far player's team changes for itself");
        } finally {
            hide.distantPlayers = distantBefore;
            leaveAll(joined);
        }
        helper.succeed();
    }

    // The shared team keeps each client's names exact too, or a later per-viewer LEAVE names a player the client no longer has
    @GameTest
    public void sharedLeaveIsNotSentAgain(GameTestHelper helper) {
        ServerScoreboard scoreboard = helper.getLevel().getServer().getScoreboard();
        YoltConfig.HideSection hide = Settings.get().hide;
        boolean distantBefore = hide.distantPlayers;
        List<ServerPlayer> joined = new ArrayList<>();
        try {
            hide.distantPlayers = false;
            // Online first, so the viewer's client gets it with the team at join
            ServerPlayer other = join(helper, joined, "tagdrifto", new BlockPos(1, 0, 0));
            ServerPlayer viewer = join(helper, joined, "tagdriftv", BlockPos.ZERO);
            helper.assertTrue(lastCreateLists(viewer, other), "switch off: the viewer's client did not get the other player in the team");
            int mark = TestPlayers.sentPackets(viewer).size();

            scoreboard.removePlayerFromTeam(other.getScoreboardName(), scoreboard.getPlayerTeam(TEAM_NAME));
            helper.assertValueEqual(moves(viewer, other, mark), List.of("leave"), "switch off: viewer's team changes for the other player");

            // The switch goes on, then the viewer stops tracking the other player
            hide.distantPlayers = true;
            PlayerVisibility.onPairingStop(viewer, other);
            helper.assertFalse(PlayerVisibility.known(viewer).contains(other.getUUID()), "the viewer still knows the other player");
            helper.assertValueEqual(moves(viewer, other, mark), List.of("leave"), "viewer's team changes after it forgot the other player");
        } finally {
            hide.distantPlayers = distantBefore;
            leaveAll(joined);
        }
        helper.succeed();
    }

    // With another rule, vanilla sends death messages to the team itself and they skip the game-message hide
    @GameTest
    public void teamDeathMessageRuleForcedOn(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        PlayerTeam team = server.getScoreboard().getPlayerTeam(TEAM_NAME);
        helper.assertTrue(team != null, "there is no " + TEAM_NAME + " team");
        Team.Visibility before = team.getDeathMessageVisibility();
        try {
            team.setDeathMessageVisibility(Team.Visibility.HIDE_FOR_OTHER_TEAMS);
            NameTagTeam.apply(server);
            helper.assertValueEqual(team.getDeathMessageVisibility(), Team.Visibility.ALWAYS, "death message rule after apply");
        } finally {
            team.setDeathMessageVisibility(before);
        }
        helper.succeed();
    }

    // What /team remove and then /yolt reload do. Removing the team takes every player out of it, so this runs on its own.
    @GameTest(environment = TEAM_REFILL)
    public void removedTeamRefilledOnApply(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerScoreboard scoreboard = server.getScoreboard();
        ServerPlayer player = TestPlayers.join(helper, "tagrefill", GameType.SURVIVAL, BlockPos.ZERO);
        try {
            scoreboard.removePlayerTeam(scoreboard.getPlayerTeam(TEAM_NAME));
            NameTagTeam.apply(server);
            PlayerTeam team = scoreboard.getPlayerTeam(TEAM_NAME);
            helper.assertTrue(team != null, "apply did not make the team again");
            helper.assertTrue(team.getPlayers().contains(player.getScoreboardName()), "an online player is not in the remade team");
        } finally {
            restoreTeam(server);
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // Every player is in our team, which must not make them allies: a sword's sweep still reaches a player beside the
    // one it hits, as for players in no team. PvP goes back in the same tick.
    @GameTest
    public void sweepHitsAnotherPlayerInOurTeam(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        GameRules rules = level.getGameRules();
        boolean pvpBefore = rules.get(GameRules.PVP);
        List<ServerPlayer> joined = new ArrayList<>();
        try {
            rules.set(GameRules.PVP, true, server);
            ServerPlayer attacker = join(helper, joined, "tagsweep", new BlockPos(1, 1, 1));
            ServerPlayer target = join(helper, joined, "tagsweept", new BlockPos(2, 1, 1));
            ServerPlayer beside = join(helper, joined, "tagsweepb", new BlockPos(2, 1, 2));
            TestPlayers.markLoaded(target);
            TestPlayers.markLoaded(beside);
            helper.assertTrue(attacker.getTeam() != null && attacker.getTeam() == beside.getTeam()
                    && NameTagTeam.isOurs(attacker.getTeam()), "the players are not both in " + TEAM_NAME);
            helper.assertFalse(attacker.isAlliedTo(beside), "players in our team count as allies");

            // A full-strength hit on the ground with a sword is a sweep
            attacker.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
            attacker.setOnGround(true);
            ((AttackStrengthAccessor) attacker).yoltSetAttackStrengthTicker(100);
            attacker.attack(target);
            helper.assertTrue(target.getHealth() < target.getMaxHealth(), "the hit did not land on its target");
            helper.assertTrue(beside.getHealth() < beside.getMaxHealth(), "the sweep skipped the player beside the target");
        } finally {
            rules.set(GameRules.PVP, pvpBefore, server);
            leaveAll(joined);
        }
        helper.succeed();
    }

    // A tamed wolf goes for whoever hurts its owner. Owner and attacker share our team, so the wolf must not see the
    // attacker as an ally.
    @GameTest
    public void wolfMayTargetAPlayerInItsOwnersTeam(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<ServerPlayer> joined = new ArrayList<>();
        Wolf wolf = null;
        try {
            ServerPlayer owner = join(helper, joined, "tagwolfo", new BlockPos(1, 1, 1));
            ServerPlayer attacker = join(helper, joined, "tagwolfa", new BlockPos(3, 1, 1));
            wolf = helper.spawnWithNoFreeWill(EntityTypes.WOLF, new BlockPos(2, 1, 2));
            wolf.tame(owner);
            // The ally check is what this is about, so the line of sight is left out
            TargetingConditions combat = TargetingConditions.forCombat().ignoreLineOfSight();
            helper.assertTrue(combat.test(level, wolf, attacker), "the wolf may not target a player in its owner's team");
            helper.assertFalse(combat.test(level, wolf, owner), "the wolf may target its owner");
        } finally {
            if (wolf != null) {
                wolf.discard();
            }
            leaveAll(joined);
        }
        helper.succeed();
    }

    // Only our team stops being one for allies: a team an admin makes keeps vanilla's answer
    @GameTest
    public void adminTeamStillMakesAllies(GameTestHelper helper) {
        ServerScoreboard scoreboard = helper.getLevel().getServer().getScoreboard();
        PlayerTeam admins = scoreboard.addPlayerTeam(CommandGameTests.uniqueName("tagadmins"));
        List<ServerPlayer> joined = new ArrayList<>();
        try {
            ServerPlayer a = join(helper, joined, "tagadmina", new BlockPos(1, 1, 1));
            ServerPlayer b = join(helper, joined, "tagadminb", new BlockPos(3, 1, 1));
            helper.assertFalse(a.isAlliedTo(b), "players in our team count as allies");
            scoreboard.addPlayerToTeam(a.getScoreboardName(), admins);
            scoreboard.addPlayerToTeam(b.getScoreboardName(), admins);
            helper.assertTrue(a.isAlliedTo(b), "players in an admin's team are not allies");
            helper.assertTrue(admins.isAlliedTo(admins), "an admin's team is not allied to itself");
        } finally {
            leaveAll(joined);
            scoreboard.removePlayerTeam(admins);
        }
        helper.succeed();
    }

    /** Puts our team back with every online player in it, as joining would have, after a test removed it. */
    static void restoreTeam(MinecraftServer server) {
        NameTagTeam.apply(server);
        ServerScoreboard scoreboard = server.getScoreboard();
        PlayerTeam team = scoreboard.getPlayerTeam(TEAM_NAME);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getTeam() == null) {
                scoreboard.addPlayerToTeam(player.getScoreboardName(), team);
            }
        }
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

    // Our team's JOIN and LEAVE packets naming seen, from the viewer's packet number from on
    static List<String> moves(ServerPlayer viewer, ServerPlayer seen, int from) {
        List<Packet<?>> packets = TestPlayers.sentPackets(viewer);
        List<String> moves = new ArrayList<>();
        for (Packet<?> packet : packets.subList(from, packets.size())) {
            if (packet instanceof ClientboundSetPlayerTeamPacket team && team.getName().equals(TEAM_NAME)
                    && team.getTeamAction() == null && names(team, seen)) {
                moves.add(team.getPlayerAction() == ClientboundSetPlayerTeamPacket.Action.ADD ? "join" : "leave");
            }
        }
        return moves;
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
