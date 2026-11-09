package bobbynooby.dev.mixin;

import bobbynooby.dev.world.Sleep;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ServerPlayer.class)
public abstract class NoBedRespawnMixin {
    // Only the bed's own call: /spawnpoint still sets a point, and one already stored is used as vanilla uses it.
    // Skipping the call also skips its "Respawn point set" message.
    @WrapWithCondition(method = "startSleepInBed", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;setRespawnPosition(Lnet/minecraft/server/level/ServerPlayer$RespawnConfig;Z)V"))
    private boolean bedSetsNoRespawnPoint(ServerPlayer player, ServerPlayer.RespawnConfig config, boolean showMessage) {
        return !Sleep.ignoresRespawnPoint();
    }
}
