package bobbynooby.dev.graves;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;

public class GravesManager extends PersistentState {


    public static class Grave {
        public final BlockPos pos;
        public final DefaultedList<ItemStack> items;

        public Grave(BlockPos pos, DefaultedList<ItemStack> items) {
            this.pos = pos;
            this.items = items;
        }

        public NbtCompound toNbt() {
            NbtCompound nbtCompound = new NbtCompound();
            nbtCompound.putInt("x", pos.getX());
            nbtCompound.putInt("y", pos.getY());
            nbtCompound.putInt("z", pos.getZ());
            return nbtCompound;
        }
    }
}
