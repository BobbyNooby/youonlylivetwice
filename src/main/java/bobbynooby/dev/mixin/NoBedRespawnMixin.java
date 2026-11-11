package bobbynooby.dev.mixin;

import bobbynooby.dev.world.Sleep;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ServerPlayer.class)
public abstract class NoBedRespawnMixin {
    // Only the bed's own call, so /spawnpoint still sets a point. Skipping it also skips "Respawn point set".
    @WrapWithCondition(method = "startSleepInBed", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;setRespawnPosition(Lnet/minecraft/server/level/ServerPlayer$RespawnConfig;Z)V"))
    private boolean bedSetsNoRespawnPoint(ServerPlayer player, ServerPlayer.RespawnConfig config, boolean showMessage) {
        return !Sleep.bedsSetNoRespawnPoint();
    }

    // Death respawns, the credits and End exits all read the point here. A bed or anchor point stored by an older
    // build counts as none, so the player goes to the world spawn; the point itself stays in the save.
    @ModifyExpressionValue(method = "findRespawnPositionAndUseSpawnBlock", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;getRespawnConfig()Lnet/minecraft/server/level/ServerPlayer$RespawnConfig;"))
    private ServerPlayer.@Nullable RespawnConfig ignoreStoredBedPoint(ServerPlayer.@Nullable RespawnConfig config) {
        return config != null && Sleep.ignoresStoredPoint(config.forced()) ? null : config;
    }
}
