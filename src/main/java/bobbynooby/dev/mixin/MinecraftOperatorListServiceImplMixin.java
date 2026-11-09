package bobbynooby.dev.mixin;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.visibility.AdminView;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.jsonrpc.internalapi.MinecraftOperatorListServiceImpl;
import net.minecraft.server.jsonrpc.methods.ClientInfo;
import net.minecraft.server.notifications.NotificationManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftOperatorListServiceImpl.class)
public abstract class MinecraftOperatorListServiceImplMixin {
    @Shadow
    @Final
    private NotificationManager notificationManager;

    // The management API's operators/clear empties the list without the deop vanilla does for each player
    @Inject(method = "clear", at = @At("TAIL"))
    private void afterClear(ClientInfo clientInfo, CallbackInfo ci) {
        DedicatedServer server = this.notificationManager.server();
        if (Settings.active() && server != null) {
            AdminView.onOpListCleared(server.getPlayerList());
        }
    }
}
