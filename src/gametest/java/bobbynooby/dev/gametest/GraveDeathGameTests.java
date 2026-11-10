package bobbynooby.dev.gametest;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.graves.Grave;
import bobbynooby.dev.graves.GraveDeath;
import bobbynooby.dev.graves.GraveItems;
import bobbynooby.dev.graves.GraveStore;
import bobbynooby.dev.lockout.DeathLockout;
import bobbynooby.dev.mixin.PlayerListInvoker;
import bobbynooby.dev.offline.OfflinePlayerData;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.BeaconMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// Each kill runs inside one tick, so settings and game rules are restored before any other test runs
public class GraveDeathGameTests {
    // Holds graves.tellLocation across ticks, until a kick
    private static final String GRAVE_NOTICE = "you-only-live-twice-gametest:grave_notice";

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
            helper.assertTrue(ItemStack.matches(items.get(1), new ItemStack(Items.DIAMOND)), "second item: " + items.get(1));
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
            helper.assertValueEqual(GraveAsserts.countInGraves(onDisk, Items.DIAMOND), 1, "diamonds in the grave on disk");
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
                helper.assertValueEqual(GraveAsserts.count(grave.items(), item), 1,
                        "grave's " + item + " from the " + slot + " slot");
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

            helper.assertValueEqual(player.getInventory().countItem(Items.DIAMOND), 1, "player's diamonds");
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
            helper.assertValueEqual(GraveAsserts.count(grave.items(), Items.IRON_HELMET), 0,
                    "cursed helmets in the grave");
            helper.assertValueEqual(GraveAsserts.count(grave.items(), Items.DIAMOND), 1, "diamonds in the grave");
            helper.assertTrue(player.getItemBySlot(EquipmentSlot.HEAD).isEmpty(), "cursed helmet stayed on the player");
            helper.assertValueEqual(itemsNear(helper, Items.IRON_HELMET, player.blockPosition(), 3.0), 0, "cursed helmets dropped");
        } finally {
            cleanUp(helper, player);
        }
        helper.succeed();
    }

    @GameTest
    public void cursorAndCraftingGridItemsGoInTheGrave(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Cursor");
        try {
            player.containerMenu.setCarried(new ItemStack(Items.GOLD_INGOT, 3));
            player.inventoryMenu.getSlot(InventoryMenu.CRAFT_SLOT_START).set(new ItemStack(Items.STICK, 2));
            kill(helper, player);

            Grave grave = onlyGrave(helper, player);
            helper.assertValueEqual(GraveAsserts.count(grave.items(), Items.GOLD_INGOT), 3,
                    "cursor gold ingots in the grave");
            helper.assertValueEqual(GraveAsserts.count(grave.items(), Items.STICK), 2,
                    "crafting grid sticks in the grave");
            helper.assertTrue(player.containerMenu.getCarried().isEmpty(), "cursor still holds an item");
            assertNoItems(helper, player.inventoryMenu, InventoryMenu.CRAFT_SLOT_START, InventoryMenu.CRAFT_SLOT_END);
            BlockPos at = player.blockPosition();
            helper.assertValueEqual(itemsNear(helper, Items.GOLD_INGOT, at, 3.0), 0, "gold ingots dropped");
            helper.assertValueEqual(itemsNear(helper, Items.STICK, at, 3.0), 0, "sticks dropped");
        } finally {
            cleanUp(helper, player);
        }
        helper.succeed();
    }

    // The beacon payment slot is thrown out by the menu's own close code, not the cursor or crafting path
    @GameTest
    public void beaconMenuItemGoesInTheGrave(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Beacon");
        try {
            player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new BeaconMenu(id, inventory), Component.empty()));
            AbstractContainerMenu menu = player.containerMenu;
            helper.assertTrue(menu instanceof BeaconMenu, "beacon menu did not open");
            menu.getSlot(0).set(new ItemStack(Items.IRON_INGOT));
            kill(helper, player);

            Grave grave = onlyGrave(helper, player);
            int ingots = grave.items().stream().filter(stack -> stack.is(Items.IRON_INGOT)).mapToInt(ItemStack::getCount).sum();
            helper.assertValueEqual(ingots, 1, "iron ingots in the grave");
            helper.assertValueEqual(itemsNear(helper, Items.IRON_INGOT, player.blockPosition(), 3.0), 0, "iron ingots dropped");
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
            menu.setCarried(new ItemStack(Items.GOLD_INGOT, 3));
            menu.getSlot(1).set(new ItemStack(Items.FEATHER, 4));
            kill(helper, player);

            Grave grave = onlyGrave(helper, player);
            helper.assertValueEqual(GraveAsserts.count(grave.items(), Items.GOLD_INGOT), 3,
                    "cursor gold ingots in the grave");
            helper.assertValueEqual(GraveAsserts.count(grave.items(), Items.FEATHER), 4,
                    "crafting table feathers in the grave");
            helper.assertTrue(player.containerMenu == player.inventoryMenu, "crafting table menu still open");
            helper.assertTrue(menu.getCarried().isEmpty(), "cursor still holds an item");
            assertNoItems(helper, menu, 1, 10);
            BlockPos at = player.blockPosition();
            helper.assertValueEqual(itemsNear(helper, Items.GOLD_INGOT, at, 3.0), 0, "gold ingots dropped");
            helper.assertValueEqual(itemsNear(helper, Items.FEATHER, at, 3.0), 0, "feathers dropped");
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

            helper.assertValueEqual(player.getEnderChestInventory().countItem(Items.EMERALD), 1, "emeralds in the ender chest");
            helper.assertValueEqual(GraveAsserts.count(onlyGrave(helper, player).items(), Items.EMERALD), 0,
                    "emeralds in the grave");
        } finally {
            cleanUp(helper, player);
        }
        helper.succeed();
    }

    @GameTest
    public void enderChestGrave(GameTestHelper helper) {
        ServerPlayer player = join(helper, "EnderGrave");
        try {
            player.getEnderChestInventory().setItem(0, new ItemStack(Items.EMERALD, 5));
            killWithEnderMode(helper, player, YoltConfig.EnderChestMode.GRAVE);

            helper.assertValueEqual(GraveAsserts.count(onlyGrave(helper, player).items(), Items.EMERALD), 5,
                    "emeralds in the grave");
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
            helper.assertValueEqual(GraveAsserts.count(onlyGrave(helper, player).items(), Items.EMERALD), 0,
                    "wiped emeralds in the grave");
            // Wiped means gone, not dropped
            helper.assertValueEqual(itemsNear(helper, Items.EMERALD, player.blockPosition(), 3.0), 0, "emeralds dropped");
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

            // Every item entity in the test area, so a second drop beside vanilla's shows wherever it lands
            List<ItemEntity> drops = helper.getEntities(EntityTypes.ITEM);
            helper.assertValueEqual(drops.size(), 1, "item entities in the test area");
            helper.assertTrue(ItemStack.matches(drops.get(0).getItem(), new ItemStack(Items.DIAMOND)),
                    "dropped item: " + drops.get(0).getItem());
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

    // A put-back that throws drops what is left of the stack at the player: all of a stack it never took, and only the
    // rest of one it took part of before it threw
    @GameTest
    public void failedPutBackDropsTheStack(GameTestHelper helper) {
        ServerPlayer player = join(helper, "PutBack");
        BlockPos at = player.blockPosition();
        try {
            GraveDeath.putBackOrDrop(player, new ItemStack(Items.DIAMOND, 5), stack -> {
                throw new IllegalStateException("a put-back that fails, for this test");
            });
            GraveDeath.putBackOrDrop(player, new ItemStack(Items.EMERALD, 4), stack -> {
                player.getInventory().add(stack.split(1));
                throw new IllegalStateException("a put-back that fails halfway, for this test");
            });

            helper.assertValueEqual(itemsNear(helper, Items.DIAMOND, at, 3.0), 5, "diamonds dropped");
            helper.assertValueEqual(itemsNear(helper, Items.EMERALD, at, 3.0), 3, "emeralds dropped");
            helper.assertValueEqual(player.getInventory().countItem(Items.EMERALD), 1, "emeralds put back");
        } finally {
            helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(3.0)).forEach(ItemEntity::discard);
            cleanUp(helper, player);
        }
        helper.succeed();
    }

    // A 3x3 lava pool with a stone rim, so the lava has nowhere to flow; the player dies in the middle of it.
    // No ceiling, which the search would take as a floor.
    @GameTest(skyAccess = true)
    public void lavaDeathPutsGraveOutOfLava(GameTestHelper helper) {
        List<BlockPos> lava = new ArrayList<>();
        List<BlockPos> rim = new ArrayList<>();
        ServerPlayer player = null;
        try {
            for (int x = 1; x <= 5; x++) {
                for (int z = 1; z <= 5; z++) {
                    BlockPos pos = new BlockPos(x, 0, z);
                    if (x == 1 || x == 5 || z == 1 || z == 5) {
                        rim.add(pos);
                        helper.setBlock(pos, Blocks.STONE);
                    } else {
                        lava.add(pos);
                        helper.setBlock(pos, Blocks.LAVA);
                    }
                }
            }
            player = TestPlayers.join(helper, "Lava", GameType.SURVIVAL, new BlockPos(3, 0, 3));
            player.getInventory().add(new ItemStack(Items.DIAMOND));
            player.getInventory().add(new ItemStack(Items.IRON_SWORD));
            TestPlayers.markLoaded(player);
            ServerLevel level = helper.getLevel();
            player.hurtServer(level, level.damageSources().lava(), Float.MAX_VALUE);
            helper.assertTrue(player.isDeadOrDying(), "player did not die");

            Grave grave = onlyGrave(helper, player);
            List<BlockPos> pool = lava.stream().map(helper::absolutePos).toList();
            // The death spot is a pool block, so a grave left there, as before fix A1, fails the check after this one
            helper.assertTrue(pool.contains(player.blockPosition()), "the death spot " + player.blockPosition() + " is not in the pool");
            helper.assertFalse(pool.contains(grave.pos()), "grave placed in the lava pool at " + grave.pos());
            BlockPos below = grave.pos().below();
            helper.assertFalse(level.getFluidState(below).is(FluidTags.LAVA), "grave over lava at " + grave.pos());
            helper.assertFalse(level.getBlockState(below).getCollisionShape(level, below).isEmpty(),
                    "nothing solid under the grave at " + grave.pos());
            List<ItemStack> items = grave.items();
            helper.assertValueEqual(items.size(), 3, "grave item count");
            helper.assertTrue(GraveItems.isGraveHead(items.get(0)), "grave head is not the first item");
            helper.assertTrue(ItemStack.matches(items.get(1), new ItemStack(Items.DIAMOND)), "second item: " + items.get(1));
            helper.assertTrue(items.get(2).is(Items.IRON_SWORD), "third item is not the sword");
            assertHeadBlock(helper, grave);
        } finally {
            if (player != null) {
                cleanUp(helper, player);
            }
            for (BlockPos pos : lava) {
                helper.setBlock(pos, Blocks.AIR);
            }
            for (BlockPos pos : rim) {
                helper.setBlock(pos, Blocks.AIR);
            }
        }
        helper.succeed();
    }

    // Killed twenty blocks up, as when shot while gliding: the grave goes where the items would land, on the floor
    @GameTest(skyAccess = true)
    public void midAirDeathGraveLandsOnFloor(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "Glider", GameType.SURVIVAL, new BlockPos(3, 20, 3));
        try {
            player.getInventory().add(new ItemStack(Items.DIAMOND, 2));
            kill(helper, player);

            Grave grave = onlyGrave(helper, player);
            helper.assertValueEqual(grave.pos(), helper.absolutePos(new BlockPos(3, 0, 3)), "grave position");
            helper.assertValueEqual(GraveAsserts.count(grave.items(), Items.DIAMOND), 2, "diamonds in the grave");
            assertHeadBlock(helper, grave);
        } finally {
            cleanUp(helper, player);
        }
        helper.succeed();
    }

    // With no kick to save them, the emptied players are saved once the kick would have come: one dies with the lockout
    // off, one with a lockout of 0 days. Each save first holds the items, like an autosave before the death, so only a
    // save after the death can empty it before the next autosave. Each switch goes back in the same tick.
    @GameTest(maxTicks = 20)
    public void deathWithoutKickSavesEmptiedPlayer(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.DeathLockoutSection lockout = Settings.get().deathLockout;
        boolean enabledBefore = lockout.enabled;
        int daysBefore = lockout.cooldownDays;
        ServerPlayer lockoutOff = TestPlayers.join(helper, "SavedOff", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        ServerPlayer noDays = TestPlayers.join(helper, "SavedNoDays", GameType.SURVIVAL, new BlockPos(5, 1, 5));
        List<ServerPlayer> players = List.of(lockoutOff, noDays);
        Runnable cleanUp = () -> players.forEach(player -> cleanUp(helper, player));
        boolean waiting = false;
        try {
            for (ServerPlayer player : players) {
                player.getInventory().add(new ItemStack(Items.DIAMOND, 3));
                ((PlayerListInvoker) server.getPlayerList()).yoltSave(player);
                helper.assertValueEqual(savedDiamonds(helper, player), 3, player.nameAndId().name() + "'s saved diamonds before the death");
            }
            lockout.enabled = false;
            try {
                kill(helper, lockoutOff);
            } finally {
                lockout.enabled = enabledBefore;
            }
            lockout.cooldownDays = 0;
            try {
                kill(helper, noDays);
            } finally {
                lockout.cooldownDays = daysBefore;
            }
            // The save is queued from a queued task, and a server running behind runs each within 4 ticks
            helper.runAfterDelay(10, () -> {
                try {
                    for (ServerPlayer player : players) {
                        String name = player.nameAndId().name();
                        helper.assertTrue(server.getPlayerList().getPlayer(player.getUUID()) == player, name + " was kicked");
                        assertSavedDead(helper, player, "after the tick");
                        helper.assertValueEqual(GraveAsserts.countInGraves(server, player.getUUID(), Items.DIAMOND), 3,
                                name + "'s diamonds in the grave");
                    }
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

    // Never says nothing even for a grave far below the death, and always tells even for one where the player died: in
    // chat after the respawn while the lockout is off, and in the kick while it is on. The setting is held until the
    // kick, so the test runs apart.
    @GameTest(environment = GRAVE_NOTICE, skyAccess = true, maxTicks = 30)
    public void tellLocationNeverAndAlways(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        YoltConfig config = Settings.get();
        YoltConfig.TellLocation modeBefore = config.graves.tellLocation;
        boolean lockoutBefore = config.deathLockout.enabled;
        ServerPlayer quiet = TestPlayers.join(helper, "Quiet", GameType.SURVIVAL, new BlockPos(3, 20, 3));
        ServerPlayer told = TestPlayers.join(helper, "Told", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        ServerPlayer kicked = TestPlayers.join(helper, "KickTold", GameType.SURVIVAL, new BlockPos(5, 1, 5));
        List<UUID> owners = List.of(quiet.getUUID(), told.getUUID(), kicked.getUUID());
        Runnable cleanUp = () -> {
            config.graves.tellLocation = modeBefore;
            config.deathLockout.enabled = lockoutBefore;
            for (UUID owner : owners) {
                ServerPlayer online = server.getPlayerList().getPlayer(owner);
                if (online != null) {
                    TestPlayers.leave(online);
                }
                removeGraves(helper, owner);
            }
        };
        boolean waiting = false;
        try {
            config.deathLockout.enabled = false;
            config.graves.tellLocation = YoltConfig.TellLocation.NEVER;
            ServerPlayer quietBack = dieAndRespawn(helper, quiet);
            Grave quietGrave = onlyGrave(helper, quiet);
            helper.assertValueEqual(quietGrave.pos(), helper.absolutePos(new BlockPos(3, 0, 3)), "quiet player's grave, 20 below the death");
            helper.assertValueEqual(graveLines(quietBack), List.of(), "chat with never");

            config.graves.tellLocation = YoltConfig.TellLocation.ALWAYS;
            ServerPlayer toldBack = dieAndRespawn(helper, told);
            Grave toldGrave = onlyGrave(helper, told);
            helper.assertValueEqual(toldGrave.pos(), helper.absolutePos(new BlockPos(1, 0, 1)), "told player's grave, just below the death");
            helper.assertValueEqual(graveLines(toldBack), List.of(graveLine(toldGrave)), "chat with always");

            // On again, so this death kicks; always is held until the kick
            config.deathLockout.enabled = lockoutBefore;
            kill(helper, kicked);
            Grave kickedGrave = onlyGrave(helper, kicked);
            // A server running behind still runs queued tasks within 4 ticks
            helper.runAfterDelay(5, () -> {
                try {
                    helper.assertTrue(server.getPlayerList().getPlayer(kicked.getUUID()) == null, "the dead player was not kicked");
                    String reason = LockoutGameTests.disconnectReason(kicked);
                    helper.assertTrue(reason.startsWith("You died: ") && reason.endsWith("\n" + graveLine(kickedGrave)),
                            "kick reason: " + reason);
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

    // The lockout is on, but a revive in the same tick leaves nothing to kick for. So the player is saved once the kick
    // would have come, like any death that doesn't kick, and told in chat at the respawn. A death 20 up puts the grave
    // far enough below for the default to tell. Only the death's own row changes, so nothing is held across ticks.
    @GameTest(skyAccess = true, maxTicks = 20)
    public void sameTickReviveSavesAndTellsInChat(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TestPlayers.join(helper, "Revived", GameType.SURVIVAL, new BlockPos(3, 20, 3));
        Runnable cleanUp = () -> leaveAndRemoveGraves(helper, player.getUUID());
        boolean waiting = false;
        try {
            assertDefaultTelling(helper);
            helper.assertTrue(Settings.get().deathLockout.enabled && Settings.get().deathLockout.cooldownDays > 0,
                    "the lockout is not on with days to wait");
            player.getInventory().add(new ItemStack(Items.DIAMOND, 3));
            ((PlayerListInvoker) server.getPlayerList()).yoltSave(player);
            helper.assertValueEqual(savedDiamonds(helper, player), 3, "saved diamonds before the death");
            respawnBesideTest(helper, player);
            kill(helper, player);
            helper.assertValueEqual(DeathLockout.revive(player.getUUID(), player.nameAndId().name(), "test"),
                    DeathLockout.ReviveResult.REVIVED, "the revive");
            Grave grave = onlyGrave(helper, player);
            helper.assertValueEqual(grave.pos(), helper.absolutePos(new BlockPos(3, 0, 3)), "grave, 20 below the death");
            // The save is queued from a queued task, and a server running behind runs each within 4 ticks
            helper.runAfterDelay(10, () -> {
                try {
                    helper.assertTrue(server.getPlayerList().getPlayer(player.getUUID()) == player, "the revived player was kicked");
                    assertSavedDead(helper, player, "after the tick");
                    ServerPlayer back = server.getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
                    helper.assertValueEqual(graveLines(back), List.of(graveLine(grave)), "chat at the respawn");
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

    // A death that makes no grave says nothing, though the player has an older grave far from its own death: not in
    // chat at the respawn, and not in the lockout's refusal for a death recorded now. The older grave is a record from
    // an hour ago, in a chunk nothing loads. Every switch goes back in the same tick.
    @GameTest
    public void deathWithNoGraveNamesNoOlderGrave(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig config = Settings.get();
        boolean gravesBefore = config.graves.enabled;
        boolean lockoutBefore = config.deathLockout.enabled;
        ServerPlayer player = TestPlayers.join(helper, "Graveless", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        String name = player.nameAndId().name();
        GraveStore store = GraveStore.get(server);
        BlockPos far = new BlockPos(30000, 70, 30000);
        Grave older = new Grave(UUID.randomUUID(), player.getUUID(), name, ResolvableProfile.createUnresolved(name), Level.OVERWORLD,
                far, List.of(new ItemStack(Items.DIAMOND)), System.currentTimeMillis() - 3_600_000L,
                GlobalPos.of(Level.OVERWORLD, far.east(100)));
        try {
            assertDefaultTelling(helper);
            store.put(older);
            respawnBesideTest(helper, player);
            // Both stay off through the respawn, as on a server that runs without them
            config.graves.enabled = false;
            config.deathLockout.enabled = false;
            ServerPlayer back;
            try {
                kill(helper, player);
                back = server.getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
            } finally {
                config.graves.enabled = gravesBefore;
                config.deathLockout.enabled = lockoutBefore;
            }
            helper.assertValueEqual(graveLines(back), List.of(), "chat at the respawn");

            DeathLockout.recordDeath(player.getUUID(), name);
            helper.assertValueEqual(refusalGraveLine(helper, server, player.getUUID(), name), Optional.empty(),
                    "grave line of the refusal");
        } finally {
            store.remove(older.id());
            leaveAndRemoveGraves(helper, player.getUUID());
        }
        helper.succeed();
    }

    // Killed, then back through the death screen's respawn at a point set beside the test, so no random spot is searched
    private static ServerPlayer dieAndRespawn(GameTestHelper helper, ServerPlayer player) {
        respawnBesideTest(helper, player);
        kill(helper, player);
        return helper.getLevel().getServer().getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
    }

    private static void respawnBesideTest(GameTestHelper helper, ServerPlayer player) {
        player.setRespawnPosition(new ServerPlayer.RespawnConfig(
                LevelData.RespawnData.of(helper.getLevel().dimension(), helper.absolutePos(new BlockPos(7, 1, 7)), 0.0F, 0.0F), true), false);
    }

    // The tests that rely on the defaults: tell only for a grave more than 16 blocks from the death
    private static void assertDefaultTelling(GameTestHelper helper) {
        helper.assertValueEqual(Settings.get().graves.tellLocation, YoltConfig.TellLocation.WHEN_MOVED, "tellLocation");
        helper.assertValueEqual(Settings.get().graves.tellLocationDistance, 16, "tellLocationDistance");
    }

    // The grave line of the refusal the owner gets now, the kick's text too
    private static Optional<String> refusalGraveLine(GameTestHelper helper, MinecraftServer server, UUID owner, String name) {
        Component refusal = DeathLockout.refusal(server, owner, name);
        helper.assertTrue(refusal != null, name + " is not locked out");
        String[] lines = refusal.getString().split("\n", -1);
        helper.assertTrue(lines.length == 2 || lines.length == 3, "refusal: " + refusal.getString());
        return lines.length == 3 ? Optional.of(lines[2]) : Optional.empty();
    }

    // Whichever player object is online for the owner now leaves, so a queued kick finds nobody
    private static void leaveAndRemoveGraves(GameTestHelper helper, UUID owner) {
        ServerPlayer online = helper.getLevel().getServer().getPlayerList().getPlayer(owner);
        if (online != null) {
            TestPlayers.leave(online);
        }
        removeGraves(helper, owner);
    }

    // The grave lines a player was sent in chat, oldest first
    private static List<String> graveLines(ServerPlayer player) {
        return TestPlayers.sentPackets(player).stream()
                .filter(packet -> packet instanceof ClientboundSystemChatPacket)
                .map(packet -> ((ClientboundSystemChatPacket) packet).content().getString())
                .filter(text -> text.startsWith("Your grave is at "))
                .toList();
    }

    private static String graveLine(Grave grave) {
        BlockPos pos = grave.pos();
        return "Your grave is at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + " in the Overworld.";
    }

    private static int savedDiamonds(GameTestHelper helper, ServerPlayer player) {
        MinecraftServer server = helper.getLevel().getServer();
        CompoundTag saved = OfflinePlayerData.load(server, player.nameAndId()).orElseThrow();
        return GraveAsserts.count(OfflinePlayerData.takeInventory(saved, server.registryAccess(), player.nameAndId().name()), Items.DIAMOND);
    }

    // The save on disk is a dead player's with nothing left on them
    private static void assertSavedDead(GameTestHelper helper, ServerPlayer player, String when) {
        MinecraftServer server = helper.getLevel().getServer();
        String name = player.nameAndId().name();
        CompoundTag saved = OfflinePlayerData.load(server, player.nameAndId()).orElseThrow();
        helper.assertTrue(OfflinePlayerData.takeInventory(saved, server.registryAccess(), name).isEmpty(),
                name + "'s save still has items " + when);
        helper.assertValueEqual(saved.getFloatOr("Health", -1.0F), 0.0F, name + "'s saved health " + when);
    }

    // Counts the item across the item entities near an absolute position. Absolute, since vanilla's relativePos turns
    // an unrotated test's position half round.
    static int itemsNear(GameTestHelper helper, Item item, BlockPos absolutePos, double distance) {
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(absolutePos).inflate(distance), Entity::isAlive).stream()
                .map(ItemEntity::getItem)
                .filter(stack -> stack.is(item))
                .mapToInt(ItemStack::getCount)
                .sum();
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
        removeGraves(helper, player.getUUID());
    }

    private static void removeGraves(GameTestHelper helper, UUID owner) {
        GraveStore store = GraveStore.get(helper.getLevel().getServer());
        for (Grave grave : gravesOf(helper, owner)) {
            store.remove(grave.id());
            helper.getLevel().setBlock(grave.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }
}
