package bobbynooby.dev.mixin;

import bobbynooby.dev.graves.Graves;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockItem.class)
public abstract class GraveHeadPlacementMixin {
    // Every head placement passes through here, after the clicked block has had its chance to react
    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void refuseMarkedHeads(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        if (Graves.refusePlacement(context)) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }
}
