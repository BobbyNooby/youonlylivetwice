package bobbynooby.dev.mixin;

import bobbynooby.dev.world.Sleep;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class NoBedRespawnMixin {
    @Inject(method = "getRespawnConfig", at = @At("HEAD"), cancellable = true)
    private void hideRespawnConfig(CallbackInfoReturnable<ServerPlayer.RespawnConfig> cir) {
        if (Sleep.ignoresRespawnPoint()) {
            cir.setReturnValue(null);
        }
    }
}
