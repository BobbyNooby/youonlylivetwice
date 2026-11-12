package bobbynooby.dev.mixin;

import bobbynooby.dev.coords.CoordinateOffsets;
import net.minecraft.network.HashedStack;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class CoordinateOffsetContainerClickMixin {
    // A click sends hashes of the client's items, which are the shifted ones, so each must match those as well
    @ModifyArg(method = "handleContainerClick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/inventory/AbstractContainerMenu;setRemoteSlotUnsafe(ILnet/minecraft/network/HashedStack;)V"),
            index = 1)
    private HashedStack matchShiftedSlot(HashedStack sent) {
        return CoordinateOffsets.clientHash(((ServerGamePacketListenerImpl) (Object) this).player, sent);
    }

    @ModifyArg(method = "handleContainerClick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/inventory/AbstractContainerMenu;setRemoteCarried(Lnet/minecraft/network/HashedStack;)V"),
            index = 0)
    private HashedStack matchShiftedCarried(HashedStack sent) {
        return CoordinateOffsets.clientHash(((ServerGamePacketListenerImpl) (Object) this).player, sent);
    }
}
