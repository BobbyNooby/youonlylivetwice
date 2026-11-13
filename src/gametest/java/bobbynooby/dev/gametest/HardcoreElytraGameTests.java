package bobbynooby.dev.gametest;

import bobbynooby.dev.core.Yolt;
import bobbynooby.dev.hardcore.Elytra;
import bobbynooby.dev.mixin.FireworkRocketInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

// The elytra nerf stays on through each test's environment, which puts it back when the batch ends. Test players are
// never ticked, so a glide is the flag the server sets when one starts, and their speed is the movement the server
// keeps from the client's move packets, set the way the packet handler sets it.
public class HardcoreElytraGameTests {
    private static final String ELYTRA = "you-only-live-twice-gametest:hardcore_elytra";
    // Another number for the cut-off, so a batch of its own
    private static final String ELYTRA_CUTOFF_ZERO = "you-only-live-twice-gametest:hardcore_elytra_cutoff_zero";
    private static final BlockPos AT = new BlockPos(1, 1, 1);
    private static final double CLOSE = 1e-9;
    // Blocks per tick: 30 m/s and 10 m/s, either side of the 20 m/s cut-off
    private static final double FAST = 1.5;
    private static final double SLOW = 0.5;
    private static final double ROCKET_REACH = 8.0;

    @GameTest(environment = ELYTRA, maxTicks = 20)
    public void elytra1GravityChangesOnlyWhileGliding(GameTestHelper helper) {
        ServerPlayer glider = glider(helper, "GravityGlider", GameType.SURVIVAL, 0.0);
        ServerPlayer walker = join(helper, "GravityWalker", GameType.SURVIVAL);
        ServerPlayer faller = join(helper, "GravityFaller", GameType.SURVIVAL);
        ServerPlayer creative = glider(helper, "GravityCreative", GameType.CREATIVE, 0.0);
        Runnable cleanUp = BodyTestKit.once(() -> leave(glider, walker, faller, creative));
        boolean waiting = false;
        try {
            faller.setOnGround(false);
            faller.setDeltaMovement(0.0, -1.0, 0.0);
            helper.runAfterDelay(2, () -> {
                boolean stillWaiting = false;
                try {
                    assertGravity(helper, glider, -0.4, "while gliding");
                    assertGravity(helper, walker, null, "while walking");
                    assertGravity(helper, faller, null, "while falling");
                    assertGravity(helper, creative, null, "on a creative glider");
                    glider.stopFallFlying();
                    helper.runAfterDelay(2, () -> {
                        try {
                            assertGravity(helper, glider, null, "2 ticks after the glide ended");
                        } finally {
                            cleanUp.run();
                        }
                        helper.succeed();
                    });
                    stillWaiting = true;
                } finally {
                    if (!stillWaiting) {
                        cleanUp.run();
                    }
                }
            });
            waiting = true;
        } finally {
            if (!waiting) {
                cleanUp.run();
            }
        }
    }

    @GameTest(environment = ELYTRA, maxTicks = 20)
    public void elytra2ABoostingRocketEndsPastTheCutoff(GameTestHelper helper) {
        ServerPlayer fast = glider(helper, "RocketFast", GameType.SURVIVAL, FAST);
        ServerPlayer slow = glider(helper, "RocketSlow", GameType.SURVIVAL, SLOW);
        FireworkRocketEntity fastRocket = attachRocket(helper, fast);
        FireworkRocketEntity slowRocket = attachRocket(helper, slow);
        Runnable cleanUp = BodyTestKit.once(() -> {
            fastRocket.discard();
            slowRocket.discard();
            leave(fast, slow);
        });
        boolean waiting = false;
        try {
            float health = fast.getHealth();
            helper.runAfterDelay(2, () -> {
                try {
                    helper.assertTrue(fastRocket.isRemoved(), "the rocket still boosts at 30 m/s");
                    helper.assertValueEqual(fast.getHealth(), health, "the glider's health after the rocket ended");
                    helper.assertFalse(slowRocket.isRemoved(), "the rocket ended at 10 m/s");
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

    @GameTest(environment = ELYTRA)
    public void elytra3ARocketPastTheCutoffIsNotUsedUp(GameTestHelper helper) {
        ServerPlayer fast = glider(helper, "UseFast", GameType.SURVIVAL, FAST);
        ServerPlayer slow = glider(helper, "UseSlow", GameType.SURVIVAL, SLOW);
        try {
            helper.assertTrue(Elytra.refusesRocket(fast), "a rocket isn't refused at 30 m/s");
            ItemStack fastRockets = useRocket(helper, fast);
            helper.assertValueEqual(fastRockets.getCount(), 5, "rockets left after one was refused");
            helper.assertTrue(rockets(helper, fast).isEmpty(), "a refused rocket was launched");

            helper.assertFalse(Elytra.refusesRocket(slow), "a rocket is refused at 10 m/s");
            ItemStack slowRockets = useRocket(helper, slow);
            helper.assertValueEqual(slowRockets.getCount(), 4, "rockets left after one was fired");
            helper.assertTrue(rockets(helper, slow).stream().anyMatch(HardcoreElytraGameTests::attached), "no rocket attached at 10 m/s");
        } finally {
            rockets(helper, fast).forEach(FireworkRocketEntity::discard);
            rockets(helper, slow).forEach(FireworkRocketEntity::discard);
            leave(fast, slow);
        }
        helper.succeed();
    }

    @GameTest(environment = ELYTRA_CUTOFF_ZERO, maxTicks = 20)
    public void elytraCutoffZeroIsOff(GameTestHelper helper) {
        ServerPlayer fast = glider(helper, "CutoffOff", GameType.SURVIVAL, FAST);
        List<FireworkRocketEntity> fired = new ArrayList<>();
        Runnable cleanUp = BodyTestKit.once(() -> {
            fired.forEach(FireworkRocketEntity::discard);
            leave(fast);
        });
        boolean waiting = false;
        try {
            helper.assertFalse(Elytra.pastCutoff(fast), "past a cut-off of 0");
            ItemStack rockets = useRocket(helper, fast);
            helper.assertValueEqual(rockets.getCount(), 4, "rockets left after one was fired");
            fired.addAll(rockets(helper, fast));
            helper.assertTrue(fired.stream().anyMatch(HardcoreElytraGameTests::attached), "no rocket attached at 30 m/s");
            helper.runAfterDelay(2, () -> {
                try {
                    helper.assertTrue(fired.stream().noneMatch(FireworkRocketEntity::isRemoved), "the rocket ended with the cut-off at 0");
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

    private static ServerPlayer join(GameTestHelper helper, String name, GameType mode) {
        ServerPlayer player = TestPlayers.join(helper, name, mode, AT);
        TestPlayers.markLoaded(player);
        return player;
    }

    // Wearing an elytra and gliding east at this many blocks per tick
    private static ServerPlayer glider(GameTestHelper helper, String name, GameType mode, double blocksPerTick) {
        ServerPlayer player = join(helper, name, mode);
        player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
        player.startFallFlying();
        player.setKnownMovement(new Vec3(blocksPerTick, 0.0, 0.0));
        return player;
    }

    private static FireworkRocketEntity attachRocket(GameTestHelper helper, ServerPlayer player) {
        FireworkRocketEntity rocket = new FireworkRocketEntity(helper.getLevel(), new ItemStack(Items.FIREWORK_ROCKET), player);
        helper.getLevel().addFreshEntity(rocket);
        return rocket;
    }

    // A stack of 5 in the main hand, used the way the use-item packet uses it; returns the stack in hand afterwards
    private static ItemStack useRocket(GameTestHelper helper, ServerPlayer player) {
        player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.FIREWORK_ROCKET, 5));
        player.gameMode.useItem(player, helper.getLevel(), player.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND);
        return player.getItemInHand(InteractionHand.MAIN_HAND);
    }

    // Every rocket this player launched
    private static List<FireworkRocketEntity> rockets(GameTestHelper helper, ServerPlayer player) {
        return helper.getLevel().getEntitiesOfClass(FireworkRocketEntity.class, player.getBoundingBox().inflate(ROCKET_REACH),
                rocket -> rocket.getOwner() == player && !rocket.isRemoved());
    }

    private static boolean attached(FireworkRocketEntity rocket) {
        return ((FireworkRocketInvoker) rocket).yoltIsAttachedToEntity();
    }

    private static void assertGravity(GameTestHelper helper, ServerPlayer player, Double expected, String when) {
        AttributeModifier modifier = player.getAttribute(Attributes.GRAVITY).getModifier(Yolt.id("elytra_gravity"));
        if (expected == null) {
            helper.assertTrue(modifier == null, "the gravity modifier is on " + when);
            return;
        }
        helper.assertTrue(modifier != null, "no gravity modifier " + when);
        helper.assertTrue(Math.abs(modifier.amount() - expected) < CLOSE, "gravity modifier " + modifier.amount() + " " + when
                + ", not " + expected);
        helper.assertValueEqual(modifier.operation(), AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL, "the gravity modifier's operation " + when);
    }

    private static void leave(ServerPlayer... players) {
        for (ServerPlayer player : players) {
            TestPlayers.leave(player);
        }
    }
}
