package bobbynooby.dev.gametest;

import bobbynooby.dev.config.HardcoreConfig;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.hardcore.Curse;
import bobbynooby.dev.hardcore.Limp;
import bobbynooby.dev.hardcore.Slowdowns;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
    // The limp on, in a batch of its own, since its test turns it off and on again across ticks
    private static final String LIMP_SWITCH = "you-only-live-twice-gametest:hardcore_limp_switch";
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

    // The switch is read every tick: off ends the limp and its slowdown at the next tick, and on starts afresh, so health
    // between the two lines doesn't limp again
    @GameTest(environment = LIMP_SWITCH, maxTicks = 30)
    public void limpSwitchedOffLiveStopsTheSlowAtOnce(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "LimpSwitched", GameType.SURVIVAL, AT);
        HardcoreConfig.LimpSection cfg = Settings.get().hardcore.limp;
        boolean before = cfg.enabled;
        Runnable cleanUp = BodyTestKit.once(() -> {
            cfg.enabled = before;
            TestPlayers.leave(player);
        });
        boolean waiting = false;
        try {
            player.setHealth(3.0F);
            helper.runAfterDelay(2, () -> step(cleanUp, () -> {
                helper.assertTrue(Limp.limping(player), "not limping at 3 health");
                assertSlow(helper, player, 0.2, "at 3 health");
                cfg.enabled = false;
            }));
            helper.runAfterDelay(3, () -> step(cleanUp, () -> {
                helper.assertFalse(Limp.limping(player), "still limping a tick after the limp went off");
                assertSlow(helper, player, 0.0, "a tick after the limp went off");
                player.setHealth(5.0F);
                cfg.enabled = true;
            }));
            helper.runAfterDelay(5, () -> {
                try {
                    helper.assertFalse(Limp.limping(player), "limping at 5 health after the limp came back on");
                    assertSlow(helper, player, 0.0, "at 5 health after the limp came back on");
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

    // The limp lives in memory only, so one who comes back still low limps again from their first tick
    @GameTest(environment = LIMP, maxTicks = 30)
    public void limp3ARelogStillLowLimpsAtOnce(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TestPlayers.join(helper, "LimpRelog", GameType.SURVIVAL, AT);
        GameProfile profile = player.getGameProfile();
        Runnable cleanUp = BodyTestKit.once(() -> {
            ServerPlayer online = server.getPlayerList().getPlayer(profile.id());
            if (online != null) {
                TestPlayers.leave(online);
            }
        });
        boolean waiting = false;
        try {
            player.setHealth(3.0F);
            helper.runAfterDelay(2, () -> step(cleanUp, () -> {
                helper.assertTrue(Limp.limping(player), "not limping at 3 health");
                TestPlayers.leave(player);
                ServerPlayer back = TestPlayers.rejoin(helper, profile, GameType.SURVIVAL, AT);
                helper.assertValueEqual(back.getHealth(), 3.0F, "health at the rejoin");
                helper.assertFalse(Limp.limping(back), "limping before a tick had looked at the player");
                helper.runAfterDelay(1, () -> {
                    try {
                        helper.assertTrue(Limp.limping(back), "not limping a tick after the rejoin at 3 health");
                        assertSlow(helper, back, 0.2, "a tick after the rejoin");
                    } finally {
                        cleanUp.run();
                    }
                    helper.succeed();
                });
            }));
            waiting = true;
        } finally {
            if (!waiting) {
                cleanUp.run();
            }
        }
    }

    // A totem leaves half a heart, and the limp lasts until 3 hearts: not at 5.9 health, only at 6
    @GameTest(environment = LIMP, maxTicks = 30)
    public void limp3ATotemLeavesYouLimping(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "LimpTotem", GameType.SURVIVAL, AT);
        Runnable cleanUp = BodyTestKit.once(() -> TestPlayers.leave(player));
        boolean waiting = false;
        try {
            // Loaded, so the player can be hurt
            TestPlayers.markLoaded(player);
            player.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
            player.setHealth(2.0F);
            player.hurtServer(helper.getLevel(), helper.getLevel().damageSources().generic(), 10.0F);
            helper.assertFalse(player.isDeadOrDying(), "the totem did not save the player");
            helper.assertValueEqual(player.getHealth(), 1.0F, "health after the totem");
            helper.runAfterDelay(2, () -> step(cleanUp, () -> {
                helper.assertTrue(Limp.limping(player), "not limping after the totem");
                player.setHealth(5.9F);
            }));
            helper.runAfterDelay(4, () -> step(cleanUp, () -> {
                helper.assertTrue(Limp.limping(player), "not limping at 5.9 health");
                player.setHealth(6.0F);
            }));
            helper.runAfterDelay(6, () -> {
                try {
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

    // A failed check ends the test at once, so the switch goes back and the player isn't left online for the batch
    private static void step(Runnable cleanUp, Runnable check) {
        try {
            check.run();
        } catch (RuntimeException e) {
            cleanUp.run();
            throw e;
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
