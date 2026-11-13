package bobbynooby.dev.mixin;

import bobbynooby.dev.coords.CoordinateOffsets;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(ServerPlayer.class)
public abstract class CoordinateOffsetSpawnProtectionMixin {
    // The line names the block by its real numbers, as text, where no packet rule can find them
    @ModifyArg(method = "sendSpawnProtectionMessage(Lnet/minecraft/core/BlockPos;)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/chat/Component;translatable(Ljava/lang/String;[Ljava/lang/Object;)"
                    + "Lnet/minecraft/network/chat/MutableComponent;"), index = 1)
    private Object[] blockTheClientSees(Object[] args, @Local(argsOnly = true) BlockPos pos) {
        return new Object[] {CoordinateOffsets.spawnProtectionSpot((ServerPlayer) (Object) this, pos)};
    }
}
