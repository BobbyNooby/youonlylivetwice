package bobbynooby.dev.gametest;

import bobbynooby.dev.config.HardcoreConfig;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.gametest.mixin.FoodDataAccessor;
import bobbynooby.dev.hardcore.HungerTax;
import bobbynooby.dev.hardcore.Status;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.level.GameType;

import java.util.List;

// The multiplier is set on the live config and put back within the same tick, so no other test ever sees it
public class HardcoreHungerGameTests {
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
        double before = tax.multiplier;
        try {
            tax.multiplier = 1.0;
            exhaustion(player, 0.0F);
            player.causeFoodExhaustion(0.1F);
            assertExhaustion(helper, player, 0.1F, "after an action's 0.1 at ×1");
            helper.assertFalse(report(player).stream().anyMatch(line -> line.contains("hunger")), "/status: " + report(player));
        } finally {
            tax.multiplier = before;
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

    private static ServerPlayer join(GameTestHelper helper, String name, GameType mode) {
        ServerPlayer player = TestPlayers.join(helper, name, mode, AT);
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
