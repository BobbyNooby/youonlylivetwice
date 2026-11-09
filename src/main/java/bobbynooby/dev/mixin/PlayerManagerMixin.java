package bobbynooby.dev.mixin;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.features.TabHeaders;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
public abstract class PlayerManagerMixin {

    /*

        Custom Tab Headers Section

     */

    // Generate custom tab header each time a player joins. Done with the switch off too,
    // so turning it on mid-session also covers players who are already online.
    @Inject(method = "placeNewPlayer", at = @At("HEAD"))
    public void sendHeader(Connection connection, ServerPlayer player, CommonListenerCookie cookie, CallbackInfo ci) {
        if (!Settings.active()) {
            return;
        }
        TabHeaders.onJoin(player);
    }

    // Called every tick. Update the custom tab header
    @Inject(method = "tick", at = @At("HEAD"))
    public void sendHeader(CallbackInfo ci) {
        if (!Settings.active() || !Settings.get().hide.tabHeader) {
            return;
        }
        TabHeaders.sendAll((PlayerList) (Object) this);
    }

}
