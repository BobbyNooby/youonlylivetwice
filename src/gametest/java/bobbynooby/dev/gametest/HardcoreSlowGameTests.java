package bobbynooby.dev.gametest;

import bobbynooby.dev.core.Yolt;
import bobbynooby.dev.hardcore.Hardcore;
import bobbynooby.dev.hardcore.Slowdowns;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.ToDoubleFunction;

// Tests share one server, so each test part slows its own player and no one else
public class HardcoreSlowGameTests {
    private static final BlockPos AT = new BlockPos(1, 1, 1);
    private static final Identifier SLOW = Yolt.id("hardcore_slow");

    @GameTest
    public void slow2SlownessPotionStacksOnTop(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "Slowed", GameType.SURVIVAL, AT);
        Runnable unregister = Slowdowns.registerForTesting("test", only(player.getUUID(), 0.5));
        boolean waiting = false;
        try {
            player.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 1200, 1));
            helper.runAfterDelay(2, () -> {
                try {
                    AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
                    helper.assertTrue(speed != null, "no movement speed");
                    double expected = speed.getBaseValue() * 0.5 * 0.7;
                    helper.assertTrue(Math.abs(speed.getValue() - expected) < 1e-6,
                            "movement speed " + speed.getValue() + ", not " + expected);
                    long ours = speed.getModifiers().stream().filter(modifier -> modifier.is(SLOW)).count();
                    helper.assertValueEqual(ours, 1L, "YOLT slow modifiers");
                } finally {
                    unregister.run();
                    TestPlayers.leave(player);
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                unregister.run();
                TestPlayers.leave(player);
            }
        }
    }

    // Each check comes straight after the step, with no tick in between, so it is the join or respawn hook that put the
    // modifier back
    @GameTest
    public void slow3ComesBackAfterRelogAndRespawn(GameTestHelper helper) {
        ServerPlayer[] current = {TestPlayers.join(helper, "Relogger", GameType.SURVIVAL, AT)};
        GameProfile profile = current[0].getGameProfile();
        Runnable unregister = Slowdowns.registerForTesting("test", only(profile.id(), 0.2));
        boolean waiting = false;
        try {
            helper.runAfterDelay(2, () -> {
                try {
                    assertSlow(helper, current[0], 0.2, "after the join");
                    TestPlayers.leave(current[0]);
                    current[0] = null;
                    current[0] = TestPlayers.rejoin(helper, profile, GameType.SURVIVAL, AT);
                    assertSlow(helper, current[0], 0.2, "after the relog");
                    GraveDeathGameTests.respawnBesideTest(helper, current[0]);
                    current[0] = TestPlayers.respawn(current[0], false, Entity.RemovalReason.KILLED);
                    assertSlow(helper, current[0], 0.2, "after a death's respawn");
                    current[0] = TestPlayers.respawn(current[0], true, Entity.RemovalReason.CHANGED_DIMENSION);
                    assertSlow(helper, current[0], 0.2, "after the way back from the End");
                } finally {
                    unregister.run();
                    if (current[0] != null) {
                        TestPlayers.leave(current[0]);
                    }
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                unregister.run();
                TestPlayers.leave(current[0]);
            }
        }
    }

    @GameTest
    public void slow4CreativeAndSpectatorGetNone(GameTestHelper helper) {
        ServerPlayer creative = TestPlayers.join(helper, "Builder", GameType.CREATIVE, AT);
        ServerPlayer spectator = TestPlayers.join(helper, "Watcher", GameType.SPECTATOR, AT);
        Set<UUID> ids = Set.of(creative.getUUID(), spectator.getUUID());
        Runnable unregister = Slowdowns.registerForTesting("test", player -> ids.contains(player.getUUID()) ? 0.5 : 0.0);
        // Once only: a failed first step cleans up, and the second step may still run after it
        AtomicBoolean cleaned = new AtomicBoolean();
        Runnable cleanUp = () -> {
            if (cleaned.compareAndSet(false, true)) {
                unregister.run();
                TestPlayers.leave(creative);
                TestPlayers.leave(spectator);
            }
        };
        boolean waiting = false;
        try {
            // Both steps are set up here, since one added while the test runs its steps could be skipped
            helper.runAfterDelay(2, () -> {
                try {
                    assertSlow(helper, creative, 0.0, "in creative");
                    assertSlow(helper, spectator, 0.0, "in spectator");
                    creative.setGameMode(GameType.SURVIVAL);
                    spectator.setGameMode(GameType.SURVIVAL);
                } catch (RuntimeException e) {
                    cleanUp.run();
                    throw e;
                }
            });
            helper.runAfterDelay(4, () -> {
                try {
                    assertSlow(helper, creative, 0.5, "two ticks after creative went survival");
                    assertSlow(helper, spectator, 0.5, "two ticks after spectator went survival");
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

    @GameTest(maxTicks = 60)
    public void off1NoModifiersWithTheLayerOff(GameTestHelper helper) {
        helper.assertFalse(Hardcore.anyOn(), "a hardcore switch is on");
        ServerPlayer player = TestPlayers.join(helper, "Plain", GameType.SURVIVAL, AT);
        boolean waiting = false;
        try {
            helper.runAfterDelay(40, () -> {
                try {
                    for (Holder<Attribute> attribute : BuiltInRegistries.ATTRIBUTE.listElements().toList()) {
                        AttributeInstance instance = player.getAttribute(attribute);
                        if (instance == null) {
                            continue;
                        }
                        for (AttributeModifier modifier : instance.getModifiers()) {
                            helper.assertFalse(modifier.id().getNamespace().equals(Yolt.MOD_ID),
                                    "modifier " + modifier.id() + " on " + attribute.getRegisteredName());
                        }
                    }
                } finally {
                    TestPlayers.leave(player);
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                TestPlayers.leave(player);
            }
        }
    }

    private static ToDoubleFunction<ServerPlayer> only(UUID id, double share) {
        return player -> player.getUUID().equals(id) ? share : 0.0;
    }

    // The amount the YOLT modifier takes off, 0 for none
    private static void assertSlow(GameTestHelper helper, ServerPlayer player, double expected, String when) {
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        helper.assertTrue(speed != null, "no movement speed " + when);
        AttributeModifier modifier = speed.getModifier(SLOW);
        double amount = modifier == null ? 0.0 : modifier.amount();
        helper.assertTrue(Math.abs(amount + expected) < 1e-9, "slow modifier " + amount + " " + when + ", not " + -expected);
        if (modifier != null) {
            helper.assertTrue(modifier.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL,
                    "slow modifier operation " + modifier.operation() + " " + when);
        }
    }
}
