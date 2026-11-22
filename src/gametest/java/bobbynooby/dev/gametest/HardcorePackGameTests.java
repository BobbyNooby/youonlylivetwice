package bobbynooby.dev.gametest;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.core.Profiles;
import bobbynooby.dev.graves.GraveItems;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// The revive paper's look is swept once a second, so each test checks one tick past a full second after setting up.
// The switch is held by each test's environment, since it has to stay as it is across those ticks.
public class HardcorePackGameTests {
    private static final String ON = "you-only-live-twice-gametest:hardcore_pack";
    private static final String OFF = "you-only-live-twice-gametest:hardcore_pack_off";
    // Each on with a batch of its own, since its test changes the shared resourcePack keys halfway through
    private static final String MODEL_CHANGE = "you-only-live-twice-gametest:hardcore_pack_model";
    private static final String MENUS = "you-only-live-twice-gametest:hardcore_pack_menus";
    private static final Identifier MODEL = Identifier.parse("you-only-live-twice:revive_paper");
    private static final Identifier OTHER = Identifier.parse("you-only-live-twice:revive_paper_two");
    private static final Identifier PAPER = Identifier.withDefaultNamespace("paper");
    private static final Identifier BOOK = Identifier.withDefaultNamespace("book");
    private static final int SWEPT = 21;
    private static final int SLOT_PAPER = 0;
    private static final int SLOT_PLAIN = 1;
    private static final int SLOT_GRAVE_HEAD = 2;
    private static final int SLOT_CUSTOM = 3;

    @GameTest(environment = ON, maxTicks = 40)
    public void pack1SwitchOnGivesTheModel(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "PackOn", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        boolean waiting = false;
        try {
            ItemStack crafted = craft(helper, GraveItems.graveHead(Profiles.of(player)));
            helper.assertTrue(GraveItems.isReviveHead(crafted), "the recipe made no revive paper");
            helper.assertValueEqual(crafted.get(DataComponents.ITEM_MODEL), MODEL, "crafted paper's model");

            // Made before the switch went on, so none of them has a model of its own yet
            Inventory inventory = player.getInventory();
            inventory.setItem(SLOT_PAPER, paper());
            inventory.setItem(SLOT_PLAIN, new ItemStack(Items.PAPER));
            inventory.setItem(SLOT_GRAVE_HEAD, GraveItems.graveHead(ghost()));
            inventory.setItem(SLOT_CUSTOM, withModel(paper(), BOOK));
            player.setItemInHand(InteractionHand.OFF_HAND, paper());
            helper.assertFalse(inventory.getItem(SLOT_PAPER).hasNonDefault(DataComponents.ITEM_MODEL), "a paper had a model already");

            helper.runAfterDelay(SWEPT, () -> {
                try {
                    helper.assertValueEqual(inventory.getItem(SLOT_PAPER).get(DataComponents.ITEM_MODEL), MODEL, "inventory paper's model");
                    helper.assertValueEqual(player.getItemInHand(InteractionHand.OFF_HAND).get(DataComponents.ITEM_MODEL), MODEL,
                            "off-hand paper's model");
                    assertNoModelOfItsOwn(helper, inventory.getItem(SLOT_PLAIN), "plain paper");
                    assertNoModelOfItsOwn(helper, inventory.getItem(SLOT_GRAVE_HEAD), "grave head");
                    helper.assertValueEqual(inventory.getItem(SLOT_CUSTOM).get(DataComponents.ITEM_MODEL), BOOK, "custom model");
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

    @GameTest(environment = OFF, maxTicks = 40)
    public void pack1SwitchOffTakesItAway(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "PackOff", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        boolean waiting = false;
        try {
            ItemStack crafted = craft(helper, GraveItems.graveHead(Profiles.of(player)));
            helper.assertTrue(GraveItems.isReviveHead(crafted), "the recipe made no revive paper");
            assertNoModelOfItsOwn(helper, crafted, "crafted paper");

            // Made while the switch was on
            Inventory inventory = player.getInventory();
            inventory.setItem(SLOT_PAPER, withModel(paper(), MODEL));
            inventory.setItem(SLOT_PLAIN, new ItemStack(Items.PAPER));
            inventory.setItem(SLOT_GRAVE_HEAD, GraveItems.graveHead(ghost()));
            player.setItemInHand(InteractionHand.OFF_HAND, withModel(paper(), MODEL));

            helper.runAfterDelay(SWEPT, () -> {
                try {
                    assertNoModelOfItsOwn(helper, inventory.getItem(SLOT_PAPER), "inventory paper");
                    assertNoModelOfItsOwn(helper, player.getItemInHand(InteractionHand.OFF_HAND), "off-hand paper");
                    helper.assertTrue(GraveItems.isReviveHead(inventory.getItem(SLOT_PAPER)), "the paper lost its marker");
                    assertNoModelOfItsOwn(helper, inventory.getItem(SLOT_PLAIN), "plain paper");
                    assertNoModelOfItsOwn(helper, inventory.getItem(SLOT_GRAVE_HEAD), "grave head");
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

    @GameTest(environment = OFF, maxTicks = 40)
    public void pack1ACustomModelIsLeftAlone(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "PackCustom", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        boolean waiting = false;
        try {
            Inventory inventory = player.getInventory();
            inventory.setItem(SLOT_CUSTOM, withModel(paper(), BOOK));
            helper.runAfterDelay(SWEPT, () -> {
                try {
                    helper.assertValueEqual(inventory.getItem(SLOT_CUSTOM).get(DataComponents.ITEM_MODEL), BOOK, "custom model");
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

    // reviveModel needs no restart: papers that carry the model YOLT gave them take a new one at the next sweep, and lose
    // whichever one they carry when the switch goes off. One paper starts as YOLT left papers before it noted the model
    // it set, showing the model with no note. A model set by hand stays through both changes.
    @GameTest(environment = MODEL_CHANGE, maxTicks = 80)
    public void pack1ChangedReviveModelReachesExistingPapers(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "PackModel", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        YoltConfig.ResourcePackSection cfg = Settings.get().resourcePack;
        String modelBefore = cfg.reviveModel;
        boolean switchBefore = cfg.reviveTexture;
        Runnable cleanUp = BodyTestKit.once(() -> {
            cfg.reviveModel = modelBefore;
            cfg.reviveTexture = switchBefore;
            TestPlayers.leave(player);
        });
        ResolvableProfile owner = ghost();
        Inventory inventory = player.getInventory();
        boolean waiting = false;
        try {
            helper.assertValueEqual(Identifier.parse(cfg.reviveModel), MODEL, "reviveModel at the start");
            inventory.setItem(SLOT_PAPER, ReviveGameTests.reviveHead(owner, 1));
            inventory.setItem(SLOT_PLAIN, withModel(ReviveGameTests.reviveHead(owner, 1), MODEL));
            inventory.setItem(SLOT_CUSTOM, withModel(paper(), BOOK));
            helper.runAfterDelay(SWEPT, () -> step(cleanUp, () -> {
                helper.assertValueEqual(inventory.getItem(SLOT_PAPER).get(DataComponents.ITEM_MODEL), MODEL, "new paper's model");
                helper.assertValueEqual(inventory.getItem(SLOT_PLAIN).get(DataComponents.ITEM_MODEL), MODEL, "older paper's model");
                cfg.reviveModel = OTHER.toString();
            }));
            helper.runAfterDelay(2 * SWEPT, () -> step(cleanUp, () -> {
                helper.assertValueEqual(inventory.getItem(SLOT_PAPER).get(DataComponents.ITEM_MODEL), OTHER,
                        "new paper's model after reviveModel changed");
                helper.assertValueEqual(inventory.getItem(SLOT_PLAIN).get(DataComponents.ITEM_MODEL), OTHER,
                        "older paper's model after reviveModel changed");
                helper.assertValueEqual(inventory.getItem(SLOT_CUSTOM).get(DataComponents.ITEM_MODEL), BOOK,
                        "custom model after reviveModel changed");
                cfg.reviveTexture = false;
            }));
            helper.runAfterDelay(3 * SWEPT, () -> {
                try {
                    for (int slot : List.of(SLOT_PAPER, SLOT_PLAIN)) {
                        ItemStack swept = inventory.getItem(slot);
                        assertNoModelOfItsOwn(helper, swept, "paper in slot " + slot + " after the switch went off");
                        helper.assertTrue(GraveItems.isReviveHead(swept), "the paper in slot " + slot + " lost its marker");
                        // As the recipe made it again, so it stacks with a paper that never had the model
                        helper.assertTrue(ItemStack.isSameItemSameComponents(swept, ReviveGameTests.reviveHead(owner, 1)),
                                "the paper in slot " + slot + " doesn't match a fresh one: " + swept.getComponentsPatch());
                    }
                    helper.assertValueEqual(inventory.getItem(SLOT_CUSTOM).get(DataComponents.ITEM_MODEL), BOOK,
                            "custom model after the switch went off");
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

    // The sweep reaches the open chest's slots and the cursor, and tells the chest its contents changed so its chunk is
    // saved with them. Papers in a closed chest, an item frame and on the ground wait until a player has them
    // (docs/known-limits.md). The switch then goes off, and the open chest's and the cursor's papers lose the model.
    @GameTest(environment = MENUS, maxTicks = 80)
    public void pack1PapersOnCursorAndInOpenChestFollowTheSwitch(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = TestPlayers.join(helper, "PackMenus", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        YoltConfig.ResourcePackSection cfg = Settings.get().resourcePack;
        boolean switchBefore = cfg.reviveTexture;
        List<Entity> made = new ArrayList<>();
        Runnable cleanUp = BodyTestKit.once(() -> {
            cfg.reviveTexture = switchBefore;
            player.closeContainer();
            made.forEach(Entity::discard);
            TestPlayers.leave(player);
        });
        boolean waiting = false;
        try {
            BlockPos openAt = new BlockPos(3, 1, 1);
            BlockPos closedAt = new BlockPos(5, 1, 1);
            helper.setBlock(openAt, Blocks.CHEST);
            helper.setBlock(closedAt, Blocks.CHEST);
            ChestBlockEntity open = helper.getBlockEntity(openAt, ChestBlockEntity.class);
            ChestBlockEntity closed = helper.getBlockEntity(closedAt, ChestBlockEntity.class);
            open.setItem(0, paper());
            closed.setItem(0, paper());
            player.openMenu(open);
            helper.assertTrue(player.containerMenu.slots.getFirst().container == open, "the chest's menu is not open");
            player.containerMenu.setCarried(paper());

            BlockPos wall = new BlockPos(3, 1, 4);
            helper.setBlock(wall, Blocks.STONE);
            ItemFrame frame = new ItemFrame(level, helper.absolutePos(wall).north(), Direction.NORTH);
            frame.setItem(paper(), false);
            level.addFreshEntity(frame);
            made.add(frame);
            Vec3 ground = helper.absoluteVec(new Vec3(6.5, 1.0, 4.5));
            ItemEntity dropped = new ItemEntity(level, ground.x, ground.y, ground.z, paper(), 0.0, 0.0, 0.0);
            dropped.setNeverPickUp();
            level.addFreshEntity(dropped);
            made.add(dropped);

            // Every tick until the sweep gives the open chest's paper its model, the chunk counts as saved, so only the
            // sweep's own change can leave it unsaved
            LevelChunk chunk = level.getChunkAt(helper.absolutePos(openAt));
            helper.onEachTick(() -> {
                if (!open.getItem(0).hasNonDefault(DataComponents.ITEM_MODEL)) {
                    chunk.tryMarkSaved();
                }
            });
            helper.runAfterDelay(SWEPT, () -> step(cleanUp, () -> {
                helper.assertValueEqual(open.getItem(0).get(DataComponents.ITEM_MODEL), MODEL, "open chest's paper's model");
                helper.assertTrue(chunk.isUnsaved(), "the open chest's chunk wasn't told its paper changed");
                helper.assertValueEqual(player.containerMenu.getCarried().get(DataComponents.ITEM_MODEL), MODEL, "cursor paper's model");
                assertNoModelOfItsOwn(helper, closed.getItem(0), "closed chest's paper");
                assertNoModelOfItsOwn(helper, frame.getItem(), "item frame's paper");
                assertNoModelOfItsOwn(helper, dropped.getItem(), "paper on the ground");
                cfg.reviveTexture = false;
            }));
            helper.runAfterDelay(2 * SWEPT, () -> {
                try {
                    assertNoModelOfItsOwn(helper, open.getItem(0), "open chest's paper after the switch went off");
                    assertNoModelOfItsOwn(helper, player.containerMenu.getCarried(), "cursor paper after the switch went off");
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

    // A failed check ends the test at once, so the switches go back and the player isn't left online for the batch
    private static void step(Runnable cleanUp, Runnable check) {
        try {
            check.run();
        } catch (RuntimeException e) {
            cleanUp.run();
            throw e;
        }
    }

    // Shows the model its item type gives it, with nothing set on the stack itself
    private static void assertNoModelOfItsOwn(GameTestHelper helper, ItemStack stack, String what) {
        helper.assertFalse(stack.hasNonDefault(DataComponents.ITEM_MODEL), what + " has a model of its own: " + stack.get(DataComponents.ITEM_MODEL));
        Identifier expected = stack.is(Items.PAPER) ? PAPER : Identifier.withDefaultNamespace("player_head");
        helper.assertValueEqual(stack.get(DataComponents.ITEM_MODEL), expected, what + "'s model");
    }

    private static ItemStack paper() {
        return ReviveGameTests.reviveHead(ghost(), 1);
    }

    private static ItemStack withModel(ItemStack stack, Identifier model) {
        stack.set(DataComponents.ITEM_MODEL, model);
        return stack;
    }

    // Static, so nothing tries to look the owner up
    private static ResolvableProfile ghost() {
        return ResolvableProfile.createResolved(new GameProfile(UUID.randomUUID(), "Ghost"));
    }

    // The grave head and the recipe's five materials, from the grid's top-left corner
    private static ItemStack craft(GameTestHelper helper, ItemStack graveHead) {
        List<ItemStack> items = new ArrayList<>(Collections.nCopies(9, ItemStack.EMPTY));
        List<ItemStack> stacks = List.of(graveHead, new ItemStack(Items.NETHER_STAR), new ItemStack(Items.NETHERITE_INGOT),
                new ItemStack(Items.DIAMOND), new ItemStack(Items.GOLD_INGOT), new ItemStack(Items.IRON_INGOT));
        for (int i = 0; i < stacks.size(); i++) {
            items.set(i, stacks.get(i));
        }
        CraftingInput input = CraftingInput.of(3, 3, items);
        ServerLevel level = helper.getLevel();
        Optional<RecipeHolder<CraftingRecipe>> recipe = level.getServer().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, level);
        helper.assertTrue(recipe.isPresent(), "no recipe for a grave head and its five materials");
        return recipe.get().value().assemble(input);
    }
}
