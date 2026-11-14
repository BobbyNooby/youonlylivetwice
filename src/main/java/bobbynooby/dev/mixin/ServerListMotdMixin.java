package bobbynooby.dev.mixin;

import bobbynooby.dev.visibility.ServerListMotd;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.server.network.ServerStatusPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ServerStatusPacketListenerImpl.class)
public abstract class ServerListMotdMixin {
    // Each ping gets a listener of its own holding vanilla's cached status, so the line is put on as the answer is sent
    @ModifyExpressionValue(method = "handleStatusRequest", at = @At(value = "FIELD",
            target = "Lnet/minecraft/server/network/ServerStatusPacketListenerImpl;status:Lnet/minecraft/network/protocol/status/ServerStatus;"))
    private ServerStatus pickedMotd(ServerStatus status) {
        return ServerListMotd.forPing(status);
    }
}
