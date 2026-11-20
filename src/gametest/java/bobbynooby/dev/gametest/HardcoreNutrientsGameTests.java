package bobbynooby.dev.gametest;

import bobbynooby.dev.config.HardcoreConfig;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.Yolt;
import bobbynooby.dev.gametest.mixin.FoodDataAccessor;
import bobbynooby.dev.hardcore.HardcoreState;
import bobbynooby.dev.hardcore.HardcoreStates;
import bobbynooby.dev.hardcore.Legs;
import bobbynooby.dev.hardcore.Nutrients;
import bobbynooby.dev.hardcore.Slowdowns;
import bobbynooby.dev.hardcore.Status;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
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
    // Nutrients on, each in a batch of its own: its test changes the switch or the difficulty across ticks
    private static final String NUTRIENTS_SWITCH = "you-only-live-twice-gametest:hardcore_nutrients_switch";
    private static final String NUTRIENTS_PEACEFUL = "you-only-live-twice-gametest:hardcore_nutrients_peaceful";
    // Low protein stops healing outright
    private static final String NUTRIENTS_NO_HEAL = "you-only-live-twice-gametest:hardcore_nutrients_no_heal";
    private static final BlockPos AT = new BlockPos(1, 1, 1);
    private static final BlockPos CAKE_AT = new BlockPos(2, 1, 1);
    private static final double CLOSE = 1e-9;

    // An empty bar, so the beef's 8 and the apple's 4 all count
    @GameTest(environment = NUTRIENTS)
    public void nut2EatingRaisesTheRightGroups(GameTestHelper helper) {
        ServerPlayer player = join(helper, "NutEater");
        try {
            levels(player, 0.6, 0.6, 0.6);
            feed(player, 0, 0.0F);
            eat(helper, player, Items.COOKED_BEEF);
            assertLevel(helper, HardcoreStates.read(player.getUUID()).protein, 0.84, "protein after a cooked beef");
            assertLevel(helper, HardcoreStates.read(player.getUUID()).vitamins, 0.66, "vitamins after a cooked beef");
            assertLevel(helper, HardcoreStates.read(player.getUUID()).sugar, 0.6, "sugar after a cooked beef");
            eat(helper, player, Items.APPLE);
            assertLevel(helper, HardcoreStates.read(player.getUUID()).vitamins, 0.78, "vitamins after an apple");
            assertLevel(helper, HardcoreStates.read(player.getUUID()).sugar, 0.66, "sugar after an apple");
            assertLevel(helper, HardcoreStates.read(player.getUUID()).protein, 0.84, "protein after an apple");
        } finally {
            leave(player);
        }
        helper.succeed();
    }

    // Only the hunger a food restores counts: a cooked beef at a full bar gives nothing, and at 19 one point's worth
    @GameTest(environment = NUTRIENTS)
    public void nut2OnlyTheHungerRestoredCounts(GameTestHelper helper) {
        ServerPlayer player = join(helper, "NutFull");
        try {
            levels(player, 0.6, 0.6, 0.6);
            feed(player, 20, 5.0F);
            eat(helper, player, Items.COOKED_BEEF);
            assertLevels(helper, player, 0.6, "after a cooked beef at a full bar");

            feed(player, 19, 0.0F);
            eat(helper, player, Items.COOKED_BEEF);
            helper.assertValueEqual(player.getFoodData().getFoodLevel(), 20, "the food bar after a cooked beef at 19");
            assertLevel(helper, HardcoreStates.read(player.getUUID()).protein, 0.63, "protein after a cooked beef at 19");
            assertLevel(helper, HardcoreStates.read(player.getUUID()).vitamins, 0.6075, "vitamins after a cooked beef at 19");
        } finally {
            leave(player);
        }
        helper.succeed();
    }

    // A slice is 2 nutrition: all of it to sugar, half to protein and a quarter to vitamins
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
            assertLevel(helper, state.protein, 0.63, "protein after a slice of cake");
            assertLevel(helper, state.vitamins, 0.615, "vitamins after a slice of cake");
        } finally {
            leave(player);
        }
        helper.succeed();
    }

    // With the bar one short of full, one of the slice's two points fits, and only that one counts
    @GameTest(environment = NUTRIENTS)
    public void nut2CakeAtAnAlmostFullBarCountsHalf(GameTestHelper helper) {
        ServerPlayer player = join(helper, "NutCakeFull");
        try {
            levels(player, 0.6, 0.6, 0.6);
            feed(player, 19, 0.0F);
            helper.setBlock(CAKE_AT, Blocks.CAKE);
            BlockPos cake = helper.absolutePos(CAKE_AT);
            helper.getLevel().getBlockState(cake).useWithoutItem(helper.getLevel(), player,
                    new BlockHitResult(Vec3.atCenterOf(cake), Direction.UP, cake, false));
            helper.assertValueEqual(helper.getBlockState(CAKE_AT).getValue(CakeBlock.BITES), 1, "bites taken from the cake");
            HardcoreState state = HardcoreStates.read(player.getUUID());
            // Half of what nut2CakeCounts gets from the whole slice
            assertLevel(helper, state.sugar, 0.63, "sugar after half a slice of cake");
            assertLevel(helper, state.protein, 0.615, "protein after half a slice of cake");
            assertLevel(helper, state.vitamins, 0.6075, "vitamins after half a slice of cake");
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
            // Room on the bar for both apples, so all 4 of each counts
            feed(player, 0, 0.0F);
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

    // Food 18 heals a point every 80 ticks and a broken leg every 80; with low protein both take 160. A test player's
    // own tick never runs the food data, so the test drives the healer's each tick; the legs heal in the hub's tick.
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
            helper.onEachTick(() -> {
                if (!healer.isRemoved()) {
                    healer.getFoodData().tick(healer);
                }
            });
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

    // The switch is read every tick: off takes the mining slowdown off at once without telling the player anything, and
    // on puts it back without telling them again what they were told before
    @GameTest(environment = NUTRIENTS_SWITCH, maxTicks = 40)
    public void nutrientsSwitchedOffLiveDropsMiningSlowQuietly(GameTestHelper helper) {
        ServerPlayer player = join(helper, "NutSwitched");
        HardcoreConfig.NutrientsSection cfg = Settings.get().hardcore.nutrients;
        boolean before = cfg.enabled;
        Runnable cleanUp = BodyTestKit.once(() -> {
            cfg.enabled = before;
            leave(player);
        });
        boolean waiting = false;
        try {
            levels(player, 0.6, 0.6, 0.6);
            helper.runAfterDelay(2, () -> step(cleanUp, () -> levels(player, 0.6, 0.1, 0.6)));
            helper.runAfterDelay(4, () -> step(cleanUp, () -> {
                assertMining(helper, player, -0.15, "with vitamins at 0.1");
                helper.assertValueEqual(count(actionBars(player), "You're low on vitamins."), 1L, "low lines: " + actionBars(player));
                cfg.enabled = false;
            }));
            helper.runAfterDelay(6, () -> step(cleanUp, () -> {
                assertMining(helper, player, null, "a tick after nutrients went off");
                cfg.enabled = true;
            }));
            helper.runAfterDelay(8, () -> {
                try {
                    assertMining(helper, player, -0.15, "a tick after nutrients came back on");
                    List<String> told = actionBars(player);
                    helper.assertValueEqual(count(told, "You're low on vitamins."), 1L, "low lines after the switch: " + told);
                    helper.assertValueEqual(count(told, "Your vitamins are back up."), 0L, "back-up lines: " + told);
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

    // On Peaceful the levels don't move, but a group that is low already still costs: here the mining slowdown, and the
    // slower healing from low protein
    @GameTest(environment = NUTRIENTS_PEACEFUL, maxTicks = 20)
    public void nut2PeacefulFreezesEatingButLowStillCosts(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = join(helper, "NutPeaceful");
        Difficulty before = helper.getLevel().getDifficulty();
        Runnable cleanUp = BodyTestKit.once(() -> {
            server.setDifficulty(before, true);
            leave(player);
        });
        boolean waiting = false;
        try {
            helper.assertFalse(before == Difficulty.PEACEFUL, "the test world is on Peaceful already");
            server.setDifficulty(Difficulty.PEACEFUL, true);
            levels(player, 0.2, 0.2, 0.6);
            feed(player, 0, 0.0F);
            eat(helper, player, Items.COOKED_BEEF);
            HardcoreState state = HardcoreStates.read(player.getUUID());
            assertLevel(helper, state.protein, 0.2, "protein after a cooked beef on Peaceful");
            assertLevel(helper, state.vitamins, 0.2, "vitamins after a cooked beef on Peaceful");
            helper.assertValueEqual(Nutrients.healingSpeed(player), 0.5, "healing speed with low protein on Peaceful");
            helper.runAfterDelay(2, () -> {
                try {
                    assertMining(helper, player, -0.15, "with low vitamins on Peaceful");
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

    // A tag entry reaches every item in the tag, and an item's own id entry beats it. Raw cod is 2 nutrition, from an
    // empty bar; the table changes on the live config for this tick only.
    @GameTest(environment = NUTRIENTS)
    public void nut2ATagEntryAndAnIdBeatsIt(GameTestHelper helper) {
        ServerPlayer player = join(helper, "NutTagged");
        HardcoreConfig.NutrientsSection cfg = Settings.get().hardcore.nutrients;
        Map<String, Map<String, Double>> foodsBefore = cfg.foods;
        try {
            helper.assertTrue(new ItemStack(Items.COD).is(ItemTags.FISHES), "cod isn't in #minecraft:fishes");
            Map<String, Map<String, Double>> withTag = new LinkedHashMap<>(foodsBefore);
            withTag.put("#minecraft:fishes", Map.of("sugar", 1.0));
            cfg.foods = withTag;
            levels(player, 0.6, 0.6, 0.6);
            feed(player, 0, 0.0F);
            eat(helper, player, Items.COD);
            HardcoreState byId = HardcoreStates.read(player.getUUID());
            assertLevel(helper, byId.protein, 0.63, "protein after a cod listed by id and tag");
            assertLevel(helper, byId.vitamins, 0.615, "vitamins after a cod listed by id and tag");
            assertLevel(helper, byId.sugar, 0.6, "sugar after a cod listed by id and tag");

            Map<String, Map<String, Double>> tagOnly = new LinkedHashMap<>(withTag);
            tagOnly.remove("minecraft:cod");
            cfg.foods = tagOnly;
            levels(player, 0.6, 0.6, 0.6);
            feed(player, 0, 0.0F);
            eat(helper, player, Items.COD);
            HardcoreState byTag = HardcoreStates.read(player.getUUID());
            assertLevel(helper, byTag.sugar, 0.66, "sugar after a cod listed by tag only");
            assertLevel(helper, byTag.protein, 0.6, "protein after a cod listed by tag only");
            assertLevel(helper, byTag.vitamins, 0.6, "vitamins after a cod listed by tag only");
        } finally {
            cfg.foods = foodsBefore;
            leave(player);
        }
        helper.succeed();
    }

    // A death outside survival and adventure is no new life, so the levels stay as they were
    @GameTest(environment = NUTRIENTS_DEATH)
    public void nutACreativeDeathKeepsTheLevels(GameTestHelper helper) {
        ServerPlayer creative = TestPlayers.join(helper, "NutCreativeDeath", GameType.CREATIVE, AT);
        ServerPlayer survivor = join(helper, "NutSurvivalDeath", AT.offset(1, 0, 0));
        try {
            for (ServerPlayer player : List.of(creative, survivor)) {
                TestPlayers.markLoaded(player);
                levels(player, 0.2, 0.2, 0.2);
                GraveDeathGameTests.respawnBesideTest(helper, player);
                player.kill(player.level());
                helper.assertTrue(player.isDeadOrDying(), "the " + player.gameMode() + " player did not die");
            }
            assertLevels(helper, creative, 0.2, "after a creative death");
            assertLevels(helper, survivor, 0.6, "after a survival death");
        } finally {
            leave(creative);
            leave(survivor);
        }
        helper.succeed();
    }

    // Full food and saturation heal every 10 ticks, and with low protein every 20. A test player's own tick never runs
    // the food data, so the test drives both players' each tick.
    @GameTest(environment = NUTRIENTS, maxTicks = 60)
    public void nut4LowProteinStretchesSaturatedRegen(GameTestHelper helper) {
        ServerPlayer low = join(helper, "NutSaturatedLow");
        ServerPlayer fed = join(helper, "NutSaturatedFed", AT.offset(1, 0, 0));
        Runnable cleanUp = BodyTestKit.once(() -> {
            leave(low);
            leave(fed);
        });
        boolean waiting = false;
        try {
            levels(low, 0.2, 0.6, 0.6);
            levels(fed, 0.6, 0.6, 0.6);
            for (ServerPlayer player : List.of(low, fed)) {
                feed(player, 20, 5.0F);
                player.setHealth(10.0F);
            }
            helper.onEachTick(() -> {
                for (ServerPlayer player : List.of(low, fed)) {
                    if (!player.isRemoved()) {
                        player.getFoodData().tick(player);
                    }
                }
            });
            helper.runAfterDelay(15, () -> step(cleanUp, () -> {
                helper.assertTrue(fed.getHealth() > 10.0F, "no heal in 15 ticks with protein at 0.6");
                helper.assertValueEqual(low.getHealth(), 10.0F, "health after 15 ticks with low protein");
            }));
            helper.runAfterDelay(25, () -> {
                try {
                    helper.assertTrue(low.getHealth() > 10.0F, "no heal in 25 ticks with low protein");
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

    // The table ships with every food in the game, so an update that adds one fails here rather than giving nothing. A
    // food is what a player can eat: food values and a use that consumes it. Fish buckets carry food values with no such
    // use, so no player ever eats one.
    @GameTest
    public void nut2EveryEdibleItemIsInTheTable(GameTestHelper helper) {
        Map<String, Map<String, Double>> table = new HardcoreConfig.NutrientsSection().foods;
        List<String> edible = BuiltInRegistries.ITEM.stream()
                .filter(item -> item.components().has(DataComponents.FOOD) && item.components().has(DataComponents.CONSUMABLE))
                .map(item -> BuiltInRegistries.ITEM.getKey(item).toString())
                .toList();
        // Each a food, so the check above can't find none at all and pass
        helper.assertTrue(edible.containsAll(List.of("minecraft:cooked_beef", "minecraft:apple", "minecraft:rabbit_stew")),
                "the foods found: " + edible);
        List<String> missing = edible.stream().filter(id -> !table.containsKey(id)).toList();
        helper.assertTrue(missing.isEmpty(), "foods the table doesn't list: " + missing);
        helper.succeed();
    }

    // Low at the join is only noted, never told; coming back up is told once
    @GameTest(environment = NUTRIENTS, maxTicks = 20)
    public void nut4JoiningLowSaysNothingProteinBackUp(GameTestHelper helper) {
        ServerPlayer player = join(helper, "NutJoinedLow");
        Runnable cleanUp = BodyTestKit.once(() -> leave(player));
        boolean waiting = false;
        try {
            levels(player, 0.2, 0.6, 0.6);
            helper.runAfterDelay(2, () -> step(cleanUp, () -> {
                helper.assertValueEqual(count(actionBars(player), "You're low on protein."), 0L, "lines at the join: " + actionBars(player));
                levels(player, 0.6, 0.6, 0.6);
            }));
            helper.runAfterDelay(4, () -> {
                try {
                    helper.assertValueEqual(count(actionBars(player), "Your protein is back up."), 1L, "lines: " + actionBars(player));
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

    // A healing multiplier of 0 stops regen outright while protein is low, and the report says so; above 1 it reads as
    // faster. Food 18 heals every 80 ticks otherwise.
    @GameTest(environment = NUTRIENTS_NO_HEAL, maxTicks = 220)
    public void nut4ZeroHealingMultiplierStopsRegen(GameTestHelper helper) {
        ServerPlayer player = join(helper, "NutNoHeal");
        HardcoreConfig.ProteinSection cfg = Settings.get().hardcore.nutrients.penalties.protein;
        Runnable cleanUp = BodyTestKit.once(() -> leave(player));
        boolean waiting = false;
        try {
            helper.assertValueEqual(cfg.healingMultiplier, 0.0, "the environment's healing multiplier");
            levels(player, 0.2, 0.6, 0.6);
            feed(player, 18, 0.0F);
            player.setHealth(10.0F);
            helper.assertTrue(statusTexts(player).contains("  low protein: healing 100% slower"), "the report: " + statusTexts(player));
            double before = cfg.healingMultiplier;
            try {
                cfg.healingMultiplier = 2.0;
                helper.assertTrue(statusTexts(player).contains("  low protein: healing 100% faster"),
                        "the report with a multiplier of 2: " + statusTexts(player));
            } finally {
                cfg.healingMultiplier = before;
            }
            helper.onEachTick(() -> {
                if (!player.isRemoved()) {
                    player.getFoodData().tick(player);
                }
            });
            helper.runAfterDelay(200, () -> {
                try {
                    helper.assertValueEqual(player.getHealth(), 10.0F, "health after 200 ticks with no healing");
                    helper.assertValueEqual(player.getFoodData().getFoodLevel(), 18, "food after 200 ticks with no healing");
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

    // A failed check ends the test at once, so the switches go back and the player isn't left online for the batch
    private static void step(Runnable cleanUp, Runnable check) {
        try {
            check.run();
        } catch (RuntimeException e) {
            cleanUp.run();
            throw e;
        }
    }

    private static long count(List<String> lines, String line) {
        return lines.stream().filter(line::equals).count();
    }

    private static List<String> statusTexts(ServerPlayer player) {
        return Status.report(player).stream().map(Component::getString).toList();
    }

    private static List<String> actionBars(ServerPlayer player) {
        return TestPlayers.sentPackets(player).stream()
                .filter(packet -> packet instanceof ClientboundSystemChatPacket chat && chat.overlay())
                .map(packet -> ((ClientboundSystemChatPacket) packet).content().getString())
                .toList();
    }
}
