package bobbynooby.dev.gametest;

import bobbynooby.dev.world.RandomSpawn;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

// Each column is built inside the test structure on its barrier floor. skyAccess leaves off the barrier ceiling,
// so the blocks placed here are the top of the column.
public class RandomSpawnGameTests {
    private static final BlockPos GROUND = new BlockPos(3, 0, 3);

    @GameTest(skyAccess = true)
    public void powderSnowColumnRefused(GameTestHelper helper) {
        helper.setBlock(GROUND, Blocks.STONE);
        helper.setBlock(GROUND.above(), Blocks.POWDER_SNOW);
        BlockPos ground = helper.absolutePos(GROUND);
        BlockPos spot = RandomSpawn.spotAt(helper.getLevel(), ground.getX(), ground.getZ());
        helper.assertTrue(spot == null, "powder snow column gave the spot " + spot);
        helper.succeed();
    }

    @GameTest(skyAccess = true)
    public void cactusColumnRefused(GameTestHelper helper) {
        helper.setBlock(GROUND, Blocks.SAND);
        helper.setBlock(GROUND.above(), Blocks.CACTUS);
        BlockPos ground = helper.absolutePos(GROUND);
        BlockPos spot = RandomSpawn.spotAt(helper.getLevel(), ground.getX(), ground.getZ());
        helper.assertTrue(spot == null, "cactus column gave the spot " + spot);
        helper.succeed();
    }

    @GameTest(skyAccess = true)
    public void grassColumnGivesBlockAbove(GameTestHelper helper) {
        helper.setBlock(GROUND, Blocks.GRASS_BLOCK);
        BlockPos ground = helper.absolutePos(GROUND);
        BlockPos spot = RandomSpawn.spotAt(helper.getLevel(), ground.getX(), ground.getZ());
        helper.assertTrue(ground.above().equals(spot), "grass column gave the spot " + spot + ", not " + ground.above());
        helper.succeed();
    }
}
