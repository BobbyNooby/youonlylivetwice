package bobbynooby.dev.mixin;

import bobbynooby.dev.world.Sleep;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PlayerList.class)
public abstract class NoBedRespawnAnchorSoundMixin {
    // A death respawn reads the new player's point here only to play the deplete sound when it is on an anchor. A point
    // the respawn ignored never used its anchor, so it plays nothing.
    @ModifyExpressionValue(method = "respawn", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;getRespawnConfig()Lnet/minecraft/server/level/ServerPlayer$RespawnConfig;"))
    private ServerPlayer.@Nullable RespawnConfig noSoundForIgnoredPoint(ServerPlayer.@Nullable RespawnConfig config) {
        return config != null && Sleep.ignoresStoredPoint(config.forced()) ? null : config;
    }
}
