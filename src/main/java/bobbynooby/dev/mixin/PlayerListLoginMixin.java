package bobbynooby.dev.mixin;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.lockout.DeathLockout;
import net.minecraft.network.chat.Component;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.SocketAddress;

@Mixin(PlayerList.class)
public abstract class PlayerListLoginMixin {
    // Refusing here means a locked-out player is never placed in the world
    @Inject(method = "canPlayerLogin", at = @At("RETURN"), cancellable = true)
    private void refuseLockedOut(SocketAddress address, NameAndId nameAndId, CallbackInfoReturnable<Component> cir) {
        if (!Settings.active() || cir.getReturnValue() != null) {
            return;
        }
        Component refusal = DeathLockout.refusal(nameAndId.id());
        if (refusal != null) {
            cir.setReturnValue(refusal);
        }
    }
}
