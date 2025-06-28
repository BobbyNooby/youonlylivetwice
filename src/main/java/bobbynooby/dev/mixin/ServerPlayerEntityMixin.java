package bobbynooby.dev.mixin;

import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayerEntity.class)
public class ServerPlayerEntityMixin {


    @Inject(method = "getRespawn", at = @At("HEAD"), cancellable = true)
    private void getRespawnPos(CallbackInfoReturnable<ServerPlayerEntity.Respawn> cir) {
        cir.setReturnValue(null);
    }
}
