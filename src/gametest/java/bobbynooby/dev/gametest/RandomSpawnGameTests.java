package bobbynooby.dev.gametest;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.mixin.PlayerSpawnFinderInvoker;
import bobbynooby.dev.world.RandomSpawn;
import bobbynooby.dev.world.WorldBorders;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.PlayerSpawnFinder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.config.PrepareSpawnTask;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

// The column tests build each column inside the test structure on its barrier floor. skyAccess leaves off the
// barrier ceiling, so the blocks placed here are the top of the column.
public class RandomSpawnGameTests {
    private static final BlockPos GROUND = new BlockPos(3, 0, 3);
    // A search that finds no ground turns random spawn off for a while, so that test runs on its own
    private static final String NO_GROUND = "you-only-live-twice-gametest:random_spawn_no_ground";
    // How long it stays off, written out for the test's maxTicks
    static final int NO_GROUND_WAIT = 100;
    // A first join's search ends in a later tick when its chunk isn't loaded yet. A respawn in another test of the batch
    // waits for its own search with server.managedBlock, which runs the server's pending work, the first join's last step
    // among it. A test that turns random spawn off around its respawn then hands the first join to vanilla's search at
    // the world spawn, so the first join test runs on its own too.
    private static final String FIRST_JOIN = "you-only-live-twice-gametest:random_spawn_first_join";
    // The tests that place a player through vanilla's spawn task change the border or the switch in the tick they place
    // them, and the waits in that tick run the server's pending work, another test's search among it, so each of them
    // runs on its own as well
    private static final String BORDER_SHRUNK = "you-only-live-twice-gametest:random_spawn_border_shrunk";
    private static final String BORDER_MOVED = "you-only-live-twice-gametest:random_spawn_border_moved";
    private static final String BORDER_KEPT = "you-only-live-twice-gametest:random_spawn_border_kept";
    private static final String NO_NEW_SPOT = "you-only-live-twice-gametest:random_spawn_no_new_spot";
    private static final String RETURNING = "you-only-live-twice-gametest:random_spawn_returning";
    private static final String RESPAWN_POINT = "you-only-live-twice-gametest:random_spawn_respawn_point";
    private static final String OFF_FIRST_JOIN = "you-only-live-twice-gametest:random_spawn_off_first_join";
    private static final String ADVENTURE = "you-only-live-twice-gametest:random_spawn_adventure";
    private static final String END_FIRST_JOIN = "you-only-live-twice-gametest:random_spawn_end_first_join";
    // Far enough that the 10,000 block area round a centre moved this far can't hold a spot near the old one
    private static final int MOVE = 20_000;
    // The spawn task loads 7 by 7 chunks round the spot before it is ready, and a random spot's have to be generated,
    // which took one to three seconds for each of these tests alone
    private static final int READY_SECONDS = 60;

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

    // The control for the test above: with random spawn off a death's respawn is vanilla's, round the world spawn
    // within the respawn radius (and the block vanilla may add to stand them on)
    @GameTest(maxTicks = 200)
    public void randomSpawnOffRespawnsByTheWorldSpawn(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel overworld = server.overworld();
        ServerPlayer player = TestPlayers.join(helper, "NearSpawn", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        ServerPlayer current = player;
        boolean before = Settings.get().randomSpawn;
        try {
            Settings.get().randomSpawn = false;
            helper.assertTrue(player.getRespawnConfig() == null, "the new player has a respawn point");
            current = TestPlayers.respawn(player, false, Entity.RemovalReason.KILLED);
            BlockPos feet = current.blockPosition();
            helper.assertTrue(current.level() == overworld, "respawned in " + current.level().dimension().identifier());
            BlockPos spawn = overworld.getRespawnData().pos();
            int radius = overworld.getGameRules().get(GameRules.RESPAWN_RADIUS);
            helper.assertTrue(Math.max(Math.abs(feet.getX() - spawn.getX()), Math.abs(feet.getZ() - spawn.getZ())) <= radius + 1,
                    "respawned at " + feet.toShortString() + ", outside the respawn radius round the world spawn " + spawn.toShortString());
        } finally {
            Settings.get().randomSpawn = before;
            TestPlayers.leave(current);
        }
        helper.succeed();
    }

    // The only column the search may pick is water on stone, which no try accepts. After the last try the spawn is the
    // block above the water's surface, as the log says, so there is always a spawn where there is ground.
    @GameTest(skyAccess = true)
    public void everyColumnUnsafeFallsBackAboveTheSurface(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(GROUND, Blocks.STONE);
        helper.setBlock(GROUND.above(), Blocks.WATER);
        BlockPos column = helper.absolutePos(GROUND);
        BlockPos above = helper.absolutePos(GROUND.above(2));
        RandomSpawn.Area only = new RandomSpawn.Area(column.getX(), column.getX() + 1, column.getZ(), column.getZ() + 1);
        boolean randomBefore = Settings.get().randomSpawn;
        BlockPos[] spot = new BlockPos[1];
        try {
            Settings.get().randomSpawn = true;
            List<String> log = OfflineGameTests.yoltLog(() -> spot[0] = RandomSpawn.pickIn(level, only));
            helper.assertValueEqual(spot[0], above, "the spot after every try failed");
            helper.assertValueEqual(log, List.of("No safe random spawn found in 50 columns, using " + above.toShortString()), "log");
        } finally {
            Settings.get().randomSpawn = randomBefore;
        }
        helper.succeed();
    }

    // A first join's spot comes from the search vanilla runs for a new player, which the hook answers with a random
    // column: inside the border and the configured diameter, on a full top face, and far from the world spawn, where
    // vanilla would put them. The search loads its chunks over the next ticks.
    @GameTest(environment = FIRST_JOIN, maxTicks = 200)
    public void firstJoinSpotIsRandom(GameTestHelper helper) {
        ServerLevel overworld = helper.getLevel().getServer().overworld();
        boolean before = Settings.get().randomSpawn;
        Runnable putBack = BodyTestKit.once(() -> Settings.get().randomSpawn = before);
        helper.runBeforeTestEnd(putBack);
        Settings.get().randomSpawn = true;
        BlockPos spawn = overworld.getRespawnData().pos();
        CompletableFuture<Vec3> search = PlayerSpawnFinder.findSpawn(overworld, spawn);
        helper.succeedWhen(() -> {
            helper.assertTrue(search.isDone(), "the search for a first join's spot hasn't ended");
            putBack.run();
            BlockPos feet = BlockPos.containing(search.join());
            WorldBorder border = overworld.getWorldBorder();
            double half = Math.min(border.getSize(), Settings.get().worldBorder.diameter) / 2.0;
            helper.assertTrue(Math.abs(feet.getX() + 0.5 - border.getCenterX()) <= half
                            && Math.abs(feet.getZ() + 0.5 - border.getCenterZ()) <= half,
                    "first join at " + feet.toShortString() + ", more than " + half + " blocks from the border's centre");
            BlockPos below = feet.below();
            helper.assertTrue(Block.isFaceFull(overworld.getBlockState(below).getCollisionShape(overworld, below), Direction.UP),
                    "first join at " + feet.toShortString() + " over " + overworld.getBlockState(below) + ", which has no full top face");
            int radius = overworld.getGameRules().get(GameRules.RESPAWN_RADIUS);
            helper.assertTrue(Math.max(Math.abs(feet.getX() - spawn.getX()), Math.abs(feet.getZ() - spawn.getZ())) > radius + 1,
                    "first join at " + feet.toShortString() + ", where vanilla puts a player round the world spawn " + spawn.toShortString());
        });
    }

    // The border shrinks round its centre between the pick and the placement, as a /yolt border or a vanilla
    // /worldborder can while the player joins, until the picked spot's block is outside it. The player is placed inside
    // the new border on a full block instead, and the log names both spots. A seeded generator gives the same first spot
    // every run.
    @GameTest(environment = BORDER_SHRUNK)
    public void firstJoinMovesInsideABorderThatShrank(GameTestHelper helper) {
        ServerLevel overworld = helper.getLevel().getServer().overworld();
        RandomSpawn.useRandomForTesting(new Random(9));
        try {
            firstJoin(helper, "Shrunk", true, held -> {
                WorldBorder border = overworld.getWorldBorder();
                helper.assertTrue(insideArea(border, held), "the picked spot " + held + " is outside the area random spawn picks from");
                border.setSize(farthestEdge(border, held));
                helper.assertFalse(insideArea(border, held), "the shrunk border still holds the picked spot " + held);
            }, (player, held, log) -> {
                assertOnGroundInside(helper, overworld, player);
                helper.assertValueEqual(log, List.of(moved(player, held)), "log");
            });
        } finally {
            RandomSpawn.useRandomForTesting(null);
        }
    }

    // The border's centre moves between the pick and the placement. YOLT's border is on for the command, as on a real
    // server, so the border is the area random spawn picks from, and a /yolt center this far off leaves the picked spot
    // outside it. The joining player isn't online, so the command can't name them. confirm, because the test structures
    // and anything left in them sit millions of blocks out, far outside a 10,000 block border.
    @GameTest(environment = BORDER_MOVED)
    public void firstJoinMovesInsideABorderThatMoved(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel overworld = server.overworld();
        firstJoin(helper, "Moved", true, held -> {
            YoltConfig.WorldBorderSection section = Settings.get().worldBorder;
            boolean enabledBefore = section.enabled;
            section.enabled = true;
            try {
                WorldBorders.apply(server);
                WorldBorder border = overworld.getWorldBorder();
                helper.assertValueEqual(border.getSize(), (double) section.diameter, "overworld border size");
                helper.assertTrue(insideArea(border, held), "the picked spot " + held + " is outside the border");
                List<String> output = CommandGameTests.run(server, "yolt center " + (int) (border.getCenterX() + MOVE) + " "
                        + (int) border.getCenterZ() + " confirm");
                helper.assertTrue(output.size() == 1 && output.get(0).startsWith("World border center set to "),
                        "command output: " + output);
                helper.assertFalse(insideArea(border, held), "the moved border still holds the picked spot " + held);
            } finally {
                section.enabled = enabledBefore;
            }
        }, (player, held, log) -> {
            assertOnGroundInside(helper, overworld, player);
            helper.assertValueEqual(log, List.of(moved(player, held)), "log");
        });
    }

    // The control for the two above: with the border as it was, the player is placed at the very spot the search
    // picked, and nothing is logged
    @GameTest(environment = BORDER_KEPT)
    public void firstJoinKeepsItsSpotWhenTheBorderStays(GameTestHelper helper) {
        ServerLevel overworld = helper.getLevel().getServer().overworld();
        firstJoin(helper, "Kept", true,
                held -> helper.assertTrue(insideArea(overworld.getWorldBorder(), held),
                        "the picked spot " + held + " is outside the area random spawn picks from"),
                (player, held, log) -> {
                    helper.assertValueEqual(player.position(), held, "where the player was placed");
                    helper.assertValueEqual(log, List.of(), "log");
                });
    }

    // The border shrinks to one column with no ground in it, as on a void map, so the new search finds nothing. The
    // player is placed where vanilla puts them, at the spot the task holds, and the log says so after the search's own
    // line. That search leaves random spawn off for a while, which ends with the test.
    @GameTest(environment = NO_NEW_SPOT)
    public void firstJoinStaysPutWhenNoNewSpotIsFound(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos column = helper.absolutePos(GROUND);
        Map<BlockPos, BlockState> dug = new HashMap<>();
        try {
            firstJoin(helper, "NoSpot", true, held -> {
                VoidGraveGameTests.dig(level, column.atY(level.getMaxY() + 1), dug);
                WorldBorder border = level.getWorldBorder();
                border.setCenter(column.getX() + 0.5, column.getZ() + 0.5);
                border.setSize(1);
                helper.assertFalse(insideArea(border, held), "the one column border holds the picked spot " + held);
            }, (player, held, log) -> {
                helper.assertValueEqual(player.position(), held, "where the player was placed");
                helper.assertValueEqual(log, List.of(
                        "No safe random spawn found in 50 columns, and the last one has no ground, so vanilla picks the spawn",
                        player.nameAndId().name() + "'s first spawn at " + BlockPos.containing(held).toShortString()
                                + " is outside the random spawn area now, and no new spot was found, so they join there"), "log");
            });
        } finally {
            VoidGraveGameTests.putBack(level, dug);
            RandomSpawn.endNoGroundForTesting();
        }
    }

    // A returning player is placed at the position they saved, even one a moved border left outside the area random
    // spawn picks from: only a first join's spot comes from the search. The save comes from a join and a leave, as every
    // test player's does.
    @GameTest(environment = RETURNING)
    public void returningPlayerKeepsTheSpotTheySaved(GameTestHelper helper) {
        WorldBorder border = helper.getLevel().getServer().overworld().getWorldBorder();
        GameProfile profile = TestPlayers.profile("Returning");
        ServerPlayer first = TestPlayers.join(helper, profile, GameType.SURVIVAL, new BlockPos(1, 1, 1));
        Vec3 saved = first.position();
        TestPlayers.leave(first);
        joinThroughSpawnTask(helper, profile, true, true, held -> {
            helper.assertValueEqual(held, saved, "the spot the task holds");
            border.setCenter(held.x + MOVE, held.z);
            helper.assertFalse(insideArea(border, held), "the moved border's area still holds the saved spot " + held);
        }, (player, held, log) -> {
            helper.assertValueEqual(player.position(), saved, "where the returning player was placed");
            helper.assertValueEqual(log, List.of(), "log");
        });
    }

    // A respawn picks and places in one call, so the check a first join gets at its placement is not on its way: a
    // player whose respawn point is outside the area random spawn picks from comes back on it
    @GameTest(environment = RESPAWN_POINT)
    public void respawnPointOutsideThePickAreaIsKept(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        WorldBorder border = server.overworld().getWorldBorder();
        ServerPlayer player = TestPlayers.join(helper, "PointBack", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        ServerPlayer current = player;
        boolean before = Settings.get().randomSpawn;
        Map<ServerLevel, double[]> borders = CommandGameTests.saveBorders(server);
        try {
            Settings.get().randomSpawn = true;
            BlockPos point = helper.absolutePos(new BlockPos(7, 1, 7));
            player.setRespawnPosition(new ServerPlayer.RespawnConfig(
                    LevelData.RespawnData.of(helper.getLevel().dimension(), point, 0.0F, 0.0F), true), false);
            helper.assertTrue(player.getRespawnConfig() != null, "the respawn point wasn't set");
            border.setCenter(point.getX() + MOVE, point.getZ());
            helper.assertFalse(insideArea(border, Vec3.atBottomCenterOf(point)),
                    "the moved border's area still holds the respawn point " + point.toShortString());
            current = TestPlayers.respawn(player, false, Entity.RemovalReason.KILLED);
            helper.assertValueEqual(current.blockPosition(), point, "where the player respawned");
        } finally {
            CommandGameTests.restoreBorders(borders);
            Settings.get().randomSpawn = before;
            TestPlayers.leave(current);
        }
        helper.succeed();
    }

    // With random spawn off a first join's spot is vanilla's, round the world spawn, and it stays so even when a moved
    // border leaves it outside the area random spawn would pick from
    @GameTest(environment = OFF_FIRST_JOIN)
    public void firstJoinWithRandomSpawnOffKeepsVanillasSpot(GameTestHelper helper) {
        ServerLevel overworld = helper.getLevel().getServer().overworld();
        firstJoin(helper, "OffJoin", false, held -> {
            BlockPos spawn = overworld.getRespawnData().pos();
            int radius = overworld.getGameRules().get(GameRules.RESPAWN_RADIUS);
            BlockPos feet = BlockPos.containing(held);
            helper.assertTrue(Math.max(Math.abs(feet.getX() - spawn.getX()), Math.abs(feet.getZ() - spawn.getZ())) <= radius + 1,
                    "the search gave " + feet.toShortString() + ", outside the respawn radius round the world spawn " + spawn.toShortString());
            WorldBorder border = overworld.getWorldBorder();
            border.setCenter(held.x + MOVE, held.z);
            helper.assertFalse(insideArea(border, held), "the moved border's area still holds vanilla's spot " + held);
        }, (player, held, log) -> {
            helper.assertValueEqual(player.position(), held, "where the player was placed");
            helper.assertValueEqual(log, List.of(), "log");
        });
    }

    // The Nether and the End keep vanilla's spawning, whatever the switch
    @GameTest
    public void onlyTheOverworldIsRandomised(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        boolean before = Settings.get().randomSpawn;
        try {
            Settings.get().randomSpawn = true;
            helper.assertTrue(RandomSpawn.appliesTo(server.overworld()), "the overworld isn't randomised");
            for (ResourceKey<Level> other : List.of(Level.NETHER, Level.END)) {
                ServerLevel level = server.getLevel(other);
                helper.assertTrue(level != null, "test server has no level " + other);
                helper.assertFalse(RandomSpawn.appliesTo(level), other.identifier() + " is randomised");
            }
        } finally {
            Settings.get().randomSpawn = before;
        }
        helper.succeed();
    }

    // In adventure mode vanilla puts a new player right on the world spawn with no search, so random spawn stays out, and
    // the border commands check the world spawn instead. The check at placement stays out too, even when a moved border
    // leaves that spot outside the area random spawn picks from. Random spawn is on, so only the game mode keeps it out.
    @GameTest(environment = ADVENTURE)
    public void firstJoinInAdventureKeepsTheWorldSpawn(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel overworld = server.overworld();
        GameType modeBefore = server.getWorldData().getGameType();
        server.getWorldData().setGameType(GameType.ADVENTURE);
        try {
            firstJoin(helper, "Adventure", true, held -> {
                helper.assertTrue(RandomSpawn.appliesTo(overworld), "random spawn doesn't answer in the overworld");
                BlockPos spawn = overworld.getRespawnData().pos();
                BlockPos feet = BlockPos.containing(held);
                helper.assertTrue(feet.getX() == spawn.getX() && feet.getZ() == spawn.getZ(),
                        "the adventure join's spot " + feet.toShortString() + " isn't on the world spawn " + spawn.toShortString());
                WorldBorder border = overworld.getWorldBorder();
                border.setCenter(held.x + MOVE, held.z);
                helper.assertFalse(insideArea(border, held), "the moved border's area still holds the world spawn " + held);
            }, (player, held, log) -> {
                helper.assertValueEqual(player.position(), held, "where the player was placed");
                helper.assertValueEqual(log, List.of(), "log");
            });
        } finally {
            server.getWorldData().setGameType(modeBefore);
        }
    }

    // A world spawn in the End sends a first join's search and placement there, where random spawn stays out, so vanilla's
    // search gives the spot. Both the overworld's and the End's borders move away from it, so a check at placement that
    // ran with either dimension's area would move the player. The world spawn's spot is beside the End's main island
    // centre, clear of its exit portal and pillars.
    @GameTest(environment = END_FIRST_JOIN)
    public void firstJoinInTheEndKeepsVanillasSpot(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel end = server.getLevel(Level.END);
        helper.assertTrue(end != null, "test server has no End");
        LevelData.RespawnData spawnBefore = server.getWorldData().overworldData().getRespawnData();
        BlockPos spawn = new BlockPos(15, 64, 0);
        server.setRespawnData(LevelData.RespawnData.of(Level.END, spawn, 0.0F, 0.0F));
        try {
            firstJoin(helper, "EndJoin", true, held -> {
                int radius = end.getGameRules().get(GameRules.RESPAWN_RADIUS);
                BlockPos feet = BlockPos.containing(held);
                helper.assertTrue(Math.max(Math.abs(feet.getX() - spawn.getX()), Math.abs(feet.getZ() - spawn.getZ())) <= radius + 1,
                        "the search gave " + feet.toShortString() + ", outside the respawn radius round the End's world spawn");
                for (ServerLevel level : List.of(server.overworld(), end)) {
                    WorldBorder border = level.getWorldBorder();
                    border.setCenter(held.x + MOVE, held.z);
                    helper.assertFalse(insideArea(border, held),
                            "the moved border's area in " + level.dimension().identifier() + " still holds the spot " + held);
                }
            }, (player, held, log) -> {
                helper.assertTrue(player.level() == end, "placed in " + player.level().dimension().identifier());
                helper.assertValueEqual(player.position(), held, "where the player was placed");
                helper.assertValueEqual(log, List.of(), "log");
            });
        } finally {
            server.setRespawnData(spawnBefore);
        }
    }

    // The gate alone, without a join: firstJoinSpot stays out of the Nether and the End, as the pick does, and a spot there
    // far outside the area random spawn picks from is left as it is. The test above takes the End through the hook.
    @GameTest
    public void firstJoinSpotOutsideTheOverworldIsLeftAlone(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        boolean before = Settings.get().randomSpawn;
        try {
            Settings.get().randomSpawn = true;
            for (ResourceKey<Level> other : List.of(Level.NETHER, Level.END)) {
                ServerLevel level = server.getLevel(other);
                helper.assertTrue(level != null, "test server has no level " + other);
                WorldBorder border = level.getWorldBorder();
                Vec3 far = new Vec3(border.getCenterX() + MOVE + 0.5, 64, border.getCenterZ() + 0.5);
                helper.assertFalse(insideArea(border, far), "the area in " + other.identifier() + " holds " + far);
                Vec3[] spot = new Vec3[1];
                List<String> log = OfflineGameTests.yoltLog(() -> spot[0] = RandomSpawn.firstJoinSpot(level, far, "Elsewhere"));
                helper.assertTrue(spot[0] == far, "a first join's spot in " + other.identifier() + " became " + spot[0]);
                helper.assertValueEqual(log, List.of(), "log in " + other.identifier());
            }
        } finally {
            Settings.get().randomSpawn = before;
        }
        helper.succeed();
    }

    private interface Placed {
        void check(ServerPlayer player, Vec3 held, List<String> log);
    }

    // A new name, with no save, so vanilla's spawn task searches for the spot
    private static void firstJoin(GameTestHelper helper, String name, boolean randomSpawn, Consumer<Vec3> change, Placed placed) {
        joinThroughSpawnTask(helper, TestPlayers.profile(name), false, randomSpawn, change, placed);
    }

    // A login the way a real client's goes: vanilla's spawn task reads the save, or searches for a first join's spot, and
    // loads the chunks round the spot, and the player is placed there once the task is ready. A client's configuration
    // ticks the task once a tick, but the test server ticks as fast as it can, so no count of ticks bounds the wait for
    // new chunks: it took about 3,750 ticks in one of these tests and 24,000 in another. The task is ticked inside a wait
    // on the server thread instead, which runs the chunk work, up to a time limit. Once it is ready, change gets the spot it holds,
    // then the player is placed, and placed gets the player, that spot and what YOLT logged while placing them. The
    // borders, the switch, the task and the player go back in the same tick, however it ends.
    private static void joinThroughSpawnTask(GameTestHelper helper, GameProfile profile, boolean returning, boolean randomSpawn,
                                             Consumer<Vec3> change, Placed placed) {
        MinecraftServer server = helper.getLevel().getServer();
        boolean randomBefore = Settings.get().randomSpawn;
        Map<ServerLevel, double[]> borders = CommandGameTests.saveBorders(server);
        PrepareSpawnTask task = new PrepareSpawnTask(server, new NameAndId(profile));
        AtomicReference<ServerPlayer> player = new AtomicReference<>();
        try {
            helper.assertValueEqual(server.getPlayerList().loadPlayerData(new NameAndId(profile)).isPresent(), returning,
                    "the player has a save");
            Settings.get().randomSpawn = randomSpawn;
            task.start(packet -> {});
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(READY_SECONDS);
            server.managedBlock(() -> task.tick() || System.nanoTime() > deadline);
            helper.assertTrue(task.tick(), "the spawn task wasn't ready after " + READY_SECONDS + " seconds");
            Vec3 held = heldSpot(task);
            change.accept(held);
            List<String> log = OfflineGameTests.yoltLog(() -> player.set(TestPlayers.placeFrom(task, profile)));
            placed.check(player.get(), held, log);
        } finally {
            CommandGameTests.restoreBorders(borders);
            Settings.get().randomSpawn = randomBefore;
            task.close();
            // Looked up by id, since a placement that threw after the player went online hands no player back
            ServerPlayer online = server.getPlayerList().getPlayer(profile.id());
            if (online != null) {
                TestPlayers.leave(online);
            }
        }
        helper.succeed();
    }

    // The spot a ready task places the player at. It is private to vanilla, and 26.3 keeps Mojang's names at runtime, so
    // it is read by name.
    private static Vec3 heldSpot(PrepareSpawnTask task) {
        try {
            Field state = PrepareSpawnTask.class.getDeclaredField("state");
            state.setAccessible(true);
            Object ready = state.get(task);
            Field spot = ready.getClass().getDeclaredField("spawnPosition");
            spot.setAccessible(true);
            return (Vec3) spot.get(ready);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not read the spot the spawn task holds", e);
        }
    }

    // Whether the whole block a spot stands in is inside the border and YOLT's diameter round its centre, worked out
    // here rather than by RandomSpawn's own check. The test server's border has no max-world-size edge to cut it.
    private static boolean insideArea(WorldBorder border, Vec3 spot) {
        double half = Math.min(border.getSize(), Settings.get().worldBorder.diameter) / 2.0;
        int x = Mth.floor(spot.x);
        int z = Mth.floor(spot.z);
        return x >= border.getCenterX() - half && x + 1 <= border.getCenterX() + half
                && z >= border.getCenterZ() - half && z + 1 <= border.getCenterZ() + half;
    }

    // How far the farthest edge of a spot's block is from the border's centre: a border that wide round the same centre
    // reaches only half as far, so it leaves the block out
    private static double farthestEdge(WorldBorder border, Vec3 spot) {
        int x = Mth.floor(spot.x);
        int z = Mth.floor(spot.z);
        return Math.max(Math.max(Math.abs(x - border.getCenterX()), Math.abs(x + 1 - border.getCenterX())),
                Math.max(Math.abs(z - border.getCenterZ()), Math.abs(z + 1 - border.getCenterZ())));
    }

    // Online, in the overworld, inside the border on a block with a full top face, and not in a fluid
    private static void assertOnGroundInside(GameTestHelper helper, ServerLevel overworld, ServerPlayer player) {
        helper.assertTrue(overworld.getServer().getPlayerList().getPlayer(player.getUUID()) == player, "the player isn't online");
        helper.assertTrue(player.level() == overworld, "placed in " + player.level().dimension().identifier());
        BlockPos feet = player.blockPosition();
        WorldBorder border = overworld.getWorldBorder();
        helper.assertTrue(insideArea(border, player.position()), "placed at " + feet.toShortString() + ", outside the border of size "
                + border.getSize() + " round " + border.getCenterX() + ", " + border.getCenterZ());
        BlockPos below = feet.below();
        helper.assertTrue(Block.isFaceFull(overworld.getBlockState(below).getCollisionShape(overworld, below), Direction.UP),
                "placed at " + feet.toShortString() + " over " + overworld.getBlockState(below) + ", which has no full top face");
        helper.assertTrue(overworld.getFluidState(feet).isEmpty() && overworld.getFluidState(feet.above()).isEmpty(),
                "placed at " + feet.toShortString() + " in a fluid");
    }

    // The line YOLT logs when it picks a first join's spot again
    private static String moved(ServerPlayer player, Vec3 held) {
        return "Moved " + player.nameAndId().name() + "'s first spawn from " + BlockPos.containing(held).toShortString() + " to "
                + player.blockPosition().toShortString() + ", since the old spot is outside the random spawn area now";
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
