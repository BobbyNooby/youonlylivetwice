package bobbynooby.dev.gametest;

import bobbynooby.dev.core.Later;
import bobbynooby.dev.graves.Grave;
import bobbynooby.dev.graves.GraveBlocks;
import bobbynooby.dev.graves.GraveItems;
import bobbynooby.dev.graves.GraveMenu;
import bobbynooby.dev.graves.GraveMenus;
import bobbynooby.dev.graves.GraveRecheck;
import bobbynooby.dev.graves.GraveStore;
import bobbynooby.dev.graves.Graves;
import bobbynooby.dev.lockout.DeathLockout;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

// Clicks go through menu.clicked, the same entry point as a client's click packet
public class GraveMenuGameTests {
    private static final int PRIMARY = 0;
    // The menu slot of the looter's inventory slot 9, the first after the grave's 54
    private static final int OWN_SLOT = 54;

    @GameTest
    public void openingShowsItemsCompacted(GameTestHelper helper) {
        Grave grave = makeGrave(helper, ItemStack.EMPTY, new ItemStack(Items.DIAMOND), ItemStack.EMPTY, new ItemStack(Items.IRON_SWORD));
        ServerPlayer player = join(helper, "Opener");
        try {
            // Holding a block must still open the grave, and must not place the block
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE, 16));
            InteractionResult result = useOn(player, grave, InteractionHand.MAIN_HAND);
            helper.assertTrue(result instanceof InteractionResult.Success, "using the grave did not succeed: " + result);
            helper.assertTrue(player.containerMenu instanceof GraveMenu, "grave menu did not open");
            GraveMenu menu = (GraveMenu) player.containerMenu;
            helper.assertTrue(menu.getType() == MenuType.GENERIC_9x6, "menu is not a 6-row chest");
            helper.assertValueEqual(menu.slots.size(), 54 + 36, "menu slot count");
            helper.assertTrue(menu.stillValid(player), "menu not valid next to the grave");
            helper.assertValueEqual(player.getMainHandItem().getCount(), 16, "stone in hand");
            helper.assertTrue(helper.getLevel().getBlockState(grave.pos().above()).isAir(), "stone placed on the grave");

            // The off-hand use a client sends straight after must not reopen the menu
            useOn(player, grave, InteractionHand.OFF_HAND);
            helper.assertTrue(player.containerMenu == menu, "off-hand use reopened the menu");

            helper.assertTrue(GraveItems.isGraveHead(menu.getSlot(0).getItem()), "slot 0 is not the grave head");
            helper.assertTrue(menu.getSlot(1).getItem().is(Items.DIAMOND), "slot 1 is not the diamond");
            helper.assertTrue(menu.getSlot(2).getItem().is(Items.IRON_SWORD), "slot 2 is not the sword");
            helper.assertFalse(menu.getSlot(3).hasItem(), "slot 3 is not empty");
            helper.assertFalse(menu.getSlot(1).getItem() == stored(helper, grave).items().get(1), "menu shares a stack with the record");
        } finally {
            cleanUp(helper, grave, player);
        }
        helper.succeed();
    }

    @GameTest
    public void insertIsRefused(GameTestHelper helper) {
        Grave grave = makeGrave(helper, new ItemStack(Items.DIAMOND), new ItemStack(Items.IRON_SWORD));
        ServerPlayer player = join(helper, "Stuffer");
        try {
            player.getInventory().setItem(0, new ItemStack(Items.STONE, 16));
            GraveMenus.open(player, grave);
            AbstractContainerMenu menu = player.containerMenu;
            int hotbar0 = menu.findSlot(player.getInventory(), 0).getAsInt();

            helper.assertFalse(menu.getSlot(0).mayPlace(new ItemStack(Items.STONE)), "grave slot accepts stone");
            helper.assertTrue(menu.quickMoveStack(player, hotbar0).isEmpty(), "shift-click from the inventory moved something");
            menu.clicked(hotbar0, PRIMARY, ContainerInput.QUICK_MOVE, player);

            // Number keys: onto an empty grave slot and onto a full one
            menu.clicked(3, 0, ContainerInput.SWAP, player);
            menu.clicked(0, 0, ContainerInput.SWAP, player);

            // Cursor: click onto an empty grave slot and onto a different item
            menu.clicked(hotbar0, PRIMARY, ContainerInput.PICKUP, player);
            helper.assertTrue(menu.getCarried().is(Items.STONE), "stone not picked up");
            menu.clicked(3, PRIMARY, ContainerInput.PICKUP, player);
            menu.clicked(1, PRIMARY, ContainerInput.PICKUP, player);

            // Drag across two empty grave slots
            menu.clicked(AbstractContainerMenu.SLOT_CLICKED_OUTSIDE, AbstractContainerMenu.getQuickcraftMask(0, 0), ContainerInput.QUICK_CRAFT, player);
            menu.clicked(3, AbstractContainerMenu.getQuickcraftMask(1, 0), ContainerInput.QUICK_CRAFT, player);
            menu.clicked(4, AbstractContainerMenu.getQuickcraftMask(1, 0), ContainerInput.QUICK_CRAFT, player);
            menu.clicked(AbstractContainerMenu.SLOT_CLICKED_OUTSIDE, AbstractContainerMenu.getQuickcraftMask(2, 0), ContainerInput.QUICK_CRAFT, player);

            assertItems(helper, stored(helper, grave).items(), "grave record", GraveItems::isGraveHead,
                    stack -> stack.is(Items.DIAMOND), stack -> stack.is(Items.IRON_SWORD));
            for (int slot = 3; slot < 54; slot++) {
                helper.assertFalse(menu.getSlot(slot).hasItem(), "grave slot " + slot + " got an item");
            }
            helper.assertTrue(menu.getSlot(1).getItem().is(Items.DIAMOND), "diamond swapped out of the grave");
            helper.assertValueEqual(count(player, Items.STONE), 16, "stone the player has");
        } finally {
            cleanUp(helper, grave, player);
        }
        helper.succeed();
    }

    @GameTest
    public void creativeCloneCopiesNothing(GameTestHelper helper) {
        Grave grave = makeGrave(helper, new ItemStack(Items.DIAMOND));
        ServerPlayer player = TestPlayers.join(helper, "Cloner", GameType.CREATIVE, new BlockPos(1, 1, 1));
        try {
            helper.assertTrue(player.hasInfiniteMaterials(), "creative player cannot clone");
            GraveMenus.open(player, grave);
            AbstractContainerMenu menu = player.containerMenu;
            // Button 2 is the middle mouse button
            menu.clicked(0, 2, ContainerInput.CLONE, player);
            helper.assertTrue(menu.getCarried().isEmpty(), "middle-click copied the grave head: " + menu.getCarried());
            menu.clicked(1, 2, ContainerInput.CLONE, player);
            helper.assertTrue(menu.getCarried().isEmpty(), "middle-click copied the diamond: " + menu.getCarried());

            assertItems(helper, stored(helper, grave).items(), "grave record", GraveItems::isGraveHead, stack -> stack.is(Items.DIAMOND));
            helper.assertTrue(GraveItems.isGraveHead(menu.getSlot(0).getItem()), "grave head left the menu");
            helper.assertTrue(menu.getSlot(1).getItem().is(Items.DIAMOND), "diamond left the menu");
        } finally {
            cleanUp(helper, grave, player);
        }
        helper.succeed();
    }

    // Q on a grave slot throws one item, as from any chest: exactly one item entity, and the grave holds one fewer
    @GameTest
    public void throwFromGraveDropsOne(GameTestHelper helper) {
        Grave grave = makeGrave(helper, new ItemStack(Items.DIAMOND, 3));
        ServerPlayer player = join(helper, "Thrower");
        AABB around = new AABB(player.blockPosition()).inflate(4.0);
        try {
            GraveMenus.open(player, grave);
            AbstractContainerMenu menu = player.containerMenu;
            // Button 0 is Q on its own, which throws one item; with Ctrl it would be button 1, the whole stack
            menu.clicked(1, 0, ContainerInput.THROW, player);

            List<ItemEntity> thrown = helper.getLevel().getEntitiesOfClass(ItemEntity.class, around, Entity::isAlive);
            helper.assertValueEqual(thrown.size(), 1, "item entities thrown");
            ItemStack item = thrown.get(0).getItem();
            helper.assertTrue(item.is(Items.DIAMOND) && item.getCount() == 1, "the thrown item: " + item);
            helper.assertValueEqual(countIn(stored(helper, grave).items(), Items.DIAMOND), 2, "diamonds in the grave");
            helper.assertValueEqual(countIn(List.of(menu.getSlot(1).getItem()), Items.DIAMOND), 2, "diamonds in the menu slot");
            helper.assertValueEqual(count(player, Items.DIAMOND), 0, "diamonds the player has");
        } finally {
            helper.getLevel().getEntitiesOfClass(ItemEntity.class, around).forEach(Entity::discard);
            cleanUp(helper, grave, player);
        }
        helper.succeed();
    }

    @GameTest
    public void takingLastItemRemovesHead(GameTestHelper helper) {
        Grave grave = makeGrave(helper, new ItemStack(Items.DIAMOND), new ItemStack(Items.IRON_SWORD));
        ServerPlayer player = join(helper, "Looter");
        try {
            GraveMenus.open(player, grave);
            AbstractContainerMenu menu = player.containerMenu;
            for (int slot = 0; slot < 3; slot++) {
                menu.clicked(slot, PRIMARY, ContainerInput.QUICK_MOVE, player);
            }

            helper.assertTrue(helper.getLevel().getBlockState(grave.pos()).isAir(), "head still at " + grave.pos());
            helper.assertTrue(GraveStore.get(helper.getLevel().getServer()).byId(grave.id()).isEmpty(), "grave record still stored");
            helper.assertTrue(player.containerMenu == player.inventoryMenu, "menu still open");
            helper.assertFalse(menu.stillValid(player), "menu still valid for a removed grave");
            helper.assertTrue(GraveMenus.containerFor(grave) == null, "container still shared");
            helper.assertValueEqual(count(player, Items.PLAYER_HEAD), 1, "heads the player has");
            helper.assertValueEqual(count(player, Items.DIAMOND), 1, "diamonds the player has");
            helper.assertValueEqual(count(player, Items.IRON_SWORD), 1, "swords the player has");
        } finally {
            cleanUp(helper, grave, player);
        }
        helper.succeed();
    }

    // Closing the screen the usual way with items left: the head and the record stay, and the stack on the cursor goes
    // to the looter's inventory, as from any chest
    @GameTest
    public void closingWithItemsLeftKeepsTheGraveAndTheCursorStack(GameTestHelper helper) {
        Grave grave = makeGrave(helper, new ItemStack(Items.DIAMOND, 3), new ItemStack(Items.EMERALD, 6));
        ServerPlayer player = join(helper, "Closer");
        try {
            GraveMenus.open(player, grave);
            AbstractContainerMenu menu = player.containerMenu;
            menu.clicked(1, PRIMARY, ContainerInput.PICKUP, player);
            helper.assertTrue(menu.getCarried().is(Items.DIAMOND), "the diamonds are not on the cursor");
            player.closeContainer();

            helper.assertTrue(player.containerMenu == player.inventoryMenu, "the menu is still open");
            helper.assertTrue(player.containerMenu.getCarried().isEmpty(), "a stack is left on the cursor");
            helper.assertValueEqual(player.getInventory().countItem(Items.DIAMOND), 3, "diamonds in the looter's inventory");
            List<ItemStack> inGrave = stored(helper, grave).items();
            helper.assertValueEqual(countIn(inGrave, Items.DIAMOND), 0, "diamonds in the grave");
            helper.assertValueEqual(countIn(inGrave, Items.EMERALD), 6, "emeralds in the grave");
            helper.assertTrue(GraveBlocks.isOwnHead(helper.getLevel(), grave.pos(), stored(helper, grave)), "the head is gone");
            helper.assertTrue(GraveMenus.containerFor(grave) == null, "the container is still shared");
        } finally {
            cleanUp(helper, grave, player);
        }
        helper.succeed();
    }

    // The looter's own tick closes a grave menu once they are more than 8 blocks off, or in another dimension: the grave
    // keeps what is left, and the stack on each cursor goes to that looter's inventory
    @GameTest
    public void menuClosesOnDistanceAndDimensionChange(GameTestHelper helper) {
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "no nether to go to");
        Grave grave = makeGrave(helper, new ItemStack(Items.DIAMOND, 3), new ItemStack(Items.EMERALD, 6),
                new ItemStack(Items.APPLE, 2));
        ServerPlayer walker = join(helper, "Walker");
        ServerPlayer traveller = join(helper, "Traveller");
        try {
            GraveMenus.open(walker, grave);
            GraveMenus.open(traveller, grave);
            walker.containerMenu.clicked(1, PRIMARY, ContainerInput.PICKUP, walker);
            traveller.containerMenu.clicked(2, PRIMARY, ContainerInput.PICKUP, traveller);
            helper.assertTrue(walker.containerMenu.getCarried().is(Items.DIAMOND) && traveller.containerMenu.getCarried().is(Items.EMERALD),
                    "the cursors don't hold what the test needs");

            BlockPos pos = grave.pos();
            walker.snapTo(pos.getX() + 12.5, pos.getY(), pos.getZ() + 0.5);
            traveller.teleportTo(nether, 0.5, 64.0, 0.5, Set.of(), 0.0F, 0.0F, true);
            helper.assertTrue(traveller.level() == nether, "the traveller did not reach the nether");
            for (ServerPlayer player : List.of(walker, traveller)) {
                player.doTick();
                helper.assertTrue(player.containerMenu == player.inventoryMenu, player.getPlainTextName() + "'s menu is still open");
                helper.assertTrue(player.containerMenu.getCarried().isEmpty(), player.getPlainTextName() + " still holds a stack");
            }
            helper.assertValueEqual(walker.getInventory().countItem(Items.DIAMOND), 3, "diamonds the walker has");
            helper.assertValueEqual(traveller.getInventory().countItem(Items.EMERALD), 6, "emeralds the traveller has");
            List<ItemStack> inGrave = stored(helper, grave).items();
            helper.assertValueEqual(countIn(inGrave, Items.APPLE), 2, "apples in the grave");
            helper.assertValueEqual(countIn(inGrave, Items.DIAMOND) + countIn(inGrave, Items.EMERALD), 0, "taken items in the grave");
            helper.assertTrue(GraveBlocks.isOwnHead(helper.getLevel(), pos, stored(helper, grave)), "the head is gone");
            helper.assertTrue(GraveMenus.containerFor(grave) == null, "the container is still shared");
        } finally {
            cleanUp(helper, grave, walker, traveller);
        }
        helper.succeed();
    }

    // Taking from a grave only marks the grave file to be saved with the world. The looter's logout writes it straight
    // after that tick, so a crash then can't hand the items back while the looter's own save keeps them.
    @GameTest(maxTicks = 20)
    public void lootIsInTheGraveFileOnceTheLooterLeaves(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        GraveStore store = GraveStore.get(server);
        Grave grave = makeGrave(helper, new ItemStack(Items.DIAMOND, 3), new ItemStack(Items.EMERALD, 6));
        ServerPlayer looter = join(helper, "SavedLooter");
        AABB around = new AABB(looter.blockPosition()).inflate(4.0);
        Runnable cleanUp = BodyTestKit.once(() -> {
            cleanUpLeft(helper, grave, around, looter);
            store.saveNow(server);
        });
        boolean waiting = false;
        try {
            store.saveNow(server);
            helper.assertValueEqual(onDiskCount(server, grave, Items.DIAMOND), 3, "diamonds in the grave file before the looting");
            GraveMenus.open(looter, grave);
            looter.containerMenu.clicked(1, PRIMARY, ContainerInput.QUICK_MOVE, looter);
            helper.assertValueEqual(looter.getInventory().countItem(Items.DIAMOND), 3, "diamonds the looter took");
            helper.assertValueEqual(onDiskCount(server, grave, Items.DIAMOND), 3, "diamonds in the grave file straight after the looting");
            TestPlayers.leave(looter);
            // Queued after the save the leave queued, so it reads the file straight after that save, before another
            // test's save could write the same record. The server may hold queued tasks for a few ticks, so the test
            // waits for this one rather than a fixed delay.
            AtomicReference<int[]> read = new AtomicReference<>();
            Later.afterTick(server, () -> read.set(new int[] {onDiskCount(server, grave, Items.DIAMOND),
                    onDiskCount(server, grave, Items.EMERALD)}));
            helper.runBeforeTestEnd(cleanUp);
            helper.startSequence()
                    .thenWaitUntil(() -> helper.assertTrue(read.get() != null, "the file has not been read after the logout yet"))
                    .thenExecute(() -> {
                        try {
                            helper.assertValueEqual(read.get()[0], 0, "diamonds in the grave file after the logout");
                            helper.assertValueEqual(read.get()[1], 6, "emeralds in the grave file after the logout");
                        } finally {
                            cleanUp.run();
                        }
                    })
                    .thenSucceed();
            waiting = true;
        } finally {
            if (!waiting) {
                cleanUp.run();
            }
        }
    }

    // An op fills the grave's spot and the 7 blocks above while two looters have it open, so the re-check moves the
    // grave up on top, 8 blocks up. From then on the menus go by the new spot: open for the looter beside it, closed
    // for the one beside the old spot, and emptying the grave takes the head from the new spot.
    @GameTest(skyAccess = true)
    public void menuFollowsAGraveTheRecheckMoved(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        GraveStore store = GraveStore.get(level.getServer());
        Grave grave = makeGrave(helper, new ItemStack(Items.DIAMOND), new ItemStack(Items.IRON_SWORD));
        BlockPos spot = grave.pos();
        BlockPos moved = spot.above(8);
        ServerPlayer byOld = join(helper, "ByOldSpot");
        ServerPlayer byNew = join(helper, "ByNewSpot");
        try {
            // Test players don't fall: one stands a block east of the old spot, the other a block east of the new spot
            // and a block up
            byOld.snapTo(spot.getX() + 1.5, spot.getY(), spot.getZ() + 0.5);
            byNew.snapTo(spot.getX() + 1.5, moved.getY() + 1, spot.getZ() + 0.5);
            GraveMenus.open(byOld, grave);
            GraveMenus.open(byNew, grave);
            AbstractContainerMenu oldMenu = byOld.containerMenu;
            AbstractContainerMenu newMenu = byNew.containerMenu;
            helper.assertTrue(oldMenu instanceof GraveMenu && newMenu instanceof GraveMenu, "the grave did not open for both");
            helper.assertTrue(oldMenu.stillValid(byOld), "the menu is not valid beside the grave");
            helper.assertFalse(newMenu.stillValid(byNew), "the menu is valid 8 blocks over the grave");

            for (int dy = 0; dy < 8; dy++) {
                level.setBlock(spot.above(dy), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            }
            GraveRecheck.recheck(level, store, stored(helper, grave));
            helper.assertValueEqual(stored(helper, grave).pos(), moved, "grave's spot after the re-check");
            helper.assertTrue(GraveBlocks.isOwnHead(level, moved, stored(helper, grave)), "no head at the new spot");
            helper.assertFalse(oldMenu.stillValid(byOld), "the menu is still valid beside the old spot");
            helper.assertTrue(newMenu.stillValid(byNew), "the menu is not valid beside the new spot");

            for (int slot = 0; slot < 3; slot++) {
                newMenu.clicked(slot, PRIMARY, ContainerInput.QUICK_MOVE, byNew);
            }
            helper.assertTrue(store.byId(grave.id()).isEmpty(), "the emptied grave's record is still there");
            helper.assertTrue(level.getBlockState(moved).isAir(), "the emptied grave's head is still at " + moved);
            helper.assertTrue(byOld.containerMenu == byOld.inventoryMenu && byNew.containerMenu == byNew.inventoryMenu,
                    "a menu on the emptied grave is still open");
            helper.assertValueEqual(count(byNew, Items.DIAMOND) + count(byNew, Items.IRON_SWORD) + count(byNew, Items.PLAYER_HEAD), 3,
                    "items the looter took");
        } finally {
            for (int dy = 0; dy <= 8; dy++) {
                level.setBlock(spot.above(dy), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
            cleanUp(helper, grave, byOld, byNew);
        }
        helper.succeed();
    }

    @GameTest
    public void twoViewersShareOneContainer(GameTestHelper helper) {
        Grave grave = makeGrave(helper, new ItemStack(Items.DIAMOND), new ItemStack(Items.IRON_SWORD));
        ServerPlayer a = join(helper, "ViewerA");
        ServerPlayer b = join(helper, "ViewerB");
        try {
            GraveMenus.open(a, grave);
            GraveMenus.open(b, grave);
            AbstractContainerMenu menuA = a.containerMenu;
            AbstractContainerMenu menuB = b.containerMenu;
            helper.assertTrue(menuA instanceof GraveMenu && menuB instanceof GraveMenu && menuA != menuB, "each viewer needs their own grave menu");
            helper.assertTrue(menuA.getSlot(0).container == menuB.getSlot(0).container
                    && menuA.getSlot(0).container == GraveMenus.containerFor(grave), "viewers do not share one container");

            menuA.clicked(1, PRIMARY, ContainerInput.PICKUP, a);
            helper.assertTrue(menuA.getCarried().is(Items.DIAMOND), "A did not pick up the diamond");
            helper.assertFalse(menuB.getSlot(1).hasItem(), "B still sees the diamond");
            menuB.clicked(1, PRIMARY, ContainerInput.PICKUP, b);
            helper.assertTrue(menuB.getCarried().isEmpty(), "B took something from the emptied slot");

            int diamonds = count(a, Items.DIAMOND) + count(b, Items.DIAMOND) + countIn(stored(helper, grave).items(), Items.DIAMOND);
            helper.assertValueEqual(diamonds, 1, "diamonds in total");
        } finally {
            cleanUp(helper, grave, a, b);
        }
        helper.succeed();
    }

    // Number-key take, double-click collect and a held cursor, then the last item goes while B still looks
    @GameTest
    public void lastItemClosesEveryViewer(GameTestHelper helper) {
        Grave grave = makeGrave(helper, new ItemStack(Items.DIAMOND), new ItemStack(Items.DIAMOND),
                new ItemStack(Items.IRON_SWORD), new ItemStack(Items.APPLE));
        ServerPlayer a = join(helper, "LastA");
        ServerPlayer b = join(helper, "LastB");
        try {
            GraveMenus.open(a, grave);
            GraveMenus.open(b, grave);
            AbstractContainerMenu menuA = a.containerMenu;
            AbstractContainerMenu menuB = b.containerMenu;

            menuB.clicked(3, PRIMARY, ContainerInput.PICKUP, b);
            menuB.clicked(4, 0, ContainerInput.SWAP, b);
            helper.assertTrue(b.getInventory().getItem(0).is(Items.APPLE), "number key did not take the apple");
            menuA.clicked(1, PRIMARY, ContainerInput.PICKUP, a);
            menuA.clicked(1, PRIMARY, ContainerInput.PICKUP_ALL, a);
            helper.assertValueEqual(menuA.getCarried().getCount(), 2, "diamonds collected onto A's cursor");
            menuA.clicked(0, PRIMARY, ContainerInput.QUICK_MOVE, a);

            helper.assertTrue(GraveStore.get(helper.getLevel().getServer()).byId(grave.id()).isEmpty(), "grave record still stored");
            helper.assertTrue(helper.getLevel().getBlockState(grave.pos()).isAir(), "head still at " + grave.pos());
            helper.assertTrue(a.containerMenu == a.inventoryMenu, "A's menu still open");
            helper.assertTrue(b.containerMenu == b.inventoryMenu, "B's menu still open");
            // Closing hands each cursor back to its own inventory
            helper.assertValueEqual(a.getInventory().countItem(Items.DIAMOND), 2, "diamonds in A's inventory");
            helper.assertValueEqual(a.getInventory().countItem(Items.PLAYER_HEAD), 1, "heads in A's inventory");
            helper.assertValueEqual(b.getInventory().countItem(Items.IRON_SWORD), 1, "swords in B's inventory");
            helper.assertValueEqual(b.getInventory().countItem(Items.APPLE), 1, "apples in B's inventory");
            helper.assertValueEqual(count(a, Items.IRON_SWORD) + count(a, Items.APPLE) + count(b, Items.DIAMOND)
                    + count(b, Items.PLAYER_HEAD), 0, "items with the wrong player");
        } finally {
            cleanUp(helper, grave, a, b);
        }
        helper.succeed();
    }

    // A looter dies holding a grave stack on the cursor. With keepInventory on, the death leaves their menus open, so
    // the menu closes as the respawn removes the dead player. Vanilla would drop the stack there to despawn; it goes
    // back into the grave instead. The rule is only on for the death's own tick.
    @GameTest
    public void cursorGoesBackWhenLooterDies(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        GameRules rules = server.getGameRules();
        Grave grave = makeGrave(helper, new ItemStack(Items.DIAMOND, 2), new ItemStack(Items.EMERALD, 6));
        ServerPlayer looter = join(helper, "DyingLooter");
        UUID id = looter.getUUID();
        AABB around = new AABB(looter.blockPosition()).inflate(4.0);
        try {
            GraveMenus.open(looter, grave);
            AbstractContainerMenu menu = looter.containerMenu;
            menu.clicked(2, PRIMARY, ContainerInput.PICKUP, looter);
            helper.assertTrue(menu.getCarried().is(Items.EMERALD), "the emeralds are not on the cursor");
            boolean before = rules.get(GameRules.KEEP_INVENTORY);
            rules.set(GameRules.KEEP_INVENTORY, true, server);
            try {
                TestPlayers.markLoaded(looter);
                looter.kill(looter.level());
            } finally {
                rules.set(GameRules.KEEP_INVENTORY, before, server);
            }
            helper.assertTrue(looter.isDeadOrDying(), "the looter did not die");
            helper.assertTrue(looter.containerMenu == menu && menu.getCarried().is(Items.EMERALD),
                    "the death closed the menu, so the respawn has nothing left to close");

            looter.connection.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
            ServerPlayer respawned = server.getPlayerList().getPlayer(id);
            helper.assertTrue(respawned != null && respawned != looter && looter.isRemoved(), "the looter did not respawn");
            helper.assertValueEqual(countIn(stored(helper, grave).items(), Items.EMERALD), 6, "emeralds in the grave");
            helper.assertValueEqual(count(respawned, Items.EMERALD), 0, "emeralds the respawned looter has");
            helper.assertValueEqual(GraveAsserts.countInGraves(server, id, Items.EMERALD), 0, "emeralds in the looter's own grave");
            helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class, around, Entity::isAlive).isEmpty(), "items dropped");
        } finally {
            cleanUpDeath(helper, grave, id, around);
        }
        helper.succeed();
    }

    // The same death with the server's own rules, which take the looter's open menus into their own grave at the
    // death, cursor and all, as for any menu. So the stack is in a grave either way, and never on the ground.
    @GameTest
    public void cursorGoesInLootersGraveWhenTheyDie(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Grave grave = makeGrave(helper, new ItemStack(Items.DIAMOND, 2), new ItemStack(Items.EMERALD, 6));
        ServerPlayer looter = join(helper, "GravedLooter");
        UUID id = looter.getUUID();
        AABB around = new AABB(looter.blockPosition()).inflate(4.0);
        try {
            GraveMenus.open(looter, grave);
            AbstractContainerMenu menu = looter.containerMenu;
            menu.clicked(2, PRIMARY, ContainerInput.PICKUP, looter);
            helper.assertTrue(menu.getCarried().is(Items.EMERALD), "the emeralds are not on the cursor");
            TestPlayers.markLoaded(looter);
            looter.kill(looter.level());
            helper.assertTrue(looter.isDeadOrDying(), "the looter did not die");

            helper.assertTrue(looter.containerMenu == looter.inventoryMenu, "the death left the menu open");
            helper.assertValueEqual(GraveAsserts.countInGraves(server, id, Items.EMERALD), 6, "emeralds in the looter's own grave");
            helper.assertValueEqual(countIn(stored(helper, grave).items(), Items.EMERALD), 0, "emeralds back in the first grave");
            helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class, around, Entity::isAlive).isEmpty(), "items dropped");
        } finally {
            cleanUpDeath(helper, grave, id, around);
        }
        helper.succeed();
    }

    // A looter's own stack on the cursor stays theirs at a disconnect, even of an item the grave holds: the grave takes
    // none of it, and it drops the vanilla way, as from any chest
    @GameTest
    public void ownCursorStackStaysOutOfTheGrave(GameTestHelper helper) {
        Grave grave = makeGrave(helper, new ItemStack(Items.DIAMOND, 3), new ItemStack(Items.EMERALD, 6));
        ServerPlayer looter = join(helper, "OwnCursor");
        AABB around = new AABB(looter.blockPosition()).inflate(4.0);
        try {
            looter.getInventory().setItem(9, new ItemStack(Items.DIAMOND, 5));
            GraveMenus.open(looter, grave);
            AbstractContainerMenu menu = looter.containerMenu;
            menu.clicked(OWN_SLOT, PRIMARY, ContainerInput.PICKUP, looter);
            helper.assertTrue(menu.getCarried().is(Items.DIAMOND) && menu.getCarried().getCount() == 5,
                    "the looter's own diamonds are not on the cursor: " + menu.getCarried());

            TestPlayers.disconnect(looter);
            List<ItemStack> inGrave = stored(helper, grave).items();
            helper.assertValueEqual(inGrave.size(), 3, "grave stacks");
            helper.assertValueEqual(countIn(inGrave, Items.DIAMOND), 3, "diamonds in the grave");
            helper.assertValueEqual(countIn(inGrave, Items.EMERALD), 6, "emeralds in the grave");
            helper.assertValueEqual(onGround(helper, around, Items.DIAMOND, false), 5, "diamonds dropped the vanilla way");
            helper.assertValueEqual(onGround(helper, around, Items.DIAMOND, true), 0, "diamonds dropped for good");
        } finally {
            cleanUpLeft(helper, grave, around, looter);
        }
        helper.succeed();
    }

    // A double-click gathers the looter's own diamonds onto two taken from the grave. At a disconnect only the
    // grave's two go back; the looter's own five drop the vanilla way.
    @GameTest
    public void gatheredCursorPutsBackOnlyTheGravesShare(GameTestHelper helper) {
        Grave grave = makeGrave(helper, new ItemStack(Items.DIAMOND, 2), new ItemStack(Items.EMERALD, 6));
        ServerPlayer looter = join(helper, "Gatherer");
        AABB around = new AABB(looter.blockPosition()).inflate(4.0);
        try {
            looter.getInventory().setItem(9, new ItemStack(Items.DIAMOND, 5));
            GraveMenus.open(looter, grave);
            AbstractContainerMenu menu = looter.containerMenu;
            menu.clicked(1, PRIMARY, ContainerInput.PICKUP, looter);
            menu.clicked(1, PRIMARY, ContainerInput.PICKUP_ALL, looter);
            helper.assertTrue(menu.getCarried().is(Items.DIAMOND) && menu.getCarried().getCount() == 7,
                    "the double-click did not gather 7 diamonds: " + menu.getCarried());
            helper.assertValueEqual(looter.getInventory().countItem(Items.DIAMOND), 0, "diamonds left in the inventory");

            TestPlayers.disconnect(looter);
            List<ItemStack> inGrave = stored(helper, grave).items();
            helper.assertValueEqual(countIn(inGrave, Items.DIAMOND), 2, "diamonds in the grave");
            helper.assertValueEqual(countIn(inGrave, Items.EMERALD), 6, "emeralds in the grave");
            helper.assertValueEqual(onGround(helper, around, Items.DIAMOND, false), 5, "diamonds dropped the vanilla way");
            helper.assertValueEqual(onGround(helper, around, Items.DIAMOND, true), 0, "diamonds dropped for good");
        } finally {
            cleanUpLeft(helper, grave, around, looter);
        }
        helper.succeed();
    }

    // A full grave. Two looters hold grave stacks on their cursors, and a third their own cobblestone, which the
    // grave also holds. The third drops out first: their stack must not take the room the grave's own need. Then each
    // grave stack is in the grave or dropped for good, exactly once, and the cobblestone drops the vanilla way.
    @GameTest
    public void fullGraveKeepsRoomForItsOwnStacks(GameTestHelper helper) {
        List<ItemStack> stacks = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            stacks.add(new ItemStack(Items.COBBLESTONE, 64));
        }
        stacks.add(new ItemStack(Items.EMERALD, 6));
        stacks.add(new ItemStack(Items.GOLD_INGOT, 2));
        Grave grave = makeGrave(helper, stacks.toArray(ItemStack[]::new));
        helper.assertValueEqual(grave.items().size(), 54, "stacks in the full grave");
        ServerPlayer a = join(helper, "FullA");
        ServerPlayer b = join(helper, "FullB");
        ServerPlayer own = join(helper, "FullOwn");
        AABB around = new AABB(a.blockPosition()).inflate(4.0);
        try {
            own.getInventory().setItem(9, new ItemStack(Items.COBBLESTONE, 64));
            GraveMenus.open(a, grave);
            GraveMenus.open(b, grave);
            GraveMenus.open(own, grave);
            a.containerMenu.clicked(52, PRIMARY, ContainerInput.PICKUP, a);
            b.containerMenu.clicked(53, PRIMARY, ContainerInput.PICKUP, b);
            own.containerMenu.clicked(OWN_SLOT, PRIMARY, ContainerInput.PICKUP, own);
            helper.assertTrue(a.containerMenu.getCarried().is(Items.EMERALD) && b.containerMenu.getCarried().is(Items.GOLD_INGOT)
                    && own.containerMenu.getCarried().is(Items.COBBLESTONE), "the cursors don't hold what the test needs");

            TestPlayers.disconnect(own);
            TestPlayers.disconnect(a);
            TestPlayers.disconnect(b);
            List<ItemStack> inGrave = stored(helper, grave).items();
            helper.assertValueEqual(inGrave.size(), 54, "stacks in the grave");
            helper.assertValueEqual(countIn(inGrave, Items.COBBLESTONE), 51 * 64, "cobblestone in the grave");
            for (Item item : List.of(Items.EMERALD, Items.GOLD_INGOT)) {
                helper.assertValueEqual(countIn(inGrave, item) + onGround(helper, around, item, true), item == Items.EMERALD ? 6 : 2,
                        item + " in the grave or dropped for good");
                helper.assertValueEqual(onGround(helper, around, item, false), 0, item + " dropped to despawn");
            }
            helper.assertValueEqual(onGround(helper, around, Items.COBBLESTONE, false), 64, "own cobblestone dropped the vanilla way");
            helper.assertValueEqual(onGround(helper, around, Items.COBBLESTONE, true), 0, "own cobblestone dropped for good");
        } finally {
            cleanUpLeft(helper, grave, around, a, b, own);
        }
        helper.succeed();
    }

    // Nothing else writes an open grave's container, so a grave stack always finds room again. If something ever did,
    // the stack with no room drops at the grave for good, never on a 5-minute timer. Stone set in the freed slot through
    // the shared container stands in for that writer.
    @GameTest
    public void cursorStackWithNoRoomDropsForGood(GameTestHelper helper) {
        List<ItemStack> stacks = new ArrayList<>();
        for (int i = 0; i < 52; i++) {
            stacks.add(new ItemStack(Items.COBBLESTONE, 64));
        }
        stacks.add(new ItemStack(Items.DIAMOND, 10));
        Grave grave = makeGrave(helper, stacks.toArray(ItemStack[]::new));
        helper.assertValueEqual(grave.items().size(), 54, "stacks in the full grave");
        ServerPlayer looter = join(helper, "NoRoom");
        AABB around = new AABB(looter.blockPosition()).inflate(4.0);
        try {
            GraveMenus.open(looter, grave);
            AbstractContainerMenu menu = looter.containerMenu;
            menu.clicked(53, PRIMARY, ContainerInput.PICKUP, looter);
            helper.assertTrue(menu.getCarried().is(Items.DIAMOND) && menu.getCarried().getCount() == 10,
                    "the diamonds are not on the cursor: " + menu.getCarried());
            GraveMenus.containerFor(grave).setItem(53, new ItemStack(Items.STONE));

            TestPlayers.disconnect(looter);
            List<ItemStack> inGrave = stored(helper, grave).items();
            helper.assertValueEqual(inGrave.size(), 54, "stacks in the grave");
            helper.assertValueEqual(countIn(inGrave, Items.DIAMOND), 0, "diamonds in the grave");
            helper.assertValueEqual(countIn(inGrave, Items.STONE), 1, "stone in the grave");
            List<ItemEntity> forGood = drops(helper, around, true);
            helper.assertValueEqual(forGood.size(), 1, "drops that never despawn");
            ItemStack dropped = forGood.get(0).getItem();
            helper.assertTrue(dropped.is(Items.DIAMOND) && dropped.getCount() == 10, "the drop that never despawns: " + dropped);
            helper.assertValueEqual(drops(helper, around, false).size(), 0, "drops that despawn");
        } finally {
            cleanUpLeft(helper, grave, around, looter);
        }
        helper.succeed();
    }

    // The same, after an op filled the grave's spot and the 7 blocks above and the re-check moved the grave up on top,
    // 8 blocks up. The stack with no room drops at the new spot, not in the op's stone at the old one.
    @GameTest(skyAccess = true)
    public void cursorStackWithNoRoomDropsWhereTheGraveMovedTo(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<ItemStack> stacks = new ArrayList<>();
        for (int i = 0; i < 52; i++) {
            stacks.add(new ItemStack(Items.COBBLESTONE, 64));
        }
        stacks.add(new ItemStack(Items.DIAMOND, 10));
        Grave grave = makeGrave(helper, stacks.toArray(ItemStack[]::new));
        helper.assertValueEqual(grave.items().size(), 54, "stacks in the full grave");
        BlockPos spot = grave.pos();
        BlockPos moved = spot.above(8);
        ServerPlayer looter = join(helper, "NoRoomMoved");
        AABB aroundOld = new AABB(spot).inflate(4.0);
        AABB aroundNew = new AABB(moved).inflate(2.0);
        try {
            GraveMenus.open(looter, grave);
            AbstractContainerMenu menu = looter.containerMenu;
            menu.clicked(53, PRIMARY, ContainerInput.PICKUP, looter);
            helper.assertTrue(menu.getCarried().is(Items.DIAMOND) && menu.getCarried().getCount() == 10,
                    "the diamonds are not on the cursor: " + menu.getCarried());
            GraveMenus.containerFor(grave).setItem(53, new ItemStack(Items.STONE));
            for (int dy = 0; dy < 8; dy++) {
                level.setBlock(spot.above(dy), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            }
            GraveRecheck.recheck(level, GraveStore.get(level.getServer()), stored(helper, grave));
            helper.assertValueEqual(stored(helper, grave).pos(), moved, "grave's spot after the re-check");

            TestPlayers.disconnect(looter);
            List<ItemEntity> forGood = drops(helper, aroundNew, true);
            helper.assertValueEqual(forGood.size(), 1, "drops that never despawn by the new spot");
            ItemStack dropped = forGood.get(0).getItem();
            helper.assertTrue(dropped.is(Items.DIAMOND) && dropped.getCount() == 10, "the drop that never despawns: " + dropped);
            helper.assertValueEqual(forGood.get(0).blockPosition(), moved, "the drop's spot");
            helper.assertValueEqual(drops(helper, aroundOld, true).size() + drops(helper, aroundOld, false).size(), 0,
                    "drops by the old spot");
        } finally {
            for (int dy = 0; dy <= 8; dy++) {
                level.setBlock(spot.above(dy), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
            level.getEntitiesOfClass(ItemEntity.class, aroundNew).forEach(Entity::discard);
            cleanUpLeft(helper, grave, aroundOld, looter);
        }
        helper.succeed();
    }

    // The grave head comes first, as in a real death; empty stacks in the list are dropped by the record
    private static Grave makeGrave(GameTestHelper helper, ItemStack... items) {
        ResolvableProfile profile = ResolvableProfile.createUnresolved("Ghost");
        List<ItemStack> stacks = new ArrayList<>();
        stacks.add(GraveItems.graveHead(profile));
        stacks.addAll(List.of(items));
        List<Grave> made = Graves.create(helper.getLevel(), helper.absolutePos(new BlockPos(2, 1, 2)), profile,
                UUID.randomUUID(), "Ghost", stacks);
        helper.assertValueEqual(made.size(), 1, "graves made");
        return made.get(0);
    }

    private static ServerPlayer join(GameTestHelper helper, String name) {
        return TestPlayers.join(helper, name, GameType.SURVIVAL, new BlockPos(1, 1, 1));
    }

    private static InteractionResult useOn(ServerPlayer player, Grave grave, InteractionHand hand) {
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(grave.pos()), Direction.UP, grave.pos(), false);
        return player.gameMode.useItemOn(player, player.level(), player.getItemInHand(hand), hand, hit);
    }

    private static Grave stored(GameTestHelper helper, Grave grave) {
        return GraveStore.get(helper.getLevel().getServer()).byId(grave.id()).orElseThrow();
    }

    // Inventory plus whatever is on the cursor of the open menu
    private static int count(ServerPlayer player, Item item) {
        Inventory inventory = player.getInventory();
        ItemStack carried = player.containerMenu.getCarried();
        return inventory.countItem(item) + (carried.is(item) ? carried.getCount() : 0);
    }

    // How many of the item the grave file holds for this grave now; -1 when the file doesn't have the grave
    private static int onDiskCount(MinecraftServer server, Grave grave, Item item) {
        return GraveStoreGameTests.onDisk(server).stream()
                .filter(saved -> saved.id().equals(grave.id()))
                .findFirst()
                .map(saved -> countIn(saved.items(), item))
                .orElse(-1);
    }

    private static int countIn(List<ItemStack> stacks, Item item) {
        return stacks.stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }

    @SafeVarargs
    private static void assertItems(GameTestHelper helper, List<ItemStack> items, String what, Predicate<ItemStack>... expected) {
        helper.assertValueEqual(items.size(), expected.length, what + " stack count");
        for (int i = 0; i < expected.length; i++) {
            helper.assertTrue(expected[i].test(items.get(i)) && items.get(i).getCount() == 1, what + " stack " + i + " changed: " + items.get(i));
        }
    }

    // How many of the item lie on the ground, dropped for good or the vanilla way, which despawns
    private static int onGround(GameTestHelper helper, AABB around, Item item, boolean forGood) {
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, around, Entity::isAlive).stream()
                .filter(entity -> entity.getItem().is(item) && (entity.getAge() < 0) == forGood)
                .mapToInt(entity -> entity.getItem().getCount())
                .sum();
    }

    // Every item entity lying there, dropped for good or the vanilla way
    private static List<ItemEntity> drops(GameTestHelper helper, AABB around, boolean forGood) {
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, around, Entity::isAlive).stream()
                .filter(entity -> (entity.getAge() < 0) == forGood)
                .toList();
    }

    // After disconnects: anyone still online leaves, and anything dropped and the grave go
    private static void cleanUpLeft(GameTestHelper helper, Grave grave, AABB around, ServerPlayer... players) {
        for (ServerPlayer player : players) {
            ServerPlayer online = helper.getLevel().getServer().getPlayerList().getPlayer(player.getUUID());
            if (online != null) {
                TestPlayers.leave(online);
            }
        }
        helper.getLevel().getEntitiesOfClass(ItemEntity.class, around).forEach(Entity::discard);
        GraveStore.get(helper.getLevel().getServer()).remove(grave.id());
        helper.getLevel().setBlock(grave.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    // After a looter's death: the looter leaves, their lockout ends, and their own grave, anything dropped and the
    // looted grave go
    private static void cleanUpDeath(GameTestHelper helper, Grave grave, UUID id, AABB around) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) {
            TestPlayers.leave(online);
        }
        DeathLockout.revive(id, "Test", "console");
        for (Grave own : BodyTestKit.gravesOf(helper, id)) {
            GraveStore.get(server).remove(own.id());
            helper.getLevel().setBlock(own.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        helper.getLevel().getEntitiesOfClass(ItemEntity.class, around).forEach(Entity::discard);
        GraveStore.get(server).remove(grave.id());
        helper.getLevel().setBlock(grave.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    // Leaving closes each menu; the grave goes too, whether or not the test emptied it
    private static void cleanUp(GameTestHelper helper, Grave grave, ServerPlayer... players) {
        for (ServerPlayer player : players) {
            TestPlayers.leave(player);
        }
        GraveStore.get(helper.getLevel().getServer()).remove(grave.id());
        helper.getLevel().setBlock(grave.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }
}
