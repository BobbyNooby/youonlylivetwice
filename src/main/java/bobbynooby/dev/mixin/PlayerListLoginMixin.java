package bobbynooby.dev.mixin;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.lockout.DeathLockout;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.SocketAddress;

@Mixin(PlayerList.class)
public abstract class PlayerListLoginMixin {
    @Shadow
    @Final
    private MinecraftServer server;

    // Refusing here means a locked-out player is never placed in the world
    @Inject(method = "canPlayerLogin", at = @At("RETURN"), cancellable = true)
    private void refuseLockedOut(SocketAddress address, NameAndId nameAndId, CallbackInfoReturnable<Component> cir) {
        // Bans and the whitelist still win, but "server full" would hide that this player is locked out
        if (!Settings.active() || (cir.getReturnValue() != null && !isServerFull(cir.getReturnValue()))) {
            return;
        }
        Component refusal = DeathLockout.refusal(server, nameAndId.id(), nameAndId.name());
        if (refusal != null) {
            cir.setReturnValue(refusal);
        }
    }

    private static boolean isServerFull(Component refusal) {
        return refusal.getContents() instanceof TranslatableContents translatable
                && translatable.getKey().equals("multiplayer.disconnect.server_full");
    }
}
