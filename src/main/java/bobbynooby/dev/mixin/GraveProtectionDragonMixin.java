package bobbynooby.dev.mixin;

import bobbynooby.dev.graves.Graves;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(EnderDragon.class)
public abstract class GraveProtectionDragonMixin {
    // The dragon removes every block it flies through, not through an explosion
    @WrapOperation(method = "checkWalls", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"))
    private boolean keepGraves(ServerLevel level, BlockPos pos, boolean movedByPiston, Operation<Boolean> original) {
        return !Graves.isGrave(level, pos) && original.call(level, pos, movedByPiston);
    }
}
