package bobbynooby.dev.hardcore;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * What the mod's GameTests share: how close a sum is checked, the shared slowdown, a bed to lie in, a zombie spawned as
 * the spawner spawns one, and a blood moon day no other test has had.
 */
final class HardcoreTestKit {
    static final double CLOSE = 1e-9;
    // A float keeps about seven digits, so a sum of floats is only this close
    static final double FLOAT_CLOSE = 1e-5;
    // Beside the foot of the bed placeBed puts down
    static final BlockPos NEXT_TO_BED = new BlockPos(1, 1, 0);
    // The last day freshDay handed out, read and written on the server thread only, as the tests run
    private static long lastFreshDay = Long.MIN_VALUE;

    private HardcoreTestKit() {}

    static void assertClose(GameTestHelper helper, double actual, double expected, String what) {
        assertClose(helper, actual, expected, CLOSE, what);
    }

    static void assertClose(GameTestHelper helper, double actual, double expected, double within, String what) {
        helper.assertTrue(Math.abs(actual - expected) < within, what + " is " + actual + ", not " + expected);
    }

    /** The shared slowdown on the player now, after its steps. */
    static void assertSlow(GameTestHelper helper, ServerPlayer player, double expected, String when) {
        assertClose(helper, Slowdowns.applied(player), expected, "the slowdown " + when);
    }

    /** A red bed with its foot at 1, 1, 1, facing south; returns the head. */
    static BlockPos placeBed(GameTestHelper helper) {
        BlockPos foot = new BlockPos(1, 1, 1);
        BlockPos head = foot.relative(Direction.SOUTH);
        BlockState bed = Blocks.BED.pick(DyeColor.RED).defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH);
        helper.setBlock(foot, bed.setValue(BedBlock.PART, BedPart.FOOT));
        helper.setBlock(head, bed.setValue(BedBlock.PART, BedPart.HEAD));
        return head;
    }

    /** What a right click on the bed runs on the server. */
    static void useBed(GameTestHelper helper, ServerPlayer player, BlockPos relativeHead) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(relativeHead);
        level.getBlockState(pos).useWithoutItem(level, player, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
    }

    /**
     * A zombie as a natural spawn makes it: made with its reason, which a mob boost reads, then added to the world,
     * which runs the load hook. Kept from despawning; the test discards it. It keeps its goals, which YOLT's own mob
     * tests take away: each test here discards it in the tick it was made in, so no goal ever runs.
     */
    static Zombie naturalZombie(GameTestHelper helper, BlockPos relativePos) {
        ServerLevel level = helper.getLevel();
        Zombie zombie = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.NATURAL);
        helper.assertTrue(zombie != null, "no zombie made");
        Vec3 at = helper.absoluteVec(Vec3.atBottomCenterOf(relativePos));
        zombie.snapTo(at.x, at.y, at.z, 0.0F, 0.0F);
        zombie.setPersistenceRequired();
        helper.assertTrue(level.addFreshEntity(zombie), "the zombie was not added");
        return zombie;
    }

    /**
     * A day whose night a test drives on a stand-in clock: past the grace days and past any blood moon the store has
     * saved, begun or ended, so the roll and the break between nights never stand in its way and no night counts as
     * over already. Also past every day handed out before in this run: a night started and stopped on the world's real
     * clock, as the command tests do, saves the real clock's day over both saved days, after which they alone could
     * give out an earlier test's day again.
     */
    static long freshDay(HardcoreConfig.BloodMoonSection config) {
        long last = HardcoreStates.world(BloodMoon.LAST_DAY).orElse(0L);
        long ended = HardcoreStates.world(BloodMoon.ENDED_DAY).orElse(0L);
        long day = Math.max(Math.max(Math.max(last, ended), config.graceDays), lastFreshDay) + 100L;
        lastFreshDay = day;
        return day;
    }
}
