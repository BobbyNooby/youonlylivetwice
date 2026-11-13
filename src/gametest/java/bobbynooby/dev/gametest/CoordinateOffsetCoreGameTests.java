package bobbynooby.dev.gametest;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.coords.CoordinateOffsets;
import bobbynooby.dev.coords.Offset;
import bobbynooby.dev.mixin.PlayerLookAtPacketAccessor;
import bobbynooby.dev.visibility.AdminView;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.PositionAndRotation;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerLookAtPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

// Each test joins a player whose packets it reads, so the class has a batch of its own. Creative, so a leave makes no
// body. Expected values come from the offset the join got.
public class CoordinateOffsetCoreGameTests {
    private static final String CORE = "you-only-live-twice-gametest:coordinate_offset_core";

    @GameTest(environment = CORE)
    public void loginStartsAFrameAtTheJoinSpot(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "colog", GameType.CREATIVE);
        try {
            BlockPos spot = helper.absolutePos(new BlockPos(1, 1, 1));
            Offset offset = CoordinateOffsets.offset(player);
            helper.assertValueEqual(offset, Offset.at(spot.getX() + 0.5, spot.getZ() + 0.5), "offset at the join");
            helper.assertFalse(offset.isZero(), "the join got no offset");
            PositionMoveRotation sent = OffsetTestKit.last(helper, player, 0, ClientboundPlayerPositionPacket.class).change();
            helper.assertValueEqual(sent.position().x, player.getX() + offset.x(), "x of the join's teleport");
            helper.assertValueEqual(sent.position().z, player.getZ() + offset.z(), "z of the join's teleport");
            helper.assertValueEqual(sent.position().y, player.getY(), "y of the join's teleport");
            helper.assertValueEqual(sent.deltaMovement(), Vec3.ZERO, "movement of the join's teleport");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // A relative axis is a step from where the client is, which the offset doesn't change
    @GameTest(environment = CORE)
    public void relativeAxesUntouched(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "corel", GameType.CREATIVE);
        try {
            Offset offset = CoordinateOffsets.offset(player);
            PositionMoveRotation step = new PositionMoveRotation(new Vec3(3, 0, -2), Vec3.ZERO, 0.0F, 0.0F);
            int mark = OffsetTestKit.mark(player);
            player.connection.teleport(step, Set.of(Relative.X, Relative.Z));
            Vec3 both = OffsetTestKit.last(helper, player, mark, ClientboundPlayerPositionPacket.class).change().position();
            helper.assertValueEqual(both.x, 3.0, "relative x");
            helper.assertValueEqual(both.z, -2.0, "relative z");

            mark = OffsetTestKit.mark(player);
            player.connection.teleport(step, Set.of(Relative.X));
            Vec3 one = OffsetTestKit.last(helper, player, mark, ClientboundPlayerPositionPacket.class).change().position();
            helper.assertValueEqual(one.x, 3.0, "relative x beside an absolute z");
            helper.assertValueEqual(one.z, -2.0 + offset.z(), "absolute z");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // The rejoin spot is a fixed one near the world's centre, as the test area is so far out that any spot near it
    // clamps to the same offset
    @GameTest(environment = CORE)
    public void rejoinStartsANewFrameAndShiftsTheDeathSpot(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "corejoin", GameType.CREATIVE);
        GameProfile profile = player.getGameProfile();
        ServerPlayer back = null;
        try {
            Offset first = CoordinateOffsets.offset(player);
            player.setLastDeathLocation(Optional.of(GlobalPos.of(Level.OVERWORLD, new BlockPos(100, 64, -200))));
            TestPlayers.leave(player);
            // So the finally block can't remove it twice
            player = null;
            BlockPos spot = new BlockPos(40, 80, -73);
            back = OffsetTestKit.withCoordinates(
                    () -> TestPlayers.rejoin(helper.getLevel().getServer(), profile, GameType.CREATIVE, spot));
            Offset offset = CoordinateOffsets.offset(back);
            helper.assertValueEqual(offset, Offset.at(spot.getX() + 0.5, spot.getZ() + 0.5), "offset at the rejoin");
            helper.assertFalse(offset.equals(first), "the rejoin kept the first frame's offset " + first);
            ClientboundLoginPacket login = OffsetTestKit.last(helper, back, 0, ClientboundLoginPacket.class);
            helper.assertValueEqual(login.commonPlayerSpawnInfo().lastDeathLocation(),
                    Optional.of(GlobalPos.of(Level.OVERWORLD, new BlockPos(100 + offset.x(), 64, -200 + offset.z()))),
                    "death spot in the rejoin's login");
        } finally {
            if (player != null) {
                TestPlayers.leave(player);
            }
            if (back != null) {
                TestPlayers.leave(back);
            }
        }
        helper.succeed();
    }

    // A death in another dimension has no place in the frame, and the frame's offset is easy to learn, so the spot goes
    // out shifted by that dimension's away amount
    @GameTest(environment = CORE)
    public void awayDeathSpotNeverUsesTheFramesAmount(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "coaway", GameType.CREATIVE);
        GameProfile profile = player.getGameProfile();
        ServerPlayer back = null;
        try {
            BlockPos death = new BlockPos(-3_300, 70, 4_100);
            player.setLastDeathLocation(Optional.of(GlobalPos.of(Level.NETHER, death)));
            TestPlayers.leave(player);
            // So the finally block can't remove it twice
            player = null;
            BlockPos spot = new BlockPos(-90, 80, 150);
            back = OffsetTestKit.withCoordinates(
                    () -> TestPlayers.rejoin(helper.getLevel().getServer(), profile, GameType.CREATIVE, spot));
            Offset offset = CoordinateOffsets.offset(back);
            helper.assertFalse(offset.isZero(), "the rejoin got no offset");
            Offset away = CoordinateOffsets.awayOffset(back, Level.NETHER);
            GlobalPos sent = OffsetTestKit.last(helper, back, 0, ClientboundLoginPacket.class).commonPlayerSpawnInfo()
                    .lastDeathLocation().orElseThrow();
            helper.assertValueEqual(sent.dimension(), Level.NETHER, "dimension of the death spot");
            helper.assertValueEqual(sent.pos(), away.shift(death), "death spot in the login");
            helper.assertFalse(sent.pos().equals(death), "the death spot went out real");
            helper.assertFalse(sent.pos().equals(offset.shift(death)), "the death spot went out in the frame's offset " + offset);
        } finally {
            if (player != null) {
                TestPlayers.leave(player);
            }
            if (back != null) {
                TestPlayers.leave(back);
            }
        }
        helper.succeed();
    }

    @GameTest(environment = CORE)
    public void chunkLightAndForgetShifted(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "cochunk", GameType.CREATIVE);
        try {
            Offset offset = CoordinateOffsets.offset(player);
            ServerLevel level = helper.getLevel();
            LevelChunk chunk = level.getChunkAt(helper.absolutePos(new BlockPos(1, 1, 1)));
            ChunkPos pos = chunk.getPos();
            ChunkPos shifted = new ChunkPos(pos.x() + offset.x() / 16, pos.z() + offset.z() / 16);
            ClientboundLevelChunkWithLightPacket chunkPacket =
                    new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null);
            int mark = OffsetTestKit.mark(player);
            player.connection.send(chunkPacket);
            helper.assertTrue(CoordinateOffsets.holdsChunk(player, pos), "the chunk sent is not held at its real position");
            player.connection.send(new ClientboundLightUpdatePacket(pos, level.getLightEngine(), null, null));
            player.connection.send(new ClientboundForgetLevelChunkPacket(pos));
            helper.assertFalse(CoordinateOffsets.holdsChunk(player, pos), "the forgotten chunk is still held");

            ClientboundLevelChunkWithLightPacket sentChunk =
                    OffsetTestKit.last(helper, player, mark, ClientboundLevelChunkWithLightPacket.class);
            helper.assertValueEqual(new ChunkPos(sentChunk.x(), sentChunk.z()), shifted, "chunk sent");
            helper.assertTrue(sentChunk.chunkData() == chunkPacket.chunkData(), "the chunk's payload was copied");
            helper.assertTrue(sentChunk.lightData() == chunkPacket.lightData(), "the chunk's light was copied");
            ClientboundLightUpdatePacket light = OffsetTestKit.last(helper, player, mark, ClientboundLightUpdatePacket.class);
            helper.assertValueEqual(new ChunkPos(light.x(), light.z()), shifted, "light update sent");
            helper.assertValueEqual(OffsetTestKit.last(helper, player, mark, ClientboundForgetLevelChunkPacket.class).pos(), shifted,
                    "chunk forgotten");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    @GameTest(environment = CORE)
    public void chunkCacheCentreAndBiomesShifted(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "cocentre", GameType.CREATIVE);
        try {
            Offset offset = CoordinateOffsets.offset(player);
            int mark = OffsetTestKit.mark(player);
            player.connection.send(new ClientboundSetChunkCacheCenterPacket(3, 4));
            ClientboundSetChunkCacheCenterPacket centre =
                    OffsetTestKit.last(helper, player, mark, ClientboundSetChunkCacheCenterPacket.class);
            helper.assertValueEqual(centre.getX(), 3 + offset.x() / 16, "centre x");
            helper.assertValueEqual(centre.getZ(), 4 + offset.z() / 16, "centre z");

            byte[] buffer = {1, 2, 3};
            player.connection.send(new ClientboundChunksBiomesPacket(
                    List.of(new ClientboundChunksBiomesPacket.ChunkBiomeData(new ChunkPos(5, -6), buffer))));
            List<ClientboundChunksBiomesPacket.ChunkBiomeData> biomes =
                    OffsetTestKit.last(helper, player, mark, ClientboundChunksBiomesPacket.class).chunkBiomeData();
            helper.assertValueEqual(biomes.size(), 1, "chunks in the biome packet");
            helper.assertValueEqual(biomes.get(0).pos(), new ChunkPos(5 + offset.x() / 16, -6 + offset.z() / 16), "biome chunk");
            helper.assertTrue(biomes.get(0).buffer() == buffer, "the biomes were copied");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    @GameTest(environment = CORE)
    public void lookAtAndVehicleMoveShifted(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "colook", GameType.CREATIVE);
        try {
            Offset offset = CoordinateOffsets.offset(player);
            int mark = OffsetTestKit.mark(player);
            player.connection.send(new ClientboundPlayerLookAtPacket(EntityAnchorArgument.Anchor.EYES, 10.5, 70, -20.5));
            ClientboundPlayerLookAtPacket look = OffsetTestKit.last(helper, player, mark, ClientboundPlayerLookAtPacket.class);
            helper.assertValueEqual(look.getPosition(helper.getLevel()), new Vec3(10.5 + offset.x(), 70, -20.5 + offset.z()),
                    "spot looked at");
            helper.assertValueEqual(look.getFromAnchor(), EntityAnchorArgument.Anchor.EYES, "anchor looked from");

            // The copy keeps the entity and the anchor on it, so the client looks at the entity wherever it shows it
            player.connection.send(new ClientboundPlayerLookAtPacket(EntityAnchorArgument.Anchor.FEET, player,
                    EntityAnchorArgument.Anchor.EYES));
            ClientboundPlayerLookAtPacket atEntity = OffsetTestKit.last(helper, player, mark, ClientboundPlayerLookAtPacket.class);
            Vec3 eyes = EntityAnchorArgument.Anchor.EYES.apply(player);
            helper.assertValueEqual(atEntity.getPosition(helper.getLevel()), eyes, "spot looked at on the entity");
            helper.assertValueEqual(atEntity.getFromAnchor(), EntityAnchorArgument.Anchor.FEET, "anchor looked from at the entity");
            PlayerLookAtPacketAccessor fallback = (PlayerLookAtPacketAccessor) atEntity;
            helper.assertValueEqual(fallback.yoltX(), eyes.x + offset.x(), "x looked at when the entity isn't there");
            helper.assertValueEqual(fallback.yoltZ(), eyes.z + offset.z(), "z looked at when the entity isn't there");

            player.connection.send(new ClientboundMoveVehiclePacket(PositionAndRotation.of(new Vec3(5, 64, 6), 0.0F, 0.0F)));
            Vec3 vehicle = OffsetTestKit.last(helper, player, mark, ClientboundMoveVehiclePacket.class).movingTo().position();
            helper.assertValueEqual(vehicle, new Vec3(5 + offset.x(), 64, 6 + offset.z()), "vehicle moved to");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    @GameTest(environment = CORE)
    public void bundleShiftedInOrder(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "cobundle", GameType.CREATIVE);
        try {
            Offset offset = CoordinateOffsets.offset(player);
            // Only a chunk the client holds is forgotten
            ServerLevel level = helper.getLevel();
            LevelChunk chunk = level.getChunkAt(helper.absolutePos(new BlockPos(1, 1, 1)));
            ChunkPos pos = chunk.getPos();
            player.connection.send(new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null));
            ClientboundEntityEventPacket event = new ClientboundEntityEventPacket(player, (byte) 9);
            ClientboundBundlePacket bundle = new ClientboundBundlePacket(List.<Packet<? super ClientGamePacketListener>>of(
                    new ClientboundSetChunkCacheCenterPacket(1, 2), new ClientboundForgetLevelChunkPacket(pos), event));
            int mark = OffsetTestKit.mark(player);
            player.connection.send(bundle);
            ClientboundBundlePacket sent = OffsetTestKit.last(helper, player, mark, ClientboundBundlePacket.class);
            helper.assertTrue(sent != bundle, "the bundle went out as it was");
            List<Packet<?>> parts = new ArrayList<>();
            sent.subPackets().forEach(parts::add);
            helper.assertValueEqual(parts.size(), 3, "packets in the bundle");
            helper.assertTrue(parts.get(0) instanceof ClientboundSetChunkCacheCenterPacket, "the bundle's first packet moved");
            ClientboundSetChunkCacheCenterPacket centre = (ClientboundSetChunkCacheCenterPacket) parts.get(0);
            helper.assertValueEqual(new ChunkPos(centre.getX(), centre.getZ()),
                    new ChunkPos(1 + offset.x() / 16, 2 + offset.z() / 16), "centre in the bundle");
            helper.assertTrue(parts.get(1) instanceof ClientboundForgetLevelChunkPacket, "the bundle's second packet moved");
            helper.assertValueEqual(((ClientboundForgetLevelChunkPacket) parts.get(1)).pos(),
                    new ChunkPos(pos.x() + offset.x() / 16, pos.z() + offset.z() / 16), "chunk forgotten in the bundle");
            helper.assertFalse(CoordinateOffsets.holdsChunk(player, pos), "the chunk forgotten in the bundle is still held");
            helper.assertTrue(parts.get(2) == event, "the entity event was copied or moved");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // Vanilla sends one object to every viewer, so a translation must make a copy and leave the original as it was
    @GameTest(environment = CORE)
    public void oneSentPacketNeverMutated(GameTestHelper helper) {
        ServerPlayer shifted = OffsetTestKit.join(helper, "comut", GameType.CREATIVE);
        ServerPlayer plain = null;
        try {
            plain = TestPlayers.join(helper, "coplain", GameType.CREATIVE, new BlockPos(1, 1, 1));
            Offset offset = CoordinateOffsets.offset(shifted);
            helper.assertTrue(CoordinateOffsets.offset(plain).isZero(), "the player joined with the switch off got an offset");
            ClientboundSetChunkCacheCenterPacket packet = new ClientboundSetChunkCacheCenterPacket(3, 4);
            int shiftedMark = OffsetTestKit.mark(shifted);
            int plainMark = OffsetTestKit.mark(plain);
            shifted.connection.send(packet);
            plain.connection.send(packet);
            ClientboundSetChunkCacheCenterPacket copy =
                    OffsetTestKit.last(helper, shifted, shiftedMark, ClientboundSetChunkCacheCenterPacket.class);
            helper.assertTrue(copy != packet, "the offset player got the very packet");
            helper.assertValueEqual(new ChunkPos(copy.getX(), copy.getZ()), new ChunkPos(3 + offset.x() / 16, 4 + offset.z() / 16),
                    "centre the offset player got");
            helper.assertTrue(OffsetTestKit.last(helper, plain, plainMark, ClientboundSetChunkCacheCenterPacket.class) == packet,
                    "the plain player got a copy");
            helper.assertValueEqual(new ChunkPos(packet.getX(), packet.getZ()), new ChunkPos(3, 4), "the packet after both sends");
        } finally {
            TestPlayers.leave(shifted);
            if (plain != null) {
                TestPlayers.leave(plain);
            }
        }
        helper.succeed();
    }

    // The chunks are noted all the same, so a frame that starts later can forget them
    @GameTest(environment = CORE)
    public void switchOffSendsTheSameObjects(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "cooff", GameType.CREATIVE, new BlockPos(1, 1, 1));
        try {
            helper.assertValueEqual(CoordinateOffsets.offset(player), Offset.ZERO, "offset with the switch off");
            ClientboundSetChunkCacheCenterPacket packet = new ClientboundSetChunkCacheCenterPacket(3, 4);
            int mark = OffsetTestKit.mark(player);
            player.connection.send(packet);
            helper.assertTrue(OffsetTestKit.last(helper, player, mark, ClientboundSetChunkCacheCenterPacket.class) == packet,
                    "a copy went out with the switch off");

            ServerLevel level = helper.getLevel();
            LevelChunk chunk = level.getChunkAt(helper.absolutePos(new BlockPos(1, 1, 1)));
            ClientboundLevelChunkWithLightPacket chunkPacket =
                    new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null);
            player.connection.send(chunkPacket);
            helper.assertTrue(OffsetTestKit.last(helper, player, mark, ClientboundLevelChunkWithLightPacket.class) == chunkPacket,
                    "a copy of the chunk went out with the switch off");
            helper.assertTrue(CoordinateOffsets.holdsChunk(player, chunk.getPos()), "the chunk sent with the switch off is not held");
            player.connection.send(new ClientboundForgetLevelChunkPacket(chunk.getPos()));
            helper.assertFalse(CoordinateOffsets.holdsChunk(player, chunk.getPos()), "the forgotten chunk is still held");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // An admin's frame is real when it starts with admin view on, which a rejoin does
    @GameTest(environment = CORE)
    public void adminViewRejoinGetsRealCoordinates(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer admin = OffsetTestKit.join(helper, "coadmin", GameType.CREATIVE);
        GameProfile profile = admin.getGameProfile();
        NameAndId who = admin.nameAndId();
        ServerPlayer online = admin;
        try {
            TestPlayers.op(admin);
            AdminView.set(admin, true);
            TestPlayers.leave(admin);
            // So the finally block can't remove it twice if joining again fails
            online = null;
            online = OffsetTestKit.withCoordinates(
                    () -> TestPlayers.rejoin(helper, profile, GameType.CREATIVE, new BlockPos(1, 1, 1)));
            helper.assertTrue(AdminView.isOn(online), "admin view was lost on the rejoin");
            helper.assertValueEqual(CoordinateOffsets.offset(online), Offset.ZERO, "offset with admin view on");
            Vec3 sent = OffsetTestKit.last(helper, online, 0, ClientboundPlayerPositionPacket.class).change().position();
            helper.assertValueEqual(sent, online.position(), "the rejoin's teleport");
        } finally {
            server.getPlayerList().deop(who);
            if (online != null) {
                AdminView.set(online, false);
                TestPlayers.leave(online);
            }
        }
        helper.succeed();
    }

    // Untranslated, the accept would be a long way from the real spot and the move after it too quick, so the player
    // would be sent back and never get the step
    @GameTest(environment = CORE)
    public void incomingMoveLandsAtTheRealPosition(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "comove", GameType.CREATIVE);
        try {
            TestPlayers.markLoaded(player);
            // What each tick does first, so the move check measures from where the player is, not from 0, 0, 0
            player.connection.resetPosition();
            Vec3 real = player.position();
            ClientboundPlayerPositionPacket teleport = OffsetTestKit.last(helper, player, 0, ClientboundPlayerPositionPacket.class);
            Vec3 client = teleport.change().position();
            TestPlayers.receive(player,
                    new ServerboundAcceptTeleportationPacket(teleport.id(), client.x, client.y, client.z, 0.0F, 0.0F));
            helper.assertValueEqual(player.position(), real, "position after the client accepted the join's teleport");
            TestPlayers.receive(player, new ServerboundMovePlayerPacket.Pos(client.x + 1, client.y, client.z, false, false));
            helper.assertValueEqual(player.getX(), real.x + 1, "x after a step east");
            helper.assertValueEqual(player.getZ(), real.z, "z after a step east");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    @GameTest(environment = CORE)
    public void configCommandSwitchesCoordinates(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        byte[] fileBefore = Files.readAllBytes(Settings.path());
        try {
            helper.assertValueEqual(CommandGameTests.run(server, "yolt config hide.coordinates true"),
                    List.of("hide.coordinates changed from false to true (applies when a player next joins)."), "reply");
            helper.assertTrue(Settings.get().hide.coordinates, "live hide.coordinates is still false");
        } finally {
            ConfigCommandGameTests.restore(server, fileBefore);
        }
        helper.succeed();
    }

    // A packet whose position is missing makes its translator throw, as a broken one would. As it is, the packet would
    // carry real coordinates, so it goes nowhere, and the sender never sees the exception. A bundle loses only that part.
    @GameTest(environment = CORE)
    public void failedTranslationDropsTheSentPacket(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "codrop", GameType.CREATIVE);
        try {
            Offset offset = CoordinateOffsets.offset(player);
            int mark = OffsetTestKit.mark(player);
            List<String> log = OfflineGameTests.yoltLog(() -> {
                player.connection.send(new ClientboundForgetLevelChunkPacket((ChunkPos) null));
                player.connection.send(new ClientboundForgetLevelChunkPacket((ChunkPos) null));
                player.connection.send(new ClientboundBundlePacket(List.<Packet<? super ClientGamePacketListener>>of(
                        new ClientboundMoveVehiclePacket((PositionAndRotation) null), new ClientboundSetChunkCacheCenterPacket(3, 4))));
            });
            helper.assertTrue(OffsetTestKit.sent(player, mark, ClientboundForgetLevelChunkPacket.class).isEmpty(),
                    "a packet that could not be translated went out");
            helper.assertTrue(OffsetTestKit.sent(player, mark, ClientboundMoveVehiclePacket.class).isEmpty(),
                    "a bundle's part that could not be translated went out");
            assertOneDropLine(helper, log, "clientbound/minecraft:forget_level_chunk", player);
            assertOneDropLine(helper, log, "clientbound/minecraft:move_vehicle", player);
            // The rest of the bundle goes on, and so does the connection
            ClientboundSetChunkCacheCenterPacket centre =
                    OffsetTestKit.last(helper, player, mark, ClientboundSetChunkCacheCenterPacket.class);
            helper.assertValueEqual(new ChunkPos(centre.getX(), centre.getZ()), new ChunkPos(3 + offset.x() / 16, 4 + offset.z() / 16),
                    "centre in the bundle");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // The same for what the client sends: as it is, the server would read it in the wrong frame
    @GameTest(environment = CORE)
    public void failedTranslationDropsTheReceivedPacket(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "codropin", GameType.CREATIVE);
        try {
            List<String> log = OfflineGameTests.yoltLog(() -> {
                TestPlayers.receive(player, new ServerboundMoveVehiclePacket((PositionAndRotation) null, false));
                TestPlayers.receive(player, new ServerboundMoveVehiclePacket((PositionAndRotation) null, false));
            });
            assertOneDropLine(helper, log, "serverbound/minecraft:move_vehicle", player);
            helper.assertTrue(player.connection.isAcceptingMessages(), "the player was disconnected");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // With no offset nothing in the packet can leak, so one whose chunk can't be noted still goes, and that is logged once
    @GameTest(environment = CORE)
    public void failedNotesSendThePacketAsItIs(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "conotes", GameType.CREATIVE, new BlockPos(1, 1, 1));
        try {
            ClientboundForgetLevelChunkPacket forget = new ClientboundForgetLevelChunkPacket((ChunkPos) null);
            int mark = OffsetTestKit.mark(player);
            List<String> log = OfflineGameTests.yoltLog(() -> {
                player.connection.send(forget);
                player.connection.send(forget);
            });
            helper.assertValueEqual(OffsetTestKit.sent(player, mark, ClientboundForgetLevelChunkPacket.class), List.of(forget, forget),
                    "forgets sent");
            String line = "Could not note what a clientbound/minecraft:forget_level_chunk packet for " + player.getPlainTextName()
                    + " tells the client, so it was sent as it is";
            helper.assertValueEqual(log.stream().filter(entry -> entry.startsWith(line)).count(), 1L, "lines in " + log);
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // A type whose notes failed still has its first drop logged, as the two failures are logged apart
    @GameTest(environment = CORE)
    public void dropLoggedAfterTheSameTypeFailedItsNotes(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "conotedrop", GameType.CREATIVE, new BlockPos(1, 1, 1));
        ServerPlayer current = player;
        try {
            List<String> notes = OfflineGameTests.yoltLog(
                    () -> player.connection.send(new ClientboundForgetLevelChunkPacket((ChunkPos) null)));
            helper.assertValueEqual(notes.stream().filter(entry -> entry.startsWith("Could not note what a "
                    + "clientbound/minecraft:forget_level_chunk packet")).count(), 1L, "note lines in " + notes);
            // As after a death, which is when vanilla sends no forgets
            player.setHealth(0.0F);
            current = OffsetTestKit.respawn(helper, player, helper.absolutePos(new BlockPos(3, 1, 3)), false);
            helper.assertFalse(CoordinateOffsets.offset(current).isZero(), "the respawn got no offset");
            ServerPlayer respawned = current;
            List<String> drops = OfflineGameTests.yoltLog(
                    () -> respawned.connection.send(new ClientboundForgetLevelChunkPacket((ChunkPos) null)));
            assertOneDropLine(helper, drops, "clientbound/minecraft:forget_level_chunk", player);
        } finally {
            TestPlayers.leave(current);
        }
        helper.succeed();
    }

    private static void assertOneDropLine(GameTestHelper helper, List<String> log, String type, ServerPlayer player) {
        String line = "Could not translate the coordinates in a " + type + " packet for " + player.getPlainTextName()
                + ", so it was dropped";
        helper.assertValueEqual(log.stream().filter(entry -> entry.startsWith(line)).count(), 1L, "drop lines in " + log);
    }
}
