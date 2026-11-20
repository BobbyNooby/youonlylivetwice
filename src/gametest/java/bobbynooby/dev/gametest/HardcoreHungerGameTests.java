package bobbynooby.dev.gametest;

import bobbynooby.dev.config.HardcoreConfig;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.gametest.mixin.FoodDataAccessor;
import bobbynooby.dev.hardcore.HardcoreStates;
import bobbynooby.dev.hardcore.HungerTax;
import bobbynooby.dev.hardcore.Legs;
import bobbynooby.dev.hardcore.Status;
import bobbynooby.dev.hardcore.Weight;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.UUID;

// The multiplier is set on the live config and put back within the same tick, so no other test ever sees it, or held at
// 2 by an environment for a test that waits
public class HardcoreHungerGameTests {
    // ×2 with legs and bed rest on, a bed rest heal costing 1.0, at noon with the clock stopped
    private static final String HUNGER_HEAL = "you-only-live-twice-gametest:hardcore_hunger_heal";
    // ×2 with weight and cold on
    private static final String HUNGER_SHIVER = "you-only-live-twice-gametest:hardcore_hunger_shiver";
    private static final BlockPos AT = new BlockPos(1, 1, 1);
    private static final float CLOSE = 1e-5F;

    @GameTest
    public void hunger1TwoTimesDoublesActionHunger(GameTestHelper helper) {
        ServerPlayer player = join(helper, "HungerTaxed", GameType.SURVIVAL);
        HardcoreConfig.HungerTaxSection tax = Settings.get().hardcore.hungerTax;
        double before = tax.multiplier;
        try {
            tax.multiplier = 2.0;
            FoodData food = player.getFoodData();
            exhaustion(player, 0.0F);
            player.causeFoodExhaustion(0.1F);
            assertExhaustion(helper, player, 0.2F, "after an action's 0.1 at ×2");
            helper.assertTrue(report(player).stream().anyMatch(line -> line.contains("hunger ×2")), "/status: " + report(player));

            // Vanilla's food tick, one tick short of a fed heal, so this one heals
            food.setFoodLevel(18);
            food.setSaturation(5.0F);
            exhaustion(player, 0.0F);
            ((FoodDataAccessor) food).yoltSetTickTimer(79);
            player.setHealth(10.0F);
            food.tick(player);
            helper.assertValueEqual(player.getHealth(), 11.0F, "health after the regen heal");
            assertExhaustion(helper, player, 6.0F, "after a regen heal at ×2");
        } finally {
            tax.multiplier = before;
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    @GameTest
    public void hunger1OneChangesNothing(GameTestHelper helper) {
        ServerPlayer player = join(helper, "HungerVanilla", GameType.SURVIVAL);
        HardcoreConfig.HungerTaxSection tax = Settings.get().hardcore.hungerTax;
        HardcoreConfig.WeightSection weight = Settings.get().hardcore.weight;
        double before = tax.multiplier;
        boolean weightBefore = weight.enabled;
        try {
            tax.multiplier = 1.0;
            exhaustion(player, 0.0F);
            player.causeFoodExhaustion(0.1F);
            assertExhaustion(helper, player, 0.1F, "after an action's 0.1 at ×1");
            // Weight on, so the report is built rather than "Nothing to report"
            weight.enabled = true;
            helper.assertFalse(report(player).stream().anyMatch(line -> line.contains("hunger")), "/status: " + report(player));
        } finally {
            tax.multiplier = before;
            weight.enabled = weightBefore;
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // Creative players are invulnerable, which skips vanilla's hunger altogether, so that is turned off to see the
    // hunger come through unscaled
    @GameTest
    public void hunger1CreativeIsUntouched(GameTestHelper helper) {
        ServerPlayer player = join(helper, "HungerCreative", GameType.CREATIVE);
        HardcoreConfig.HungerTaxSection tax = Settings.get().hardcore.hungerTax;
        double before = tax.multiplier;
        try {
            tax.multiplier = 2.0;
            helper.assertValueEqual(HungerTax.scale(player, 0.1F), 0.1F, "a creative player's scaled hunger");
            player.getAbilities().invulnerable = false;
            exhaustion(player, 0.0F);
            player.causeFoodExhaustion(0.1F);
            assertExhaustion(helper, player, 0.1F, "after a creative player's 0.1 at ×2");
        } finally {
            tax.multiplier = before;
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // Each action's own hunger, as vanilla charges it, comes through doubled at ×2: a block sprinted, a jump, a
    // sprint-jump, an attack, a block mined, a block swum and a hit taken
    @GameTest
    public void hunger2EveryActionIsTaxed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = join(helper, "HungerActive", GameType.SURVIVAL);
        HardcoreConfig.HungerTaxSection tax = Settings.get().hardcore.hungerTax;
        double before = tax.multiplier;
        BlockPos glassAt = new BlockPos(3, 1, 1);
        Zombie target = helper.spawnWithNoFreeWill(EntityTypes.ZOMBIE, new BlockPos(2, 1, 1));
        try {
            tax.multiplier = 2.0;
            player.setOnGround(true);
            player.setSprinting(true);
            taxed(helper, player, 0.1F, "a block sprinted", () -> player.checkMovementStatistics(1.0, 0.0, 0.0));
            player.setSprinting(false);
            taxed(helper, player, 0.05F, "a jump", player::jumpFromGround);
            player.setSprinting(true);
            taxed(helper, player, 0.2F, "a sprint-jump", player::jumpFromGround);
            player.setSprinting(false);
            taxed(helper, player, 0.1F, "an attack", () -> player.attack(target));
            helper.assertTrue(target.getHealth() < target.getMaxHealth(), "the attack did no damage");
            // Vanilla charges a mined block only when the hand could harvest it; glass needs no tool and drops nothing
            helper.setBlock(glassAt, Blocks.GLASS);
            taxed(helper, player, 0.005F, "a block mined", () -> player.gameMode.destroyBlock(helper.absolutePos(glassAt)));
            helper.assertBlockPresent(Blocks.AIR, glassAt);
            player.setSwimming(true);
            taxed(helper, player, 0.01F, "a block swum", () -> player.checkMovementStatistics(1.0, 0.0, 0.0));
            player.setSwimming(false);
            DamageSource cactus = level.damageSources().cactus();
            helper.assertTrue(cactus.getFoodExhaustion() > 0.0F, "a cactus hit costs no hunger in vanilla");
            taxed(helper, player, cactus.getFoodExhaustion(), "a hit taken", () -> player.hurtServer(level, cactus, 2.0F));
            helper.assertValueEqual(player.getHealth(), 18.0F, "health after the hit");
        } finally {
            tax.multiplier = before;
            target.discard();
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // Healing puts its cost straight on the food data: a leg point costs its 3.0 and a heal in bed its 1.0, not double.
    // A leg point comes every 40 ticks at full food and saturation, a heal in bed every 50; a test player's own food
    // tick never runs, so nothing else touches the exhaustion.
    @GameTest(environment = HUNGER_HEAL, maxTicks = 80)
    public void hunger2LegHealingAndBedRestAreUntaxed(GameTestHelper helper) {
        BlockPos head = SleepGameTests.placeBed(helper);
        ServerPlayer rester = join(helper, "HungerRester", GameType.SURVIVAL, SleepGameTests.NEXT_TO_BED);
        ServerPlayer mender = join(helper, "HungerMender", GameType.SURVIVAL, new BlockPos(4, 1, 4));
        UUID menderId = mender.getUUID();
        Runnable cleanUp = BodyTestKit.once(() -> {
            Legs.set(menderId, false);
            SleepGameTests.wakeAndLeave(rester);
            TestPlayers.leave(mender);
        });
        boolean waiting = false;
        try {
            helper.assertValueEqual(Settings.get().hardcore.hungerTax.multiplier, 2.0, "the environment's multiplier");
            Legs.set(menderId, true);
            feed(mender, 20, 5.0F);
            feed(rester, 17, 0.0F);
            rester.setHealth(10.0F);
            SleepGameTests.useBed(helper, rester, head);
            helper.assertTrue(rester.isSleeping(), "the player could not lie down at noon");
            helper.runAfterDelay(55, () -> {
                try {
                    helper.assertValueEqual(HardcoreStates.read(menderId).legHealPoints, 1.0, "leg points after 55 ticks");
                    assertExhaustion(helper, mender, 3.0F, "after a leg point at ×2");
                    helper.assertValueEqual(rester.getHealth(), 11.0F, "health after 55 ticks in bed");
                    assertExhaustion(helper, rester, 1.0F, "after a heal in bed at ×2");
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

    // The two costs YOLT adds go through the player's own hunger, so the tax doubles them too: the heavy jump's 0.2 at a
    // full load, on top of the jump's own 0.05, and a second's shiver, 0.05 at full chill, for the chill after that second
    @GameTest(environment = HUNGER_SHIVER, maxTicks = 40)
    public void hunger2HeavyJumpAndShiverAreTaxed(GameTestHelper helper) {
        ServerPlayer jumper = join(helper, "HungerHeavy", GameType.SURVIVAL);
        ServerPlayer shiverer = join(helper, "HungerShiver", GameType.SURVIVAL, new BlockPos(4, 1, 4));
        UUID shivererId = shiverer.getUUID();
        Runnable cleanUp = BodyTestKit.once(() -> {
            HardcoreStates.update(shivererId, state -> state.chill = 0.0);
            jumper.getInventory().clearContent();
            TestPlayers.leave(jumper);
            TestPlayers.leave(shiverer);
        });
        boolean waiting = false;
        try {
            helper.assertValueEqual(Settings.get().hardcore.hungerTax.multiplier, 2.0, "the environment's multiplier");
            // Six stacks of iron blocks, 27 each, are past the full load of 144
            for (int i = 0; i < 6; i++) {
                jumper.getInventory().add(new ItemStack(Items.IRON_BLOCK, 64));
            }
            helper.assertValueEqual(Weight.loadShare(jumper), 1.0, "the load share");
            exhaustion(jumper, 0.0F);
            jumper.jumpFromGround();
            assertExhaustion(helper, jumper, 2.0F * (0.05F + 0.2F), "after a jump at a full load at ×2");

            HardcoreStates.update(shivererId, state -> state.chill = 1.0);
            exhaustion(shiverer, 0.0F);
            helper.runAfterDelay(25, () -> {
                try {
                    double chill = HardcoreStates.read(shivererId).chill;
                    helper.assertTrue(chill > 0.9, "the chill after a second: " + chill);
                    assertExhaustion(helper, shiverer, (float) (2.0 * 0.05 * chill), "after a second's shiver at ×2");
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

    // Vanilla's saturated regen puts its cost straight on the food data, so it is never taxed: 5 saturation heals and
    // costs 5.0, not 10.0
    @GameTest
    public void hunger1SaturatedRegenIsNeverTaxed(GameTestHelper helper) {
        ServerPlayer player = join(helper, "HungerSaturated", GameType.SURVIVAL);
        HardcoreConfig.HungerTaxSection tax = Settings.get().hardcore.hungerTax;
        double before = tax.multiplier;
        try {
            tax.multiplier = 2.0;
            FoodData food = player.getFoodData();
            feed(player, 20, 5.0F);
            // One tick short of a saturated heal, so this one heals
            ((FoodDataAccessor) food).yoltSetTickTimer(9);
            player.setHealth(10.0F);
            food.tick(player);
            helper.assertTrue(player.getHealth() > 10.0F, "the saturated regen didn't heal");
            assertExhaustion(helper, player, 5.0F, "after a saturated heal at ×2");
        } finally {
            tax.multiplier = before;
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // A whole number shows without its ".0", any other as it is
    @GameTest
    public void hunger1StatusPrintsTheMultiplierPlainly(GameTestHelper helper) {
        ServerPlayer player = join(helper, "HungerShown", GameType.SURVIVAL);
        HardcoreConfig.HungerTaxSection tax = Settings.get().hardcore.hungerTax;
        double before = tax.multiplier;
        try {
            tax.multiplier = 2.0;
            helper.assertTrue(report(player).contains("  hunger ×2"), "/status at 2.0: " + report(player));
            tax.multiplier = 1.25;
            helper.assertTrue(report(player).contains("  hunger ×1.25"), "/status at 1.25: " + report(player));
        } finally {
            tax.multiplier = before;
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // Exhaustion from nothing, then the action, which must cost twice vanilla's own amount
    private static void taxed(GameTestHelper helper, ServerPlayer player, float vanilla, String what, Runnable action) {
        exhaustion(player, 0.0F);
        action.run();
        assertExhaustion(helper, player, 2.0F * vanilla, "after " + what + " at ×2");
    }

    private static void feed(ServerPlayer player, int food, float saturation) {
        FoodData data = player.getFoodData();
        data.setFoodLevel(food);
        data.setSaturation(saturation);
        ((FoodDataAccessor) data).yoltSetExhaustion(0.0F);
    }

    private static ServerPlayer join(GameTestHelper helper, String name, GameType mode) {
        return join(helper, name, mode, AT);
    }

    private static ServerPlayer join(GameTestHelper helper, String name, GameType mode, BlockPos at) {
        ServerPlayer player = TestPlayers.join(helper, name, mode, at);
        TestPlayers.markLoaded(player);
        return player;
    }

    private static void exhaustion(ServerPlayer player, float exhaustion) {
        ((FoodDataAccessor) player.getFoodData()).yoltSetExhaustion(exhaustion);
    }

    private static void assertExhaustion(GameTestHelper helper, ServerPlayer player, float expected, String when) {
        float actual = ((FoodDataAccessor) player.getFoodData()).yoltExhaustion();
        helper.assertTrue(Math.abs(actual - expected) < CLOSE, "exhaustion " + actual + " " + when + ", not " + expected);
    }

    // What /status shows the player
    private static List<String> report(ServerPlayer player) {
        return Status.report(player).stream().map(Component::getString).toList();
    }
}
