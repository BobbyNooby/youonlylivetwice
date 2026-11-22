package bobbynooby.dev.hardcore;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * Weight, broken legs, the limp and bed rest with no YOLT on the server, each the simplest case of YOLT's own test of
 * it. The hardcore tick reads the settings at the end of every server tick, so each test holds its switch on in a batch
 * of its own. Test players get no living tick from a client, so vanilla's own regen never moves a health a test sets.
 */
public class HardcoreBodyAloneGameTests {
    private static final String WEIGHT = "yolt-hardcore-gametest:weight_alone";
    private static final String LEGS = "yolt-hardcore-gametest:legs_alone";
    private static final String LIMP = "yolt-hardcore-gametest:limp_alone";
    // Noon with the clock stopped and the sky clear, so the bed is a daytime nap
    private static final String BED = "yolt-hardcore-gametest:bed_rest_alone";
    private static final BlockPos AT = new BlockPos(1, 1, 1);
    // Beside the foot of the bed placeBed puts down
    static final BlockPos NEXT_TO_BED = new BlockPos(1, 1, 0);
    private static final double CLOSE = 1e-9;
    // Weight weighs again every 10 ticks, plus 2 for the slowdown to follow
    private static final int SETTLE = 12;

    // 12 stacks of cobblestone at 3 each: (36 - 16) / (144 - 16) × 0.5 is 0.078, which the shared slowdown's steps make
    // 0.1. Nothing is slowed until the hardcore tick weighs the load, and a weighing is kept for 10 ticks, so putting
    // it down shows within SETTLE ticks as well.
    @GameTest(environment = WEIGHT, maxTicks = 60)
    public void aLoadSlowsThePlayerAndPuttingItDownLiftsIt(GameTestHelper helper) {
        Held held = new Held(helper, c -> c.weight.enabled = true);
        try {
            ServerPlayer player = held.join("Hauler", GameType.SURVIVAL, AT);
            held.onCleanUp(() -> player.getInventory().clearContent());
            int from = TestPlayers.mark(player);
            fill(player, Items.COBBLESTONE, 12);
            assertSlow(helper, player, 0.0, "as the load is picked up");
            helper.runAfterDelay(SETTLE, held.step(() -> {
                assertSlow(helper, player, 0.1, "at a load of 36");
                helper.assertValueEqual(TestPlayers.actionBars(player, from), List.of("Your load is slowing you down."),
                        "the hotbar");
                List<String> status = TestPlayers.status(helper, player);
                helper.assertTrue(status.contains("Load 36 / 144 · 8% slower") && status.contains("  Cobblestone ×768 (36.0)")
                        && status.contains("Total 8% slower · cap 50%"), "/status: " + status);
                player.getInventory().clearContent();
            }));
            helper.runAfterDelay(2 * SETTLE, held.last(() -> assertSlow(helper, player, 0.0, "after the load was put down")));
        } catch (RuntimeException | Error e) {
            held.cleanUp();
            throw e;
        }
    }

    // A fall is the damage itself, sent into the hurt path as a landing does: 9 blocks less the 3 safe ones is 6, which
    // breaks them. Healing waits for full health and takes a point every 40 ticks on full, saturated food, so the
    // thirtieth point comes 40 ticks after the twenty-ninth is set.
    @GameTest(environment = LEGS, maxTicks = 80)
    public void aLongFallBreaksTheLegsAndTheyHeal(GameTestHelper helper) {
        Held held = new Held(helper, c -> c.legs.enabled = true);
        try {
            ServerPlayer player = held.join("Faller", GameType.SURVIVAL, AT);
            UUID id = player.getUUID();
            held.onCleanUp(() -> Legs.set(id, false));
            helper.assertFalse(Legs.broken(player), "the legs are broken before the fall");
            int from = TestPlayers.mark(player);
            player.causeFallDamage(9.0, 1.0F, helper.getLevel().damageSources().fall());
            helper.assertTrue(player.getHealth() < player.getMaxHealth(), "the fall did no damage");
            helper.assertTrue(Legs.broken(player), "a 9-block fall left the legs whole");
            helper.assertValueEqual(TestPlayers.actionBars(player, from), List.of("Your legs broke."), "the hotbar");
            List<String> status = TestPlayers.status(helper, player);
            helper.assertTrue(status.contains("Legs broken · 20% slower · healing 0/30"), "/status: " + status);
            helper.runAfterDelay(2, held.step(() -> {
                assertSlow(helper, player, 0.2, "with broken legs");
                player.setHealth(player.getMaxHealth());
                player.getFoodData().setFoodLevel(20);
                player.getFoodData().setSaturation(5.0F);
                HardcoreStates.update(id, state -> state.legHealPoints = 29.0);
            }));
            helper.runAfterDelay(47, held.last(() -> {
                helper.assertFalse(Legs.broken(player), "the legs are still broken after the thirtieth point");
                helper.assertTrue(TestPlayers.actionBars(player, from).contains("Your legs have healed."),
                        "the hotbar: " + TestPlayers.actionBars(player, from));
                assertSlow(helper, player, 0.0, "with the legs healed");
            }));
        } catch (RuntimeException | Error e) {
            held.cleanUp();
            throw e;
        }
    }

    // Under 2 hearts the player limps; 5 health is still short of the 3 hearts that end it, and 6 ends it
    @GameTest(environment = LIMP, maxTicks = 30)
    public void aLowHealthPlayerLimpsUntilHealed(GameTestHelper helper) {
        Held held = new Held(helper, c -> c.limp.enabled = true);
        try {
            ServerPlayer player = held.join("Limper", GameType.SURVIVAL, AT);
            player.setHealth(3.0F);
            assertSlow(helper, player, 0.0, "before a tick saw the health");
            helper.runAfterDelay(2, held.step(() -> {
                assertSlow(helper, player, 0.2, "at 3 health");
                helper.assertTrue(Limp.limping(player), "not limping at 3 health");
                // The limp has no line of its own, only its share of the total
                List<String> status = TestPlayers.status(helper, player);
                helper.assertTrue(status.contains("Total 20% slower · cap 50%"), "/status: " + status);
                player.setHealth(5.0F);
            }));
            helper.runAfterDelay(4, held.step(() -> {
                assertSlow(helper, player, 0.2, "healed to 5 health");
                player.setHealth(6.0F);
            }));
            helper.runAfterDelay(6, held.last(() -> {
                assertSlow(helper, player, 0.0, "healed to 6 health");
                helper.assertFalse(Limp.limping(player), "still limping at 6 health");
            }));
        } catch (RuntimeException | Error e) {
            held.cleanUp();
            throw e;
        }
    }

    // One health every 50 ticks from lying down; each check is a few ticks after a heal and well before the next. At
    // noon vanilla wouldn't let anyone lie down: bed rest lets them.
    @GameTest(environment = BED, maxTicks = 140)
    public void bedRestHealsAPlayerInBed(GameTestHelper helper) {
        Held held = new Held(helper, c -> c.bedRest.enabled = true);
        try {
            BlockPos head = placeBed(helper);
            ServerPlayer player = held.join("Rester", GameType.SURVIVAL, NEXT_TO_BED);
            held.onCleanUp(() -> {
                if (player.isSleeping()) {
                    player.stopSleepInBed(true, true);
                }
            });
            helper.assertFalse(helper.getLevel().isDarkOutside(), "it is dark at the batch's noon");
            player.setHealth(10.0F);
            useBed(helper, player, head);
            helper.assertTrue(player.isSleeping(), "the player could not lie down at noon");
            List<String> status = TestPlayers.status(helper, player);
            helper.assertTrue(status.contains("Resting healing in bed"), "/status: " + status);
            helper.runAfterDelay(55, held.step(() -> assertHealth(helper, player, 11.0F, "50 ticks in bed")));
            helper.runAfterDelay(105, held.last(() -> assertHealth(helper, player, 12.0F, "100 ticks in bed")));
        } catch (RuntimeException | Error e) {
            held.cleanUp();
            throw e;
        }
    }

    // A red bed with its foot at 1, 1, 1, facing south; returns the head
    static BlockPos placeBed(GameTestHelper helper) {
        BlockPos foot = new BlockPos(1, 1, 1);
        BlockPos head = foot.relative(Direction.SOUTH);
        BlockState bed = Blocks.BED.pick(DyeColor.RED).defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH);
        helper.setBlock(foot, bed.setValue(BedBlock.PART, BedPart.FOOT));
        helper.setBlock(head, bed.setValue(BedBlock.PART, BedPart.HEAD));
        return head;
    }

    // What a right click on the bed runs on the server
    static void useBed(GameTestHelper helper, ServerPlayer player, BlockPos relativeHead) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(relativeHead);
        level.getBlockState(pos).useWithoutItem(level, player, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
    }

    // Full stacks into the first empty slots
    private static void fill(ServerPlayer player, Item item, int stacks) {
        for (int i = 0; i < stacks; i++) {
            player.getInventory().add(new ItemStack(item, 64));
        }
    }

    static void assertSlow(GameTestHelper helper, ServerPlayer player, double expected, String when) {
        double applied = Slowdowns.applied(player);
        helper.assertTrue(Math.abs(applied - expected) < CLOSE, "slowdown " + applied + " " + when + ", not " + expected);
    }

    private static void assertHealth(GameTestHelper helper, ServerPlayer player, float expected, String when) {
        helper.assertTrue(Math.abs(player.getHealth() - expected) < CLOSE,
                "health " + player.getHealth() + " after " + when + ", not " + expected);
    }
}
