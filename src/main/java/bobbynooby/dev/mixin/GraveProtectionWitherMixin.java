package bobbynooby.dev.mixin;

import bobbynooby.dev.graves.Graves;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(WitherBoss.class)
public abstract class GraveProtectionWitherMixin {
    // A hurt Wither breaks the blocks around itself directly, not through an explosion
    @WrapOperation(method = "customServerAiStep", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;)Z"))
    private boolean keepGraves(ServerLevel level, BlockPos pos, boolean dropResources, Entity breaker, Operation<Boolean> original) {
        return !Graves.isGrave(level, pos) && original.call(level, pos, dropResources, breaker);
    }
}
