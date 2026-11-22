package bobbynooby.dev.hardcore;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.datafixers.util.Either;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.commands.FillBiomeCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.clock.ClockTimeMarkers;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;

import static bobbynooby.dev.hardcore.HardcoreTestKit.NEXT_TO_BED;
import static bobbynooby.dev.hardcore.HardcoreTestKit.assertClose;
import static bobbynooby.dev.hardcore.HardcoreTestKit.freshDay;
import static bobbynooby.dev.hardcore.HardcoreTestKit.naturalZombie;
import static bobbynooby.dev.hardcore.HardcoreTestKit.placeBed;
import static bobbynooby.dev.hardcore.HardcoreTestKit.useBed;

/**
 * Tougher and relentless mobs, the blood moon and the cold with no YOLT on the server. The mob tests swap their
 * settings in and out within one tick; the blood moon and the cold wait across ticks, each in a batch of its own.
 */
public class HardcoreWorldAloneGameTests {
    // The world's clock running, with clear skies and no monster spawning, which would refuse the bed. Its clock_time
    // puts the clock back as the batch ends.
    private static final String NIGHT = "yolt-hardcore-gametest:blood_moon_night_alone";
    private static final String COLD = "yolt-hardcore-gametest:cold_alone";
    private static final BlockPos PLAYER_AT = new BlockPos(1, 0, 1);
    private static final BlockPos MOB_AT = new BlockPos(4, 0, 4);
    // The structure and two blocks round it, so the biome blur at its edges still reads the new biome
    private static final BlockPos AREA_FROM = new BlockPos(-2, -2, -2);
    private static final BlockPos AREA_TO = new BlockPos(9, 8, 9);
    private static final long DAY = 24000L;

    // A hit of 4 from a tougher zombie lands as 5; one from a zombie made with tougher mobs off stays 4. Normal is the
    // difficulty that leaves a player's damage as dealt.
    @GameTest
    public void aTougherMobHitsHarder(GameTestHelper helper) {
        HardcoreFile.Loaded before = YoltHardcore.swapForTesting(HardcoreCommandGameTests.with(c -> c.tougherMobs.enabled = true));
        List<Zombie> zombies = new ArrayList<>();
        List<ServerPlayer> players = new ArrayList<>();
        try {
            helper.assertValueEqual(helper.getLevel().getDifficulty(), Difficulty.NORMAL, "the test world's difficulty");
            Zombie tough = spawned(zombies, naturalZombie(helper, MOB_AT));
            helper.assertTrue(tough.entityTags().contains(MobBoosts.TOUGH), "the zombie was not boosted");
            assertPlayerTakes(helper, players, "ToughHit", tough, 5.0F);

            YoltHardcore.swapForTesting(HardcoreFile.Loaded.defaults());
            Zombie plain = spawned(zombies, naturalZombie(helper, MOB_AT));
            helper.assertFalse(plain.entityTags().contains(MobBoosts.TOUGH), "a zombie was boosted with tougher mobs off");
            assertPlayerTakes(helper, players, "PlainHit", plain, 4.0F);
        } finally {
            try {
                players.forEach(TestPlayers::leave);
                zombies.forEach(Zombie::discard);
            } finally {
                YoltHardcore.swapForTesting(before);
            }
        }
        helper.succeed();
    }

    // A zombie's follow range is one and a half times its base; one made with relentless mobs off keeps the base
    @GameTest
    public void aRelentlessMobFollowsFurther(GameTestHelper helper) {
        HardcoreFile.Loaded before = YoltHardcore.swapForTesting(HardcoreCommandGameTests.with(c -> c.relentlessMobs.enabled = true));
        List<Zombie> zombies = new ArrayList<>();
        try {
            AttributeInstance range = followRange(spawned(zombies, naturalZombie(helper, MOB_AT)));
            helper.assertTrue(range.getModifier(Relentless.RELENTLESS) != null, "no " + Relentless.RELENTLESS + " modifier");
            assertClose(helper, range.getValue(), range.getBaseValue() * 1.5, "a relentless zombie's follow range");

            YoltHardcore.swapForTesting(HardcoreFile.Loaded.defaults());
            AttributeInstance plain = followRange(spawned(zombies, naturalZombie(helper, MOB_AT)));
            assertClose(helper, plain.getValue(), plain.getBaseValue(), "the follow range with relentless mobs off");
        } finally {
            try {
                zombies.forEach(Zombie::discard);
            } finally {
                YoltHardcore.swapForTesting(before);
            }
        }
        helper.succeed();
    }

    // The night rolls, rises and sets on a stand-in clock of the test's, on a day past every one the store has saved,
    // so the roll and the break between nights never stand in its way. The sleeper lies down at the world's own
    // midnight, with that clock running. After more than the 100 ticks a skip needs, that clock has only run on by the
    // ticks slept, so the skip was blocked; canSleepThrough then lets the same sleep skip to morning, so nothing else
    // held it back.
    @GameTest(environment = NIGHT, maxTicks = 400)
    public void aBloodMoonRisesCantBeSleptThroughAndSets(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        HardcoreFile.Loaded settings = HardcoreCommandGameTests.with(c -> {
            c.bloodMoon.enabled = true;
            c.bloodMoon.chance = 1.0;
            c.bloodMoon.minNightsBetween = 0;
        });
        HardcoreConfig.BloodMoonSection config = settings.hardcore().bloodMoon;
        long day = freshDay(config);
        long[] clock = {day * DAY + config.startTick};
        long[] asleepAt = new long[1];
        int[] from = new int[1];
        Held held = new Held(helper, settings);
        try {
            Runnable realClock = BloodMoon.useClockForTesting(() -> clock[0]);
            held.onCleanUp(realClock);
            held.onCleanUp(() -> BloodMoon.stop(server));
            BlockPos head = placeBed(helper);
            ServerPlayer sleeper = held.join("MoonSleeper", GameType.SURVIVAL, NEXT_TO_BED);
            held.onCleanUp(() -> {
                if (sleeper.isSleeping()) {
                    sleeper.stopSleepInBed(true, true);
                }
            });
            from[0] = TestPlayers.mark(sleeper);
            // A test player gets no tick of its own, and only that tick counts how long it has slept
            helper.onEachTick(() -> {
                if (!sleeper.isRemoved() && sleeper.isSleeping()) {
                    sleeper.doTick();
                }
            });
            helper.startSequence()
                    .thenWaitUntil(() -> helper.assertTrue(BloodMoon.active(), "no blood moon at startTick"))
                    .thenExecute(held.step(() -> {
                        helper.assertTrue(TestPlayers.said(sleeper, from[0]).contains(BloodMoonTexts.DEFAULTS.risenLine()),
                                "chat as it rose: " + TestPlayers.said(sleeper, from[0]));
                        List<String> status = TestPlayers.status(helper, sleeper);
                        helper.assertTrue(status.contains("Blood moon tonight"), "/status: " + status);
                        // The test server ticks without sleeping while it waits for a structure's chunks, so the
                        // batch's own midnight can be tens of thousands of ticks gone by now
                        toMidnight(helper);
                        helper.assertTrue(helper.getLevel().isDarkOutside(), "it isn't dark at midnight");
                        useBed(helper, sleeper, head);
                        helper.assertTrue(sleeper.isSleeping(), "the player could not lie down at midnight");
                        asleepAt[0] = clockTicks(helper);
                    }))
                    .thenIdle(120)
                    .thenExecute(held.step(() -> {
                        long moved = clockTicks(helper) - asleepAt[0];
                        helper.assertTrue(Math.abs(moved - 120) <= 5, "the clock moved " + moved + " ticks in 120 of sleep");
                        helper.assertTrue(sleeper.isSleepingLongEnough(), "the player hasn't slept long enough to skip");
                        helper.assertTrue(BloodMoon.active(), "the blood moon ended under the sleeper");
                        config.canSleepThrough = true;
                    }))
                    .thenWaitUntil(() -> helper.assertTrue(clockTicks(helper) - asleepAt[0] > 1000,
                            "the night wasn't skipped with canSleepThrough on"))
                    .thenExecute(held.step(() -> {
                        from[0] = TestPlayers.mark(sleeper);
                        clock[0] = day * DAY + config.endTick;
                    }))
                    .thenWaitUntil(() -> helper.assertFalse(BloodMoon.active(), "still up at endTick"))
                    .thenExecute(held.step(() -> {
                        helper.assertTrue(TestPlayers.said(sleeper, from[0]).contains(BloodMoon.SET_LINE),
                                "chat as it set: " + TestPlayers.said(sleeper, from[0]));
                        helper.assertValueEqual(HardcoreStates.world(BloodMoon.ENDED_DAY), OptionalLong.of(day),
                                "the day saved as ended");
                    }))
                    .thenExecute(held::cleanUp)
                    .thenSucceed();
        } catch (RuntimeException | Error e) {
            held.cleanUpAfter(e);
            throw e;
        }
    }

    // In the snow and under open sky the chill of a player who starts at none rises, while that of one three blocks
    // from a lit campfire, a heat source by the mod's own block tag, falls from half. Two once-a-second steps run
    // before the check. The environment clears the weather, but rain left from before fades by 0.01 a tick, over up to
    // 100 ticks, and would still count as wet, so the test waits it out.
    @GameTest(environment = COLD, maxTicks = 300, skyAccess = true)
    public void theColdChillsAndAFireWarms(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Held held = new Held(helper, c -> c.cold.enabled = true);
        AtomicReference<ServerPlayer> open = new AtomicReference<>();
        AtomicReference<ServerPlayer> byFire = new AtomicReference<>();
        try {
            held.onCleanUp(setBiome(helper, AREA_FROM, AREA_TO, Biomes.SNOWY_PLAINS));
            helper.setBlock(new BlockPos(0, 0, 7), Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true));
            helper.startSequence()
                    .thenWaitUntil(() -> helper.assertTrue(level.getRainLevel(1.0F) == 0, "the last rain hasn't faded"))
                    .thenExecute(held.step(() -> {
                        open.set(chilled(helper, held, "OpenSky", PLAYER_AT, 0.0));
                        byFire.set(chilled(helper, held, "ByTheFire", new BlockPos(3, 0, 7), 0.5));
                    }))
                    .thenIdle(50)
                    .thenExecute(held.step(() -> {
                        helper.assertTrue(chill(open.get()) > 0.0, "chill " + chill(open.get()) + " in the snow");
                        helper.assertTrue(chill(byFire.get()) < 0.5, "chill " + chill(byFire.get()) + " by a lit campfire");
                        assertColdLine(helper, open.get(), "getting colder");
                        assertColdLine(helper, byFire.get(), "warming (fire)");
                    }))
                    .thenExecute(held::cleanUp)
                    .thenSucceed();
        } catch (RuntimeException | Error e) {
            held.cleanUpAfter(e);
            throw e;
        }
    }

    // A player at a spot that is cold, with this chill stored, put back to none at the clean-up
    private static ServerPlayer chilled(GameTestHelper helper, Held held, String base, BlockPos at, double chill) {
        ServerPlayer player = held.join(base, GameType.SURVIVAL, at);
        held.onCleanUp(() -> HardcoreStates.update(player.getUUID(), state -> state.chill = 0.0));
        helper.assertTrue(Cold.coldSpot(helper.getLevel(), player.blockPosition()), "not cold at " + base + "'s feet");
        HardcoreStates.update(player.getUUID(), state -> state.chill = chill);
        return player;
    }

    // Noted for the test to discard as it ends
    private static Zombie spawned(List<Zombie> zombies, Zombie zombie) {
        zombies.add(zombie);
        return zombie;
    }

    // A fresh player for each hit, so no hurt cooldown cuts it
    private static void assertPlayerTakes(GameTestHelper helper, List<ServerPlayer> players, String base, Zombie attacker,
                                          float expected) {
        ServerPlayer player = TestPlayers.join(helper, base, GameType.SURVIVAL, PLAYER_AT);
        players.add(player);
        TestPlayers.markLoaded(player);
        helper.assertTrue(player.hurtServer(helper.getLevel(), helper.getLevel().damageSources().mobAttack(attacker), 4.0F),
                "the hit on " + base + " did not land");
        helper.assertValueEqual(player.getMaxHealth() - player.getHealth(), expected, "damage to " + base);
    }

    private static AttributeInstance followRange(Zombie zombie) {
        AttributeInstance range = zombie.getAttribute(Attributes.FOLLOW_RANGE);
        if (range == null) {
            throw new IllegalStateException("a zombie has no follow range");
        }
        return range;
    }

    private static long clockTicks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        return level.getServer().clockManager().getInstance(clock(level)).totalTicks();
    }

    // The sky's brightness is kept until the next level tick, so it is worked out again here
    private static void toMidnight(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        level.getServer().clockManager().moveToTimeMarker(clock(level), ClockTimeMarkers.MIDNIGHT);
        level.updateSkyBrightness();
    }

    private static Holder<WorldClock> clock(ServerLevel level) {
        return level.dimensionType().defaultClock().orElseThrow();
    }

    /**
     * Sets the biome between two corners relative to the test, and returns what puts back each cell's own biome, since a
     * structure may stand where two biomes meet. Biomes are kept per 4×4×4 cell, so the change reaches up to 3 blocks
     * past the corners, and a fill from a cell's corner to that same corner sets that one cell.
     */
    private static Runnable setBiome(GameTestHelper helper, BlockPos relativeFrom, BlockPos relativeTo, ResourceKey<Biome> biome) {
        ServerLevel level = helper.getLevel();
        BlockPos from = helper.absolutePos(relativeFrom);
        BlockPos to = helper.absolutePos(relativeTo);
        // The test's rotation can swap the corners
        BoundingBox area = BoundingBox.fromCorners(from, to);
        Map<BlockPos, Holder<Biome>> before = new LinkedHashMap<>();
        for (int x = QuartPos.fromBlock(area.minX()); x <= QuartPos.fromBlock(area.maxX()); x++) {
            for (int y = QuartPos.fromBlock(area.minY()); y <= QuartPos.fromBlock(area.maxY()); y++) {
                for (int z = QuartPos.fromBlock(area.minZ()); z <= QuartPos.fromBlock(area.maxZ()); z++) {
                    BlockPos cell = new BlockPos(QuartPos.toBlock(x), QuartPos.toBlock(y), QuartPos.toBlock(z));
                    before.put(cell, level.getNoiseBiome(x, y, z));
                }
            }
        }
        fill(level, from, to, level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(biome));
        return () -> before.forEach((cell, was) -> fill(level, cell, cell, was));
    }

    // As vanilla's own test helper does it: nothing to change is fine
    private static void fill(ServerLevel level, BlockPos from, BlockPos to, Holder<Biome> biome) {
        Either<Integer, CommandSyntaxException> result = FillBiomeCommand.fill(level, from, to, biome);
        result.right().ifPresent(error -> {
            if (error.getType() != FillBiomeCommand.ERROR_NO_BIOMES_SET) {
                throw new IllegalStateException("Could not set the biome: " + error.getMessage());
            }
        });
    }

    private static double chill(ServerPlayer player) {
        return HardcoreStates.read(player.getUUID()).chill;
    }

    // The status line's reason, after "Cold <word> <percent>% · "
    private static void assertColdLine(GameTestHelper helper, ServerPlayer player, String reason) {
        List<String> lines = TestPlayers.status(helper, player).stream().filter(line -> line.startsWith("Cold ")).toList();
        helper.assertTrue(lines.size() == 1 && lines.getFirst().endsWith(" · " + reason),
                player.getPlainTextName() + "'s cold line is " + lines + ", not one ending in '" + reason + "'");
    }
}
