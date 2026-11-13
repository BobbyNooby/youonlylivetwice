package bobbynooby.dev.gametest;

import bobbynooby.dev.coords.BlockRules;
import bobbynooby.dev.coords.CoordinateOffsets;
import bobbynooby.dev.coords.Coverage;
import bobbynooby.dev.coords.EntityRules;
import bobbynooby.dev.coords.ItemRule;
import bobbynooby.dev.coords.ParticleRule;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.ColumnPosArgument;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.commands.arguments.coordinates.RotationArgument;
import net.minecraft.commands.arguments.coordinates.Vec2Argument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.gameevent.PositionSourceType;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

// A packet type nobody looked at fails here, such as one a Minecraft update adds, so no position in it goes out real
// unseen. They only read the tables, so they share the default batch.
public class CoordinateOffsetCoverageGameTests {
    @GameTest
    public void everyClientboundPacketTypeIsClassified(GameTestHelper helper) {
        assertClassified(helper, GameProtocols.CLIENTBOUND_TEMPLATE.details(), CoordinateOffsets.clientboundCoverage());
    }

    @GameTest
    public void everyServerboundPacketTypeIsClassified(GameTestHelper helper) {
        assertClassified(helper, GameProtocols.SERVERBOUND_TEMPLATE.details(), CoordinateOffsets.serverboundCoverage());
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

    // A typed spot is read in the typist's frame through the hooks on the coordinate arguments, and a 3D argument that
    // keeps whole numbers as typed is taken for a vector and left alone. So a new argument type that parses coordinates,
    // or a second such vector, would be read real; both fail here. Rotation parses coordinates too, but holds no place.
    @GameTest
    public void commandTreeHasOnlyKnownCoordinateArguments(GameTestHelper helper) throws ReflectiveOperationException {
        Field centred = Vec3Argument.class.getDeclaredField("centerCorrect");
        centred.setAccessible(true);
        Set<Class<?>> coordinateTypes = new HashSet<>();
        Set<String> vectors = new TreeSet<>();
        CommandNode<CommandSourceStack> root = helper.getLevel().getServer().getCommands().getDispatcher().getRoot();
        walk(root, "", Collections.newSetFromMap(new IdentityHashMap<>()), node -> {
            ArgumentType<?> type = node.node().getType();
            if (parsesCoordinates(type.getClass())) {
                coordinateTypes.add(type.getClass());
            }
            if (type instanceof Vec3Argument) {
                try {
                    if (!centred.getBoolean(type)) {
                        vectors.add(node.path());
                    }
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        });
        helper.assertValueEqual(coordinateTypes, Set.<Class<?>>of(BlockPosArgument.class, ColumnPosArgument.class,
                Vec2Argument.class, Vec3Argument.class, RotationArgument.class), "argument types that parse coordinates");
        helper.assertValueEqual(vectors, Set.of("particle name pos delta"), "3D arguments read as a vector");
        helper.succeed();
    }

    private record Argument(ArgumentCommandNode<CommandSourceStack, ?> node, String path) {}

    // Children only: a redirect such as execute's run leads back to the root
    private static void walk(CommandNode<CommandSourceStack> node, String path, Set<CommandNode<CommandSourceStack>> seen,
                             Consumer<Argument> each) {
        if (!seen.add(node)) {
            return;
        }
        for (CommandNode<CommandSourceStack> child : node.getChildren()) {
            String at = path.isEmpty() ? child.getName() : path + " " + child.getName();
            if (child instanceof ArgumentCommandNode<CommandSourceStack, ?> argument) {
                each.accept(new Argument(argument, at));
            }
            walk(child, at, seen, each);
        }
    }

    // An argument type whose parser returns Coordinates, on the class or one it extends
    private static boolean parsesCoordinates(Class<?> type) {
        for (Class<?> at = type; at != null; at = at.getSuperclass()) {
            for (Type implemented : at.getGenericInterfaces()) {
                if (implemented instanceof ParameterizedType parameterized && parameterized.getRawType() == ArgumentType.class
                        && parameterized.getActualTypeArguments()[0] == Coordinates.class) {
                    return true;
                }
            }
        }
        return false;
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

    // Every type the protocol lists is in the tables
    private static void assertClassified(GameTestHelper helper, ProtocolInfo.Details protocol, Map<PacketType<?>, Coverage> tables) {
        List<String> problems = new ArrayList<>();
        protocol.listPackets((type, id) -> {
            if (!tables.containsKey(type)) {
                problems.add(type + " is in no table");
            }
        });
        helper.assertTrue(problems.isEmpty(), String.join("; ", problems));
        helper.succeed();
    }
}
