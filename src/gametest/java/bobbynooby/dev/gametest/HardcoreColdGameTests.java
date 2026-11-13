package bobbynooby.dev.gametest;

import bobbynooby.dev.hardcore.Cold;
import bobbynooby.dev.hardcore.HardcoreStates;
import bobbynooby.dev.hardcore.Slowdowns;
import bobbynooby.dev.world.RandomSpawn;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.datafixers.util.Either;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.commands.FillBiomeCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

// Cold stays on through each test's environment, which also holds clear weather, so no rain doubles the rate. Biomes
// are kept per 4×4×4 cell and read with a little blur at the cell edges, so a test's biome reaches a few blocks past
// its own structure: each test that changes the biome has an environment to itself, or shares one only with tests that
// want the same biome, and it puts the old biome back when it ends, also when it fails. Test players get no living
// tick from a client, so the tests that need vanilla's freezing drive it.
public class HardcoreColdGameTests {
    private static final String COLD = "you-only-live-twice-gametest:hardcore_cold";
    private static final String HEAT = "you-only-live-twice-gametest:hardcore_cold_heat";
    private static final String FROST = "you-only-live-twice-gametest:hardcore_cold_frost";
    private static final String DAMAGE = "you-only-live-twice-gametest:hardcore_cold_damage";
    // canKill on, and the lockout and graves off, so a death neither kicks the player nor leaves a grave
    private static final String KILL = "you-only-live-twice-gametest:hardcore_cold_kill";
    private static final String SPAWN = "you-only-live-twice-gametest:hardcore_cold_spawn";
    // Cold explicitly off
    private static final String OFF = "you-only-live-twice-gametest:hardcore_cold_off";
    // The structure and two blocks round it, so the biome blur at its edges still reads the new biome
    private static final BlockPos AREA_FROM = new BlockPos(-2, -2, -2);
    private static final BlockPos AREA_TO = new BlockPos(9, 8, 9);
    private static final Identifier POWDER_SNOW_SLOW = Identifier.withDefaultNamespace("powder_snow");
    private static final double CLOSE = 1e-9;

    // Five once-a-second steps at 0.01 each. The player joins as the test starts, so the hub has ticked them 100 times
    // when the check runs.
    @GameTest(environment = COLD, maxTicks = 120, skyAccess = true)
    public void cold2RisesUnderOpenSkyNotUnderARoof(GameTestHelper helper) {
        Runnable biome = setBiome(helper, AREA_FROM, AREA_TO, Biomes.SNOWY_PLAINS);
        BlockPos roofed = new BlockPos(5, 0, 5);
        // A closed stone hut, three blocks a side, with the player inside
        for (int x = 4; x <= 6; x++) {
            for (int z = 4; z <= 6; z++) {
                helper.setBlock(new BlockPos(x, 2, z), Blocks.STONE);
                if (x != roofed.getX() || z != roofed.getZ()) {
                    helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                    helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
                }
            }
        }
        ServerPlayer open = join(helper, "OpenSky", new BlockPos(1, 0, 1));
        ServerPlayer inside = join(helper, "Indoors", roofed);
        Runnable cleanUp = cleanUp(biome, open, inside);
        boolean waiting = false;
        try {
            assertCold(helper, open, true);
            assertCold(helper, inside, true);
            helper.runAfterDelay(100, () -> {
                try {
                    assertChill(helper, open, 0.05, "after 100 ticks under open sky");
                    assertChill(helper, inside, 0.0, "after 100 ticks under a roof");
                } finally {
                    cleanUp.run();
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                cleanUp.run();
            }
        }
    }

    // Each player is more than 4 blocks from the lit campfire unless it is theirs. Two once-a-second steps run before
    // the check.
    @GameTest(environment = HEAT, maxTicks = 70, skyAccess = true)
    public void cold3TorchCampfireAndLeatherWarm(GameTestHelper helper) {
        Runnable biome = setBiome(helper, AREA_FROM, AREA_TO, Biomes.SNOWY_PLAINS);
        helper.setBlock(new BlockPos(0, 0, 7), Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true));
        helper.setBlock(new BlockPos(7, 0, 3), Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false));
        ServerPlayer torch = join(helper, "TorchBearer", new BlockPos(5, 0, 1));
        ServerPlayer fire = join(helper, "ByTheFire", new BlockPos(3, 0, 7));
        ServerPlayer unlit = join(helper, "ByTheAshes", new BlockPos(7, 0, 0));
        ServerPlayer leather = join(helper, "InLeather", new BlockPos(5, 0, 5));
        Runnable cleanUp = cleanUp(biome, torch, fire, unlit, leather);
        AtomicBoolean done = new AtomicBoolean();
        AtomicBoolean leatherChilled = new AtomicBoolean();
        boolean waiting = false;
        try {
            torch.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.TORCH));
            leather.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
            leather.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.LEATHER_CHESTPLATE));
            leather.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.LEATHER_LEGGINGS));
            leather.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.LEATHER_BOOTS));
            for (ServerPlayer player : List.of(torch, fire, unlit)) {
                assertCold(helper, player, true);
                setChill(player, 0.5);
            }
            assertCold(helper, leather, true);
            setChill(leather, 0.0);
            helper.onEachTick(() -> {
                if (!done.get() && chill(leather) > 0.0) {
                    leatherChilled.set(true);
                }
            });
            helper.runAfterDelay(50, () -> {
                try {
                    helper.assertTrue(chill(torch) < 0.5, "chill " + chill(torch) + " while holding a torch in the snow");
                    helper.assertTrue(chill(fire) < 0.5, "chill " + chill(fire) + " three blocks from a lit campfire");
                    helper.assertTrue(chill(unlit) > 0.5, "chill " + chill(unlit) + " three blocks from an unlit campfire");
                    helper.assertFalse(leatherChilled.get(), "a full leather set let the chill rise");
                } finally {
                    done.set(true);
                    cleanUp.run();
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                cleanUp.run();
            }
        }
    }

    // The first player is never ticked by vanilla, so nothing takes frozen ticks off and the chill alone sets them. The
    // second stands in powder snow with no chill while the test runs vanilla's own tick.
    @GameTest(environment = FROST, maxTicks = 30)
    public void cold4FrozenTicksFollowChill(GameTestHelper helper) {
        ServerPlayer chilled = join(helper, "Frosty", new BlockPos(1, 0, 1));
        helper.setBlock(new BlockPos(5, 0, 5), Blocks.STONE);
        helper.setBlock(new BlockPos(5, 1, 5), Blocks.POWDER_SNOW);
        helper.setBlock(new BlockPos(5, 2, 5), Blocks.POWDER_SNOW);
        ServerPlayer sinking = join(helper, "Sinking", new BlockPos(5, 1, 5));
        Runnable cleanUp = cleanUp(() -> {}, chilled, sinking);
        int[] earlier = new int[1];
        boolean waiting = false;
        try {
            setChill(chilled, 0.5);
            setChill(sinking, 0.0);
            helper.onEachTick(() -> {
                if (!sinking.isRemoved()) {
                    tickVanilla(sinking, cleanUp);
                }
            });
            helper.runAfterDelay(2, () -> {
                try {
                    helper.assertValueEqual(chilled.getTicksFrozen(), 70, "frozen ticks at chill 0.5");
                    setChill(chilled, 1.0);
                    earlier[0] = sinking.getTicksFrozen();
                    helper.assertTrue(earlier[0] > 0, "powder snow left the frozen ticks at 0");
                } catch (RuntimeException e) {
                    cleanUp.run();
                    throw e;
                }
            });
            helper.runAfterDelay(8, () -> {
                try {
                    helper.assertValueEqual(chilled.getTicksFrozen(), 140, "frozen ticks at chill 1.0");
                    helper.assertTrue(sinking.getTicksFrozen() > earlier[0],
                            "powder snow's frozen ticks went from " + earlier[0] + " to " + sinking.getTicksFrozen());
                    assertChill(helper, sinking, 0.0, "in powder snow");
                    AttributeInstance speed = sinking.getAttribute(Attributes.MOVEMENT_SPEED);
                    helper.assertTrue(speed != null && speed.getModifier(POWDER_SNOW_SLOW) != null,
                            "vanilla's powder snow slowdown is missing");
                } finally {
                    cleanUp.run();
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                cleanUp.run();
            }
        }
    }

    // With no leather the hit is vanilla's own, every 40 of the player's ticks; with two pieces vanilla's stops and
    // cold's own takes over, every 2 seconds. Either way it stops at half a heart. Food 10 means no natural regen.
    @GameTest(environment = DAMAGE, maxTicks = 230, skyAccess = true)
    public void cold5NeverKillsWithCanKillOff(GameTestHelper helper) {
        Runnable biome = setBiome(helper, AREA_FROM, AREA_TO, Biomes.SNOWY_PLAINS);
        ServerPlayer bare = join(helper, "Barehanded", new BlockPos(1, 0, 1));
        ServerPlayer partly = join(helper, "HalfLeather", new BlockPos(5, 0, 5));
        Runnable cleanUp = cleanUp(biome, bare, partly);
        boolean waiting = false;
        try {
            partly.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
            partly.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.LEATHER_BOOTS));
            for (ServerPlayer player : List.of(bare, partly)) {
                assertCold(helper, player, true);
                freeze(player);
            }
            helper.onEachTick(() -> {
                for (ServerPlayer player : List.of(bare, partly)) {
                    if (!player.isRemoved() && player.isAlive()) {
                        tickVanilla(player, cleanUp);
                    }
                }
            });
            helper.runAfterDelay(200, () -> {
                try {
                    for (ServerPlayer player : List.of(bare, partly)) {
                        helper.assertTrue(player.isAlive(), player.getPlainTextName() + " froze to death");
                        helper.assertTrue(Math.abs(player.getHealth() - 1.0F) < 1e-6,
                                player.getPlainTextName() + " has " + player.getHealth() + " health, not 1.0");
                    }
                } finally {
                    cleanUp.run();
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                cleanUp.run();
            }
        }
    }

    // canKill on: vanilla's hit takes the half heart left too
    @GameTest(environment = KILL, maxTicks = 230, skyAccess = true)
    public void cold5CanKillOnLetsTheColdKill(GameTestHelper helper) {
        Runnable biome = setBiome(helper, AREA_FROM, AREA_TO, Biomes.SNOWY_PLAINS);
        ServerPlayer player = join(helper, "Frozen", new BlockPos(4, 0, 4));
        ServerPlayer[] online = {player};
        Runnable cleanUp = BodyTestKit.once(() -> {
            biome.run();
            HardcoreStates.update(player.getUUID(), state -> state.chill = 0.0);
            leave(online[0]);
        });
        boolean waiting = false;
        try {
            assertCold(helper, player, true);
            freeze(player);
            GraveDeathGameTests.respawnBesideTest(helper, player);
            helper.onEachTick(() -> {
                if (!player.isRemoved() && player.isAlive()) {
                    tickVanilla(player, cleanUp);
                }
            });
            helper.runAfterDelay(200, () -> {
                try {
                    helper.assertTrue(player.isDeadOrDying(), "the cold left the player alive with " + player.getHealth()
                            + " health");
                    online[0] = TestPlayers.respawn(player, false, Entity.RemovalReason.KILLED);
                } finally {
                    cleanUp.run();
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                cleanUp.run();
            }
        }
    }

    // Snow on the low-x side only. Where the edge falls depends on the cells, so the test reads which columns are cold
    // and asks that there are both kinds. A seeded generator gives the same picks every run.
    @GameTest(environment = SPAWN, skyAccess = true)
    public void cold6RandomRespawnSkipsColdSpots(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Runnable biome = setBiome(helper, AREA_FROM, new BlockPos(1, 8, 9), Biomes.SNOWY_PLAINS);
        try {
            int cold = grassFloor(helper);
            helper.assertTrue(cold > 0 && cold < 64, cold + " of the 64 columns are cold, so the area isn't half and half");
            RandomSpawn.useRandomForTesting(new Random(9));
            RandomSpawn.Area area = area(helper);
            for (int pick = 0; pick < 50; pick++) {
                BlockPos spot = RandomSpawn.pickIn(level, area);
                helper.assertTrue(spot != null, "pick " + pick + " found no spot");
                helper.assertFalse(Cold.coldSpot(level, spot), "pick " + pick + " landed in the snow");
            }
        } finally {
            RandomSpawn.useRandomForTesting(null);
            biome.run();
        }
        helper.succeed();
    }

    @GameTest(environment = KILL, skyAccess = true)
    public void cold7DeathResetsChillAndAllColdStillSpawns(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Runnable biome = setBiome(helper, AREA_FROM, AREA_TO, Biomes.SNOWY_PLAINS);
        ServerPlayer player = join(helper, "Shiverer", new BlockPos(4, 1, 4));
        ServerPlayer back = null;
        try {
            setChill(player, 0.8);
            GraveDeathGameTests.respawnBesideTest(helper, player);
            player.kill(level);
            helper.assertTrue(player.isDeadOrDying(), "the player did not die");
            back = TestPlayers.respawn(player, false, Entity.RemovalReason.KILLED);
            assertChill(helper, back, 0.0, "after a death and a respawn");

            helper.assertValueEqual(grassFloor(helper), 64, "cold columns");
            RandomSpawn.useRandomForTesting(new Random(9));
            BlockPos spot = RandomSpawn.pickIn(level, area(helper));
            helper.assertTrue(spot != null, "an area that is cold everywhere gave no spawn");
            helper.assertTrue(Cold.coldSpot(level, spot), "the spot " + spot + " isn't cold, so the fallback wasn't tested");
        } finally {
            RandomSpawn.useRandomForTesting(null);
            HardcoreStates.update(player.getUUID(), state -> state.chill = 0.0);
            leave(back != null ? back : player);
            biome.run();
        }
        helper.succeed();
    }

    @GameTest(environment = OFF, maxTicks = 60, skyAccess = true)
    public void coldOffIsVanilla(GameTestHelper helper) {
        Runnable biome = setBiome(helper, AREA_FROM, AREA_TO, Biomes.SNOWY_PLAINS);
        ServerPlayer player = join(helper, "Unchilled", new BlockPos(4, 0, 4));
        Runnable cleanUp = cleanUp(biome, player);
        boolean waiting = false;
        try {
            assertCold(helper, player, true);
            helper.onEachTick(() -> {
                if (!player.isRemoved()) {
                    tickVanilla(player, cleanUp);
                }
            });
            helper.runAfterDelay(45, () -> {
                try {
                    assertChill(helper, player, 0.0, "with cold off");
                    helper.assertValueEqual(player.getTicksFrozen(), 0, "frozen ticks with cold off");
                    helper.assertValueEqual(Slowdowns.applied(player), 0.0, "slowdown with cold off");
                } finally {
                    cleanUp.run();
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                cleanUp.run();
            }
        }
    }

    /**
     * Sets the biome between two corners relative to the test, and returns what puts back the biome that was at its
     * middle. Changes are whole 4×4×4 cells, so they reach up to 3 blocks past the corners.
     */
    static Runnable setBiome(GameTestHelper helper, BlockPos relativeFrom, BlockPos relativeTo, ResourceKey<Biome> biome) {
        ServerLevel level = helper.getLevel();
        BlockPos from = helper.absolutePos(relativeFrom);
        BlockPos to = helper.absolutePos(relativeTo);
        Holder<Biome> before = level.getNoiseBiome(QuartPos.fromBlock((from.getX() + to.getX()) / 2),
                QuartPos.fromBlock((from.getY() + to.getY()) / 2), QuartPos.fromBlock((from.getZ() + to.getZ()) / 2));
        fill(level, from, to, level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(biome));
        return BodyTestKit.once(() -> fill(level, from, to, before));
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

    static void setChill(ServerPlayer player, double chill) {
        HardcoreStates.update(player.getUUID(), state -> state.chill = chill);
    }

    static double chill(ServerPlayer player) {
        return HardcoreStates.read(player.getUUID()).chill;
    }

    // One tick of vanilla's own player tick, as a client's connection would run it. A failure cleans up first, so the
    // biome and the players don't outlive the test.
    static void tickVanilla(ServerPlayer player, Runnable cleanUp) {
        try {
            player.doTick();
        } catch (RuntimeException e) {
            cleanUp.run();
            throw e;
        }
    }

    // Loaded, so the player can be hurt, which vanilla holds off until a client says the world is in
    private static ServerPlayer join(GameTestHelper helper, String name, BlockPos at) {
        ServerPlayer player = TestPlayers.join(helper, name, GameType.SURVIVAL, at);
        TestPlayers.markLoaded(player);
        return player;
    }

    // By uuid: a player who left already, or respawned as another object, is not left twice
    private static void leave(ServerPlayer player) {
        ServerPlayer online = player.level().getServer().getPlayerList().getPlayer(player.getUUID());
        if (online != null) {
            TestPlayers.leave(online);
        }
    }

    // Players leave with no chill stored, then the biome goes back
    private static Runnable cleanUp(Runnable biome, ServerPlayer... players) {
        List<ServerPlayer> all = new ArrayList<>(List.of(players));
        return BodyTestKit.once(() -> {
            try {
                for (ServerPlayer player : all) {
                    HardcoreStates.update(player.getUUID(), state -> state.chill = 0.0);
                    leave(player);
                }
            } finally {
                biome.run();
            }
        });
    }

    // Full chill and half a heart above the floor, on food that neither heals nor starves
    private static void freeze(ServerPlayer player) {
        setChill(player, 1.0);
        player.setHealth(1.5F);
        FoodData food = player.getFoodData();
        food.setFoodLevel(10);
        food.setSaturation(0.0F);
    }

    // Grass over the whole floor, so every column has a spot one block up; returns how many of those spots are cold
    private static int grassFloor(GameTestHelper helper) {
        int cold = 0;
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.GRASS_BLOCK);
                if (Cold.coldSpot(helper.getLevel(), helper.absolutePos(new BlockPos(x, 1, z)))) {
                    cold++;
                }
            }
        }
        return cold;
    }

    // The structure's own columns, in whole blocks
    private static RandomSpawn.Area area(GameTestHelper helper) {
        BlockPos low = helper.absolutePos(BlockPos.ZERO);
        return new RandomSpawn.Area(low.getX(), low.getX() + 8, low.getZ(), low.getZ() + 8);
    }

    private static void assertCold(GameTestHelper helper, ServerPlayer player, boolean cold) {
        helper.assertValueEqual(Cold.coldSpot(helper.getLevel(), player.blockPosition()), cold,
                "cold at " + player.getPlainTextName() + "'s feet");
    }

    private static void assertChill(GameTestHelper helper, ServerPlayer player, double expected, String when) {
        double chill = chill(player);
        helper.assertTrue(Math.abs(chill - expected) < CLOSE, "chill " + chill + " " + when + ", not " + expected);
    }
}
