package bobbynooby.dev.gametest;

import bobbynooby.dev.graves.Grave;
import bobbynooby.dev.graves.GravePlacement;
import bobbynooby.dev.graves.GraveStore;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.border.WorldBorder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PlacementGameTests {
    // The middle of the wide lava pool
    private static final BlockPos LAVA_DEATH = new BlockPos(3, 0, 3);

    private static GraveStore store(GameTestHelper helper) {
        return GraveStore.get(helper.getLevel().getServer());
    }

    private static Grave graveAt(BlockPos pos) {
        return new Grave(UUID.randomUUID(), UUID.randomUUID(), "Alex", ResolvableProfile.createUnresolved("Alex"),
                Level.OVERWORLD, pos, List.of(new ItemStack(Items.DIAMOND)), 1L);
    }

    @GameTest
    public void openAirUsesDeathSpot(GameTestHelper helper) {
        BlockPos death = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos result = GravePlacement.find(helper.getLevel(), death, store(helper));
        helper.assertValueEqual(result, death, "grave position");
        helper.succeed();
    }

    @GameTest
    public void insideStoneMovesUp(GameTestHelper helper) {
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.STONE);
        helper.setBlock(new BlockPos(1, 2, 1), Blocks.STONE);
        BlockPos death = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos result = GravePlacement.find(helper.getLevel(), death, store(helper));
        helper.assertValueEqual(result, death.above(2), "grave position");
        helper.succeed();
    }

    @GameTest
    public void belowWorldFindsSolidGround(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos death = helper.absolutePos(new BlockPos(1, 1, 1)).atY(level.getMinY() - 40);
        BlockPos result = GravePlacement.find(level, death, store(helper));
        helper.assertTrue(result.getY() > level.getMinY(), "grave at or below the world bottom: " + result);
        helper.assertTrue(!level.getBlockState(result.below()).getCollisionShape(level, result.below()).isEmpty(), "nothing solid under the grave: " + result);
        helper.succeed();
    }

    @GameTest
    public void aboveBuildLimitClampsDown(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos death = helper.absolutePos(new BlockPos(1, 1, 1)).atY(level.getMaxY() + 10);
        BlockPos result = GravePlacement.find(level, death, store(helper));
        helper.assertTrue(result.getY() <= level.getMaxY() - 1, "grave above the build limit: " + result);
        helper.succeed();
    }

    // Nobody can reach a grave past the border. The death block is the border's corner and its column is full,
    // so the search has to look around it, where most spots are outside.
    @GameTest
    public void placementStaysInsideWorldBorder(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WorldBorder border = level.getWorldBorder();
        double size = border.getSize();
        double centerX = border.getCenterX();
        double centerZ = border.getCenterZ();
        BlockPos death = helper.absolutePos(new BlockPos(1, 1, 1));
        try {
            border.setCenter(death.getX() + 8, death.getZ() + 8);
            border.setSize(16);
            for (int dy = 0; dy <= 8; dy++) {
                level.setBlock(death.above(dy), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            }
            helper.assertTrue(border.isWithinBounds(death), "death spot outside the border");
            BlockPos result = GravePlacement.find(level, death, store(helper));
            helper.assertTrue(border.isWithinBounds(result), "grave outside the world border: " + result);
        } finally {
            border.setSize(size);
            border.setCenter(centerX, centerZ);
            for (int dy = 0; dy <= 8; dy++) {
                level.setBlock(death.above(dy), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        helper.succeed();
    }

    // The death is 40 blocks past the east edge of a 50 wide border around the test. Set and put back in this tick.
    @GameTest
    public void deathFarOutsideBorderGraveInside(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WorldBorder border = level.getWorldBorder();
        double size = border.getSize();
        double centerX = border.getCenterX();
        double centerZ = border.getCenterZ();
        BlockPos centre = helper.absolutePos(new BlockPos(4, 1, 4));
        try {
            border.setCenter(centre.getX(), centre.getZ());
            border.setSize(50);
            BlockPos death = new BlockPos(Mth.floor(border.getMaxX()) + 40, centre.getY(), centre.getZ());
            helper.assertFalse(border.isWithinBounds(death), "death spot inside the border");
            BlockPos result = GravePlacement.find(level, death, store(helper));
            helper.assertTrue(border.isWithinBounds(result), "grave outside the world border: " + result);
        } finally {
            border.setSize(size);
            border.setCenter(centerX, centerZ);
        }
        helper.succeed();
    }

    // The death spot and the eight blocks above it are stone, so the spot comes from the first ring
    @GameTest(skyAccess = true)
    public void ringFindsSideSpotWhenColumnBlocked(GameTestHelper helper) {
        BlockPos death = new BlockPos(3, 0, 3);
        List<BlockPos> placed = new ArrayList<>();
        try {
            for (int dy = 0; dy <= 8; dy++) {
                place(helper, placed, death.above(dy), Blocks.STONE);
            }
            BlockPos at = helper.absolutePos(death);
            BlockPos result = GravePlacement.find(helper.getLevel(), at, store(helper));
            helper.assertValueEqual(result.getY(), at.getY(), "grave height");
            helper.assertValueEqual(Math.max(Math.abs(result.getX() - at.getX()), Math.abs(result.getZ() - at.getZ())), 1,
                    "grave's distance from the death column");
        } finally {
            clear(helper, placed);
        }
        helper.succeed();
    }

    // Stone fills the death column eight blocks up and the first three rings three blocks up, so only the surface on
    // top of the column is left
    @GameTest(skyAccess = true)
    public void surfaceFallbackAboveBlockedArea(GameTestHelper helper) {
        BlockPos death = new BlockPos(3, 0, 3);
        List<BlockPos> placed = new ArrayList<>();
        try {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    for (int dy = 0; dy <= 3; dy++) {
                        place(helper, placed, death.offset(dx, dy, dz), Blocks.STONE);
                    }
                }
            }
            for (int dy = 4; dy <= 8; dy++) {
                place(helper, placed, death.above(dy), Blocks.STONE);
            }
            BlockPos result = GravePlacement.find(helper.getLevel(), helper.absolutePos(death), store(helper));
            helper.assertValueEqual(result, helper.absolutePos(death.above(9)), "grave position");
        } finally {
            clear(helper, placed);
        }
        helper.succeed();
    }

    // In the middle of a lava pool wider than the first rings, the grave goes on the rim, not in the air over lava
    @GameTest(skyAccess = true)
    public void wideLavaPoolGraveOnFirmGround(GameTestHelper helper) {
        List<BlockPos> placed = new ArrayList<>();
        try {
            BlockPos result = findInWideLavaPool(helper, placed);
            assertOnFirmGround(helper, result);
        } finally {
            clear(helper, placed);
        }
        helper.succeed();
    }

    // The same pool under a roof like the Nether's bedrock: the heightmap is the roof's top, which nobody can reach in
    // survival, so the grave must stay under it
    @GameTest(dimension = "minecraft:the_nether", skyAccess = true)
    public void netherLavaDeathGraveStaysUnderRoof(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int roofY = level.getMinY() + level.getLogicalHeight() - 1;
        BlockPos roof = helper.absolutePos(LAVA_DEATH).atY(roofY);
        List<BlockPos> placed = new ArrayList<>();
        try {
            level.setBlock(roof, Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_ALL);
            BlockPos result = findInWideLavaPool(helper, placed);
            helper.assertTrue(result.getY() < roofY, "grave on top of the roof at " + result);
            assertOnFirmGround(helper, result);
        } finally {
            level.setBlock(roof, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            clear(helper, placed);
        }
        helper.succeed();
    }

    // The border is one column wide. That column holds a lava cauldron, two graves on it and a cobweb, so no spot is
    // safe and the last resort climbs past the graves and the cobweb, which is no block to replace. Set and put back in
    // this tick.
    @GameTest(skyAccess = true)
    public void lastResortNeverReturnsAGrave(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        GraveStore store = store(helper);
        WorldBorder border = level.getWorldBorder();
        double size = border.getSize();
        double centerX = border.getCenterX();
        double centerZ = border.getCenterZ();
        BlockPos floor = new BlockPos(3, 0, 3);
        BlockPos death = helper.absolutePos(floor.above());
        Grave lower = graveAt(death);
        Grave upper = graveAt(death.above());
        try {
            helper.setBlock(floor, Blocks.LAVA_CAULDRON);
            helper.setBlock(floor.above(3), Blocks.COBWEB);
            store.put(lower);
            store.put(upper);
            border.setCenter(death.getX() + 0.5, death.getZ() + 0.5);
            border.setSize(1);
            BlockPos result = GravePlacement.find(level, death, store);
            helper.assertTrue(store.at(level.dimension(), result).isEmpty(), "grave placed on another grave at " + result);
            helper.assertFalse(level.getBlockState(result).is(Blocks.COBWEB), "grave placed over the cobweb at " + result);
            helper.assertValueEqual(result, death.above(3), "grave position");
        } finally {
            border.setSize(size);
            border.setCenter(centerX, centerZ);
            store.remove(lower.id());
            store.remove(upper.id());
            helper.setBlock(floor.above(3), Blocks.AIR);
            helper.setBlock(floor, Blocks.AIR);
        }
        helper.succeed();
    }

    // Broken by hand, leaf litter drops itself, so a head set over it would delete that item
    @GameTest
    public void leafLitterIsNotReplaced(GameTestHelper helper) {
        BlockPos ground = new BlockPos(1, 0, 1);
        try {
            helper.setBlock(ground, Blocks.STONE);
            helper.setBlock(ground.above(), Blocks.LEAF_LITTER);
            BlockPos death = helper.absolutePos(ground.above());
            BlockPos result = GravePlacement.find(helper.getLevel(), death, store(helper));
            helper.assertFalse(result.equals(death), "grave placed over the leaf litter");
            helper.assertTrue(helper.getLevel().getBlockState(death).is(Blocks.LEAF_LITTER), "leaf litter is gone");
        } finally {
            helper.setBlock(ground.above(), Blocks.AIR);
            helper.setBlock(ground, Blocks.AIR);
        }
        helper.succeed();
    }

    // A 7 by 7 lava pool, held by a stone rim on the open sides and the barrier walls on the others. Every spot the first
    // rings look at is over lava. The death is in the middle of it.
    private static BlockPos findInWideLavaPool(GameTestHelper helper, List<BlockPos> placed) {
        for (int x = 0; x <= 7; x++) {
            for (int z = 0; z <= 7; z++) {
                place(helper, placed, new BlockPos(x, 0, z), x == 7 || z == 7 ? Blocks.STONE : Blocks.LAVA);
            }
        }
        return GravePlacement.find(helper.getLevel(), helper.absolutePos(LAVA_DEATH), store(helper));
    }

    private static void assertOnFirmGround(GameTestHelper helper, BlockPos result) {
        ServerLevel level = helper.getLevel();
        BlockPos below = result.below();
        helper.assertFalse(level.getFluidState(below).is(FluidTags.LAVA), "grave over lava at " + result);
        helper.assertFalse(level.getBlockState(below).getCollisionShape(level, below).isEmpty(), "nothing solid under the grave at " + result);
    }

    // Placed blocks are noted first, so finally clears even the ones placed before a failure
    private static void place(GameTestHelper helper, List<BlockPos> placed, BlockPos pos, Block block) {
        placed.add(pos);
        helper.setBlock(pos, block);
    }

    private static void clear(GameTestHelper helper, List<BlockPos> placed) {
        for (BlockPos pos : placed) {
            helper.setBlock(pos, Blocks.AIR);
        }
    }

    @GameTest
    public void secondDeathAtSameSpotFindsNewPosition(GameTestHelper helper) {
        GraveStore store = store(helper);
        BlockPos death = helper.absolutePos(new BlockPos(1, 1, 1));
        Grave first = graveAt(GravePlacement.find(helper.getLevel(), death, store));
        try {
            store.put(first);
            BlockPos second = GravePlacement.find(helper.getLevel(), death, store);
            helper.assertFalse(second.equals(first.pos()), "second grave placed on the first: " + second);
        } finally {
            store.remove(first.id());
        }
        helper.succeed();
    }
}
