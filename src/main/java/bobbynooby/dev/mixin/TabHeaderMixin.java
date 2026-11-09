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
public abstract class TabHeaderMixin {

    // Generate custom tab header each time a player joins. Done with the switch off too,
    // so turning it on mid-session also covers players who are already online.
    @Inject(method = "placeNewPlayer", at = @At("HEAD"))
    private void headerOnJoin(Connection connection, ServerPlayer player, CommonListenerCookie cookie, CallbackInfo ci) {
        if (!Settings.active()) {
            return;
        }
        TabHeaders.onJoin(player);
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void headerEveryTick(CallbackInfo ci) {
        if (!Settings.on(c -> c.hide.tabHeader)) {
            return;
        }
        TabHeaders.sendAll((PlayerList) (Object) this);
    }
}
