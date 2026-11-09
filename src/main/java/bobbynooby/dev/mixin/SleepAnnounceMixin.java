package bobbynooby.dev.mixin;

import bobbynooby.dev.world.Sleep;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class SleepAnnounceMixin {
    // Vanilla sends "X/Y players sleeping" to everyone in the dimension whenever someone gets in or out of bed, or a
    // player arrives or leaves while someone sleeps, so it shows that someone is online
    @Inject(method = "announceSleepStatus", at = @At("HEAD"), cancellable = true)
    private void hideSleepStatus(CallbackInfo ci) {
        if (Sleep.hidesSleepStatus()) {
            ci.cancel();
        }
    }
}
