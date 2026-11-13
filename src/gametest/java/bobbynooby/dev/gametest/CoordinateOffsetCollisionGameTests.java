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
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.function.Predicate;

// Bamboo, pointed dripstone and sulfur spikes are placed off centre by a hash of their spot, which differs in a shifted
// frame. An offset player, and a mount they steer, collide with the shape their client computes; while the switch is on
// everything else collides with the block centred. The tests join players, so the class has a batch of its own.
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

    // With the switch on, nothing but an offset player collides where the real hash puts the stalk: a mob and a context
    // with no entity get it centred. With the switch off, vanilla.
    @GameTest(environment = COLLISION)
    public void othersCollideWithTheCentredShapeWhileTheSwitchIsOn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Zombie zombie = helper.spawnWithNoFreeWill(EntityTypes.ZOMBIE, new BlockPos(1, 1, 6));
        try {
            BlockState state = Blocks.BAMBOO.defaultBlockState();
            BlockPos pos = spotWhere(helper, candidate -> !state.getOffset(candidate).equals(Vec3.ZERO), "bamboo sits off centre");
            BlockState placed = place(helper, pos, state);
            VoxelShape real = placed.getCollisionShape(level, pos, CollisionContext.empty());
            VoxelShape centred = real.move(state.getOffset(pos).reverse());
            OffsetTestKit.withCoordinates(() -> {
                helper.assertValueEqual(placed.getCollisionShape(level, pos, CollisionContext.empty()).toAabbs(), centred.toAabbs(),
                        "shape for no entity with the switch on");
                helper.assertValueEqual(placed.getCollisionShape(level, pos, CollisionContext.of(zombie)).toAabbs(), centred.toAabbs(),
                        "shape for a zombie with the switch on");
                // What a zombie's path is worked out on
                PathNavigationRegion region = new PathNavigationRegion(level, pos.offset(-2, -2, -2), pos.offset(2, 2, 2));
                helper.assertValueEqual(placed.getCollisionShape(region, pos, CollisionContext.of(zombie)).toAabbs(), centred.toAabbs(),
                        "shape for a zombie's path with the switch on");
                return null;
            });
            helper.assertValueEqual(placed.getCollisionShape(level, pos, CollisionContext.of(zombie)).toAabbs(), real.toAabbs(),
                    "shape for a zombie with the switch off");
        } finally {
            zombie.discard();
        }
        helper.succeed();
    }

    // The server moves a mount itself, so a mount the offset player steers collides where their client puts the block
    @GameTest(environment = COLLISION)
    public void steeredMountGetsItsRidersShape(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = OffsetTestKit.join(helper, "comount", GameType.CREATIVE);
        Boat boat = null;
        try {
            Offset offset = CoordinateOffsets.offset(player);
            helper.assertFalse(offset.isZero(), "the player got no offset");
            BlockState state = Blocks.BAMBOO.defaultBlockState();
            BlockPos pos = spotWhere(helper, candidate -> !state.getOffset(candidate).equals(state.getOffset(offset.shift(candidate))),
                    "the client puts bamboo elsewhere");
            BlockState placed = place(helper, pos, state);
            boat = helper.spawn(EntityTypes.OAK_BOAT, new BlockPos(1, 2, 1));
            helper.assertTrue(player.startRiding(boat, true, false), "the player did not get in the boat");
            helper.assertTrue(boat.getControllingPassenger() == player, "the player does not steer the boat");
            VoxelShape real = placed.getCollisionShape(level, pos, CollisionContext.empty());
            VoxelShape client = real.move(state.getOffset(offset.shift(pos)).subtract(state.getOffset(pos)));
            CollisionContext mount = CollisionContext.of(boat);
            helper.assertValueEqual(placed.getCollisionShape(level, pos, mount).toAabbs(), client.toAabbs(),
                    "shape for the steered boat, switch off");
            helper.assertValueEqual(OffsetTestKit.withCoordinates(() -> placed.getCollisionShape(level, pos, mount)).toAabbs(),
                    client.toAabbs(), "shape for the steered boat, switch on");
        } finally {
            player.stopRiding();
            if (boat != null) {
                boat.discard();
            }
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // The server's item rays, a bucket's for one, aim at a flower's outline, which the hash moves too: for an offset
    // player it is where their client draws it. Anyone else, and a player with no offset, keeps vanilla's, on both shapes.
    @GameTest(environment = COLLISION)
    public void flowerOutlineMatchesTheClient(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = OffsetTestKit.join(helper, "coflower", GameType.CREATIVE);
        ServerPlayer plain = null;
        try {
            plain = TestPlayers.join(helper, "coflowerplain", GameType.CREATIVE, new BlockPos(1, 1, 1));
            Offset offset = CoordinateOffsets.offset(player);
            helper.assertFalse(offset.isZero(), "the player got no offset");
            helper.assertTrue(CoordinateOffsets.offset(plain).isZero(), "the plain player got an offset");
            BlockState flower = Blocks.POPPY.defaultBlockState();
            helper.assertTrue(flower.hasOffsetFunction(), "a poppy sits in the middle of its block");
            BlockPos pos = spotWhere(helper, candidate -> !flower.getOffset(candidate).equals(flower.getOffset(offset.shift(candidate))),
                    "the client puts a poppy elsewhere");
            BlockState placed = place(helper, pos, flower);
            VoxelShape real = placed.getShape(level, pos);
            helper.assertValueEqual(placed.getShape(level, pos, CollisionContext.of(player)).toAabbs(),
                    real.move(flower.getOffset(offset.shift(pos)).subtract(flower.getOffset(pos))).toAabbs(),
                    "outline for the offset player");
            ServerPlayer noOffset = plain;
            OffsetTestKit.withCoordinates(() -> {
                helper.assertValueEqual(placed.getShape(level, pos, CollisionContext.of(noOffset)).toAabbs(), real.toAabbs(),
                        "outline for a player with no offset");
                helper.assertValueEqual(placed.getShape(level, pos, CollisionContext.empty()).toAabbs(), real.toAabbs(),
                        "outline for no entity");
                return null;
            });

            BlockState stalk = Blocks.BAMBOO.defaultBlockState();
            BlockPos stalkPos = spotWhere(helper, candidate -> !candidate.equals(pos) && !stalk.getOffset(candidate).equals(Vec3.ZERO),
                    "bamboo sits off centre");
            BlockState placedStalk = place(helper, stalkPos, stalk);
            VoxelShape realStalk = placedStalk.getCollisionShape(level, stalkPos, CollisionContext.empty());
            helper.assertValueEqual(OffsetTestKit.withCoordinates(() -> placedStalk.getCollisionShape(level, stalkPos,
                    CollisionContext.of(noOffset))).toAabbs(), realStalk.toAabbs(), "shape for a player with no offset, switch on");
        } finally {
            TestPlayers.leave(player);
            if (plain != null) {
                TestPlayers.leave(plain);
            }
        }
        helper.succeed();
    }

    // The first spot in the test's area, four by four, that the check likes
    private static BlockPos spotWhere(GameTestHelper helper, Predicate<BlockPos> check, String what) {
        for (int step = 0; step < 16; step++) {
            BlockPos candidate = helper.absolutePos(new BlockPos(2 + step % 4, 2, 2 + step / 4));
            if (check.test(candidate)) {
                return candidate;
            }
        }
        helper.fail("no spot in 16 where " + what);
        return BlockPos.ZERO;
    }

    // Only the clients are told, so no neighbour update can change or break the block
    private static BlockState place(GameTestHelper helper, BlockPos pos, BlockState state) {
        ServerLevel level = helper.getLevel();
        level.setBlock(pos.below(), Blocks.DIRT.defaultBlockState(), Block.UPDATE_CLIENTS);
        level.setBlock(pos, state, Block.UPDATE_CLIENTS);
        BlockState placed = level.getBlockState(pos);
        helper.assertValueEqual(placed, state, "block placed");
        return placed;
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
