package bobbynooby.dev.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.ServerMetadata;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(ServerMetadata.Players.class)
public class PlayerCountSpoofer {

    @Inject(method="max" , at = @At("HEAD"), cancellable = true)
    private void getMaxPlayers(CallbackInfoReturnable<Integer> cir){
        cir.setReturnValue(1);
    }

    @Inject(method="online" , at = @At("HEAD"), cancellable = true)
    private void getOnlinePlayers(CallbackInfoReturnable<Integer> cir){
        cir.setReturnValue(0);
    }

    @Inject(method = "sample", at = @At("HEAD"), cancellable = true)
    private void getSamplePlayers(CallbackInfoReturnable<List<GameProfile>> cir) {
        cir.setReturnValue(List.of());
    }
}
