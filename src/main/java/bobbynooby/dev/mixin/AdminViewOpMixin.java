package bobbynooby.dev.mixin;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.visibility.AdminView;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

@Mixin(PlayerList.class)
public abstract class AdminViewOpMixin {
    // Admin view only counts for ops, so the client must catch up as soon as op status changes.
    // The other overload of op calls this one.
    @Inject(method = "op(Lnet/minecraft/server/players/NameAndId;Ljava/util/Optional;Ljava/util/Optional;)V", at = @At("TAIL"))
    private void afterOp(NameAndId nameAndId, Optional<LevelBasedPermissionSet> permissions, Optional<Boolean> canBypassPlayerLimit,
                         CallbackInfo ci) {
        if (Settings.active()) {
            AdminView.onOpChange((PlayerList) (Object) this, nameAndId);
        }
    }

    @Inject(method = "deop", at = @At("TAIL"))
    private void afterDeop(NameAndId nameAndId, CallbackInfo ci) {
        if (Settings.active()) {
            AdminView.onOpChange((PlayerList) (Object) this, nameAndId);
        }
    }
}
