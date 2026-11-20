package bobbynooby.dev.gametest;

import bobbynooby.dev.hardcore.Cold;
import bobbynooby.dev.hardcore.HardcoreStates;
import bobbynooby.dev.hardcore.Slowdowns;
import bobbynooby.dev.hardcore.Status;
import bobbynooby.dev.world.RandomSpawn;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.datafixers.util.Either;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.commands.FillBiomeCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

// Biomes are kept per 4×4×4 cell and read with a blur at the cell edges, so a test's biome reaches a few blocks past its
// structure: each biome layout has an environment of its own. Every environment also holds clear weather.
public class HardcoreColdGameTests {
    private static final String COLD = "you-only-live-twice-gametest:hardcore_cold";
    private static final String HEAT = "you-only-live-twice-gametest:hardcore_cold_heat";
    private static final String FROST = "you-only-live-twice-gametest:hardcore_cold_frost";
    private static final String DAMAGE = "you-only-live-twice-gametest:hardcore_cold_damage";
    // canKill on, and the lockout and graves off, so a death neither kicks the player nor leaves a grave
    private static final String KILL = "you-only-live-twice-gametest:hardcore_cold_kill";
    private static final String SPAWN = "you-only-live-twice-gametest:hardcore_cold_spawn";
    private static final String SPAWN_WATER = "you-only-live-twice-gametest:hardcore_cold_spawn_water";
    // Cold explicitly off
    private static final String OFF = "you-only-live-twice-gametest:hardcore_cold_off";
    // Cold on, for a test in the Nether and one in the End
    private static final String NETHER = "you-only-live-twice-gametest:hardcore_cold_nether";
    private static final String END = "you-only-live-twice-gametest:hardcore_cold_end";
    // Rain, which is snow where it is cold
    private static final String RAIN = "you-only-live-twice-gametest:hardcore_cold_rain";
    // freezeDamage off
    private static final String NO_DAMAGE = "you-only-live-twice-gametest:hardcore_cold_no_damage";
    // As COLD, each for a biome layout, or none, of its own
    private static final String POWDER = "you-only-live-twice-gametest:hardcore_cold_powder";
    private static final String REASONS = "you-only-live-twice-gametest:hardcore_cold_reasons";
    private static final String RELOG = "you-only-live-twice-gametest:hardcore_cold_relog";
    private static final String ABSORPTION = "you-only-live-twice-gametest:hardcore_cold_absorption";
    private static final String FIRE = "you-only-live-twice-gametest:hardcore_cold_fire";
    private static final String RADIUS = "you-only-live-twice-gametest:hardcore_cold_radius";
    // The structure and two blocks round it, so the biome blur at its edges still reads the new biome
    private static final BlockPos AREA_FROM = new BlockPos(-2, -2, -2);
    private static final BlockPos AREA_TO = new BlockPos(9, 8, 9);
    private static final Identifier POWDER_SNOW_SLOW = Identifier.withDefaultNamespace("powder_snow");
    private static final double CLOSE = 1e-9;

    // Five once-a-second steps at 0.01 each, counted from when the players join. The environment clears the weather,
    // but rain left from before fades over up to 80 ticks and would still count as wet, so the test waits it out. The
    // structure's floor is barrier, which lets sky light up through it, so the hut has a stone floor of its own and is
    // closed on all six sides; the players join only once the light engine has darkened the hut. The limit is large
    // because the test server ticks without sleeping while the light engine works in real time, so that wait can take
    // a couple of thousand ticks.
    @GameTest(environment = COLD, maxTicks = 2400, skyAccess = true)
    public void cold2RisesUnderOpenSkyNotUnderARoof(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Scene scene = new Scene(helper);
        BlockPos roofed = new BlockPos(5, 1, 5);
        BlockPos eyes = helper.absolutePos(roofed.above());
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(level.getRainLevel(1.0F) == 0, "the last rain hasn't faded"))
                .thenExecute(() -> guarded(scene, () -> {
                    scene.snow(AREA_FROM, AREA_TO);
                    buildHut(helper, roofed);
                }))
                .thenWaitUntil(() -> helper.assertTrue(level.getBrightness(LightLayer.SKY, eyes) == 0,
                        "sky light " + level.getBrightness(LightLayer.SKY, eyes) + " inside the hut"))
                .thenExecute(() -> guarded(scene, () -> measureOpenAndRoofed(helper, scene, roofed)));
    }

    // Stone all round the column the player stands in: a 3×3 floor, walls two high round the middle, a 3×3 roof
    private static void buildHut(GameTestHelper helper, BlockPos inside) {
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                helper.setBlock(inside.offset(x, -1, z), Blocks.STONE);
                helper.setBlock(inside.offset(x, 2, z), Blocks.STONE);
                if (x != 0 || z != 0) {
                    helper.setBlock(inside.offset(x, 0, z), Blocks.STONE);
                    helper.setBlock(inside.offset(x, 1, z), Blocks.STONE);
                }
            }
        }
    }

    private static void measureOpenAndRoofed(GameTestHelper helper, Scene scene, BlockPos roofed) {
        ServerPlayer open = scene.join("OpenSky", new BlockPos(1, 0, 1));
        ServerPlayer inside = scene.join("Indoors", roofed);
        assertCold(helper, open, true);
        assertCold(helper, inside, true);
        helper.runAfterDelay(100, () -> {
            try {
                assertChill(helper, open, 0.05, "after 100 ticks under open sky");
                assertChill(helper, inside, 0.0, "after 100 ticks under a roof");
            } finally {
                scene.cleanUp.run();
            }
            helper.succeed();
        });
    }

    // A step that throws cleans up first, since the test then ends without its later steps
    private static void guarded(Scene scene, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            scene.cleanUp.run();
            throw e;
        }
    }

    // Each player is more than 4 blocks from the lit campfire unless it is theirs. Two once-a-second steps run before
    // the check.
    @GameTest(environment = HEAT, maxTicks = 70, skyAccess = true)
    public void cold3TorchCampfireAndLeatherWarm(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        AtomicBoolean done = new AtomicBoolean();
        AtomicBoolean leatherChilled = new AtomicBoolean();
        boolean waiting = false;
        try {
            scene.snow(AREA_FROM, AREA_TO);
            helper.setBlock(new BlockPos(0, 0, 7), Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true));
            helper.setBlock(new BlockPos(7, 0, 3), Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false));
            ServerPlayer torch = scene.join("TorchBearer", new BlockPos(5, 0, 1));
            ServerPlayer fire = scene.join("ByTheFire", new BlockPos(3, 0, 7));
            ServerPlayer unlit = scene.join("ByTheAshes", new BlockPos(7, 0, 0));
            ServerPlayer leather = scene.join("InLeather", new BlockPos(5, 0, 5));
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
                    scene.cleanUp.run();
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                scene.cleanUp.run();
            }
        }
    }

    // The first player is never ticked by vanilla, so nothing takes frozen ticks off and the chill alone sets them. The
    // second stands in powder snow with no chill while the test runs vanilla's own tick; the third too, at a full chill,
    // and powder snow's slowdown stays vanilla's for them as well.
    @GameTest(environment = FROST, maxTicks = 30)
    public void cold4FrozenTicksFollowChill(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        int[] earlier = new int[1];
        boolean waiting = false;
        try {
            ServerPlayer chilled = scene.join("Frosty", new BlockPos(1, 0, 1));
            helper.setBlock(new BlockPos(5, 0, 5), Blocks.STONE);
            helper.setBlock(new BlockPos(5, 1, 5), Blocks.POWDER_SNOW);
            helper.setBlock(new BlockPos(5, 2, 5), Blocks.POWDER_SNOW);
            ServerPlayer sinking = scene.join("Sinking", new BlockPos(5, 1, 5));
            helper.setBlock(new BlockPos(2, 0, 6), Blocks.STONE);
            helper.setBlock(new BlockPos(2, 1, 6), Blocks.POWDER_SNOW);
            helper.setBlock(new BlockPos(2, 2, 6), Blocks.POWDER_SNOW);
            ServerPlayer frozenInSnow = scene.join("FrozenInSnow", new BlockPos(2, 1, 6));
            setChill(chilled, 0.5);
            setChill(sinking, 0.0);
            setChill(frozenInSnow, 1.0);
            helper.onEachTick(() -> {
                for (ServerPlayer player : List.of(sinking, frozenInSnow)) {
                    if (!player.isRemoved()) {
                        tickVanilla(player, scene.cleanUp);
                    }
                }
            });
            helper.runAfterDelay(2, () -> {
                try {
                    helper.assertValueEqual(chilled.getTicksFrozen(), 70, "frozen ticks at chill 0.5");
                    setChill(chilled, 1.0);
                    earlier[0] = sinking.getTicksFrozen();
                    helper.assertTrue(earlier[0] > 0, "powder snow left the frozen ticks at 0");
                } catch (RuntimeException e) {
                    scene.cleanUp.run();
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
                    AttributeInstance frozenSpeed = frozenInSnow.getAttribute(Attributes.MOVEMENT_SPEED);
                    helper.assertTrue(frozenSpeed != null && frozenSpeed.getModifier(POWDER_SNOW_SLOW) != null,
                            "vanilla's powder snow slowdown is missing at a full chill");
                } finally {
                    scene.cleanUp.run();
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                scene.cleanUp.run();
            }
        }
    }

    // With no leather the hit is vanilla's own, every 40 of the player's ticks; with two pieces vanilla's stops and
    // cold's own takes over, every 2 seconds. Either way it stops at half a heart. Food 10 means no natural regen.
    @GameTest(environment = DAMAGE, maxTicks = 230, skyAccess = true)
    public void cold5NeverKillsWithCanKillOff(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        boolean waiting = false;
        try {
            scene.snow(AREA_FROM, AREA_TO);
            ServerPlayer bare = scene.join("Barehanded", new BlockPos(1, 0, 1));
            ServerPlayer partly = scene.join("HalfLeather", new BlockPos(5, 0, 5));
            partly.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
            partly.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.LEATHER_BOOTS));
            for (ServerPlayer player : List.of(bare, partly)) {
                assertCold(helper, player, true);
                freeze(player);
            }
            helper.onEachTick(() -> {
                for (ServerPlayer player : List.of(bare, partly)) {
                    if (!player.isRemoved() && player.isAlive()) {
                        tickVanilla(player, scene.cleanUp);
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
                    scene.cleanUp.run();
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                scene.cleanUp.run();
            }
        }
    }

    // canKill on: vanilla's hit takes the half heart left too. The cleanup leaves by uuid, so it finds the respawned
    // player as well.
    @GameTest(environment = KILL, maxTicks = 230, skyAccess = true)
    public void cold5CanKillOnLetsTheColdKill(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        boolean waiting = false;
        try {
            scene.snow(AREA_FROM, AREA_TO);
            ServerPlayer player = scene.join("Frozen", new BlockPos(4, 0, 4));
            assertCold(helper, player, true);
            freeze(player);
            GraveDeathGameTests.respawnBesideTest(helper, player);
            helper.onEachTick(() -> {
                if (!player.isRemoved() && player.isAlive()) {
                    tickVanilla(player, scene.cleanUp);
                }
            });
            helper.runAfterDelay(200, () -> {
                try {
                    helper.assertTrue(player.isDeadOrDying(), "the cold left the player alive with " + player.getHealth()
                            + " health");
                    TestPlayers.respawn(player, false, Entity.RemovalReason.KILLED);
                } finally {
                    scene.cleanUp.run();
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                scene.cleanUp.run();
            }
        }
    }

    // Snow on the low-x side only. Where the edge falls depends on the cells, so the test reads which columns are cold
    // and asks that there are both kinds. A seeded generator gives the same picks every run.
    @GameTest(environment = SPAWN, skyAccess = true)
    public void cold6RandomRespawnSkipsColdSpots(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Scene scene = new Scene(helper);
        try {
            scene.snow(AREA_FROM, new BlockPos(1, 8, 9));
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
            scene.cleanUp.run();
        }
        helper.succeed();
    }

    @GameTest(environment = KILL, skyAccess = true)
    public void cold7DeathResetsChillAndAllColdStillSpawns(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Scene scene = new Scene(helper);
        try {
            scene.snow(AREA_FROM, AREA_TO);
            ServerPlayer player = scene.join("Shiverer", new BlockPos(4, 1, 4));
            setChill(player, 0.8);
            GraveDeathGameTests.respawnBesideTest(helper, player);
            player.kill(level);
            helper.assertTrue(player.isDeadOrDying(), "the player did not die");
            ServerPlayer back = TestPlayers.respawn(player, false, Entity.RemovalReason.KILLED);
            assertChill(helper, back, 0.0, "after a death and a respawn");

            helper.assertValueEqual(grassFloor(helper), 64, "cold columns");
            RandomSpawn.useRandomForTesting(new Random(9));
            BlockPos spot = RandomSpawn.pickIn(level, area(helper));
            helper.assertTrue(spot != null, "an area that is cold everywhere gave no spawn");
            helper.assertTrue(Cold.coldSpot(level, spot), "the spot " + spot + " isn't cold, so the fallback wasn't tested");
        } finally {
            RandomSpawn.useRandomForTesting(null);
            scene.cleanUp.run();
        }
        helper.succeed();
    }

    // Two columns, both in the snow: water in one, grass in the other. Every try in the first search is refused, for
    // cold or for the water, so the normal search runs again and must take the grass, not the surface of the water.
    // Over in the one tick, so the water has no time to flow.
    @GameTest(environment = SPAWN_WATER, skyAccess = true)
    public void cold7AllColdWithAWaterColumnStillSpawnsDry(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos water = new BlockPos(3, 0, 3);
        BlockPos grass = new BlockPos(4, 0, 3);
        Scene scene = new Scene(helper);
        try {
            scene.snow(AREA_FROM, AREA_TO);
            helper.setBlock(water, Blocks.WATER);
            helper.setBlock(grass, Blocks.GRASS_BLOCK);
            BlockPos dry = helper.absolutePos(grass.above());
            helper.assertTrue(Cold.coldSpot(level, dry), "the grass column isn't cold");
            BlockPos low = helper.absolutePos(water);
            RandomSpawn.Area twoColumns = new RandomSpawn.Area(low.getX(), low.getX() + 2, low.getZ(), low.getZ() + 1);
            RandomSpawn.useRandomForTesting(new Random(9));
            for (int pick = 0; pick < 20; pick++) {
                BlockPos spot = RandomSpawn.pickIn(level, twoColumns);
                helper.assertValueEqual(spot, dry, "spawn " + pick);
            }
        } finally {
            RandomSpawn.useRandomForTesting(null);
            helper.setBlock(water, Blocks.AIR);
            scene.cleanUp.run();
        }
        helper.succeed();
    }

    // A stored chill and the snow, with cold off: neither the living tick's mixin, the hub's tick nor the refresh (the
    // same showChill call) gives frost or a slowdown, and the stored chill stays as it was
    @GameTest(environment = OFF, maxTicks = 60, skyAccess = true)
    public void coldOffIsVanilla(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        boolean waiting = false;
        try {
            scene.snow(AREA_FROM, AREA_TO);
            ServerPlayer player = scene.join("Unchilled", new BlockPos(4, 0, 4));
            assertCold(helper, player, true);
            setChill(player, 0.5);
            Cold.showChill(player);
            helper.onEachTick(() -> {
                if (!player.isRemoved()) {
                    tickVanilla(player, scene.cleanUp);
                }
            });
            helper.runAfterDelay(45, () -> {
                try {
                    assertChill(helper, player, 0.5, "with cold off");
                    helper.assertValueEqual(player.getTicksFrozen(), 0, "frozen ticks with cold off");
                    helper.assertValueEqual(Slowdowns.applied(player), 0.0, "slowdown with cold off");
                    helper.assertValueEqual(Slowdowns.raw(player), 0.0, "slowdown parts with cold off");
                } finally {
                    scene.cleanUp.run();
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                scene.cleanUp.run();
            }
        }
    }

    // Only the overworld is ever cold: a Nether spot whose biome would snow, as snowy plains does, still isn't, and a
    // player standing there gains no chill
    @GameTest(environment = NETHER, dimension = "minecraft:the_nether", maxTicks = 100, skyAccess = true)
    public void cold2TheNetherIsNeverCold(GameTestHelper helper) {
        neverColdOutsideTheOverworld(helper, "NetherSnow");
    }

    @GameTest(environment = END, dimension = "minecraft:the_end", maxTicks = 100, skyAccess = true)
    public void cold2TheEndIsNeverCold(GameTestHelper helper) {
        neverColdOutsideTheOverworld(helper, "EndSnow");
    }

    private static void neverColdOutsideTheOverworld(GameTestHelper helper, String name) {
        ServerLevel level = helper.getLevel();
        Scene scene = new Scene(helper);
        boolean waiting = false;
        try {
            helper.assertFalse(level.dimension() == Level.OVERWORLD, "the test is in the overworld");
            scene.snow(AREA_FROM, AREA_TO);
            ServerPlayer player = scene.join(name, new BlockPos(4, 0, 4));
            BlockPos feet = player.blockPosition();
            helper.assertTrue(level.getBiome(feet).value().coldEnoughToSnow(feet, level.getSeaLevel()),
                    "the biome here wouldn't snow, so nothing was tested");
            assertCold(helper, player, false);
            helper.runAfterDelay(65, () -> {
                try {
                    assertChill(helper, player, 0.0, "after 3 seconds in " + level.dimension().identifier());
                    helper.assertValueEqual(player.getTicksFrozen(), 0, "frozen ticks");
                } finally {
                    scene.cleanUp.run();
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                scene.cleanUp.run();
            }
        }
    }

    // Snow falling on the player doubles the chill's rise, and water makes it four times. Rain from the environment fades
    // in over a few ticks, so the test waits for it, then counts three once-a-second steps. The swimmer gets vanilla's
    // own tick, which is what notices the water.
    @GameTest(environment = RAIN, maxTicks = 400, skyAccess = true)
    public void cold2RainAndWaterRaiseItFaster(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Scene scene = new Scene(helper);
        BlockPos pool = new BlockPos(5, 0, 5);
        double[] start = new double[2];
        ServerPlayer[] players = new ServerPlayer[2];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(level.isRaining(), "it isn't raining yet"))
                .thenExecute(() -> guarded(scene, () -> {
                    scene.snow(AREA_FROM, AREA_TO);
                    helper.setBlock(pool, Blocks.WATER);
                    players[0] = scene.join("SnowedOn", new BlockPos(1, 0, 1));
                    players[1] = scene.join("Swimmer", pool);
                    for (ServerPlayer player : players) {
                        assertCold(helper, player, true);
                    }
                    helper.onEachTick(() -> {
                        if (!players[1].isRemoved()) {
                            tickVanilla(players[1], scene.cleanUp);
                        }
                    });
                }))
                .thenIdle(5)
                .thenExecute(() -> guarded(scene, () -> {
                    helper.assertTrue(players[1].isInWater(), "the swimmer isn't in the water");
                    for (int i = 0; i < 2; i++) {
                        start[i] = chill(players[i]);
                    }
                }))
                .thenIdle(60)
                .thenExecute(() -> {
                    try {
                        assertRose(helper, players[0], start[0], 3 * 0.02, "3 seconds in falling snow");
                        assertRose(helper, players[1], start[1], 3 * 0.04, "3 seconds in water");
                    } finally {
                        helper.setBlock(pool, Blocks.AIR);
                        scene.cleanUp.run();
                    }
                })
                .thenSucceed();
    }

    // With freezeDamage off a full chill freezes the screen and never hits: neither vanilla's own hit, for the player
    // with no leather, nor cold's, for the one in two leather pieces. Food 10 means no natural regen to hide a hit.
    @GameTest(environment = NO_DAMAGE, maxTicks = 230, skyAccess = true)
    public void cold5FreezeDamageOffDealsNoHit(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        boolean waiting = false;
        try {
            scene.snow(AREA_FROM, AREA_TO);
            ServerPlayer bare = scene.join("Unbitten", new BlockPos(1, 0, 1));
            ServerPlayer partly = scene.join("HalfUnbitten", new BlockPos(5, 0, 5));
            partly.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
            partly.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.LEATHER_BOOTS));
            for (ServerPlayer player : List.of(bare, partly)) {
                assertCold(helper, player, true);
                setChill(player, 1.0);
                FoodData food = player.getFoodData();
                food.setFoodLevel(10);
                food.setSaturation(0.0F);
            }
            helper.onEachTick(() -> {
                for (ServerPlayer player : List.of(bare, partly)) {
                    if (!player.isRemoved()) {
                        tickVanilla(player, scene.cleanUp);
                    }
                }
            });
            helper.runAfterDelay(200, () -> {
                try {
                    for (ServerPlayer player : List.of(bare, partly)) {
                        helper.assertValueEqual(player.getHealth(), 20.0F, player.getPlainTextName() + "'s health");
                        helper.assertValueEqual(player.getTicksFrozen(), 140, player.getPlainTextName() + "'s frozen ticks");
                    }
                } finally {
                    scene.cleanUp.run();
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                scene.cleanUp.run();
            }
        }
    }

    // The frost a chill shows on solid ground is the chill's alone: vanilla's own tick would take it for powder snow and
    // add powder snow's slowdown, which stays off, leaving the chill's 0.1 at half a chill
    @GameTest(environment = POWDER, maxTicks = 30)
    public void cold4AChilledPlayerGetsNoPowderSnowSlowdown(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        boolean waiting = false;
        try {
            ServerPlayer player = scene.join("FrostedWalker", new BlockPos(1, 0, 1));
            setChill(player, 0.5);
            helper.onEachTick(() -> {
                if (!player.isRemoved()) {
                    tickVanilla(player, scene.cleanUp);
                }
            });
            helper.runAfterDelay(8, () -> {
                try {
                    helper.assertTrue(player.getTicksFrozen() > 0, "the chill shows no frost");
                    helper.assertFalse(player.isInPowderSnow, "the player stands in powder snow");
                    AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
                    helper.assertTrue(speed != null && speed.getModifier(POWDER_SNOW_SLOW) == null,
                            "vanilla's powder snow slowdown is on a player on solid ground");
                    helper.assertTrue(Math.abs(Slowdowns.applied(player) - 0.1) < CLOSE,
                            "slowdown " + Slowdowns.applied(player) + " at half a chill, not 0.1");
                } finally {
                    scene.cleanUp.run();
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                scene.cleanUp.run();
            }
        }
    }

    // The status line says why the chill moves, for the same chill: out in the open it gets colder; a torch, a lit
    // campfire, a roof, a warm biome and a full leather set each warm, and each says which. Snow covers the low-x side;
    // the campfire and the warm player stand on the other. The roofed player joins once the light engine has darkened
    // the hut, as cold2RisesUnderOpenSkyNotUnderARoof explains.
    @GameTest(environment = REASONS, maxTicks = 2400, skyAccess = true)
    public void cold3StatusNamesWhyItMoves(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Scene scene = new Scene(helper);
        BlockPos roofed = new BlockPos(1, 1, 1);
        BlockPos eyes = helper.absolutePos(roofed.above());
        helper.startSequence()
                .thenExecute(() -> guarded(scene, () -> {
                    scene.snow(AREA_FROM, new BlockPos(1, 8, 9));
                    buildHut(helper, roofed);
                    helper.setBlock(new BlockPos(7, 0, 7), Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true));
                }))
                .thenWaitUntil(() -> helper.assertTrue(level.getBrightness(LightLayer.SKY, eyes) == 0,
                        "sky light " + level.getBrightness(LightLayer.SKY, eyes) + " inside the hut"))
                .thenExecute(() -> {
                    try {
                        ServerPlayer open = scene.join("ReasonOpen", new BlockPos(0, 0, 5));
                        ServerPlayer torch = scene.join("ReasonTorch", new BlockPos(0, 0, 7));
                        ServerPlayer leather = scene.join("ReasonLeather", new BlockPos(0, 0, 3));
                        ServerPlayer inside = scene.join("ReasonRoof", roofed);
                        ServerPlayer fire = scene.join("ReasonFire", new BlockPos(7, 0, 5));
                        ServerPlayer warm = scene.join("ReasonWarm", new BlockPos(6, 0, 0));
                        torch.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.TORCH));
                        for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
                            leather.setItemSlot(slot, new ItemStack(switch (slot) {
                                case HEAD -> Items.LEATHER_HELMET;
                                case CHEST -> Items.LEATHER_CHESTPLATE;
                                case LEGS -> Items.LEATHER_LEGGINGS;
                                default -> Items.LEATHER_BOOTS;
                            }));
                        }
                        for (ServerPlayer player : List.of(open, torch, leather, inside)) {
                            assertCold(helper, player, true);
                        }
                        assertCold(helper, warm, false);
                        List<ServerPlayer> all = List.of(open, torch, leather, inside, fire, warm);
                        for (ServerPlayer player : all) {
                            setChill(player, 0.35);
                        }
                        assertReason(helper, open, "getting colder");
                        assertReason(helper, torch, "warming (torch)");
                        assertReason(helper, fire, "warming (fire)");
                        assertReason(helper, inside, "warming (shelter)");
                        assertReason(helper, warm, "warming");
                        assertReason(helper, leather, "warming (leather)");
                    } finally {
                        scene.cleanUp.run();
                    }
                })
                .thenSucceed();
    }

    // Chill is stored, so a relog keeps it, and nothing moves it while the player is away
    @GameTest(environment = RELOG, maxTicks = 100)
    public void cold7ChillOutlastsARelogAndStandsStillOffline(GameTestHelper helper) {
        ServerPlayer player = join(helper, "ChillRelog", new BlockPos(1, 0, 1));
        GameProfile profile = player.getGameProfile();
        Runnable cleanUp = BodyTestKit.once(() -> {
            HardcoreStates.update(profile.id(), state -> state.chill = 0.0);
            leave(player);
        });
        boolean waiting = false;
        try {
            setChill(player, 0.5);
            TestPlayers.leave(player);
            helper.runAfterDelay(60, () -> {
                try {
                    helper.assertTrue(Math.abs(HardcoreStates.read(profile.id()).chill - 0.5) < CLOSE,
                            "chill " + HardcoreStates.read(profile.id()).chill + " after 60 ticks away");
                    ServerPlayer back = TestPlayers.rejoin(helper, profile, GameType.SURVIVAL, new BlockPos(1, 0, 1));
                    assertChill(helper, back, 0.5, "at the rejoin");
                    helper.assertValueEqual(back.getTicksFrozen(), 70, "frozen ticks shown at the rejoin");
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

    // A freeze hit stops above half a heart counting absorption, since absorption soaks a hit before health: at 1 health
    // with Absorption I's 4, vanilla's hits take the absorption away and leave the health at 1
    @GameTest(environment = ABSORPTION, maxTicks = 230, skyAccess = true)
    public void cold5AbsorptionCountsAboveTheFloor(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        boolean waiting = false;
        try {
            scene.snow(AREA_FROM, AREA_TO);
            ServerPlayer player = scene.join("Gilded", new BlockPos(4, 0, 4));
            assertCold(helper, player, true);
            setChill(player, 1.0);
            player.setHealth(1.0F);
            player.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 12_000, 0));
            helper.assertValueEqual(player.getAbsorptionAmount(), 4.0F, "absorption from Absorption I");
            FoodData food = player.getFoodData();
            food.setFoodLevel(10);
            food.setSaturation(0.0F);
            helper.onEachTick(() -> {
                if (!player.isRemoved() && player.isAlive()) {
                    tickVanilla(player, scene.cleanUp);
                }
            });
            helper.runAfterDelay(200, () -> {
                try {
                    helper.assertTrue(player.isAlive(), "the player froze to death");
                    helper.assertTrue(player.getAbsorptionAmount() < 4.0F, "the freeze hits took no absorption");
                    helper.assertValueEqual(player.getHealth(), 1.0F, "health under the absorption");
                } finally {
                    scene.cleanUp.run();
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                scene.cleanUp.run();
            }
        }
    }

    // Fire clears freezing: while the player burns the chill no longer holds the frost up, so vanilla's tick thaws it,
    // and the status line names the fire as what warms them
    @GameTest(environment = FIRE, maxTicks = 120)
    public void cold4FireClearsTheFrost(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        boolean waiting = false;
        try {
            ServerPlayer player = scene.join("Burning", new BlockPos(4, 0, 4));
            setChill(player, 1.0);
            Cold.showChill(player);
            helper.assertValueEqual(player.getTicksFrozen(), 140, "frozen ticks at a full chill");
            player.setRemainingFireTicks(200);
            helper.assertTrue(player.isOnFire(), "the player isn't burning");
            helper.onEachTick(() -> {
                if (!player.isRemoved()) {
                    tickVanilla(player, scene.cleanUp);
                }
            });
            helper.runAfterDelay(80, () -> {
                try {
                    helper.assertTrue(player.isOnFire(), "the fire went out before the check");
                    helper.assertValueEqual(player.getTicksFrozen(), 0, "frozen ticks after 80 ticks on fire");
                    assertReason(helper, player, "warming (fire)");
                } finally {
                    player.clearFire();
                    scene.cleanUp.run();
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                scene.cleanUp.run();
            }
        }
    }

    // Heat reaches 4 blocks and no further; a furnace warms only while lit; a soul lantern in the off hand counts as a
    // held torch. Each is placed, read and taken away again within the one tick, so the lava never flows.
    @GameTest(environment = RADIUS, skyAccess = true)
    public void cold3HeatRadiusEdgeAndUnlitFurnace(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        BlockPos fourOff = new BlockPos(5, 0, 1);
        BlockPos fiveOff = new BlockPos(6, 0, 1);
        BlockPos furnaceAt = new BlockPos(3, 0, 1);
        try {
            scene.snow(AREA_FROM, AREA_TO);
            ServerPlayer player = scene.join("Edgewise", new BlockPos(1, 0, 1));
            assertCold(helper, player, true);
            setChill(player, 0.35);
            assertReason(helper, player, "getting colder");

            helper.setBlock(fourOff, Blocks.LAVA);
            assertReason(helper, player, "warming (fire)");
            helper.setBlock(fourOff, Blocks.AIR);
            helper.setBlock(fiveOff, Blocks.LAVA);
            assertReason(helper, player, "getting colder");
            helper.setBlock(fiveOff, Blocks.AIR);

            helper.setBlock(furnaceAt, Blocks.FURNACE.defaultBlockState().setValue(AbstractFurnaceBlock.LIT, false));
            assertReason(helper, player, "getting colder");
            helper.setBlock(furnaceAt, Blocks.FURNACE.defaultBlockState().setValue(AbstractFurnaceBlock.LIT, true));
            assertReason(helper, player, "warming (fire)");
            helper.setBlock(furnaceAt, Blocks.AIR);

            player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SOUL_LANTERN));
            assertReason(helper, player, "warming (torch)");
        } finally {
            for (BlockPos pos : List.of(fourOff, fiveOff, furnaceAt)) {
                helper.setBlock(pos, Blocks.AIR);
            }
            scene.cleanUp.run();
        }
        helper.succeed();
    }

    // The status line's reason, after "Cold <word> <percent>% · "
    private static void assertReason(GameTestHelper helper, ServerPlayer player, String reason) {
        List<String> lines = Status.report(player).stream().map(Component::getString).filter(line -> line.startsWith("Cold ")).toList();
        helper.assertTrue(lines.size() == 1 && lines.getFirst().endsWith(" · " + reason),
                player.getPlainTextName() + "'s cold line is " + lines + ", not one ending in '" + reason + "'");
    }

    private static void assertRose(GameTestHelper helper, ServerPlayer player, double from, double by, String when) {
        double now = chill(player);
        helper.assertTrue(Math.abs(now - from - by) < 1e-6, "chill went from " + from + " to " + now + " in " + when + ", not up " + by);
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

    // What a test changed, put back once: its players leave with no chill stored, then the biome goes back. It is
    // made before anything changes and also runs just before a timeout, so a throw or a hang can't leak the biome.
    private static final class Scene {
        private final GameTestHelper helper;
        private final List<ServerPlayer> players = new ArrayList<>();
        private Runnable biome = () -> {};
        final Runnable cleanUp;

        Scene(GameTestHelper helper) {
            this.helper = helper;
            this.cleanUp = BodyTestKit.once(this::end);
            helper.runBeforeTestEnd(cleanUp);
        }

        void snow(BlockPos relativeFrom, BlockPos relativeTo) {
            biome = setBiome(helper, relativeFrom, relativeTo, Biomes.SNOWY_PLAINS);
        }

        ServerPlayer join(String name, BlockPos at) {
            ServerPlayer player = HardcoreColdGameTests.join(helper, name, at);
            players.add(player);
            return player;
        }

        private void end() {
            try {
                for (ServerPlayer player : players) {
                    HardcoreStates.update(player.getUUID(), state -> state.chill = 0.0);
                    leave(player);
                }
            } finally {
                biome.run();
            }
        }
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
