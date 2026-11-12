package bobbynooby.dev.gametest;

import bobbynooby.dev.coords.CoordinateOffsets;
import bobbynooby.dev.coords.Offset;
import bobbynooby.dev.visibility.AdminView;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;
import java.util.Set;

// A new frame at every respawn and dimension change. Each test joins a player whose packets it reads, so the class has a
// batch of its own. Creative, so a leave makes no body. Respawns go to a spot beside the test, so no random one is
// searched; a player that must start in another frame joins near the world's centre.
public class CoordinateOffsetFrameGameTests {
    private static final String FRAMES = "you-only-live-twice-gametest:coordinate_offset_frames";
    private static final BlockPos RESPAWN = new BlockPos(2, 1, 2);

    @GameTest(environment = FRAMES)
    public void deathRespawnStartsAFrameAtTheSpawn(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.joinNearTheCentre(helper, "cfdeath", GameType.CREATIVE, new BlockPos(200, 80, 300));
        ServerPlayer current = player;
        try {
            Offset before = CoordinateOffsets.offset(player);
            GlobalPos death = GlobalPos.of(Level.OVERWORLD, new BlockPos(-500, 70, 1_200));
            player.setLastDeathLocation(Optional.of(death));
            int mark = OffsetTestKit.mark(player);
            current = OffsetTestKit.respawn(helper, player, helper.absolutePos(RESPAWN), false);
            Offset offset = CoordinateOffsets.offset(current);
            helper.assertValueEqual(offset, Offset.at(current.getX(), current.getZ()), "offset at the respawn");
            helper.assertFalse(offset.equals(before), "the respawn kept the old frame's offset " + before);
            int respawn = OffsetTestKit.lastIndex(helper, current, mark, ClientboundRespawnPacket.class);
            ClientboundRespawnPacket packet = (ClientboundRespawnPacket) TestPlayers.sentPackets(current).get(respawn);
            helper.assertValueEqual(packet.commonPlayerSpawnInfo().lastDeathLocation(), Optional.of(offset.shift(death)),
                    "death spot in the respawn");
            Vec3 sent = OffsetTestKit.first(helper, current, respawn + 1, ClientboundPlayerPositionPacket.class)
                    .change().position();
            helper.assertValueEqual(sent, offset.shift(current.position()), "the respawn's teleport");
        } finally {
            TestPlayers.leave(current);
        }
        helper.succeed();
    }

    // Vanilla forgets nothing for a dead player, and a respawn in the same dimension keeps the client's level, so every
    // chunk it holds would sit in the new frame at its old-frame spot
    @GameTest(environment = FRAMES)
    public void sameDimensionRespawnForgetsEveryHeldChunkFirst(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.joinNearTheCentre(helper, "cfforget", GameType.CREATIVE, new BlockPos(-150, 80, 90));
        ServerPlayer current = player;
        try {
            Offset old = CoordinateOffsets.offset(player);
            ServerLevel level = helper.getLevel();
            LevelChunk chunk = level.getChunkAt(helper.absolutePos(new BlockPos(1, 1, 1)));
            ChunkPos first = chunk.getPos();
            ChunkPos second = new ChunkPos(first.x() + 1, first.z());
            ClientboundLevelChunkWithLightPacket packet =
                    new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null);
            player.connection.send(packet);
            player.connection.send(new ClientboundLevelChunkWithLightPacket(second.x(), second.z(), packet.chunkData(),
                    packet.lightData()));
            // As after a death, which is when vanilla sends no forgets
            player.setHealth(0.0F);
            int mark = OffsetTestKit.mark(player);
            current = OffsetTestKit.respawn(helper, player, helper.absolutePos(RESPAWN), false);
            Offset offset = CoordinateOffsets.offset(current);
            helper.assertFalse(offset.equals(old), "the respawn kept the old frame's offset " + old);

            int respawn = OffsetTestKit.lastIndex(helper, current, mark, ClientboundRespawnPacket.class);
            List<ChunkPos> before = OffsetTestKit.sent(current, mark, respawn, ClientboundForgetLevelChunkPacket.class).stream()
                    .map(ClientboundForgetLevelChunkPacket::pos).toList();
            helper.assertValueEqual(before.size(), 2, "chunks forgotten before the respawn, " + before);
            helper.assertValueEqual(Set.copyOf(before), Set.of(old.shift(first), old.shift(second)),
                    "chunks forgotten before the respawn");
            Set<ChunkPos> those = Set.of(old.shift(first), old.shift(second), offset.shift(first), offset.shift(second));
            helper.assertTrue(OffsetTestKit.sent(current, respawn + 1, ClientboundForgetLevelChunkPacket.class).stream()
                    .noneMatch(forget -> those.contains(forget.pos())), "a held chunk was forgotten after the respawn");
            helper.assertFalse(CoordinateOffsets.holdsChunk(current, first), "the forgotten chunk is still held");
        } finally {
            TestPlayers.leave(current);
        }
        helper.succeed();
    }

    // The client moves in the old frame until it has the respawn's teleport. Calling the respawn leaves the connection
    // holding the old player, as only the real client's path hands it the new one, so only the frame is checked here.
    @GameTest(environment = FRAMES)
    public void movesBeforeTheAcceptUseTheOldFrame(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.joinNearTheCentre(helper, "cfmove", GameType.CREATIVE, new BlockPos(310, 80, -260));
        ServerPlayer current = player;
        try {
            Offset old = CoordinateOffsets.offset(player);
            Connection connection = OffsetTestKit.connection(player);
            double oldClientX = player.getX() + old.x();
            int mark = OffsetTestKit.mark(player);
            current = OffsetTestKit.respawn(helper, player, helper.absolutePos(RESPAWN), false);
            Offset offset = CoordinateOffsets.offset(current);
            helper.assertFalse(offset.equals(old), "the respawn kept the old frame's offset " + old);

            ServerboundMovePlayerPacket early = (ServerboundMovePlayerPacket) CoordinateOffsets.incoming(connection,
                    new ServerboundMovePlayerPacket.Pos(oldClientX, 80.0, 0.5, false, false));
            helper.assertValueEqual(early.getX(0.0), oldClientX - old.x(), "x of a move sent before the accept");

            int respawn = OffsetTestKit.lastIndex(helper, current, mark, ClientboundRespawnPacket.class);
            ClientboundPlayerPositionPacket teleport =
                    OffsetTestKit.first(helper, current, respawn + 1, ClientboundPlayerPositionPacket.class);
            Vec3 client = teleport.change().position();
            ServerboundAcceptTeleportationPacket accept = (ServerboundAcceptTeleportationPacket) CoordinateOffsets.incoming(
                    connection, new ServerboundAcceptTeleportationPacket(teleport.id(), client.x, client.y, client.z, 0.0F, 0.0F));
            helper.assertValueEqual(accept.x(), client.x - offset.x(), "x of the accept");
            helper.assertValueEqual(accept.z(), client.z - offset.z(), "z of the accept");

            ServerboundMovePlayerPacket late = (ServerboundMovePlayerPacket) CoordinateOffsets.incoming(connection,
                    new ServerboundMovePlayerPacket.Pos(client.x + 1, client.y, client.z, false, false));
            helper.assertValueEqual(late.getX(0.0), client.x + 1 - offset.x(), "x of a move sent after the accept");
        } finally {
            TestPlayers.leave(current);
        }
        helper.succeed();
    }

    // The nether gets a frame of its own, anchored at the arrival. The client starts a new level, so nothing is forgotten
    // first, and the old level's chunks forgotten after would show in the new frame where the player came from.
    @GameTest(environment = FRAMES)
    public void dimensionChangeStartsAFrameAtTheArrival(GameTestHelper helper) {
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "no nether to go to");
        ServerPlayer player = OffsetTestKit.join(helper, "cfnether", GameType.CREATIVE);
        try {
            ServerLevel level = helper.getLevel();
            LevelChunk chunk = level.getChunkAt(helper.absolutePos(new BlockPos(1, 1, 1)));
            player.connection.send(new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null));
            int mark = OffsetTestKit.mark(player);
            OffsetTestKit.withCoordinates(() -> player.teleportTo(nether, 37.5, 64.0, -50.5, Set.of(), 0.0F, 0.0F, true));
            helper.assertTrue(player.level() == nether, "the player did not reach the nether");
            helper.assertValueEqual(CoordinateOffsets.offset(player), new Offset(-32, 64), "offset at the arrival");

            int respawn = OffsetTestKit.lastIndex(helper, player, mark, ClientboundRespawnPacket.class);
            helper.assertTrue(OffsetTestKit.sent(player, mark, respawn, ClientboundForgetLevelChunkPacket.class).isEmpty(),
                    "a chunk was forgotten before the respawn");
            Vec3 sent = OffsetTestKit.first(helper, player, respawn + 1, ClientboundPlayerPositionPacket.class)
                    .change().position();
            helper.assertValueEqual(sent, new Vec3(5.5, 64.0, 13.5), "the arrival's teleport");

            player.connection.send(new ClientboundForgetLevelChunkPacket(chunk.getPos()));
            helper.assertTrue(OffsetTestKit.sent(player, mark, ClientboundForgetLevelChunkPacket.class).isEmpty(),
                    "a chunk of the old level was forgotten in the new frame");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // The portal sound comes at the zero position, which the client ignores: shifted, it would hand out the new offset
    @GameTest(environment = FRAMES)
    public void portalTripSendsThePortalSoundAsItIs(GameTestHelper helper) {
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "no nether to go to");
        ServerPlayer player = OffsetTestKit.join(helper, "cfportal", GameType.CREATIVE);
        try {
            int mark = OffsetTestKit.mark(player);
            TeleportTransition trip = new TeleportTransition(nether, new Vec3(37.5, 64.0, -50.5), Vec3.ZERO, 0.0F, 0.0F,
                    Set.of(), TeleportTransition.PLAY_PORTAL_SOUND);
            OffsetTestKit.withCoordinates(() -> player.teleport(trip));
            helper.assertTrue(player.level() == nether, "the player did not reach the nether");
            Offset offset = CoordinateOffsets.offset(player);
            helper.assertFalse(offset.isZero(), "the trip got no offset");

            List<ClientboundLevelEventPacket> events = OffsetTestKit.sent(player, mark, ClientboundLevelEventPacket.class);
            List<ClientboundLevelEventPacket> sounds =
                    events.stream().filter(event -> event.getType() == LevelEvent.SOUND_PORTAL_TRAVEL).toList();
            helper.assertValueEqual(sounds.size(), 1, "portal sounds");
            helper.assertValueEqual(sounds.getFirst().getPos(), BlockPos.ZERO, "portal sound spot");
            helper.assertTrue(events.stream().noneMatch(event -> event.getPos().getX() == offset.x()
                    && event.getPos().getZ() == offset.z()), "a level event carries the offset " + offset);
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    @GameTest(environment = FRAMES)
    public void endArrivalAndExitEachStartAFrame(GameTestHelper helper) {
        ServerLevel end = helper.getLevel().getServer().getLevel(Level.END);
        helper.assertTrue(end != null, "no End to go to");
        ServerPlayer player = OffsetTestKit.join(helper, "cfend", GameType.CREATIVE);
        ServerPlayer current = player;
        try {
            int mark = OffsetTestKit.mark(player);
            OffsetTestKit.withCoordinates(() -> player.teleportTo(end, 41.5, 64.0, 23.5, Set.of(), 0.0F, 0.0F, true));
            helper.assertTrue(player.level() == end, "the player did not reach the End");
            helper.assertValueEqual(CoordinateOffsets.offset(player), new Offset(-32, -16), "offset at the End");
            int respawn = OffsetTestKit.lastIndex(helper, player, mark, ClientboundRespawnPacket.class);
            Vec3 sent = OffsetTestKit.first(helper, player, respawn + 1, ClientboundPlayerPositionPacket.class)
                    .change().position();
            helper.assertValueEqual(sent, new Vec3(9.5, 64.0, 7.5), "the End's teleport");

            current = OffsetTestKit.respawn(helper, player, helper.absolutePos(RESPAWN), true);
            helper.assertTrue(current.level() == helper.getLevel(), "the way back from the End did not reach the overworld");
            helper.assertValueEqual(CoordinateOffsets.offset(current), Offset.at(current.getX(), current.getZ()),
                    "offset on the way back from the End");
        } finally {
            TestPlayers.leave(current);
        }
        helper.succeed();
    }

    // A Respawn packet sent by anything but a respawn or a dimension change has no spot to go by
    @GameTest(environment = FRAMES)
    public void respawnWithoutAnAnchorGetsARandomFrame(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "cfrandom", GameType.CREATIVE);
        try {
            ServerLevel level = helper.getLevel();
            OffsetTestKit.withCoordinates(() -> {
                player.connection.send(new ClientboundRespawnPacket(player.createCommonSpawnInfo(level), (byte) 0));
                return null;
            });
            Offset offset = CoordinateOffsets.offset(player);
            helper.assertTrue(offset.x() % 16 == 0 && offset.z() % 16 == 0, "offset off the grid: " + offset);
            helper.assertTrue(Math.abs(offset.x()) <= Offset.LIMIT && Math.abs(offset.z()) <= Offset.LIMIT,
                    "offset past the limit: " + offset);
            helper.assertFalse(offset.isZero(), "the respawn packet got no offset");
            int mark = OffsetTestKit.mark(player);
            player.connection.teleport(player.getX(), player.getY(), player.getZ(), 0.0F, 0.0F);
            Vec3 sent = OffsetTestKit.last(helper, player, mark, ClientboundPlayerPositionPacket.class).change().position();
            helper.assertValueEqual(sent, offset.shift(player.position()), "the teleport after the respawn packet");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // The switch is read as a frame starts, so a respawn with it off goes back to real coordinates
    @GameTest(environment = FRAMES)
    public void switchOffAtRespawnGivesRealCoordinatesAndForgets(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = OffsetTestKit.join(helper, "cfswitch", GameType.CREATIVE);
        ServerPlayer current = player;
        try {
            Offset old = CoordinateOffsets.offset(player);
            helper.assertFalse(old.isZero(), "the join got no offset");
            ServerLevel level = helper.getLevel();
            LevelChunk chunk = level.getChunkAt(helper.absolutePos(new BlockPos(1, 1, 1)));
            player.connection.send(new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null));
            // Dead, so the only forget is the frame's own
            player.setHealth(0.0F);
            player.setRespawnPosition(new ServerPlayer.RespawnConfig(
                    LevelData.RespawnData.of(level.dimension(), helper.absolutePos(RESPAWN), 0.0F, 0.0F), true), false);
            int mark = OffsetTestKit.mark(player);
            current = server.getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
            helper.assertValueEqual(CoordinateOffsets.offset(current), Offset.ZERO, "offset after a respawn with the switch off");

            int respawn = OffsetTestKit.lastIndex(helper, current, mark, ClientboundRespawnPacket.class);
            List<ChunkPos> forgotten = OffsetTestKit.sent(current, mark, respawn, ClientboundForgetLevelChunkPacket.class).stream()
                    .map(ClientboundForgetLevelChunkPacket::pos).toList();
            helper.assertValueEqual(forgotten, List.of(old.shift(chunk.getPos())), "chunks forgotten before the respawn");
            Vec3 sent = OffsetTestKit.first(helper, current, respawn + 1, ClientboundPlayerPositionPacket.class)
                    .change().position();
            helper.assertValueEqual(sent, current.position(), "the respawn's teleport");
        } finally {
            TestPlayers.leave(current);
        }
        helper.succeed();
    }

    // Nothing is added from the respawn on, so the very packets go out, while what the client sends is read in the old
    // frame until it accepts the respawn's teleport
    @GameTest(environment = FRAMES)
    public void adminViewAtRespawnGivesRealCoordinates(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "cfadmin", GameType.CREATIVE);
        ServerPlayer current = player;
        try {
            Offset old = CoordinateOffsets.offset(player);
            helper.assertFalse(old.isZero(), "the join got no offset");
            Connection connection = OffsetTestKit.connection(player);
            TestPlayers.op(player);
            AdminView.set(player, true);
            int mark = OffsetTestKit.mark(player);
            current = OffsetTestKit.respawn(helper, player, helper.absolutePos(RESPAWN), false);
            helper.assertValueEqual(CoordinateOffsets.offset(current), Offset.ZERO, "offset after a respawn with admin view on");
            int respawn = OffsetTestKit.lastIndex(helper, current, mark, ClientboundRespawnPacket.class);
            ClientboundPlayerPositionPacket teleport =
                    OffsetTestKit.first(helper, current, respawn + 1, ClientboundPlayerPositionPacket.class);
            helper.assertValueEqual(teleport.change().position(), current.position(), "the respawn's teleport");

            ClientboundSetChunkCacheCenterPacket centre = new ClientboundSetChunkCacheCenterPacket(3, 4);
            current.connection.send(centre);
            helper.assertTrue(OffsetTestKit.last(helper, current, mark, ClientboundSetChunkCacheCenterPacket.class) == centre,
                    "a copy went out with no offset");
            ServerboundMovePlayerPacket early = (ServerboundMovePlayerPacket) CoordinateOffsets.incoming(connection,
                    new ServerboundMovePlayerPacket.Pos(0.5, 80.0, 0.5, false, false));
            helper.assertValueEqual(early.getX(0.0), 0.5 - old.x(), "x of a move sent before the accept");
            Vec3 at = teleport.change().position();
            ServerboundAcceptTeleportationPacket accept =
                    new ServerboundAcceptTeleportationPacket(teleport.id(), at.x, at.y, at.z, 0.0F, 0.0F);
            helper.assertTrue(CoordinateOffsets.incoming(connection, accept) == accept, "the accept was not taken as it is");
            ServerboundMovePlayerPacket late = new ServerboundMovePlayerPacket.Pos(at.x + 1, at.y, at.z, false, false);
            helper.assertTrue(CoordinateOffsets.incoming(connection, late) == late, "a move after the accept was not taken as it is");
        } finally {
            AdminView.set(current, false);
            TestPlayers.deop(current);
            TestPlayers.leave(current);
        }
        helper.succeed();
    }
}
