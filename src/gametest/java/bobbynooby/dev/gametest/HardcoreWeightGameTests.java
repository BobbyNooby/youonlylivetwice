package bobbynooby.dev.gametest;

import bobbynooby.dev.config.HardcoreConfig;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.gametest.mixin.FoodDataAccessor;
import bobbynooby.dev.graves.GraveItems;
import bobbynooby.dev.hardcore.Slowdowns;
import bobbynooby.dev.hardcore.Status;
import bobbynooby.dev.hardcore.Weight;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.GameType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Weighing is worked out straight from a fresh set of the default numbers, so those tests need no switch. The tests that
// wait for the tick hold weight on through their environment, which puts it back when the batch ends.
public class HardcoreWeightGameTests {
    private static final String WEIGHT = "you-only-live-twice-gametest:hardcore_weight";
    // A batch of its own: it changes a number the other weight tests count on
    private static final String WEIGHT_CONFIG = "you-only-live-twice-gametest:hardcore_weight_config";
    private static final BlockPos AT = new BlockPos(1, 1, 1);
    private static final double CLOSE = 1e-9;
    // Weight weighs again every 10 ticks, plus 2 for the slowdown to follow; raise it with that interval
    private static final int SETTLE = 12;

    @GameTest
    public void weight1ToolsUnstackablesElytraAndMace(GameTestHelper helper) {
        HardcoreConfig.WeightSection cfg = new HardcoreConfig.WeightSection();
        assertWeighs(helper, cfg, new ItemStack(Items.IRON_SWORD), 0.25);
        assertWeighs(helper, cfg, new ItemStack(Items.POTION), 0.1);
        assertWeighs(helper, cfg, new ItemStack(Items.ELYTRA), 0.5);
        assertWeighs(helper, cfg, new ItemStack(Items.MACE), 1.0);
        assertWeighs(helper, cfg, new ItemStack(Items.ANVIL), 1.5);
        assertWeighs(helper, cfg, new ItemStack(Items.CHIPPED_ANVIL), 1.5);
        assertWeighs(helper, cfg, new ItemStack(Items.HEAVY_CORE), 1.0);
        assertWeighs(helper, cfg, new ItemStack(Items.TRIDENT), 0.25);
        // Animal armour weighs its material's chestplate: 8 × 0.2 and 4 × 0.1
        assertWeighs(helper, cfg, new ItemStack(Items.NETHERITE_HORSE_ARMOR), 1.6);
        assertWeighs(helper, cfg, new ItemStack(Items.COPPER_NAUTILUS_ARMOR), 0.4);
        assertWeighs(helper, cfg, new ItemStack(Items.END_STONE, 64), 3.0);
        assertWeighs(helper, cfg, new ItemStack(Items.SULFUR, 64), 3.0);
        assertWeighs(helper, cfg, new ItemStack(Items.COBBLESTONE, 64), 3.0);
        assertWeighs(helper, cfg, new ItemStack(Items.PAPER, 64), 0.25);
        assertWeighs(helper, cfg, new ItemStack(Items.DIRT, 64), 1.0);
        helper.succeed();
    }

    @GameTest
    public void weight2ArmourFromTheRealItems(GameTestHelper helper) {
        HardcoreConfig.WeightSection cfg = new HardcoreConfig.WeightSection();
        // Armour points times the factor: 6 × 0.1, 3 × 0.2, 2 × 0.1 and 2 × 0.05
        assertWeighs(helper, cfg, new ItemStack(Items.IRON_CHESTPLATE), 0.6);
        assertWeighs(helper, cfg, new ItemStack(Items.NETHERITE_BOOTS), 0.6);
        assertWeighs(helper, cfg, new ItemStack(Items.TURTLE_HELMET), 0.2);
        assertWeighs(helper, cfg, new ItemStack(Items.LEATHER_LEGGINGS), 0.1);
        helper.succeed();
    }

    @GameTest
    public void weight4StorageBlocksWeighNineTimesTheirIngredient(GameTestHelper helper) {
        HardcoreConfig.WeightSection cfg = new HardcoreConfig.WeightSection();
        assertWeighs(helper, cfg, new ItemStack(Items.IRON_BLOCK, 64), 27.0);
        assertWeighs(helper, cfg, new ItemStack(Items.IRON_INGOT, 64), 3.0);
        assertWeighs(helper, cfg, new ItemStack(Items.COAL_BLOCK, 64), 9.0);
        assertWeighs(helper, cfg, new ItemStack(Items.COAL, 64), 1.0);
        helper.succeed();
    }

    @GameTest
    public void weight5ContainersCountToDepthThree(GameTestHelper helper) {
        HardcoreConfig.WeightSection cfg = new HardcoreConfig.WeightSection();
        ItemStack fullOfStone = shulker(Collections.nCopies(27, new ItemStack(Items.COBBLESTONE, 64)));
        assertWeighs(helper, cfg, fullOfStone, 0.1 + 27 * 3.0);

        // Shulker, shulker, bundle: the bundle's dirt counts. The bundle inside it is a fourth level, so its cobblestone
        // doesn't, though the bundle itself does.
        ItemStack fourth = bundle(List.of(new ItemStack(Items.COBBLESTONE, 32)));
        ItemStack third = bundle(List.of(new ItemStack(Items.DIRT, 16), fourth));
        ItemStack nested = shulker(List.of(shulker(List.of(third))));
        assertWeighs(helper, cfg, nested, 0.1 + 0.1 + 0.1 + 0.25 + 0.1);
        HardcoreConfig.WeightSection deeper = new HardcoreConfig.WeightSection();
        deeper.containerDepth = 4;
        assertWeighs(helper, deeper, nested, 0.1 + 0.1 + 0.1 + 0.25 + 0.1 + 1.5);

        HardcoreConfig.WeightSection gentle = new HardcoreConfig.WeightSection();
        gentle.containerContents = 0.5;
        assertWeighs(helper, gentle, fullOfStone, 0.1 + 40.5);
        helper.succeed();
    }

    @GameTest
    public void weight6EnderChestDoesNotCountCursorAndGridDo(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "Packer", GameType.SURVIVAL, AT);
        HardcoreConfig.WeightSection cfg = new HardcoreConfig.WeightSection();
        try {
            player.getInventory().clearContent();
            player.getEnderChestInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 64));
            assertCarries(helper, player, cfg, 0.0, "with cobblestone in the ender chest");
            player.getEnderChestInventory().clearContent();

            player.containerMenu.setCarried(new ItemStack(Items.COBBLESTONE, 64));
            assertCarries(helper, player, cfg, 3.0, "with cobblestone on the cursor");
            player.containerMenu.setCarried(ItemStack.EMPTY);

            player.inventoryMenu.getCraftSlots().setItem(0, new ItemStack(Items.COBBLESTONE, 64));
            assertCarries(helper, player, cfg, 3.0, "with cobblestone in the crafting grid");
            cfg.countCursorAndGrid = false;
            assertCarries(helper, player, cfg, 0.0, "with cobblestone in the crafting grid and countCursorAndGrid off");
        } finally {
            player.getEnderChestInventory().clearContent();
            player.containerMenu.setCarried(ItemStack.EMPTY);
            player.inventoryMenu.getCraftSlots().clearContent();
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // Items a player moves into a crafting table's grid, or holds on the cursor of a chest's screen, are still carried:
    // they go back to the inventory when the screen closes. Neither menu has a block behind it, so neither closes itself.
    @GameTest
    public void weight6ACraftingTableGridAndAChestCursorCount(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "Crafter", GameType.SURVIVAL, AT);
        HardcoreConfig.WeightSection cfg = new HardcoreConfig.WeightSection();
        try {
            player.getInventory().clearContent();
            player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new CraftingMenu(id, inventory),
                    Component.literal("Crafting")));
            helper.assertTrue(player.containerMenu instanceof CraftingMenu, "the crafting table's screen did not open");
            CraftingMenu table = (CraftingMenu) player.containerMenu;
            table.getInputGridSlots().get(4).set(new ItemStack(Items.COBBLESTONE, 64));
            assertCarries(helper, player, cfg, 3.0, "with cobblestone in a crafting table's grid");
            cfg.countCursorAndGrid = false;
            assertCarries(helper, player, cfg, 0.0, "with cobblestone in a crafting table's grid and countCursorAndGrid off");
            cfg.countCursorAndGrid = true;
            table.getInputGridSlots().get(4).set(ItemStack.EMPTY);
            player.closeContainer();

            player.openMenu(new SimpleMenuProvider((id, inventory, p) -> ChestMenu.threeRows(id, inventory),
                    Component.literal("Chest")));
            helper.assertTrue(player.containerMenu instanceof ChestMenu, "the chest's screen did not open");
            player.containerMenu.setCarried(new ItemStack(Items.COBBLESTONE, 64));
            assertCarries(helper, player, cfg, 3.0, "with cobblestone on a chest screen's cursor");
            player.containerMenu.setCarried(ItemStack.EMPTY);
            assertCarries(helper, player, cfg, 0.0, "with nothing on a chest screen's cursor");
        } finally {
            if (player.containerMenu instanceof CraftingMenu table) {
                table.getInputGridSlots().forEach(slot -> slot.set(ItemStack.EMPTY));
            }
            player.containerMenu.setCarried(ItemStack.EMPTY);
            player.closeContainer();
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // A grave head and a revive paper both name someone, so the line names each by its kind. Weight is on for this one
    // tick only.
    @GameTest
    public void status1HeaviestLineNeverNamesAPlayer(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "Mourner", GameType.SURVIVAL, AT);
        ResolvableProfile someone = ResolvableProfile.createResolved(new GameProfile(UUID.randomUUID(), "Someone"));
        ItemStack paper = ReviveGameTests.reviveHead(someone, 1);
        paper.set(DataComponents.ITEM_NAME, Component.literal("Revive Someone"));
        HardcoreConfig.WeightSection weight = Settings.get().hardcore.weight;
        boolean before = weight.enabled;
        try {
            player.getInventory().clearContent();
            player.getInventory().add(GraveItems.graveHead(someone));
            player.getInventory().add(paper);
            weight.enabled = true;
            List<String> heaviest = Status.lines(player).stream().map(Component::getString)
                    .filter(line -> line.startsWith("  heaviest:")).toList();
            helper.assertTrue(heaviest.size() == 1, "heaviest lines: " + heaviest);
            String line = heaviest.getFirst();
            helper.assertTrue(line.contains("Player Head") && line.contains("Paper"), "the heaviest line: " + line);
            helper.assertFalse(line.contains("Someone"), "the heaviest line names a player: " + line);
        } finally {
            weight.enabled = before;
            player.getInventory().clearContent();
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // Vanilla's jump costs 0.05; the load adds heavyJumpExhaustion times how far it is from the free load to the full one
    @GameTest(environment = WEIGHT, maxTicks = 30)
    public void weight7HeavyJumpCostsHunger(GameTestHelper helper) {
        ServerPlayer full = TestPlayers.join(helper, "Laden", GameType.SURVIVAL, AT);
        ServerPlayer light = TestPlayers.join(helper, "Unladen", GameType.SURVIVAL, AT.offset(1, 0, 0));
        ServerPlayer half = TestPlayers.join(helper, "Halfladen", GameType.SURVIVAL, AT.offset(2, 0, 0));
        Runnable cleanUp = BodyTestKit.once(() -> {
            for (ServerPlayer player : List.of(full, light, half)) {
                player.getInventory().clearContent();
                TestPlayers.leave(player);
            }
        });
        boolean waiting = false;
        try {
            // 6 × 27 + 2 × 1 is over the full load of 144, 10 × 1 is under the free 16, and 26 × 3 + 2 × 1 is halfway
            fill(full, Items.IRON_BLOCK, 6);
            fill(full, Items.DIRT, 2);
            fill(light, Items.DIRT, 10);
            fill(half, Items.COBBLESTONE, 26);
            fill(half, Items.DIRT, 2);
            helper.runAfterDelay(SETTLE, () -> {
                try {
                    assertJumpCosts(helper, full, 164.0, 0.05 + 0.2);
                    assertJumpCosts(helper, light, 10.0, 0.05);
                    assertJumpCosts(helper, half, 80.0, 0.05 + 0.1);
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

    @GameTest(environment = WEIGHT, maxTicks = 40)
    public void weight3SlowsThePlayer(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "Hauler", GameType.SURVIVAL, AT);
        Runnable cleanUp = BodyTestKit.once(() -> {
            player.getInventory().clearContent();
            TestPlayers.leave(player);
        });
        boolean waiting = false;
        try {
            fill(player, Items.COBBLESTONE, 12);
            // Both steps are set up here, since one added while the test runs its steps could be skipped
            helper.runAfterDelay(SETTLE, () -> {
                try {
                    // (36 - 16) / (144 - 16) × 0.5 is 0.078, which the shared slowdown's steps make 0.1
                    assertSlow(helper, player, 0.1, "at a load of 36");
                    player.getInventory().clearContent();
                } catch (RuntimeException e) {
                    cleanUp.run();
                    throw e;
                }
            });
            helper.runAfterDelay(2 * SETTLE, () -> {
                try {
                    assertSlow(helper, player, 0.0, "after the load was put down");
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

    // /yolt config writes the file and swaps the config object; the test puts the file back and reloads it at its end
    @GameTest(environment = WEIGHT_CONFIG, maxTicks = 40)
    public void config2ConfigCommandChangesAWeightNumberLive(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        byte[] fileBefore = Files.readAllBytes(Settings.path());
        ServerPlayer player = TestPlayers.join(helper, "Overloaded", GameType.SURVIVAL, AT);
        Runnable cleanUp = BodyTestKit.once(() -> {
            player.getInventory().clearContent();
            TestPlayers.leave(player);
            try {
                ConfigCommandGameTests.restore(server, fileBefore);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        boolean waiting = false;
        try {
            fill(player, Items.COBBLESTONE, 12);
            helper.runAfterDelay(SETTLE, () -> {
                try {
                    assertSlow(helper, player, 0.1, "at a load of 36 with the default free load");
                    helper.assertValueEqual(CommandGameTests.run(server, "yolt config hardcore.weight.freeLoad 20"),
                            List.of("hardcore.weight.freeLoad changed from 16.0 to 20.0."), "the freeLoad reply");

                    Map<String, Double> groups = new LinkedHashMap<>(Settings.get().hardcore.weight.groups);
                    byte[] fileNow = Files.readAllBytes(Settings.path());
                    helper.assertValueEqual(CommandGameTests.run(server, "yolt config hardcore.weight.groups"),
                            List.of("hardcore.weight.groups can only be changed in the config file. Change it there, then run /yolt reload."),
                            "the groups reply");
                    helper.assertValueEqual(Settings.get().hardcore.weight.groups, groups, "groups after the refused command");
                    helper.assertTrue(Arrays.equals(Files.readAllBytes(Settings.path()), fileNow), "the refused command changed the file");
                } catch (IOException e) {
                    cleanUp.run();
                    throw new UncheckedIOException(e);
                } catch (RuntimeException e) {
                    cleanUp.run();
                    throw e;
                }
            });
            // (36 - 20) / (144 - 20) × 0.5 is 0.065, which the shared slowdown's steps make 0.05
            helper.runAfterDelay(2 * SETTLE, () -> {
                try {
                    assertSlow(helper, player, 0.05, "after freeLoad went to 20");
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

    private static void assertWeighs(GameTestHelper helper, HardcoreConfig.WeightSection cfg, ItemStack stack, double expected) {
        double weight = Weight.weigh(stack, cfg, 0);
        helper.assertTrue(Math.abs(weight - expected) < CLOSE, stack + " weighs " + weight + ", not " + expected);
    }

    private static void assertCarries(GameTestHelper helper, ServerPlayer player, HardcoreConfig.WeightSection cfg, double expected,
                                      String when) {
        double load = Weight.carriedWeight(player, cfg);
        helper.assertTrue(Math.abs(load - expected) < CLOSE, "load " + load + " " + when + ", not " + expected);
    }

    // Exhaustion is a float, so the sum is only as close as a float holds it
    private static void assertJumpCosts(GameTestHelper helper, ServerPlayer player, double load, double expected) {
        helper.assertTrue(Math.abs(Weight.load(player) - load) < CLOSE, "load " + Weight.load(player) + ", not " + load);
        FoodDataAccessor food = (FoodDataAccessor) player.getFoodData();
        food.yoltSetExhaustion(0.0F);
        player.jumpFromGround();
        float spent = food.yoltExhaustion();
        helper.assertTrue(Math.abs(spent - expected) < 1e-5, "one jump at a load of " + load + " cost " + spent + ", not " + expected);
    }

    private static void assertSlow(GameTestHelper helper, ServerPlayer player, double expected, String when) {
        double applied = Slowdowns.applied(player);
        helper.assertTrue(Math.abs(applied - expected) < CLOSE, "slowdown " + applied + " " + when + ", not " + expected);
    }

    // Full stacks into the first empty slots
    private static void fill(ServerPlayer player, Item item, int stacks) {
        for (int i = 0; i < stacks; i++) {
            player.getInventory().add(new ItemStack(item, 64));
        }
    }

    private static ItemStack shulker(List<ItemStack> items) {
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(new ArrayList<>(items)));
        return box;
    }

    private static ItemStack bundle(List<ItemStack> items) {
        ItemStack bundle = new ItemStack(Items.BUNDLE);
        bundle.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(items.stream().map(ItemStackTemplate::fromNonEmptyStack).toList()));
        return bundle;
    }
}
