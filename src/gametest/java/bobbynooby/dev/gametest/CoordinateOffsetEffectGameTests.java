package bobbynooby.dev.gametest;

import bobbynooby.dev.coords.CoordinateOffsets;
import bobbynooby.dev.coords.Offset;
import bobbynooby.dev.mixin.SoundPacketAccessor;
import bobbynooby.dev.mixin.WaypointChunkAccessor;
import bobbynooby.dev.mixin.WaypointVectorAccessor;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ExplosionParticleInfo;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.TrailParticleOption;
import net.minecraft.core.particles.VibrationParticleOption;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundGameTestHighlightPosPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundTrackedWaypointPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.random.Weighted;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.BlockPositionSource;
import net.minecraft.world.level.gameevent.EntityPositionSource;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.waypoints.Waypoint;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Sounds, particles, explosions and the other effects that carry a spot. Each test joins a player whose packets it reads,
// so the class has a batch of its own. Creative, so a leave makes no body.
public class CoordinateOffsetEffectGameTests {
    private static final String EFFECTS = "you-only-live-twice-gametest:coordinate_offset_effects";
    private static final BlockPos FAR = new BlockPos(1_234, 70, -5_678);

    // Far out, a float can't hold a spot to an eighth of a block, so the shift goes on the packet's own ints
    @GameTest(environment = EFFECTS)
    public void soundShiftIsExactFarOut(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "cesound", GameType.CREATIVE);
        try {
            Offset offset = CoordinateOffsets.offset(player);
            ClientboundSoundPacket packet = new ClientboundSoundPacket(SoundEvents.NOTE_BLOCK_BELL, SoundSource.RECORDS,
                    2_500_000.5, 64.0, -2_500_000.25, 1.5F, 0.75F, 1_234_567L);
            int mark = OffsetTestKit.mark(player);
            player.connection.send(packet);

            ClientboundSoundPacket sent = OffsetTestKit.last(helper, player, mark, ClientboundSoundPacket.class);
            SoundPacketAccessor before = (SoundPacketAccessor) packet;
            SoundPacketAccessor after = (SoundPacketAccessor) sent;
            helper.assertValueEqual(before.yoltX(), 20_000_004, "eighths of x in the packet");
            helper.assertValueEqual(after.yoltX(), 20_000_004 + 8 * offset.x(), "eighths of x sent");
            helper.assertValueEqual(after.yoltZ(), -20_000_002 + 8 * offset.z(), "eighths of z sent");
            helper.assertValueEqual(sent.getY(), 64.0, "y sent");
            helper.assertValueEqual(List.of(sent.getVolume(), sent.getPitch()), List.of(1.5F, 0.75F), "volume and pitch");
            helper.assertValueEqual(sent.getSeed(), 1_234_567L, "seed");
            helper.assertTrue(sent.getSound() == packet.getSound() && sent.getSource() == SoundSource.RECORDS, "sound or source changed");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // A vibration heading for a block kicks a player near a warden when it lands somewhere else
    @GameTest(environment = EFFECTS)
    public void particlesAndVibrationShifted(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "ceparticles", GameType.CREATIVE);
        try {
            Offset offset = CoordinateOffsets.offset(player);
            ParticleOptions toEntity = new VibrationParticleOption(new EntityPositionSource(player, 1.0F), 15);
            ItemStack compass = new ItemStack(Items.COMPASS);
            compass.set(DataComponents.LODESTONE_TRACKER,
                    new LodestoneTracker(Optional.of(GlobalPos.of(Level.OVERWORLD, FAR)), false));
            int mark = OffsetTestKit.mark(player);
            player.connection.send(new ClientboundLevelParticlesPacket(new VibrationParticleOption(new BlockPositionSource(FAR), 20),
                    false, true, 100.5, 65.0, -200.25, 0.5F, 0.25F, 0.125F, 0.3F, 7));
            player.connection.send(particles(toEntity));
            player.connection.send(particles(new TrailParticleOption(new Vec3(1_000.5, 70.0, -40.5), 0xFF00FF, 30)));
            player.connection.send(particles(new ItemParticleOption(ParticleTypes.ITEM, ItemStackTemplate.fromNonEmptyStack(compass))));

            List<ClientboundLevelParticlesPacket> sent = OffsetTestKit.sent(player, mark, ClientboundLevelParticlesPacket.class);
            helper.assertValueEqual(sent.size(), 4, "particle packets");
            ClientboundLevelParticlesPacket toBlock = sent.get(0);
            helper.assertValueEqual(new Vec3(toBlock.x(), toBlock.y(), toBlock.z()), offset.shift(new Vec3(100.5, 65.0, -200.25)),
                    "spot of the vibration");
            helper.assertValueEqual(List.of(toBlock.xDist(), toBlock.yDist(), toBlock.zDist(), toBlock.xMaxSpeed()),
                    List.of(0.5F, 0.25F, 0.125F, 0.3F), "spreads and speed");
            helper.assertValueEqual(toBlock.count(), 7, "count");
            helper.assertTrue(!toBlock.overrideLimiter() && toBlock.alwaysShow(), "the limiter flags changed");
            VibrationParticleOption vibration = (VibrationParticleOption) toBlock.particle();
            helper.assertValueEqual(((BlockPositionSource) vibration.getDestination()).pos(), offset.shift(FAR),
                    "block the vibration heads for");
            helper.assertValueEqual(vibration.getArrivalInTicks(), 20, "ticks the vibration takes");
            helper.assertTrue(sent.get(1).particle() == toEntity, "a vibration heading for an entity was copied");
            helper.assertValueEqual(((TrailParticleOption) sent.get(2).particle()).target(),
                    offset.shift(new Vec3(1_000.5, 70.0, -40.5)), "end of the trail");
            helper.assertValueEqual(((TrailParticleOption) sent.get(2).particle()).color(), 0xFF00FF, "colour of the trail");
            helper.assertValueEqual(((ItemParticleOption) sent.get(3).particle()).getItem().get(DataComponents.LODESTONE_TRACKER)
                    .target(), Optional.of(GlobalPos.of(Level.OVERWORLD, offset.shift(FAR))), "target of the compass particle");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // The knockback is a push, so it stays; of the block particles only the one with a spot in it moves
    @GameTest(environment = EFFECTS)
    public void explosionShifted(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "ceexplode", GameType.CREATIVE);
        try {
            Offset offset = CoordinateOffsets.offset(player);
            Vec3 centre = new Vec3(-7_000.5, 40.0, 3_333.25);
            Weighted<ExplosionParticleInfo> poof = new Weighted<>(new ExplosionParticleInfo(ParticleTypes.POOF, 0.5F, 1.0F), 3);
            Weighted<ExplosionParticleInfo> toBlock = new Weighted<>(new ExplosionParticleInfo(
                    new VibrationParticleOption(new BlockPositionSource(FAR), 10), 1.0F, 0.5F), 1);
            Optional<Vec3> knockback = Optional.of(new Vec3(0.1, 0.2, 0.3));
            ClientboundExplodePacket packet = new ClientboundExplodePacket(centre, 4.0F, 12, knockback,
                    ParticleTypes.EXPLOSION_EMITTER, SoundEvents.GENERIC_EXPLODE, WeightedList.of(List.of(poof, toBlock)), true);
            int mark = OffsetTestKit.mark(player);
            player.connection.send(packet);

            ClientboundExplodePacket sent = OffsetTestKit.last(helper, player, mark, ClientboundExplodePacket.class);
            helper.assertValueEqual(sent.center(), offset.shift(centre), "centre of the explosion");
            helper.assertValueEqual(sent.playerKnockback(), knockback, "knockback");
            helper.assertTrue(sent.radius() == 4.0F && sent.blockCount() == 12 && sent.playSound(), "radius, blocks or sound flag changed");
            helper.assertTrue(sent.explosionParticle() == ParticleTypes.EXPLOSION_EMITTER, "the explosion's particle changed");
            List<Weighted<ExplosionParticleInfo>> particles = sent.blockParticles().unwrap();
            helper.assertValueEqual(particles.size(), 2, "block particles");
            helper.assertTrue(particles.get(0) == poof, "a block particle with no spot was copied");
            ExplosionParticleInfo moved = particles.get(1).value();
            helper.assertValueEqual(((BlockPositionSource) ((VibrationParticleOption) moved.particle()).getDestination()).pos(),
                    offset.shift(FAR), "block the explosion's vibration heads for");
            helper.assertValueEqual(List.of(moved.scaling(), moved.speed()), List.of(1.0F, 0.5F), "scaling and speed");
            helper.assertValueEqual(particles.get(1).weight(), 1, "weight");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // The hurt camera tilts towards the spot a hit came from, such as a bed that blew up
    @GameTest(environment = EFFECTS)
    public void damageSourceShifted(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "cedamage", GameType.CREATIVE);
        try {
            Offset offset = CoordinateOffsets.offset(player);
            Holder<DamageType> type = helper.getLevel().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE)
                    .getOrThrow(DamageTypes.BAD_RESPAWN_POINT);
            Vec3 source = new Vec3(5_000.5, 64.0, -6_000.5);
            ClientboundDamageEventPacket withSpot = new ClientboundDamageEventPacket(77, type, -1, -1, Optional.of(source));
            ClientboundDamageEventPacket withoutSpot = new ClientboundDamageEventPacket(78, type, 5, 6, Optional.empty());
            int mark = OffsetTestKit.mark(player);
            player.connection.send(withSpot);
            player.connection.send(withoutSpot);

            List<ClientboundDamageEventPacket> sent = OffsetTestKit.sent(player, mark, ClientboundDamageEventPacket.class);
            helper.assertValueEqual(sent.size(), 2, "damage events");
            helper.assertValueEqual(sent.get(0), new ClientboundDamageEventPacket(77, type, -1, -1, Optional.of(offset.shift(source))),
                    "damage event with a spot");
            helper.assertTrue(sent.get(1) == withoutSpot, "a damage event with no spot was copied");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // The locator bar: a block nearby, a chunk farther out, an angle for the farthest
    @GameTest(environment = EFFECTS)
    public void waypointsShifted(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "cewaypoint", GameType.CREATIVE);
        try {
            Offset offset = CoordinateOffsets.offset(player);
            UUID id = UUID.randomUUID();
            Waypoint.Icon icon = new Waypoint.Icon();
            Vec3i at = new Vec3i(3_000, 70, -4_000);
            ChunkPos chunk = new ChunkPos(200, -300);
            ClientboundTrackedWaypointPacket block = ClientboundTrackedWaypointPacket.addWaypointPosition(id, icon, at);
            ClientboundTrackedWaypointPacket inChunk = ClientboundTrackedWaypointPacket.updateWaypointChunk(id, icon, chunk);
            ClientboundTrackedWaypointPacket azimuth = ClientboundTrackedWaypointPacket.addWaypointAzimuth(id, icon, 1.25F);
            int mark = OffsetTestKit.mark(player);
            player.connection.send(block);
            player.connection.send(inChunk);
            player.connection.send(azimuth);

            List<ClientboundTrackedWaypointPacket> sent = OffsetTestKit.sent(player, mark, ClientboundTrackedWaypointPacket.class);
            helper.assertValueEqual(sent.size(), 3, "waypoint packets");
            helper.assertValueEqual(((WaypointVectorAccessor) sent.get(0).waypoint()).yoltVector(),
                    new Vec3i(3_000 + offset.x(), 70, -4_000 + offset.z()), "block of the waypoint");
            helper.assertValueEqual(((WaypointChunkAccessor) sent.get(1).waypoint()).yoltChunkPos(), offset.shift(chunk),
                    "chunk of the waypoint");
            for (ClientboundTrackedWaypointPacket waypoint : sent.subList(0, 2)) {
                helper.assertValueEqual(waypoint.waypoint().id().left(), Optional.of(id), "id of the waypoint");
                helper.assertTrue(waypoint.waypoint().icon() == icon, "the waypoint's icon changed");
            }
            // The operation's type is private, so it is only compared
            Object track = sent.get(0).operation();
            Object update = sent.get(1).operation();
            helper.assertTrue(track == block.operation() && update == inChunk.operation(), "a waypoint's operation changed");
            helper.assertTrue(sent.get(2) == azimuth, "an angle waypoint was copied");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    @GameTest(environment = EFFECTS)
    public void gameTestHighlightShifted(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "cehighlight", GameType.CREATIVE);
        try {
            Offset offset = CoordinateOffsets.offset(player);
            BlockPos relative = new BlockPos(2, 1, 3);
            int mark = OffsetTestKit.mark(player);
            player.connection.send(new ClientboundGameTestHighlightPosPacket(FAR, relative));

            ClientboundGameTestHighlightPosPacket sent =
                    OffsetTestKit.last(helper, player, mark, ClientboundGameTestHighlightPosPacket.class);
            helper.assertValueEqual(sent.absolutePos(), offset.shift(FAR), "spot of the marker");
            helper.assertValueEqual(sent.relativePos(), relative, "spot of the marker in the test");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    private static ClientboundLevelParticlesPacket particles(ParticleOptions particle) {
        return new ClientboundLevelParticlesPacket(particle, false, false, 10.5, 64.0, 20.5, 0.0F, 0.0F, 0.0F, 0.0F, 1);
    }
}
