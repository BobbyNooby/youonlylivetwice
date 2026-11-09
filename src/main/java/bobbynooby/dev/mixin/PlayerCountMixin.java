package bobbynooby.dev.mixin;

import bobbynooby.dev.visibility.PlayerCount;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.server.players.NameAndId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(ServerStatus.Players.class)
public abstract class PlayerCountMixin {

    @Inject(method = "max", at = @At("HEAD"), cancellable = true)
    private void getMaxPlayers(CallbackInfoReturnable<Integer> cir) {
        if (PlayerCount.hides()) {
            cir.setReturnValue(PlayerCount.SHOWN_MAX);
        }
    }

    @Inject(method = "online", at = @At("HEAD"), cancellable = true)
    private void getOnlinePlayers(CallbackInfoReturnable<Integer> cir) {
        if (PlayerCount.hides()) {
            cir.setReturnValue(PlayerCount.SHOWN_ONLINE);
        }
    }

    @Inject(method = "sample", at = @At("HEAD"), cancellable = true)
    private void getSamplePlayers(CallbackInfoReturnable<List<NameAndId>> cir) {
        if (PlayerCount.hides()) {
            cir.setReturnValue(List.of());
        }
    }
}
