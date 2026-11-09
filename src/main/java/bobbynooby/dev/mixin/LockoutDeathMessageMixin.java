package bobbynooby.dev.mixin;

import bobbynooby.dev.lockout.DeathMessages;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// die clears the combat tracker before the death event, where the lockout records how the player died
@Mixin(ServerPlayer.class)
public abstract class LockoutDeathMessageMixin {
    @Inject(method = "die", at = @At("HEAD"))
    private void keepDeathMessage(DamageSource source, CallbackInfo ci) {
        DeathMessages.keep((ServerPlayer) (Object) this);
    }
}
