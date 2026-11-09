package bobbynooby.dev.mixin;

import bobbynooby.dev.visibility.PlayerCount;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.server.players.NameAndId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(ServerStatus.Players.class)
public abstract class PlayerCountMixin {

    @ModifyReturnValue(method = "max", at = @At("RETURN"))
    private int shownMax(int real) {
        return PlayerCount.max(real);
    }

    @ModifyReturnValue(method = "online", at = @At("RETURN"))
    private int shownOnline(int real) {
        return PlayerCount.online(real);
    }

    @Inject(method = "sample", at = @At("HEAD"), cancellable = true)
    private void getSamplePlayers(CallbackInfoReturnable<List<NameAndId>> cir) {
        if (PlayerCount.hides()) {
            cir.setReturnValue(List.of());
        }
    }
}
