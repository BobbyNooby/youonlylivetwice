package bobbynooby.dev.mixin;

import bobbynooby.dev.world.Sleep;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RespawnAnchorBlock.class)
public abstract class NoBedRespawnAnchorMixin {
    // Stops just before the anchor sets the point, so nothing is stored and there's no message or set-spawn sound.
    // The anchor still explodes outside the Nether. CONSUME is vanilla's answer for an anchor that is already your point.
    @Inject(method = "useWithoutItem", cancellable = true, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;getRespawnConfig()Lnet/minecraft/server/level/ServerPlayer$RespawnConfig;"))
    private void anchorSetsNoRespawnPoint(CallbackInfoReturnable<InteractionResult> cir) {
        if (Sleep.ignoresRespawnPoint()) {
            cir.setReturnValue(InteractionResult.CONSUME);
        }
    }
}
