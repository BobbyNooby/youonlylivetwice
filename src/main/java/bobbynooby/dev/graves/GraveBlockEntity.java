package bobbynooby.dev.graves;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventories;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ItemScatterer;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;

public class GraveBlockEntity extends BlockEntity {

    private DefaultedList<ItemStack> inventory = DefaultedList.ofSize(42, ItemStack.EMPTY);


    public GraveBlockEntity(BlockPos pos, BlockState state) {
        super(GravesRegistry.GRAVE_BLOCK_ENTITY, pos, state);
    }

    public DefaultedList<ItemStack> getInventory() {
        return inventory;
    }

    public void setInventory(PlayerInventory playerInventory) {
        System.out.println(playerInventory.size());
        for (int i = 0; i < playerInventory.size() && i < inventory.size(); i++) {
            ItemStack itemStack = playerInventory.getStack(i);
            if (!itemStack.isEmpty()) {
                inventory.set(i, itemStack.copy());
            }
        }
    }

    public void setHead(ServerPlayerEntity player) {
        ItemStack head = new ItemStack(GravesRegistry.GRAVE_BLOCK);
        head.setHolder(player);
        inventory.set(41, head);
    }


    @Override
    public void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.readNbt(nbt, lookup);
        Inventories.readNbt(nbt, inventory, lookup);

    }

    @Override
    public void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.writeNbt(nbt, lookup);
        Inventories.writeNbt(nbt, inventory, lookup);
    }


    @Override
    public void onBlockReplaced(BlockPos pos, BlockState newState) {
        if (getWorld() == null) return;
        ItemScatterer.spawn(getWorld(), pos, getInventory());
    }

}
