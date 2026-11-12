package bobbynooby.dev.mixin;

import bobbynooby.dev.coords.CoordinateOffsets;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class CoordinateOffsetCollisionMixin {
    // Every collision with a block in a context asks here, a player's own movement included
    @ModifyReturnValue(method = "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;", at = @At("RETURN"))
    private VoxelShape shapeTheClientSees(VoxelShape shape, @Local(argsOnly = true) BlockPos pos,
                                          @Local(argsOnly = true) CollisionContext context) {
        return CoordinateOffsets.collisionFor((BlockState) (Object) this, pos, context, shape);
    }
}
