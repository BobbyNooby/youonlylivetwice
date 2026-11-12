package bobbynooby.dev.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

// The packet is only built from a block entity, so the coordinate offset makes its shifted copy through the private constructor
@Mixin(ClientboundBlockEntityDataPacket.class)
public interface BlockEntityDataPacketInvoker {

    @Invoker("<init>")
    static ClientboundBlockEntityDataPacket yoltNew(BlockPos pos, BlockEntityType<?> type, CompoundTag tag) {
        throw new AssertionError();
    }
}
