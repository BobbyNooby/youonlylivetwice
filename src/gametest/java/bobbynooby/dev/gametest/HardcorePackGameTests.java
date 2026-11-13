package bobbynooby.dev.gametest;

import bobbynooby.dev.core.Profiles;
import bobbynooby.dev.graves.GraveItems;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;

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
    private static final Identifier MODEL = Identifier.parse("you-only-live-twice:revive_paper");
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
