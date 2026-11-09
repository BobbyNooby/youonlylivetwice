package bobbynooby.dev.mixin;

import bobbynooby.dev.visibility.PlayerCount;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.server.players.NameAndId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

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

    @ModifyReturnValue(method = "sample", at = @At("RETURN"))
    private List<NameAndId> shownSample(List<NameAndId> real) {
        return PlayerCount.sample(real);
    }
}
