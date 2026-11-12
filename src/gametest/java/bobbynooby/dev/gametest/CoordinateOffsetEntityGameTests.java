package bobbynooby.dev.gametest;

import bobbynooby.dev.coords.CoordinateOffsets;
import bobbynooby.dev.coords.Offset;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.TrailParticleOption;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveMinecartPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.PositionPath;
import net.minecraft.world.entity.PositionStep;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.vehicle.minecart.NewMinecartBehavior;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

// Entities: spawns, moves and the values in their entity data that hold a spot. Each test joins a player whose packets
// it reads, so the class has a batch of its own, and the one test that waits for a real spawn has another. Creative, so
// a leave makes no body.
public class CoordinateOffsetEntityGameTests {
    private static final String ENTITIES = "you-only-live-twice-gametest:coordinate_offset_entities";
    private static final String SPAWN = "you-only-live-twice-gametest:coordinate_offset_entity_spawn";
    private static final Vec3 FAR = new Vec3(1_234.5, 70.0, -5_678.25);
    private static final BlockPos FAR_BLOCK = new BlockPos(-3_000, 75, 4_100);

    @GameTest(environment = ENTITIES)
    public void spawnAndMovePacketsShifted(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "censpawn", GameType.CREATIVE);
        try {
            Offset offset = CoordinateOffsets.offset(player);
            Vec3 movement = new Vec3(0.1, 0.0, 0.2);
            ClientboundAddEntityPacket add = new ClientboundAddEntityPacket(99, UUID.randomUUID(), FAR.x, FAR.y, FAR.z, -12.0F, 37.0F,
                    EntityTypes.PIG, 3, movement, 90.0);
            List<PositionStep> steps = List.of(new PositionStep(FAR, 0), new PositionStep(FAR.add(1.0, 0.0, 0.5), 1),
                    new PositionStep(FAR.add(2.0, 0.5, 1.0), 3));
            int mark = OffsetTestKit.mark(player);
            player.connection.send(add);
            player.connection.send(ClientboundTeleportEntityPacket.teleport(99, new PositionMoveRotation(FAR, movement, 10.0F, 5.0F),
                    Set.of(), true));
            player.connection.send(ClientboundTeleportEntityPacket.teleport(99,
                    new PositionMoveRotation(new Vec3(2.0, 70.0, FAR.z), Vec3.ZERO, 0.0F, 0.0F), Set.of(Relative.X), false));
            player.connection.send(new ClientboundEntityPositionSyncPacket(99, PositionPath.of(FAR), 10.0F, 5.0F, true));
            player.connection.send(new ClientboundEntityPositionSyncPacket(99, PositionPath.stepped(steps), 10.0F, 5.0F, false));

            ClientboundAddEntityPacket sentAdd = OffsetTestKit.last(helper, player, mark, ClientboundAddEntityPacket.class);
            helper.assertValueEqual(new Vec3(sentAdd.getX(), sentAdd.getY(), sentAdd.getZ()), offset.shift(FAR), "spawn spot");
            helper.assertValueEqual(List.of(sentAdd.getXRot(), sentAdd.getYRot(), sentAdd.getYHeadRot()),
                    List.of(add.getXRot(), add.getYRot(), add.getYHeadRot()), "spawn angles");
            helper.assertValueEqual(sentAdd.getMovement(), movement, "spawn movement");
            helper.assertTrue(sentAdd.getId() == 99 && sentAdd.getUUID().equals(add.getUUID()) && sentAdd.getType() == EntityTypes.PIG
                    && sentAdd.getData() == 3, "spawn id, UUID, type or data changed");

            List<ClientboundTeleportEntityPacket> teleports = OffsetTestKit.sent(player, mark, ClientboundTeleportEntityPacket.class);
            helper.assertValueEqual(teleports.size(), 2, "entity teleports");
            helper.assertValueEqual(teleports.get(0), ClientboundTeleportEntityPacket.teleport(99,
                    new PositionMoveRotation(offset.shift(FAR), movement, 10.0F, 5.0F), Set.of(), true), "absolute entity teleport");
            helper.assertValueEqual(teleports.get(1).change().position(), new Vec3(2.0, 70.0, offset.shiftZ(FAR.z)),
                    "entity teleport with a relative x");

            List<ClientboundEntityPositionSyncPacket> syncs = OffsetTestKit.sent(player, mark, ClientboundEntityPositionSyncPacket.class);
            helper.assertValueEqual(syncs.size(), 2, "position syncs");
            helper.assertValueEqual(syncs.get(0).position(), PositionPath.of(offset.shift(FAR)), "linear path");
            PositionPath.Stepped stepped = (PositionPath.Stepped) syncs.get(1).position();
            helper.assertValueEqual(stepped.steps(), steps.stream()
                    .map(step -> new PositionStep(offset.shift(step.position()), step.tickOffset())).toList(), "steps in order");
            helper.assertValueEqual(stepped.endPosition(), offset.shift(steps.getLast().position()), "end of the stepped path");
            helper.assertTrue(syncs.get(1).yRot() == 10.0F && syncs.get(1).xRot() == 5.0F && !syncs.get(1).onGround(),
                    "the stepped sync's angles or ground flag changed");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    @GameTest(environment = ENTITIES)
    public void minecartStepsShifted(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "cencart", GameType.CREATIVE);
        try {
            Offset offset = CoordinateOffsets.offset(player);
            NewMinecartBehavior.MinecartStep first = new NewMinecartBehavior.MinecartStep(FAR, new Vec3(0.5, 0.0, 0.0), 90.0F, 0.0F, 0.5F);
            NewMinecartBehavior.MinecartStep second = new NewMinecartBehavior.MinecartStep(FAR.add(0.5, 0.25, 0.0),
                    new Vec3(0.5, 0.25, 0.0), 90.0F, 15.0F, 1.0F);
            int mark = OffsetTestKit.mark(player);
            player.connection.send(new ClientboundMoveMinecartPacket(55, List.of(first, second)));

            ClientboundMoveMinecartPacket sent = OffsetTestKit.last(helper, player, mark, ClientboundMoveMinecartPacket.class);
            helper.assertValueEqual(sent.entityId(), 55, "minecart id");
            helper.assertValueEqual(sent.lerpSteps(), List.of(
                    new NewMinecartBehavior.MinecartStep(offset.shift(first.position()), first.movement(), 90.0F, 0.0F, 0.5F),
                    new NewMinecartBehavior.MinecartStep(offset.shift(second.position()), second.movement(), 90.0F, 15.0F, 1.0F)),
                    "minecart steps");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // Untranslated, a sleeper lies in the void
    @GameTest(environment = ENTITIES)
    public void sleeperBedPositionShifted(GameTestHelper helper) {
        ServerPlayer viewer = OffsetTestKit.join(helper, "cenviewer", GameType.CREATIVE);
        ServerPlayer sleeper = null;
        try {
            sleeper = TestPlayers.join(helper, "censleeper", GameType.CREATIVE, new BlockPos(3, 1, 1));
            Offset offset = CoordinateOffsets.offset(viewer);
            BlockPos bedAt = new BlockPos(3, 1, 3);
            helper.setBlock(bedAt, Blocks.BED.pick(DyeColor.RED));
            BlockPos bed = helper.absolutePos(bedAt);
            helper.assertTrue(sleeper.startSleeping(bed), "the sleeper could not lie down");
            int mark = OffsetTestKit.mark(viewer);
            viewer.connection.send(new ClientboundSetEntityDataPacket(sleeper.getId(), sleeper.getEntityData().getNonDefaultValues()));

            ClientboundSetEntityDataPacket sent = OffsetTestKit.last(helper, viewer, mark, ClientboundSetEntityDataPacket.class);
            helper.assertValueEqual(valueOf(helper, sent, EntityDataSerializers.OPTIONAL_BLOCK_POS), Optional.of(offset.shift(bed)),
                    "the sleeper's bed");
            helper.assertValueEqual(sleeper.getSleepingPos(), Optional.of(bed), "the sleeper's own bed");
        } finally {
            if (sleeper != null) {
                // Awake again in the same tick, so no other test's night is skipped
                sleeper.stopSleeping();
                TestPlayers.leave(sleeper);
            }
            TestPlayers.leave(viewer);
        }
        helper.succeed();
    }

    @GameTest(environment = ENTITIES)
    public void fallingBlockAndCrystalBeamShifted(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "cenbeam", GameType.CREATIVE);
        try {
            Offset offset = CoordinateOffsets.offset(player);
            ServerLevel level = helper.getLevel();
            FallingBlockEntity falling = new FallingBlockEntity(EntityTypes.FALLING_BLOCK, level);
            falling.setStartPos(FAR_BLOCK);
            EndCrystal crystal = new EndCrystal(EntityTypes.END_CRYSTAL, level);
            crystal.setBeamTarget(FAR_BLOCK.above());
            int mark = OffsetTestKit.mark(player);
            sendData(player, falling);
            sendData(player, crystal);

            List<ClientboundSetEntityDataPacket> sent = OffsetTestKit.sent(player, mark, ClientboundSetEntityDataPacket.class);
            helper.assertValueEqual(sent.size(), 2, "entity data packets");
            helper.assertValueEqual(valueOf(helper, sent.get(0), EntityDataSerializers.BLOCK_POS), offset.shift(FAR_BLOCK),
                    "where the falling block started");
            helper.assertValueEqual(valueOf(helper, sent.get(1), EntityDataSerializers.OPTIONAL_BLOCK_POS),
                    Optional.of(offset.shift(FAR_BLOCK.above())), "the crystal's beam target");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    @GameTest(environment = ENTITIES)
    public void itemFrameCompassShifted(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "cenframe", GameType.CREATIVE);
        try {
            Offset offset = CoordinateOffsets.offset(player);
            ItemFrame frame = new ItemFrame(EntityTypes.ITEM_FRAME, helper.getLevel());
            // It is in no world, so its sound would play at the world's centre
            frame.setSilent(true);
            ItemStack compass = new ItemStack(Items.COMPASS);
            compass.set(DataComponents.LODESTONE_TRACKER,
                    new LodestoneTracker(Optional.of(GlobalPos.of(Level.OVERWORLD, FAR_BLOCK)), true));
            frame.setItem(compass, false);
            int mark = OffsetTestKit.mark(player);
            sendData(player, frame);

            ClientboundSetEntityDataPacket sent = OffsetTestKit.last(helper, player, mark, ClientboundSetEntityDataPacket.class);
            ItemStack shown = valueOf(helper, sent, EntityDataSerializers.ITEM_STACK);
            helper.assertValueEqual(shown.get(DataComponents.LODESTONE_TRACKER).target(),
                    Optional.of(GlobalPos.of(Level.OVERWORLD, offset.shift(FAR_BLOCK))), "target of the compass in the frame");
            helper.assertValueEqual(frame.getItem().get(DataComponents.LODESTONE_TRACKER).target(),
                    Optional.of(GlobalPos.of(Level.OVERWORLD, FAR_BLOCK)), "target of the frame's own compass");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    @GameTest(environment = ENTITIES)
    public void cloudParticleShifted(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "cencloud", GameType.CREATIVE);
        try {
            Offset offset = CoordinateOffsets.offset(player);
            AreaEffectCloud cloud = new AreaEffectCloud(helper.getLevel(), FAR.x, FAR.y, FAR.z);
            cloud.setCustomParticle(new TrailParticleOption(FAR.add(3.0, 1.0, -2.0), 0x00FF00, 40));
            int mark = OffsetTestKit.mark(player);
            sendData(player, cloud);

            ClientboundSetEntityDataPacket sent = OffsetTestKit.last(helper, player, mark, ClientboundSetEntityDataPacket.class);
            ParticleOptions particle = valueOf(helper, sent, EntityDataSerializers.PARTICLE);
            helper.assertValueEqual(((TrailParticleOption) particle).target(), offset.shift(FAR.add(3.0, 1.0, -2.0)),
                    "end of the cloud's trail");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // Through the server's own tracking: the spawn comes as a bundle, its entity data right after it
    @GameTest(environment = SPAWN, maxTicks = 100)
    public void realSpawnBundleShifted(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "cenpaired", GameType.CREATIVE);
        boolean waiting = false;
        try {
            Offset offset = CoordinateOffsets.offset(player);
            Pig pig = helper.spawn(EntityTypes.PIG, new BlockPos(3, 1, 3));
            // Still, so the spot it was sent at is where it stays
            pig.setNoAi(true);
            pig.setNoGravity(true);
            int mark = OffsetTestKit.mark(player);
            helper.onEachTick(() -> TestPlayers.driveTracking(player));
            helper.runAfterDelay(40, () -> {
                try {
                    ClientboundBundlePacket bundle = spawnBundle(player, mark, pig);
                    helper.assertTrue(bundle != null, "the pig was never sent to the player");
                    List<Packet<?>> parts = parts(bundle);
                    ClientboundAddEntityPacket add = (ClientboundAddEntityPacket) parts.getFirst();
                    helper.assertValueEqual(new Vec3(add.getX(), add.getY(), add.getZ()), offset.shift(pig.position()),
                            "where the pig spawned");
                    helper.assertTrue(parts.get(1) instanceof ClientboundSetEntityDataPacket data && data.id() == pig.getId(),
                            "the pig's entity data does not follow its spawn: " + parts.get(1));
                } finally {
                    TestPlayers.leave(player);
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                TestPlayers.leave(player);
            }
        }
    }

    private static void sendData(ServerPlayer player, Entity entity) {
        player.connection.send(new ClientboundSetEntityDataPacket(entity.getId(), entity.getEntityData().getNonDefaultValues()));
    }

    // The one value in the packet with this serializer
    @SuppressWarnings("unchecked")
    private static <T> T valueOf(GameTestHelper helper, ClientboundSetEntityDataPacket packet, EntityDataSerializer<T> serializer) {
        List<SynchedEntityData.DataValue<?>> values = packet.packedItems().stream()
                .filter(value -> value.serializer() == serializer).toList();
        helper.assertValueEqual(values.size(), 1, "values of the serializer in " + packet.packedItems());
        return (T) values.getFirst().value();
    }

    // The bundle whose first part spawns the entity
    private static @Nullable ClientboundBundlePacket spawnBundle(ServerPlayer player, int from, Entity entity) {
        List<Packet<?>> packets = TestPlayers.sentPackets(player);
        for (Packet<?> packet : packets.subList(from, packets.size())) {
            if (packet instanceof ClientboundBundlePacket bundle && !parts(bundle).isEmpty()
                    && parts(bundle).getFirst() instanceof ClientboundAddEntityPacket add && add.getId() == entity.getId()) {
                return bundle;
            }
        }
        return null;
    }

    private static List<Packet<?>> parts(ClientboundBundlePacket bundle) {
        List<Packet<?>> parts = new ArrayList<>();
        bundle.subPackets().forEach(parts::add);
        return parts;
    }
}
