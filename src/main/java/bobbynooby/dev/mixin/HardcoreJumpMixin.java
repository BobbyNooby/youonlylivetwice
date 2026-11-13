package bobbynooby.dev.mixin;

import bobbynooby.dev.hardcore.Weight;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class HardcoreJumpMixin {
    // After vanilla's own jump hunger, so a heavy load adds to it
    @Inject(method = "jumpFromGround", at = @At("TAIL"))
    private void afterJump(CallbackInfo ci) {
        Weight.onJump((ServerPlayer) (Object) this);
    }
}
