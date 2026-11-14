package bobbynooby.dev.gametest;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.hardcore.Curse;
import bobbynooby.dev.hardcore.Limp;
import bobbynooby.dev.hardcore.Slowdowns;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.GameType;

import java.util.UUID;

// The limp stays on through each test's environment, which puts the switch back when the batch ends. Test players get
// no living tick, so vanilla's own regen never moves the health a test sets.
public class HardcoreLimpGameTests {
    private static final String LIMP = "you-only-live-twice-gametest:hardcore_limp";
    // Golden hearts count towards health
    private static final String LIMP_ABSORPTION = "you-only-live-twice-gametest:hardcore_limp_absorption";
    // The curse on as well, taking up to 8 hearts, more than the limp's lines allow for
    private static final String CURSE_LIMP = "you-only-live-twice-gametest:hardcore_curse_limp";
    private static final BlockPos AT = new BlockPos(1, 1, 1);
    private static final double CLOSE = 1e-9;

    // Absorption II gives 8 golden hearts' worth of points, more than enough to reach 3 hearts if they counted
    @GameTest(environment = LIMP, maxTicks = 20)
    public void limp2AbsorptionDoesNotCount(GameTestHelper helper) {
        ServerPlayer player = shielded(helper, "Shielded");
        Runnable cleanUp = BodyTestKit.once(() -> TestPlayers.leave(player));
        boolean waiting = false;
        try {
            helper.runAfterDelay(2, () -> {
                try {
                    helper.assertTrue(Limp.limping(player), "3 health with 8 absorption is not limping");
                    assertSlow(helper, player, 0.2, "at 3 health with 8 absorption");
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

    @GameTest(environment = LIMP_ABSORPTION, maxTicks = 20)
    public void limp2AbsorptionCountsWhenSwitchedOn(GameTestHelper helper) {
        ServerPlayer player = shielded(helper, "Gilded");
        Runnable cleanUp = BodyTestKit.once(() -> TestPlayers.leave(player));
        boolean waiting = false;
        try {
            helper.runAfterDelay(2, () -> {
                try {
                    helper.assertFalse(Limp.limping(player), "3 health with 8 absorption limps with countAbsorption on");
                    assertSlow(helper, player, 0.0, "at 3 health with 8 absorption counted");
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

    // Down to 3, then up to 5, still short of 3 hearts, and then to 6, which is 3 hearts
    @GameTest(environment = LIMP, maxTicks = 30)
    public void limp3LowHealthSlowsHealingLifts(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "Limper", GameType.SURVIVAL, AT);
        Runnable cleanUp = BodyTestKit.once(() -> TestPlayers.leave(player));
        boolean waiting = false;
        try {
            assertSlow(helper, player, 0.0, "at full health");
            player.setHealth(3.0F);
            helper.runAfterDelay(2, () -> {
                try {
                    assertSlow(helper, player, 0.2, "at 3 health");
                    player.setHealth(5.0F);
                } catch (RuntimeException e) {
                    cleanUp.run();
                    throw e;
                }
            });
            helper.runAfterDelay(4, () -> {
                try {
                    assertSlow(helper, player, 0.2, "healed to 5 health");
                    player.setHealth(6.0F);
                } catch (RuntimeException e) {
                    cleanUp.run();
                    throw e;
                }
            });
            helper.runAfterDelay(6, () -> {
                try {
                    assertSlow(helper, player, 0.0, "healed to 6 health");
                    helper.assertFalse(Limp.limping(player), "still limping at 6 health");
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

    // The curse may take more than the limp leaves: 8 hearts lost of 10 is 4 health at most, short of the 3 hearts the
    // limp stops at. Once limping, a full heal can't end it; a heart back from the curse can.
    @GameTest(environment = CURSE_LIMP, maxTicks = 30)
    public void limp3ACursedPlayerLimpsUntilAHeartComesBack(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "CursedLimper", GameType.SURVIVAL, AT);
        UUID uuid = player.getUUID();
        Runnable cleanUp = BodyTestKit.once(() -> {
            Curse.set(uuid, 0);
            TestPlayers.leave(player);
        });
        boolean waiting = false;
        try {
            helper.assertValueEqual(Settings.get().hardcore.curse.maxLostHearts, 8, "maxLostHearts");
            Curse.set(uuid, 8);
            Curse.apply(player);
            helper.assertValueEqual(player.getMaxHealth(), 4.0F, "max health with 8 hearts lost");
            player.setHealth(3.0F);
            helper.runAfterDelay(2, () -> {
                try {
                    helper.assertTrue(Limp.limping(player), "not limping at 3 health");
                    player.setHealth(player.getMaxHealth());
                } catch (RuntimeException e) {
                    cleanUp.run();
                    throw e;
                }
            });
            helper.runAfterDelay(6, () -> {
                try {
                    helper.assertValueEqual(player.getHealth(), 4.0F, "health after a full heal");
                    helper.assertTrue(Limp.limping(player), "a full heal to 4 health ended the limp");
                    assertSlow(helper, player, 0.2, "at full health with 8 hearts lost");
                    Curse.set(uuid, 7);
                    Curse.apply(player);
                    player.setHealth(player.getMaxHealth());
                } catch (RuntimeException e) {
                    cleanUp.run();
                    throw e;
                }
            });
            helper.runAfterDelay(10, () -> {
                try {
                    helper.assertValueEqual(player.getHealth(), 6.0F, "health after a heart came back and a full heal");
                    helper.assertFalse(Limp.limping(player), "still limping at 6 health");
                    assertSlow(helper, player, 0.0, "with a heart back");
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

    // At 3 health with Absorption II's 8 golden points
    private static ServerPlayer shielded(GameTestHelper helper, String name) {
        ServerPlayer player = TestPlayers.join(helper, name, GameType.SURVIVAL, AT);
        try {
            player.setHealth(3.0F);
            player.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 1200, 1));
            helper.assertTrue(Math.abs(player.getAbsorptionAmount() - 8.0F) < CLOSE,
                    "Absorption II gave " + player.getAbsorptionAmount() + " absorption, not 8");
        } catch (RuntimeException e) {
            TestPlayers.leave(player);
            throw e;
        }
        return player;
    }

    private static void assertSlow(GameTestHelper helper, ServerPlayer player, double expected, String when) {
        double applied = Slowdowns.applied(player);
        helper.assertTrue(Math.abs(applied - expected) < CLOSE, "slowdown " + applied + " " + when + ", not " + expected);
    }
}
