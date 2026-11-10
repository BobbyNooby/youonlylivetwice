package bobbynooby.dev.graves;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.core.Guard;
import bobbynooby.dev.core.Profiles;
import bobbynooby.dev.core.Stacks;
import bobbynooby.dev.core.StandIn;
import bobbynooby.dev.core.Yolt;
import bobbynooby.dev.mixin.PlayerInvoker;
import bobbynooby.dev.mixin.PlayerListInvoker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.gamerules.GameRules;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Moves a dying player's items into graves, the ones in their open menus included. */
public final class GraveDeath {
    // Non-null only while a dying player's menus close. Server thread only.
    private static ServerPlayer collectingFor;
    private static List<ItemStack> collected = new ArrayList<>();

    private GraveDeath() {
    }

    /** True when the items went into a grave, so vanilla must not drop them as well. */
    public static boolean onPlayerDeathDrops(ServerPlayer player, ServerLevel level) {
        // Before every switch: a player body only wears copies of its owner's saved gear, and the owner's real
        // grave comes from the body's death. Dropping the copies would make the items exist twice.
        if (StandIn.is(player)) {
            player.getInventory().clearContent();
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

        // Until the graves exist, any failure falls back to vanilla dropping everything
        List<ItemStack> fromMenus = List.of();
        try {
            ResolvableProfile profile = Profiles.of(player);
            // The head comes first and is always there: it is the revive token
            List<ItemStack> items = new ArrayList<>();
            items.add(GraveItems.graveHead(profile));
            if (!keepInventory) {
                // Menus close first, so a menu that hands items back into the inventory is covered by the scan
                fromMenus = takeMenuItems(player);
                ((PlayerInvoker) player).yoltDestroyVanishingCursedItems();
                addStacks(items, inventory);
                items.addAll(fromMenus);
            }
            if (enderMode == YoltConfig.EnderChestMode.GRAVE) {
                addStacks(items, enderChest);
            }
            Graves.create(level, player.blockPosition(), profile, player.getUUID(), name, items);
        } catch (RuntimeException e) {
            // Only the menu items have left the player; put them back so they drop with the rest
            Yolt.LOGGER.error("Could not make a grave for {}, dropping their items instead", name, e);
            handBack(player, fromMenus);
            // With keepInventory vanilla drops nothing, and the inventory stays
            return !keepInventory && dropWhereTheyLast(player, level, name);
        }

        // The lockout kick right after a death saves the emptied player at once, so the graves must reach the
        // disk first. This runs inside the death, so a failed write only logs; the next save tries again.
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
        // A death with no kick would wait for the next autosave, and a crash before it would bring the items back
        // beside their graves. Only once the graves are on disk, or that crash would lose them instead.
        if (gravesOnDisk && !deathKicks()) {
            Guard.run(() -> ((PlayerListInvoker) level.getServer().getPlayerList()).yoltSave(player),
                    "Could not save {} after their death, so their items are in their save until the next one", name);
        }
        return true;
    }

    /**
     * True when a death now locks the player out and so kicks them: the lockout is on and has days to wait. The kick
     * saves the player and tells them where their grave is.
     */
    static boolean deathKicks() {
        return Settings.on(c -> c.deathLockout.enabled) && Settings.get().deathLockout.cooldownDays > 0;
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

    // Vanilla would drop them at the player, which over the void is below the world, where they are gone at once. So
    // they drop where the grave would have gone, or on the last ground, the way vanilla's own death drops go: curse of
    // vanishing destroyed, the rest out of the inventory. False if that fails too, so vanilla drops them after all.
    private static boolean dropWhereTheyLast(ServerPlayer player, ServerLevel level, String name) {
        Inventory inventory = player.getInventory();
        BlockPos spot;
        try {
            ((PlayerInvoker) player).yoltDestroyVanishingCursedItems();
            spot = GravePlacement.dropSpot(level, player.blockPosition(),
                    LastGround.in(player.getUUID(), level.dimension()).orElse(null), GraveStore.get(level.getServer()));
            // Copies, so a drop that fails leaves every item in the inventory for vanilla to drop
            List<ItemStack> stacks = new ArrayList<>();
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                stacks.add(inventory.getItem(i));
            }
            Graves.dropForGood(level, spot, Stacks.nonEmptyCopies(stacks));
        } catch (RuntimeException e) {
            Yolt.LOGGER.error("Could not drop {}'s items where they would last either, so they drop where {} died", name, name, e);
            return false;
        }
        inventory.clearContent();
        Yolt.LOGGER.info("Dropped {}'s items at {} {}, since their grave could not be made", name, level.dimension().identifier(), spot);
        return true;
    }

    private static void addStacks(List<ItemStack> into, Container container) {
        List<ItemStack> all = new ArrayList<>();
        for (int i = 0; i < container.getContainerSize(); i++) {
            all.add(container.getItem(i));
        }
        into.addAll(Stacks.nonEmpty(all));
    }

    // Closes the menus with their own vanilla code, so any menu hands back its cursor and input items (crafting
    // grids, anvil, loom, trades...). The hooks catch each item on its way to the inventory or the ground.
    private static List<ItemStack> takeMenuItems(ServerPlayer player) {
        List<ItemStack> taken = new ArrayList<>();
        try {
            closeMenusInto(player, taken);
        } catch (RuntimeException e) {
            // The items caught so far would go down with the grave, so they go back for vanilla to drop
            handBack(player, taken);
            throw e;
        }
        return taken;
    }

    // A menu's close code could kill another player and start their collection, so each call keeps its own list
    // and restores the one before
    private static void closeMenusInto(ServerPlayer player, List<ItemStack> into) {
        ServerPlayer previousFor = collectingFor;
        List<ItemStack> previous = collected;
        collectingFor = player;
        collected = into;
        try {
            if (player.containerMenu != player.inventoryMenu) {
                // No close packet: the client already shows the death screen, and a close would swap it for one
                // without the death message
                player.doCloseContainer();
            }
            // The inventory menu is never closed, but this returns its cursor and 2x2 grid the same way
            player.inventoryMenu.removed(player);
        } finally {
            collectingFor = previousFor;
            collected = previous;
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
