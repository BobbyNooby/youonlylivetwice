package bobbynooby.dev.mixin;

import bobbynooby.dev.world.Sleep;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ServerPlayer.class)
public abstract class NoBedRespawnMixin {
    // Death respawns, the credits and End exits all read the point here. A bed or anchor point stored by an older
    // build counts as none, so the player goes to the world spawn; the point itself stays in the save.
    @ModifyExpressionValue(method = "findRespawnPositionAndUseSpawnBlock", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;getRespawnConfig()Lnet/minecraft/server/level/ServerPlayer$RespawnConfig;"))
    private ServerPlayer.@Nullable RespawnConfig ignoreStoredBedPoint(ServerPlayer.@Nullable RespawnConfig config) {
        return config != null && Sleep.ignoresStoredPoint(config.forced()) ? null : config;
    }
}
