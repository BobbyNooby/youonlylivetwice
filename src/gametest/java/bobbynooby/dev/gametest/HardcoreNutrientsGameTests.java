package bobbynooby.dev.gametest;

import bobbynooby.dev.config.HardcoreConfig;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.Yolt;
import bobbynooby.dev.gametest.mixin.FoodDataAccessor;
import bobbynooby.dev.hardcore.HardcoreState;
import bobbynooby.dev.hardcore.HardcoreStates;
import bobbynooby.dev.hardcore.Legs;
import bobbynooby.dev.hardcore.Slowdowns;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Nutrients stay on through each test's environment, which puts the switch back when the batch ends. Eating is the
// server's own end of an item use, so no test waits out the eating time.
public class HardcoreNutrientsGameTests {
    // Legs on as well, for the leg healing that low protein slows
    private static final String NUTRIENTS = "you-only-live-twice-gametest:hardcore_nutrients";
    // A death turns the lockout and graves off, so the dead player is neither kicked nor leaves a grave behind
    private static final String NUTRIENTS_DEATH = "you-only-live-twice-gametest:hardcore_nutrients_death";
    private static final BlockPos AT = new BlockPos(1, 1, 1);
    private static final BlockPos CAKE_AT = new BlockPos(2, 1, 1);
    private static final double CLOSE = 1e-9;

    @GameTest(environment = NUTRIENTS)
    public void nut2EatingRaisesTheRightGroups(GameTestHelper helper) {
        ServerPlayer player = join(helper, "NutEater");
        try {
            levels(player, 0.6, 0.6, 0.6);
            eat(helper, player, Items.COOKED_BEEF);
            assertLevel(helper, HardcoreStates.read(player.getUUID()).protein, 0.84, "protein after a cooked beef");
            assertLevel(helper, HardcoreStates.read(player.getUUID()).vitamins, 0.6, "vitamins after a cooked beef");
            eat(helper, player, Items.APPLE);
            assertLevel(helper, HardcoreStates.read(player.getUUID()).vitamins, 0.72, "vitamins after an apple");
            assertLevel(helper, HardcoreStates.read(player.getUUID()).protein, 0.84, "protein after an apple");
        } finally {
            leave(player);
        }
        helper.succeed();
    }

    // A slice is 2 nutrition, all sugar
    @GameTest(environment = NUTRIENTS)
    public void nut2CakeCounts(GameTestHelper helper) {
        ServerPlayer player = join(helper, "NutCake");
        try {
            levels(player, 0.6, 0.6, 0.6);
            // Cake can only be eaten when hungry
            feed(player, 10, 0.0F);
            helper.setBlock(CAKE_AT, Blocks.CAKE);
            BlockPos cake = helper.absolutePos(CAKE_AT);
            helper.getLevel().getBlockState(cake).useWithoutItem(helper.getLevel(), player,
                    new BlockHitResult(Vec3.atCenterOf(cake), Direction.UP, cake, false));
            helper.assertValueEqual(helper.getBlockState(CAKE_AT).getValue(CakeBlock.BITES), 1, "bites taken from the cake");
            HardcoreState state = HardcoreStates.read(player.getUUID());
            assertLevel(helper, state.sugar, 0.66, "sugar after a slice of cake");
            assertLevel(helper, state.protein, 0.6, "protein after a slice of cake");
        } finally {
            leave(player);
        }
        helper.succeed();
    }

    // The table and the unknown-food rule change on the live config for this tick only
    @GameTest(environment = NUTRIENTS)
    public void nut2UnlistedFoodGivesNothing(GameTestHelper helper) {
        ServerPlayer player = join(helper, "NutUnlisted");
        HardcoreConfig.NutrientsSection cfg = Settings.get().hardcore.nutrients;
        Map<String, Map<String, Double>> foodsBefore = cfg.foods;
        HardcoreConfig.UnknownFood unknownBefore = cfg.unknownFood;
        try {
            levels(player, 0.6, 0.6, 0.6);
            Map<String, Map<String, Double>> withoutApple = new LinkedHashMap<>(foodsBefore);
            withoutApple.remove("minecraft:apple");
            cfg.foods = withoutApple;
            cfg.unknownFood = HardcoreConfig.UnknownFood.NONE;
            eat(helper, player, Items.APPLE);
            assertLevels(helper, player, 0.6, "after an unlisted apple");

            // 4 nutrition × 0.03, a third to each group
            cfg.unknownFood = HardcoreConfig.UnknownFood.EVEN;
            eat(helper, player, Items.APPLE);
            assertLevels(helper, player, 0.64, "after an unlisted apple shared evenly");
        } finally {
            cfg.foods = foodsBefore;
            cfg.unknownFood = unknownBefore;
            leave(player);
        }
        helper.succeed();
    }

    // Vanilla's food tick run by hand, so the test picks what each tick burns. It takes off more than 4.0 at a time.
    @GameTest(environment = NUTRIENTS)
    public void nut3FoodBarDropsDrainSaturationDoesNot(GameTestHelper helper) {
        ServerPlayer player = join(helper, "NutBurner");
        try {
            helper.assertFalse(helper.getLevel().getDifficulty() == Difficulty.PEACEFUL,
                    "the test world is on Peaceful, where the food bar never drops");
            levels(player, 0.6, 0.6, 0.6);
            FoodData food = player.getFoodData();
            feed(player, 10, 1.0F);
            player.causeFoodExhaustion(4.5F);
            food.tick(player);
            helper.assertValueEqual(food.getFoodLevel(), 10, "food after burning saturation");
            helper.assertValueEqual(food.getSaturationLevel(), 0.0F, "saturation after burning 1");
            assertLevels(helper, player, 0.6, "after saturation burned");

            // 0.5 is left over, so the next 4.0 takes it past 4
            player.causeFoodExhaustion(4.0F);
            food.tick(player);
            helper.assertValueEqual(food.getFoodLevel(), 9, "food after burning with no saturation");
            assertLevels(helper, player, 0.59, "after a food point burned");
        } finally {
            leave(player);
        }
        helper.succeed();
    }

    // Low comes in after a first look at the player, so the action bar tells it
    @GameTest(environment = NUTRIENTS, maxTicks = 20)
    public void nut4LowVitaminsSlowMining(GameTestHelper helper) {
        ServerPlayer player = join(helper, "NutMiner");
        Runnable cleanUp = BodyTestKit.once(() -> leave(player));
        boolean waiting = false;
        try {
            levels(player, 0.6, 0.6, 0.6);
            helper.runAfterDelay(2, () -> {
                try {
                    assertMining(helper, player, null, "with vitamins at 0.6");
                    levels(player, 0.6, 0.2, 0.6);
                } catch (RuntimeException e) {
                    cleanUp.run();
                    throw e;
                }
            });
            helper.runAfterDelay(4, () -> {
                try {
                    assertMining(helper, player, -0.15, "with vitamins at 0.2");
                    helper.assertTrue(actionBars(player).contains("You're low on vitamins."), "action bar: " + actionBars(player));
                    levels(player, 0.6, 0.3, 0.6);
                } catch (RuntimeException e) {
                    cleanUp.run();
                    throw e;
                }
            });
            helper.runAfterDelay(6, () -> {
                try {
                    assertMining(helper, player, null, "with vitamins back at 0.3");
                    helper.assertTrue(actionBars(player).contains("Your vitamins are back up."), "action bar: " + actionBars(player));
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

    // Food 18 heals a point every 80 ticks and a broken leg every 80; with low protein both take 160
    @GameTest(environment = NUTRIENTS, maxTicks = 200)
    public void nut4LowProteinSlowsHealing(GameTestHelper helper) {
        ServerPlayer healer = join(helper, "NutHealer");
        ServerPlayer mender = join(helper, "NutMender", AT.offset(1, 0, 0));
        Runnable cleanUp = BodyTestKit.once(() -> {
            Legs.set(mender.getUUID(), false);
            leave(healer);
            leave(mender);
        });
        boolean waiting = false;
        try {
            for (ServerPlayer player : List.of(healer, mender)) {
                levels(player, 0.2, 0.6, 0.6);
                feed(player, 18, 5.0F);
            }
            healer.setHealth(10.0F);
            Legs.set(mender.getUUID(), true);
            helper.runAfterDelay(100, () -> {
                try {
                    helper.assertValueEqual(healer.getHealth(), 10.0F, "health after 100 ticks with low protein");
                } catch (RuntimeException e) {
                    cleanUp.run();
                    throw e;
                }
            });
            helper.runAfterDelay(150, () -> {
                try {
                    assertPoints(helper, mender, 0.0, "after 150 ticks with low protein");
                } catch (RuntimeException e) {
                    cleanUp.run();
                    throw e;
                }
            });
            helper.runAfterDelay(170, () -> {
                try {
                    helper.assertValueEqual(healer.getHealth(), 11.0F, "health after 170 ticks with low protein");
                    assertPoints(helper, mender, 1.0, "after 170 ticks with low protein");
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

    @GameTest(environment = NUTRIENTS, maxTicks = 20)
    public void nut4LowSugarSlowsSprinting(GameTestHelper helper) {
        ServerPlayer player = join(helper, "NutSprinter");
        Runnable cleanUp = BodyTestKit.once(() -> leave(player));
        boolean waiting = false;
        try {
            levels(player, 0.6, 0.6, 0.2);
            player.setSprinting(true);
            helper.runAfterDelay(2, () -> {
                try {
                    assertSlow(helper, player, 0.10, "sprinting with low sugar");
                    player.setSprinting(false);
                } catch (RuntimeException e) {
                    cleanUp.run();
                    throw e;
                }
            });
            helper.runAfterDelay(4, () -> {
                try {
                    assertSlow(helper, player, 0.0, "walking with low sugar");
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

    @GameTest(environment = NUTRIENTS_DEATH)
    public void nutNewLifeStartsAgain(GameTestHelper helper) {
        ServerPlayer player = join(helper, "NutReborn");
        UUID uuid = player.getUUID();
        ServerPlayer back = null;
        try {
            levels(player, 0.1, 0.1, 0.1);
            GraveDeathGameTests.respawnBesideTest(helper, player);
            player.kill(player.level());
            helper.assertTrue(player.isDeadOrDying(), "the player did not die");
            back = TestPlayers.respawn(player, false, Entity.RemovalReason.KILLED);
            HardcoreState state = HardcoreStates.read(uuid);
            assertLevel(helper, state.protein, 0.6, "protein after a new life");
            assertLevel(helper, state.vitamins, 0.6, "vitamins after a new life");
            assertLevel(helper, state.sugar, 0.6, "sugar after a new life");
        } finally {
            leave(back != null ? back : player);
        }
        helper.succeed();
    }

    private static ServerPlayer join(GameTestHelper helper, String name) {
        return join(helper, name, AT);
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

    private static void levels(ServerPlayer player, double protein, double vitamins, double sugar) {
        HardcoreStates.update(player.getUUID(), s -> {
            s.protein = protein;
            s.vitamins = vitamins;
            s.sugar = sugar;
        });
    }

    // What the end of an item use runs on the server, once the eating time is up
    private static void eat(GameTestHelper helper, ServerPlayer player, Item food) {
        new ItemStack(food).finishUsingItem(helper.getLevel(), player);
    }

    private static void feed(ServerPlayer player, int food, float saturation) {
        FoodData data = player.getFoodData();
        data.setFoodLevel(food);
        data.setSaturation(saturation);
        ((FoodDataAccessor) data).yoltSetExhaustion(0.0F);
    }

    private static void assertLevel(GameTestHelper helper, double actual, double expected, String what) {
        helper.assertTrue(Math.abs(actual - expected) < CLOSE, what + " is " + actual + ", not " + expected);
    }

    private static void assertLevels(GameTestHelper helper, ServerPlayer player, double expected, String when) {
        HardcoreState state = HardcoreStates.read(player.getUUID());
        assertLevel(helper, state.protein, expected, "protein " + when);
        assertLevel(helper, state.vitamins, expected, "vitamins " + when);
        assertLevel(helper, state.sugar, expected, "sugar " + when);
    }

    private static void assertMining(GameTestHelper helper, ServerPlayer player, Double expected, String when) {
        AttributeModifier modifier = player.getAttribute(Attributes.BLOCK_BREAK_SPEED).getModifier(Yolt.id("nutrients"));
        if (expected == null) {
            helper.assertTrue(modifier == null, "the mining modifier is on " + when);
            return;
        }
        helper.assertTrue(modifier != null, "no mining modifier " + when);
        helper.assertTrue(Math.abs(modifier.amount() - expected) < CLOSE, "mining modifier " + modifier.amount() + " " + when
                + ", not " + expected);
    }

    private static void assertPoints(GameTestHelper helper, ServerPlayer player, double expected, String when) {
        HardcoreState state = HardcoreStates.read(player.getUUID());
        helper.assertTrue(state.legsBroken, "the legs healed " + when);
        helper.assertTrue(Math.abs(state.legHealPoints - expected) < CLOSE, state.legHealPoints + " heal points " + when
                + ", not " + expected);
    }

    private static void assertSlow(GameTestHelper helper, ServerPlayer player, double expected, String when) {
        double applied = Slowdowns.applied(player);
        helper.assertTrue(Math.abs(applied - expected) < CLOSE, "slowdown " + applied + " " + when + ", not " + expected);
    }

    private static List<String> actionBars(ServerPlayer player) {
        return TestPlayers.sentPackets(player).stream()
                .filter(packet -> packet instanceof ClientboundSystemChatPacket chat && chat.overlay())
                .map(packet -> ((ClientboundSystemChatPacket) packet).content().getString())
                .toList();
    }
}
