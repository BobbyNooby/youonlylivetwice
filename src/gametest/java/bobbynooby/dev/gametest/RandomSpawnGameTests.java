package bobbynooby.dev.gametest;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.mixin.PlayerSpawnFinderInvoker;
import bobbynooby.dev.world.RandomSpawn;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.gamerules.GameRules;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// The column tests build each column inside the test structure on its barrier floor. skyAccess leaves off the
// barrier ceiling, so the blocks placed here are the top of the column.
public class RandomSpawnGameTests {
    private static final BlockPos GROUND = new BlockPos(3, 0, 3);
    // A search that finds no ground turns random spawn off for a while, so that test runs on its own
    private static final String NO_GROUND = "you-only-live-twice-gametest:random_spawn_no_ground";
    // How long it stays off, written out for the test's maxTicks
    static final int NO_GROUND_WAIT = 100;

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

    // Four columns, spread out so each is checked on its own. Fire sits on netherrack, where it never burns out. A plain
    // grass column among them must still give a spot, so a structure that refuses everything can't pass.
    @GameTest(skyAccess = true)
    public void randomSpawnAvoidsFireBerriesAndMagma(GameTestHelper helper) {
        BlockPos fire = new BlockPos(1, 0, 1);
        helper.setBlock(fire, Blocks.NETHERRACK);
        helper.setBlock(fire.above(), Blocks.FIRE);
        BlockPos berries = new BlockPos(5, 0, 1);
        helper.setBlock(berries, Blocks.GRASS_BLOCK);
        helper.setBlock(berries.above(), Blocks.SWEET_BERRY_BUSH);
        BlockPos magma = new BlockPos(1, 0, 5);
        helper.setBlock(magma, Blocks.MAGMA_BLOCK);
        BlockPos witherRose = new BlockPos(5, 0, 5);
        helper.setBlock(witherRose, Blocks.GRASS_BLOCK);
        helper.setBlock(witherRose.above(), Blocks.WITHER_ROSE);

        for (BlockPos ground : List.of(fire, berries, magma, witherRose)) {
            BlockPos column = helper.absolutePos(ground);
            BlockPos spot = RandomSpawn.spotAt(helper.getLevel(), column.getX(), column.getZ());
            helper.assertTrue(spot == null, helper.getBlockState(ground.above()).getBlock() + " on "
                    + helper.getBlockState(ground).getBlock() + " gave the spot " + spot);
        }

        helper.setBlock(GROUND, Blocks.GRASS_BLOCK);
        BlockPos grass = helper.absolutePos(GROUND);
        BlockPos spot = RandomSpawn.spotAt(helper.getLevel(), grass.getX(), grass.getZ());
        helper.assertTrue(grass.above().equals(spot), "the plain grass column gave the spot " + spot + ", not " + grass.above());
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

    // A death's respawn, the way the death screen asks for it, for a player with no bed or /spawnpoint. Vanilla would
    // put them within the respawn radius of the world spawn; the random spot is somewhere in the whole border, and it
    // lands there only about once in 190,000 respawns.
    @GameTest(maxTicks = 200)
    public void respawnLandsInsideBorderOnSolidGround(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel overworld = server.overworld();
        ServerPlayer player = TestPlayers.join(helper, "Respawner", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        ServerPlayer current = player;
        boolean before = Settings.get().randomSpawn;
        try {
            Settings.get().randomSpawn = true;
            helper.assertTrue(player.getRespawnConfig() == null, "the new player has a respawn point");
            current = TestPlayers.respawn(player, false, Entity.RemovalReason.KILLED);
            BlockPos feet = current.blockPosition();
            helper.assertTrue(current.level() == overworld, "respawned in " + current.level().dimension().identifier());

            WorldBorder border = overworld.getWorldBorder();
            double half = Math.min(border.getSize(), Settings.get().worldBorder.diameter) / 2.0;
            helper.assertTrue(Math.abs(feet.getX() + 0.5 - border.getCenterX()) <= half
                            && Math.abs(feet.getZ() + 0.5 - border.getCenterZ()) <= half,
                    "respawned at " + feet.toShortString() + ", more than " + half + " blocks from the border's centre");

            BlockPos below = feet.below();
            helper.assertTrue(Block.isFaceFull(overworld.getBlockState(below).getCollisionShape(overworld, below), Direction.UP),
                    "respawned at " + feet.toShortString() + " over " + overworld.getBlockState(below) + ", which has no full top face");
            helper.assertTrue(overworld.getFluidState(feet).isEmpty() && overworld.getFluidState(feet.above()).isEmpty(),
                    "respawned at " + feet.toShortString() + " in a fluid");

            BlockPos spawn = overworld.getRespawnData().pos();
            int radius = overworld.getGameRules().get(GameRules.RESPAWN_RADIUS);
            helper.assertTrue(Math.max(Math.abs(feet.getX() - spawn.getX()), Math.abs(feet.getZ() - spawn.getZ())) > radius + 1,
                    "respawned at " + feet.toShortString() + ", where vanilla puts a player round the world spawn " + spawn.toShortString());
        } finally {
            Settings.get().randomSpawn = before;
            TestPlayers.leave(current);
        }
        helper.succeed();
    }

    // A column dug out from the bottom of the world to the top, as on a void map, is the only one the search may pick.
    // With no ground in it after every try, the search gives no spot, vanilla's own check answers that candidate (no
    // spot either), and the hook stays out for a while, so vanilla's search ends on the world spawn even when its
    // candidates run in later ticks. Random spawn is back once the while is over. Each switch goes back in its own tick.
    @GameTest(environment = NO_GROUND, maxTicks = RandomSpawnGameTests.NO_GROUND_WAIT + 20)
    public void noGroundLeavesTheSearchToVanilla(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos column = helper.absolutePos(GROUND);
        Map<BlockPos, BlockState> before = new HashMap<>();
        boolean randomBefore = Settings.get().randomSpawn;
        try {
            Settings.get().randomSpawn = true;
            VoidGraveGameTests.dig(level, column.atY(level.getMaxY() + 1), before);
            RandomSpawn.Area only = new RandomSpawn.Area(column.getX(), column.getX() + 1, column.getZ(), column.getZ() + 1);
            BlockPos[] spot = new BlockPos[1];
            List<String> log = OfflineGameTests.yoltLog(() -> spot[0] = RandomSpawn.pickIn(level, only));
            helper.assertTrue(spot[0] == null, "the search over the void gave the spot " + spot[0]);
            helper.assertValueEqual(log, List.of("No safe random spawn found in 50 columns, and the last one has no ground,"
                    + " so vanilla picks the spawn"), "log");
            helper.assertFalse(RandomSpawn.appliesTo(level), "random spawn still answers in the tick that found no ground");
            helper.assertValueEqual(RandomSpawn.NO_GROUND_TICKS, NO_GROUND_WAIT, "ticks random spawn stays off");
            BlockPos vanilla = PlayerSpawnFinderInvoker.yoltLevelRespawnPos(level, column.getX(), column.getZ());
            helper.assertTrue(vanilla == null, "vanilla's check of the empty column gave the spot " + vanilla);
        } catch (RuntimeException | Error e) {
            RandomSpawn.endNoGroundForTesting();
            throw e;
        } finally {
            Settings.get().randomSpawn = randomBefore;
            VoidGraveGameTests.putBack(level, before);
        }
        helper.runAfterDelay(1, () -> assertRandomSpawn(helper, level, false, "a tick later"));
        helper.runAfterDelay(NO_GROUND_WAIT + 1, () -> {
            assertRandomSpawn(helper, level, true, "once the while is over");
            helper.succeed();
        });
    }

    private static void assertRandomSpawn(GameTestHelper helper, ServerLevel level, boolean on, String when) {
        boolean randomThen = Settings.get().randomSpawn;
        try {
            Settings.get().randomSpawn = true;
            helper.assertValueEqual(RandomSpawn.appliesTo(level), on, "random spawn answers " + when);
        } catch (RuntimeException | Error e) {
            // Failed, so the rest of the while never runs out here: other batches need random spawn
            RandomSpawn.endNoGroundForTesting();
            throw e;
        } finally {
            Settings.get().randomSpawn = randomThen;
        }
    }
}
