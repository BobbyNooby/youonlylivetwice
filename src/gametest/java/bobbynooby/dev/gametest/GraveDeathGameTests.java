package bobbynooby.dev.gametest;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.graves.Grave;
import bobbynooby.dev.graves.GraveItems;
import bobbynooby.dev.graves.GraveStore;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.gamerules.GameRules;

import java.util.List;
import java.util.Map;
import java.util.UUID;

// Each kill runs inside one tick, so settings and game rules are restored before any other test runs
public class GraveDeathGameTests {
    @GameTest
    public void deathCreatesGraveWithItemsAndHead(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Grave");
        try {
            player.getInventory().add(new ItemStack(Items.DIAMOND));
            player.getInventory().add(new ItemStack(Items.IRON_SWORD));
            kill(helper, player);

            Grave grave = onlyGrave(helper, player);
            List<ItemStack> items = grave.items();
            helper.assertValueEqual(items.size(), 3, "grave item count");
            helper.assertTrue(GraveItems.isGraveHead(items.get(0)), "grave head is not the first item");
            helper.assertTrue(items.get(1).is(Items.DIAMOND), "second item is not the diamond");
            helper.assertTrue(items.get(2).is(Items.IRON_SWORD), "third item is not the sword");
            helper.assertTrue(player.getInventory().isEmpty(), "player kept items");
            assertHeadBlock(helper, grave);
        } finally {
            cleanUp(helper, player);
        }
        helper.succeed();
    }

    // The death kick saves the emptied player straight away, so the grave has to be in the file by then
    @GameTest
    public void deathWritesGraveToDisk(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Disk");
        try {
            player.getInventory().add(new ItemStack(Items.DIAMOND));
            kill(helper, player);

            Grave grave = onlyGrave(helper, player);
            List<Grave> onDisk = GraveStoreGameTests.onDisk(helper.getLevel().getServer()).stream()
                    .filter(saved -> saved.id().equals(grave.id()))
                    .toList();
            helper.assertValueEqual(onDisk.size(), 1, "copies of the grave on disk");
            helper.assertTrue(contains(onDisk.get(0), Items.DIAMOND), "grave on disk lacks the diamond");
        } finally {
            cleanUp(helper, player);
        }
        helper.succeed();
    }

    @GameTest
    public void armourAndOffhandGoInTheGrave(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Armour");
        Map<EquipmentSlot, Item> worn = Map.of(EquipmentSlot.HEAD, Items.IRON_HELMET, EquipmentSlot.CHEST, Items.IRON_CHESTPLATE,
                EquipmentSlot.LEGS, Items.IRON_LEGGINGS, EquipmentSlot.FEET, Items.IRON_BOOTS, EquipmentSlot.OFFHAND, Items.SHIELD);
        try {
            worn.forEach((slot, item) -> player.setItemSlot(slot, new ItemStack(item)));
            kill(helper, player);

            Grave grave = onlyGrave(helper, player);
            worn.forEach((slot, item) -> {
                helper.assertTrue(contains(grave, item), item + " from the " + slot + " slot missing from the grave");
                helper.assertTrue(player.getItemBySlot(slot).isEmpty(), slot + " slot still holds an item");
            });
        } finally {
            cleanUp(helper, player);
        }
        helper.succeed();
    }

    @GameTest
    public void emptyInventoryStillMakesGrave(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Empty");
        try {
            kill(helper, player);

            Grave grave = onlyGrave(helper, player);
            helper.assertValueEqual(grave.items().size(), 1, "grave item count");
            helper.assertTrue(GraveItems.isGraveHead(grave.items().get(0)), "the only item is not the grave head");
            assertHeadBlock(helper, grave);
        } finally {
            cleanUp(helper, player);
        }
        helper.succeed();
    }

    @GameTest
    public void keepInventoryLeavesItemsAndGraveHasOnlyHead(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        GameRules rules = server.getGameRules();
        ServerPlayer player = join(helper, "Keep");
        try {
            player.getInventory().add(new ItemStack(Items.DIAMOND));
            boolean before = rules.get(GameRules.KEEP_INVENTORY);
            rules.set(GameRules.KEEP_INVENTORY, true, server);
            try {
                kill(helper, player);
            } finally {
                rules.set(GameRules.KEEP_INVENTORY, before, server);
            }

            helper.assertTrue(player.getInventory().contains(new ItemStack(Items.DIAMOND)), "player lost the diamond");
            Grave grave = onlyGrave(helper, player);
            helper.assertValueEqual(grave.items().size(), 1, "grave item count");
            helper.assertTrue(GraveItems.isGraveHead(grave.items().get(0)), "the only item is not the grave head");
        } finally {
            cleanUp(helper, player);
        }
        helper.succeed();
    }

    @GameTest
    public void curseOfVanishingItemsDoNotEnterGrave(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Vanish");
        try {
            ItemStack helmet = new ItemStack(Items.IRON_HELMET);
            helmet.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                    .getOrThrow(Enchantments.VANISHING_CURSE), 1);
            player.setItemSlot(EquipmentSlot.HEAD, helmet);
            player.getInventory().add(new ItemStack(Items.DIAMOND));
            kill(helper, player);

            Grave grave = onlyGrave(helper, player);
            helper.assertFalse(contains(grave, Items.IRON_HELMET), "cursed helmet went into the grave");
            helper.assertTrue(contains(grave, Items.DIAMOND), "diamond missing from the grave");
            helper.assertTrue(player.getItemBySlot(EquipmentSlot.HEAD).isEmpty(), "cursed helmet stayed on the player");
            helper.assertItemEntityNotPresent(Items.IRON_HELMET, helper.relativePos(player.blockPosition()), 3.0);
        } finally {
            cleanUp(helper, player);
        }
        helper.succeed();
    }

    @GameTest
    public void cursorAndCraftingGridItemsGoInTheGrave(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Cursor");
        try {
            player.containerMenu.setCarried(new ItemStack(Items.GOLD_INGOT));
            player.inventoryMenu.getSlot(InventoryMenu.CRAFT_SLOT_START).set(new ItemStack(Items.STICK));
            kill(helper, player);

            Grave grave = onlyGrave(helper, player);
            helper.assertTrue(contains(grave, Items.GOLD_INGOT), "cursor item missing from the grave");
            helper.assertTrue(contains(grave, Items.STICK), "crafting grid item missing from the grave");
            helper.assertTrue(player.containerMenu.getCarried().isEmpty(), "cursor still holds an item");
            assertNoItems(helper, player.inventoryMenu, InventoryMenu.CRAFT_SLOT_START, InventoryMenu.CRAFT_SLOT_END);
            BlockPos at = helper.relativePos(player.blockPosition());
            helper.assertItemEntityNotPresent(Items.GOLD_INGOT, at, 3.0);
            helper.assertItemEntityNotPresent(Items.STICK, at, 3.0);
        } finally {
            cleanUp(helper, player);
        }
        helper.succeed();
    }

    // Any open menu is closed through its own code, so a crafting table stands in for the other kinds
    @GameTest
    public void openCraftingTableItemsGoInTheGrave(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Table");
        try {
            BlockPos table = new BlockPos(2, 1, 1);
            helper.setBlock(table, Blocks.CRAFTING_TABLE);
            ContainerLevelAccess access = ContainerLevelAccess.create(helper.getLevel(), helper.absolutePos(table));
            player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new CraftingMenu(id, inventory, access), Component.empty()));
            AbstractContainerMenu menu = player.containerMenu;
            helper.assertTrue(menu instanceof CraftingMenu, "crafting table menu did not open");
            menu.setCarried(new ItemStack(Items.GOLD_INGOT));
            menu.getSlot(1).set(new ItemStack(Items.FEATHER));
            kill(helper, player);

            Grave grave = onlyGrave(helper, player);
            helper.assertTrue(contains(grave, Items.GOLD_INGOT), "cursor item missing from the grave");
            helper.assertTrue(contains(grave, Items.FEATHER), "crafting table item missing from the grave");
            helper.assertTrue(player.containerMenu == player.inventoryMenu, "crafting table menu still open");
            helper.assertTrue(menu.getCarried().isEmpty(), "cursor still holds an item");
            assertNoItems(helper, menu, 1, 10);
            BlockPos at = helper.relativePos(player.blockPosition());
            helper.assertItemEntityNotPresent(Items.GOLD_INGOT, at, 3.0);
            helper.assertItemEntityNotPresent(Items.FEATHER, at, 3.0);
        } finally {
            cleanUp(helper, player);
        }
        helper.succeed();
    }

    @GameTest
    public void enderChestKeep(GameTestHelper helper) {
        ServerPlayer player = join(helper, "EnderKeep");
        try {
            player.getEnderChestInventory().setItem(0, new ItemStack(Items.EMERALD));
            killWithEnderMode(helper, player, YoltConfig.EnderChestMode.KEEP);

            helper.assertTrue(player.getEnderChestInventory().getItem(0).is(Items.EMERALD), "ender chest lost its emerald");
            helper.assertFalse(contains(onlyGrave(helper, player), Items.EMERALD), "grave holds the ender chest emerald");
        } finally {
            cleanUp(helper, player);
        }
        helper.succeed();
    }

    @GameTest
    public void enderChestGrave(GameTestHelper helper) {
        ServerPlayer player = join(helper, "EnderGrave");
        try {
            player.getEnderChestInventory().setItem(0, new ItemStack(Items.EMERALD));
            killWithEnderMode(helper, player, YoltConfig.EnderChestMode.GRAVE);

            helper.assertTrue(contains(onlyGrave(helper, player), Items.EMERALD), "grave lacks the ender chest emerald");
            helper.assertTrue(player.getEnderChestInventory().isEmpty(), "ender chest still holds items");
        } finally {
            cleanUp(helper, player);
        }
        helper.succeed();
    }

    @GameTest
    public void enderChestWipe(GameTestHelper helper) {
        ServerPlayer player = join(helper, "EnderWipe");
        try {
            player.getEnderChestInventory().setItem(0, new ItemStack(Items.EMERALD));
            killWithEnderMode(helper, player, YoltConfig.EnderChestMode.WIPE);

            helper.assertTrue(player.getEnderChestInventory().isEmpty(), "ender chest not wiped");
            helper.assertFalse(contains(onlyGrave(helper, player), Items.EMERALD), "grave holds the wiped emerald");
            // Wiped means gone, not dropped
            helper.assertItemEntityNotPresent(Items.EMERALD, helper.relativePos(player.blockPosition()), 3.0);
        } finally {
            cleanUp(helper, player);
        }
        helper.succeed();
    }

    @GameTest
    public void gravesOffMeansVanillaDrops(GameTestHelper helper) {
        YoltConfig.GravesSection graves = Settings.get().graves;
        ServerPlayer player = join(helper, "NoGrave");
        try {
            player.getInventory().add(new ItemStack(Items.DIAMOND));
            boolean before = graves.enabled;
            graves.enabled = false;
            try {
                kill(helper, player);
            } finally {
                graves.enabled = before;
            }

            helper.assertItemEntityPresent(Items.DIAMOND, helper.relativePos(player.blockPosition()), 2.0);
            helper.assertTrue(gravesOf(helper, player.getUUID()).isEmpty(), "grave made with graves off");
        } finally {
            cleanUp(helper, player);
        }
        helper.succeed();
    }

    @GameTest
    public void moreThan54StacksMakesTwoGraves(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Full");
        try {
            Inventory inventory = player.getInventory();
            for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
                inventory.setItem(i, new ItemStack(Items.COBBLESTONE));
            }
            PlayerEnderChestContainer enderChest = player.getEnderChestInventory();
            for (int i = 0; i < enderChest.getContainerSize(); i++) {
                enderChest.setItem(i, new ItemStack(Items.EMERALD));
            }
            killWithEnderMode(helper, player, YoltConfig.EnderChestMode.GRAVE);

            // The store keeps graves in the order they were made
            List<Grave> found = gravesOf(helper, player.getUUID());
            helper.assertValueEqual(found.size(), 2, "graves for the player");
            Grave first = found.get(0);
            Grave second = found.get(1);
            helper.assertValueEqual(first.items().size() + second.items().size(), 64, "stacks across both graves");
            helper.assertValueEqual(first.items().size(), 54, "stacks in the first grave");
            helper.assertTrue(GraveItems.isGraveHead(first.items().get(0)), "first grave does not start with the head");
            helper.assertFalse(second.items().stream().anyMatch(GraveItems::isGraveHead), "second grave holds a grave head");
            helper.assertFalse(first.pos().equals(second.pos()), "both graves on one block");
            assertHeadBlock(helper, first);
            assertHeadBlock(helper, second);
        } finally {
            cleanUp(helper, player);
        }
        helper.succeed();
    }

    private static ServerPlayer join(GameTestHelper helper, String name) {
        return TestPlayers.join(helper, name, GameType.SURVIVAL, new BlockPos(1, 1, 1));
    }

    private static void kill(GameTestHelper helper, ServerPlayer player) {
        TestPlayers.markLoaded(player);
        player.kill(player.level());
        helper.assertTrue(player.isDeadOrDying(), "player did not die");
    }

    private static void killWithEnderMode(GameTestHelper helper, ServerPlayer player, YoltConfig.EnderChestMode mode) {
        YoltConfig.GravesSection graves = Settings.get().graves;
        YoltConfig.EnderChestMode before = graves.enderChestOnDeath;
        graves.enderChestOnDeath = mode;
        try {
            kill(helper, player);
        } finally {
            graves.enderChestOnDeath = before;
        }
    }

    private static List<Grave> gravesOf(GameTestHelper helper, UUID owner) {
        return GraveStore.get(helper.getLevel().getServer()).all().stream()
                .filter(grave -> grave.owner().equals(owner))
                .toList();
    }

    private static Grave onlyGrave(GameTestHelper helper, ServerPlayer player) {
        List<Grave> graves = gravesOf(helper, player.getUUID());
        helper.assertValueEqual(graves.size(), 1, "graves for the player");
        return graves.get(0);
    }

    private static boolean contains(Grave grave, Item item) {
        return grave.items().stream().anyMatch(stack -> stack.is(item));
    }

    private static void assertNoItems(GameTestHelper helper, AbstractContainerMenu menu, int fromSlot, int toSlot) {
        for (int i = fromSlot; i < toSlot; i++) {
            helper.assertFalse(menu.getSlot(i).hasItem(), "menu slot " + i + " still holds an item");
        }
    }

    private static void assertHeadBlock(GameTestHelper helper, Grave grave) {
        ServerLevel level = helper.getLevel();
        helper.assertTrue(level.getBlockState(grave.pos()).is(Blocks.PLAYER_HEAD), "no player head at " + grave.pos());
        helper.assertTrue(level.getBlockEntity(grave.pos()) instanceof SkullBlockEntity skull
                && grave.profile().equals(skull.getOwnerProfile()), "head at " + grave.pos() + " lacks the owner's profile");
    }

    // Leaving first means the queued death kick finds nobody; the graves go too, so no test sees another's
    private static void cleanUp(GameTestHelper helper, ServerPlayer player) {
        TestPlayers.leave(player);
        GraveStore store = GraveStore.get(helper.getLevel().getServer());
        for (Grave grave : gravesOf(helper, player.getUUID())) {
            store.remove(grave.id());
            helper.getLevel().setBlock(grave.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }
}
