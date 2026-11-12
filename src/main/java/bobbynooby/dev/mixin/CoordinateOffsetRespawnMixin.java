package bobbynooby.dev.mixin;

import bobbynooby.dev.coords.CoordinateOffsets;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerList.class)
public abstract class CoordinateOffsetRespawnMixin {
    // The new player, the second one here, is in place, and the respawn packet that starts its frame is built next
    @Inject(method = "respawn", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;"
            + "createCommonSpawnInfo(Lnet/minecraft/server/level/ServerLevel;)Lnet/minecraft/network/protocol/game/CommonPlayerSpawnInfo;"))
    private void anchorAtTheRespawn(ServerPlayer previous, boolean keepAllPlayerData, Entity.RemovalReason removalReason,
                                    CallbackInfoReturnable<ServerPlayer> cir, @Local(ordinal = 1) ServerPlayer player) {
        CoordinateOffsets.anchorNextFrame(player, player.position());
    }
}
