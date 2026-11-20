package bobbynooby.dev.mixin;

import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

// No field constructor and no getter for the section, so the coordinate offset makes a copy and fills in its fields
@Mixin(ClientboundSectionBlocksUpdatePacket.class)
public interface SectionBlocksUpdatePacketAccessor {

    @Accessor("sectionPos")
    SectionPos yoltSectionPos();

    @Accessor("sectionPos")
    @Mutable
    void yoltSetSectionPos(SectionPos sectionPos);

    @Accessor("positions")
    short[] yoltPositions();

    @Accessor("positions")
    @Mutable
    void yoltSetPositions(short[] positions);

    @Accessor("states")
    BlockState[] yoltStates();

    @Accessor("states")
    @Mutable
    void yoltSetStates(BlockState[] states);
}
