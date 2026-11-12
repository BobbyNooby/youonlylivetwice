package bobbynooby.dev.gametest;

import bobbynooby.dev.coords.BlockRules;
import bobbynooby.dev.coords.CoordinateOffsets;
import bobbynooby.dev.coords.Coverage;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// A packet type nobody looked at fails here, such as one a Minecraft update adds, so no position in it goes out real
// unseen. They only read the tables, so they share the default batch.
public class CoordinateOffsetCoverageGameTests {
    // Types with positions that a later task of the plan translates, each with that task's number. The task takes its
    // types out of here as it puts them in a table.
    private static final Map<PacketType<?>, Integer> PENDING_CLIENTBOUND = Map.ofEntries(
            Map.entry(GamePacketTypes.CLIENTBOUND_CONTAINER_SET_CONTENT, 5),
            Map.entry(GamePacketTypes.CLIENTBOUND_CONTAINER_SET_SLOT, 5),
            Map.entry(GamePacketTypes.CLIENTBOUND_SET_CURSOR_ITEM, 5),
            Map.entry(GamePacketTypes.CLIENTBOUND_SET_PLAYER_INVENTORY, 5),
            Map.entry(GamePacketTypes.CLIENTBOUND_SET_EQUIPMENT, 5),
            Map.entry(GamePacketTypes.CLIENTBOUND_MERCHANT_OFFERS, 5),
            Map.entry(GamePacketTypes.CLIENTBOUND_SOUND, 6),
            Map.entry(GamePacketTypes.CLIENTBOUND_LEVEL_PARTICLES, 6),
            Map.entry(GamePacketTypes.CLIENTBOUND_EXPLODE, 6),
            Map.entry(GamePacketTypes.CLIENTBOUND_DAMAGE_EVENT, 6),
            Map.entry(GamePacketTypes.CLIENTBOUND_WAYPOINT, 6),
            Map.entry(GamePacketTypes.CLIENTBOUND_GAME_TEST_HIGHLIGHT_POS, 6),
            Map.entry(GamePacketTypes.CLIENTBOUND_ADD_ENTITY, 7),
            Map.entry(GamePacketTypes.CLIENTBOUND_TELEPORT_ENTITY, 7),
            Map.entry(GamePacketTypes.CLIENTBOUND_ENTITY_POSITION_SYNC, 7),
            Map.entry(GamePacketTypes.CLIENTBOUND_MOVE_MINECART_ALONG_TRACK, 7),
            Map.entry(GamePacketTypes.CLIENTBOUND_SET_ENTITY_DATA, 7),
            Map.entry(GamePacketTypes.CLIENTBOUND_INITIALIZE_BORDER, 9),
            Map.entry(GamePacketTypes.CLIENTBOUND_SET_BORDER_CENTER, 9),
            Map.entry(GamePacketTypes.CLIENTBOUND_SET_BORDER_SIZE, 9),
            Map.entry(GamePacketTypes.CLIENTBOUND_SET_BORDER_LERP_SIZE, 9),
            Map.entry(GamePacketTypes.CLIENTBOUND_SET_DEFAULT_SPAWN_POSITION, 9));
    private static final Map<PacketType<?>, Integer> PENDING_SERVERBOUND = Map.ofEntries(
            Map.entry(GamePacketTypes.SERVERBOUND_SET_CREATIVE_MODE_SLOT, 5),
            Map.entry(GamePacketTypes.SERVERBOUND_CONTAINER_CLICK, 5));

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
