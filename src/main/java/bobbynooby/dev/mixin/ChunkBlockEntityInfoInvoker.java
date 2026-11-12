package bobbynooby.dev.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Optional;

// Reads one block entity of a chunk packet, a private record. Only instance invokers: they are called through this
// interface, while a static one would be called on the record itself, which nothing outside its package may do.
@Mixin(targets = "net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData$BlockEntityInfo")
public interface ChunkBlockEntityInfoInvoker {

    @Invoker("packedXZ")
    byte yoltPackedXZ();

    @Invoker("y")
    short yoltY();

    @Invoker("type")
    BlockEntityType<?> yoltType();

    @Invoker("tag")
    Optional<CompoundTag> yoltTag();
}
