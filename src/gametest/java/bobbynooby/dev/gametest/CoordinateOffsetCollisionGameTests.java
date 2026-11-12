package bobbynooby.dev.gametest;

import bobbynooby.dev.coords.CoordinateOffsets;
import bobbynooby.dev.coords.Offset;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

// Bamboo, pointed dripstone and sulfur spikes are placed off centre by a hash of their spot, which differs in a shifted
// frame. An offset player collides with the shape their client computes; everyone else with the real one. The tests
// join players, so the class has a batch of its own.
public class CoordinateOffsetCollisionGameTests {
    private static final String COLLISION = "you-only-live-twice-gametest:coordinate_offset_collision";

    @GameTest(environment = COLLISION)
    public void bambooShapeMatchesTheClient(GameTestHelper helper) {
        assertShapeMatchesTheClient(helper, Blocks.BAMBOO.defaultBlockState(), "cobamboo", "cobambooplain");
        helper.succeed();
    }

    @GameTest(environment = COLLISION)
    public void dripstoneAndSulfurSpikeToo(GameTestHelper helper) {
        assertShapeMatchesTheClient(helper, Blocks.POINTED_DRIPSTONE.defaultBlockState(), "codrip", "codripplain");
        assertShapeMatchesTheClient(helper, Blocks.SULFUR_SPIKE.defaultBlockState(), "cospike", "cospikeplain");
        helper.succeed();
    }

    // Stone has no offset, and short grass an offset but no collision: both keep the very shape
    @GameTest(environment = COLLISION)
    public void otherBlocksUntouched(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "coother", GameType.CREATIVE);
        try {
            ServerLevel level = helper.getLevel();
            helper.assertFalse(CoordinateOffsets.offset(player).isZero(), "the player got no offset");
            BlockPos stone = helper.absolutePos(new BlockPos(2, 1, 2));
            BlockPos grass = helper.absolutePos(new BlockPos(3, 2, 3));
            helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE);
            helper.setBlock(new BlockPos(3, 1, 3), Blocks.GRASS_BLOCK);
            helper.setBlock(new BlockPos(3, 2, 3), Blocks.SHORT_GRASS);
            for (BlockPos pos : new BlockPos[] {stone, grass}) {
                BlockState state = level.getBlockState(pos);
                helper.assertTrue(state.getCollisionShape(level, pos, CollisionContext.of(player))
                        == state.getCollisionShape(level, pos, CollisionContext.empty()), state + " got another shape");
            }
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // At a spot whose hash differs in the player's frame: the offset player gets the real shape moved by the difference,
    // a zombie and a player with no offset the real one
    private static void assertShapeMatchesTheClient(GameTestHelper helper, BlockState state, String name, String plainName) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = OffsetTestKit.join(helper, name, GameType.CREATIVE);
        ServerPlayer plain = null;
        Zombie zombie = null;
        try {
            plain = TestPlayers.join(helper, plainName, GameType.CREATIVE, new BlockPos(1, 1, 1));
            zombie = helper.spawnWithNoFreeWill(EntityTypes.ZOMBIE, new BlockPos(1, 1, 6));
            Offset offset = CoordinateOffsets.offset(player);
            helper.assertFalse(offset.isZero(), "the player got no offset");
            BlockPos pos = null;
            // Within the test's area, four by four
            for (int step = 0; step < 16 && pos == null; step++) {
                BlockPos candidate = helper.absolutePos(new BlockPos(2 + step % 4, 2, 2 + step / 4));
                if (!state.getOffset(candidate).equals(state.getOffset(offset.shift(candidate)))) {
                    pos = candidate;
                }
            }
            helper.assertTrue(pos != null, "no spot in 16 where the client puts " + state + " elsewhere");
            // Only the clients are told, so no neighbour update can change or break the block
            level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            level.setBlock(pos, state, Block.UPDATE_CLIENTS);
            BlockState placed = level.getBlockState(pos);
            helper.assertValueEqual(placed, state, "block placed");

            VoxelShape real = placed.getCollisionShape(level, pos, CollisionContext.empty());
            helper.assertFalse(real.isEmpty(), state + " has no collision shape");
            VoxelShape client = real.move(state.getOffset(offset.shift(pos)).subtract(state.getOffset(pos)));
            helper.assertValueEqual(placed.getCollisionShape(level, pos, CollisionContext.of(player)).toAabbs(), client.toAabbs(),
                    "shape for the offset player");
            helper.assertValueEqual(placed.getCollisionShape(level, pos, CollisionContext.of(zombie)).toAabbs(), real.toAabbs(),
                    "shape for a zombie");
            helper.assertValueEqual(placed.getCollisionShape(level, pos, CollisionContext.of(plain)).toAabbs(), real.toAabbs(),
                    "shape for a player with no offset");
        } finally {
            TestPlayers.leave(player);
            if (plain != null) {
                TestPlayers.leave(plain);
            }
            if (zombie != null) {
                zombie.discard();
            }
        }
    }
}
