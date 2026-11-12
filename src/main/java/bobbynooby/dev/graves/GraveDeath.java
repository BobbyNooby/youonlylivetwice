package bobbynooby.dev.graves;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.core.Guard;
import bobbynooby.dev.core.Later;
import bobbynooby.dev.core.Profiles;
import bobbynooby.dev.core.Stacks;
import bobbynooby.dev.core.StandIn;
import bobbynooby.dev.core.Yolt;
import bobbynooby.dev.lockout.UncountedDeaths;
import bobbynooby.dev.mixin.PlayerListInvoker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.gamerules.GameRules;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Moves a dying player's items into graves, the ones in their open menus included. */
public final class GraveDeath {
    // Non-null only while a dying player's menus close. Server thread only.
    private static ServerPlayer collectingFor;
    private static List<ItemStack> collected = new ArrayList<>();

    private GraveDeath() {}

    /** True when the items went into a grave, so vanilla must not drop them as well. */
    public static boolean onPlayerDeathDrops(ServerPlayer player, ServerLevel level) {
        // Before every switch: a player body only wears copies of its owner's saved gear, and the owner's real
        // grave comes from the body's death. Dropping the copies would make the items exist twice. While YOLT is on,
        // BodyDeathLootMixin stops a tagged body's drops before this hook, so this only runs for a body that lost its
        // tag, or with YOLT off.
        if (StandIn.is(player)) {
            player.getInventory().clearContent();
            return true;
        }
        // Before the switch too: a death that makes no grave must not be told about an older one at the respawn
        GraveNotice.forget(player.getUUID());
        // Before the switch as well: a death in creative drops nothing, graves or not, and no XP. The items stay on the
        // dead player for the respawn to hand back, so there is no emptied player to save at once either.
        if (UncountedDeaths.keepsDrops(player)) {
            return true;
        }
        if (!Settings.on(c -> c.graves.enabled)) {
            return false;
        }
        boolean keepInventory = level.getGameRules().get(GameRules.KEEP_INVENTORY);
        YoltConfig.EnderChestMode enderMode = Settings.get().graves.enderChestOnDeath;
        Inventory inventory = player.getInventory();
        PlayerEnderChestContainer enderChest = player.getEnderChestInventory();
        String name = player.nameAndId().name();

        // Until the graves exist, any failure drops everything instead
        List<ItemStack> fromMenus = new ArrayList<>();
        ItemStack head = ItemStack.EMPTY;
        List<Grave> graves;
        try {
            ResolvableProfile profile = Profiles.of(player);
            // The head comes first and is always there: it is the revive token
            head = GraveItems.graveHead(profile);
            List<ItemStack> items = new ArrayList<>();
            items.add(head);
            if (!keepInventory) {
                // Menus close first, so a menu that hands items back into the inventory is covered by the scan
                closeMenusInto(player, fromMenus);
                destroyVanishing(inventory);
                addStacks(items, inventory);
                items.addAll(fromMenus);
            }
            if (enderMode == YoltConfig.EnderChestMode.GRAVE) {
                addStacks(items, enderChest);
            }
            graves = Graves.create(level, player.blockPosition(), profile, player.getUUID(), name, items);
        } catch (RuntimeException e) {
            Yolt.LOGGER.error("Could not make a grave for {}, dropping their items instead", name, e);
            // The head drops too, keepInventory or not, or nobody could revive them. With keepInventory no menu was
            // closed, the inventory stays, and vanilla drops nothing either way.
            return dropWhereTheyLast(player, level, name, head, fromMenus, keepInventory) && !keepInventory;
        }

        // The emptied player is saved right after the death, by the lockout's kick or below, so the graves must reach
        // the disk first. This runs inside the death, so a failed write only logs; the next save tries again.
        boolean gravesOnDisk = true;
        try {
            GraveStore.get(level.getServer()).saveNow(level.getServer());
        } catch (RuntimeException e) {
            gravesOnDisk = false;
            Yolt.LOGGER.error("Could not write {}'s grave to disk, it is kept in memory until the next save", name, e);
        }

        // Emptied only once the graves hold the items, so a failed grave can't lose them
        if (!keepInventory) {
            inventory.clearContent();
        }
        if (enderMode == YoltConfig.EnderChestMode.GRAVE || enderMode == YoltConfig.EnderChestMode.WIPE) {
            enderChest.clearContent();
        }
        GraveNotice.tellAtRespawn(player.getUUID(), graves.get(0).id());
        afterTheKick(level.getServer(), player.getUUID(), name, gravesOnDisk);
        return true;
    }

    // Queued from a task, so it runs after the lockout's kick, which the death queues after this: a player the kick has
    // taken already was saved as they left. One still here would wait for the next autosave, and a crash before it
    // would bring the items back beside their graves, so they are saved now; but only once the graves are on disk, or
    // that crash would lose the items instead. A kicked player on a real connection is still here, since they leave
    // only at the next connection tick, so they are saved now and again as they leave, which does no harm.
    private static void afterTheKick(MinecraftServer server, UUID id, String name, boolean gravesOnDisk) {
        Later.afterTick(server, () -> Later.afterTick(server, () -> {
            ServerPlayer current = server.getPlayerList().getPlayer(id);
            if (current != null && gravesOnDisk) {
                Guard.run(() -> ((PlayerListInvoker) server.getPlayerList()).yoltSave(current),
                        "Could not save {} after their death, so their items are in their save until the next one", name);
            }
        }));
    }

    /** Called by the menu and drop hooks. True when the stack was taken for a dying player's grave instead. */
    public static boolean takeReturnedItem(Player player, ItemStack stack) {
        if (collectingFor == null || player != collectingFor) {
            return false;
        }
        if (!stack.isEmpty()) {
            collected.add(stack);
        }
        return true;
    }

    // Vanilla would drop them at the player, which over the void is below the world, where they are gone at once. So the
    // head, the inventory and what the menus handed back drop where the grave would have gone, or on the last ground, the
    // way vanilla's own death drops go: curse of vanishing destroyed, the rest out of the inventory. With keepInventory
    // only the head drops. False if that fails too: the head and the menu items go back into the inventory, and vanilla
    // drops it all where the player died, or keeps it with keepInventory.
    private static boolean dropWhereTheyLast(ServerPlayer player, ServerLevel level, String name, ItemStack head,
                                             List<ItemStack> fromMenus, boolean keepInventory) {
        Inventory inventory = player.getInventory();
        BlockPos spot;
        try {
            if (!keepInventory) {
                destroyVanishing(inventory);
            }
            spot = GravePlacement.dropSpot(level, player.blockPosition(),
                    LastGround.in(player.getUUID(), level.dimension()).orElse(null), GraveStore.get(level.getServer()));
            // Copies, so a drop that fails leaves every item where it was
            List<ItemStack> stacks = new ArrayList<>();
            stacks.add(head);
            if (!keepInventory) {
                for (int i = 0; i < inventory.getContainerSize(); i++) {
                    stacks.add(inventory.getItem(i));
                }
            }
            stacks.addAll(fromMenus);
            Graves.dropForGood(level, spot, Stacks.nonEmptyCopies(stacks));
        } catch (RuntimeException e) {
            Yolt.LOGGER.error("Could not drop {}'s items where they would last either, so they drop where {} died", name, name, e);
            List<ItemStack> back = new ArrayList<>();
            back.add(head);
            back.addAll(fromMenus);
            handBack(player, Stacks.nonEmpty(back));
            return false;
        }
        if (!keepInventory) {
            inventory.clearContent();
        }
        Yolt.LOGGER.info("Dropped {}'s items at {} {}, since their grave could not be made", name, level.dimension().identifier(), spot);
        return true;
    }

    // As vanilla's own death does, but a grave or revive head stays: it goes into the grave, or drops, like any item
    private static void destroyVanishing(Inventory inventory) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (GraveItems.vanishesAtDeath(inventory.getItem(i))) {
                inventory.removeItemNoUpdate(i);
            }
        }
    }

    private static void addStacks(List<ItemStack> into, Container container) {
        List<ItemStack> all = new ArrayList<>();
        for (int i = 0; i < container.getContainerSize(); i++) {
            all.add(container.getItem(i));
        }
        into.addAll(Stacks.nonEmpty(all));
    }

    // Closes the menus with their own vanilla code, so any menu hands back its cursor and input items (crafting
    // grids, anvil, loom, trades...). The hooks catch each item on its way to the inventory or the ground, and what
    // they caught is in the list even when a close throws. A menu's close code could kill another player and start
    // their collection, so each call keeps its own list and restores the one before.
    private static void closeMenusInto(ServerPlayer player, List<ItemStack> into) {
        ServerPlayer previousFor = collectingFor;
        List<ItemStack> previous = collected;
        collectingFor = player;
        collected = into;
        try {
            try {
                if (player.containerMenu != player.inventoryMenu) {
                    closeContainer(player, into);
                }
            } finally {
                // The inventory menu is never closed, but this returns its cursor and 2x2 grid the same way. Also after
                // a menu that threw, or they would drop where the player is removed, over the void below the world.
                player.inventoryMenu.removed(player);
            }
        } finally {
            collectingFor = previousFor;
            collected = previous;
        }
    }

    // No close packet: the client already shows the death screen, and a close would swap it for one without the
    // death message
    private static void closeContainer(ServerPlayer player, List<ItemStack> into) {
        try {
            player.doCloseContainer();
        } catch (RuntimeException e) {
            takeCursor(player.containerMenu, into);
            throw e;
        }
    }

    // A menu whose close threw may still hold its cursor stack. The close may have handed that very stack over before
    // it threw, so it is only taken once.
    private static void takeCursor(AbstractContainerMenu menu, List<ItemStack> into) {
        ItemStack carried = menu.getCarried();
        menu.setCarried(ItemStack.EMPTY);
        if (!carried.isEmpty() && into.stream().noneMatch(taken -> taken == carried)) {
            into.add(carried);
        }
    }

    // Each stack on its own, so one that can't go back doesn't keep the rest from being dropped
    private static void handBack(ServerPlayer player, List<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            putBackOrDrop(player, stack, s -> player.getInventory().placeItemBackInInventory(s, Prediction.SERVER_ONLY));
        }
    }

    /**
     * Hands the stack to putBack. If that throws, what is left in the stack drops at the player as an item, so it is
     * never lost. A put-back that took part of the stack before it threw has already shrunk it, so nothing doubles.
     */
    public static void putBackOrDrop(ServerPlayer player, ItemStack stack, Consumer<ItemStack> putBack) {
        try {
            putBack.accept(stack);
        } catch (RuntimeException e) {
            String name = player.nameAndId().name();
            if (stack.isEmpty()) {
                Yolt.LOGGER.error("Could not hand an item back to {}, but all of it went back before the error", name, e);
                return;
            }
            Yolt.LOGGER.error("Could not hand {} back to {}, so it drops at them instead", stack, name, e);
            // A full inventory's put-back drops this very stack, which may already sit in an item entity. Emptying it
            // leaves that entity with nothing, so the items can't be in two entities and double on the next load.
            ItemStack dropped = stack.copyAndClear();
            Guard.run(() -> {
                ItemEntity item = new ItemEntity(player.level(), player.getX(), player.getEyeY() - 0.3, player.getZ(), dropped);
                item.setDefaultPickUpDelay();
                player.level().addFreshEntity(item);
            }, "Could not drop {} at {} either, so it is lost", dropped, name);
        }
    }
}
