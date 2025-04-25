package dev.bobbynooby.youOnlyLiveTwice.utils;

import net.citizensnpcs.api.trait.trait.Equipment;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;

public class InventoryHandler {

    private final Inventory inventory = Bukkit.createInventory(null, 9 * 6, "Inventory");


    public InventoryHandler(ItemStack[] inventory, ItemStack[] equipment) {
    }
}
