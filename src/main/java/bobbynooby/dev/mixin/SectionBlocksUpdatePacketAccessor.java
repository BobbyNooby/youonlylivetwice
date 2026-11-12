package bobbynooby.dev.mixin;

import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

// No field constructor and no getter for the section, so the coordinate offset moves a copy's section itself
@Mixin(ClientboundSectionBlocksUpdatePacket.class)
public interface SectionBlocksUpdatePacketAccessor {

    @Accessor("sectionPos")
    SectionPos yoltSectionPos();

    @Accessor("sectionPos")
    @Mutable
    void yoltSetSectionPos(SectionPos sectionPos);
}
