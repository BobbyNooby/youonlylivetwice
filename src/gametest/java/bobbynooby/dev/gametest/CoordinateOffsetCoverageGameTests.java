package bobbynooby.dev.gametest;

import bobbynooby.dev.coords.BlockRules;
import bobbynooby.dev.coords.CoordinateOffsets;
import bobbynooby.dev.coords.Coverage;
import bobbynooby.dev.coords.EntityRules;
import bobbynooby.dev.coords.ItemRule;
import bobbynooby.dev.coords.ParticleRule;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.gameevent.PositionSourceType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

// A packet type nobody looked at fails here, such as one a Minecraft update adds, so no position in it goes out real
// unseen. They only read the tables, so they share the default batch.
public class CoordinateOffsetCoverageGameTests {
    // Types with positions that a later task of the plan translates, each with that task's number. The task takes its
    // types out of here as it puts them in a table.
    private static final Map<PacketType<?>, Integer> PENDING_CLIENTBOUND = Map.ofEntries(
            Map.entry(GamePacketTypes.CLIENTBOUND_INITIALIZE_BORDER, 9),
            Map.entry(GamePacketTypes.CLIENTBOUND_SET_BORDER_CENTER, 9),
            Map.entry(GamePacketTypes.CLIENTBOUND_SET_BORDER_SIZE, 9),
            Map.entry(GamePacketTypes.CLIENTBOUND_SET_BORDER_LERP_SIZE, 9),
            Map.entry(GamePacketTypes.CLIENTBOUND_SET_DEFAULT_SPAWN_POSITION, 9));
    private static final Map<PacketType<?>, Integer> PENDING_SERVERBOUND = Map.of();

    @GameTest
    public void everyClientboundPacketTypeIsClassified(GameTestHelper helper) {
        assertClassified(helper, GameProtocols.CLIENTBOUND_TEMPLATE.details(), CoordinateOffsets.clientboundCoverage(),
                PENDING_CLIENTBOUND);
    }

    @GameTest
    public void everyServerboundPacketTypeIsClassified(GameTestHelper helper) {
        assertClassified(helper, GameProtocols.SERVERBOUND_TEMPLATE.details(), CoordinateOffsets.serverboundCoverage(),
                PENDING_SERVERBOUND);
    }

    // The same for block entities, whose update tags reach the client in chunks and in their own packet
    @GameTest
    public void everyBlockEntityTypeIsClassified(GameTestHelper helper) {
        Map<BlockEntityType<?>, Coverage> coverage = BlockRules.blockEntityCoverage();
        List<String> missing = new ArrayList<>();
        for (BlockEntityType<?> type : BuiltInRegistries.BLOCK_ENTITY_TYPE) {
            if (!coverage.containsKey(type)) {
                missing.add(String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type)));
            }
        }
        helper.assertTrue(missing.isEmpty(), "block entity types in no table: " + String.join(", ", missing));
        helper.succeed();
    }

    // The same for item components. Every type is checked, since every one goes to the client: those with no stream
    // codec of their own go in their saved form.
    @GameTest
    public void everySyncedDataComponentIsClassified(GameTestHelper helper) {
        Map<DataComponentType<?>, Coverage> coverage = ItemRule.componentCoverage();
        List<String> missing = new ArrayList<>();
        for (DataComponentType<?> type : BuiltInRegistries.DATA_COMPONENT_TYPE) {
            if (!coverage.containsKey(type)) {
                missing.add(String.valueOf(BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type)));
            }
        }
        helper.assertTrue(missing.isEmpty(), "data component types in no table: " + String.join(", ", missing));
        helper.succeed();
    }

    // The same for particles, which come in their own packet, in explosions and in entity data
    @GameTest
    public void everyParticleTypeIsClassified(GameTestHelper helper) {
        Map<ParticleType<?>, Coverage> coverage = ParticleRule.particleCoverage();
        List<String> missing = new ArrayList<>();
        for (ParticleType<?> type : BuiltInRegistries.PARTICLE_TYPE) {
            if (!coverage.containsKey(type)) {
                missing.add(String.valueOf(BuiltInRegistries.PARTICLE_TYPE.getKey(type)));
            }
        }
        helper.assertTrue(missing.isEmpty(), "particle types in no table: " + String.join(", ", missing));
        helper.succeed();
    }

    // The particle rule moves a vibration's block and keeps an entity's id, and the waypoint rule knows these four kinds.
    // A new kind of either would go out unshifted, so it fails here. The waypoint kinds are private, so they are read by
    // name.
    @GameTest
    public void positionSourcesAndWaypointTypesKnown(GameTestHelper helper) throws ClassNotFoundException {
        helper.assertValueEqual(Set.copyOf(BuiltInRegistries.POSITION_SOURCE_TYPE.stream().toList()),
                Set.<PositionSourceType<?>>of(PositionSourceType.BLOCK, PositionSourceType.ENTITY), "position source types");
        Object[] kinds = Class.forName("net.minecraft.world.waypoints.TrackedWaypoint$Type").getEnumConstants();
        helper.assertValueEqual(Arrays.stream(kinds).map(String::valueOf).toList(), List.of("EMPTY", "VEC3I", "CHUNK", "AZIMUTH"),
                "waypoint types");
        helper.succeed();
    }

    // The same for the serializers of entity data, which is part of every spawn
    @GameTest
    public void everyEntityDataSerializerIsClassified(GameTestHelper helper) {
        Map<EntityDataSerializer<?>, Coverage> coverage = EntityRules.serializerCoverage();
        List<Integer> missing = new ArrayList<>();
        EntityDataSerializer<?> serializer;
        for (int id = 0; (serializer = EntityDataSerializers.getSerializer(id)) != null; id++) {
            if (!coverage.containsKey(serializer)) {
                missing.add(id);
            }
        }
        helper.assertTrue(missing.isEmpty(), "entity data serializers in no table, by id: " + missing);
        helper.succeed();
    }

    // Every type the protocol lists is in the tables or still pending, never both
    private static void assertClassified(GameTestHelper helper, ProtocolInfo.Details protocol, Map<PacketType<?>, Coverage> tables,
                                         Map<PacketType<?>, Integer> pending) {
        List<String> problems = new ArrayList<>();
        protocol.listPackets((type, id) -> {
            if (!tables.containsKey(type) && !pending.containsKey(type)) {
                problems.add(type + " is in no table");
            }
        });
        pending.forEach((type, task) -> {
            if (tables.containsKey(type)) {
                problems.add(type + " is in a table and still pending for task " + task);
            }
        });
        helper.assertTrue(problems.isEmpty(), String.join("; ", problems));
        helper.succeed();
    }
}
