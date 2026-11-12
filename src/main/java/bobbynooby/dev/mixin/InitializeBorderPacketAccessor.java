package bobbynooby.dev.mixin;

import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

// The packet is built only from a world border, so the coordinate offset sets the border a client is shown on a copy
@Mixin(ClientboundInitializeBorderPacket.class)
public interface InitializeBorderPacketAccessor {

    @Accessor("newCenterX")
    @Mutable
    void yoltSetNewCenterX(double x);

    @Accessor("newCenterZ")
    @Mutable
    void yoltSetNewCenterZ(double z);

    @Accessor("oldSize")
    @Mutable
    void yoltSetOldSize(double size);

    @Accessor("newSize")
    @Mutable
    void yoltSetNewSize(double size);

    @Accessor("lerpTime")
    @Mutable
    void yoltSetLerpTime(long ticks);

    @Accessor("newAbsoluteMaxSize")
    @Mutable
    void yoltSetNewAbsoluteMaxSize(int size);
}
