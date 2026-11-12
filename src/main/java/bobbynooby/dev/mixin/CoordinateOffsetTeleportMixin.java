package bobbynooby.dev.mixin;

import bobbynooby.dev.coords.CoordinateOffsets;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class CoordinateOffsetTeleportMixin {
    // Only a teleport to another level builds a respawn packet, which starts a frame where the player arrives
    @Inject(method = "teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/server/level/ServerPlayer;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;"
                    + "createCommonSpawnInfo(Lnet/minecraft/server/level/ServerLevel;)Lnet/minecraft/network/protocol/game/CommonPlayerSpawnInfo;"))
    private void anchorAtTheArrival(TeleportTransition transition, CallbackInfoReturnable<ServerPlayer> cir) {
        CoordinateOffsets.anchorDimensionChange((ServerPlayer) (Object) this, transition);
    }
}
