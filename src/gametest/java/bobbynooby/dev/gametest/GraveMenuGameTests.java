package bobbynooby.dev.gametest;

import bobbynooby.dev.graves.Grave;
import bobbynooby.dev.graves.GraveItems;
import bobbynooby.dev.graves.GraveMenu;
import bobbynooby.dev.graves.GraveMenus;
import bobbynooby.dev.graves.GraveStore;
import bobbynooby.dev.graves.Graves;
import bobbynooby.dev.lockout.DeathLockout;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.MinecraftServer;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

// Clicks go through menu.clicked, the same entry point as a client's click packet
public class GraveMenuGameTests {
    private static final int PRIMARY = 0;

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
