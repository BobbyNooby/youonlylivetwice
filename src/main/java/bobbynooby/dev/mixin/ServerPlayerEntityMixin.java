package bobbynooby.dev.mixin;

import bobbynooby.dev.config.Settings;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public class ServerPlayerEntityMixin {


    @Inject(method = "getRespawnConfig", at = @At("HEAD"), cancellable = true)
    private void getRespawnPos(CallbackInfoReturnable<ServerPlayer.RespawnConfig> cir) {
        if (Settings.active() && Settings.get().sleep.noBedRespawn) {
            cir.setReturnValue(null);
        }
    }
}
