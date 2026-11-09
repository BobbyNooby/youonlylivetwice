package bobbynooby.dev.mixin;

import bobbynooby.dev.graves.Graves;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FlowingFluid.class)
public abstract class GraveProtectionFluidMixin {
    // Water and lava ask this before flowing into a block, and heads are washed away by both.
    // Fluid spread calls it very often, so the head check on the state comes first.
    @Inject(method = "canHoldSpecificFluid", at = @At("HEAD"), cancellable = true)
    private static void keepGraves(BlockGetter level, BlockPos pos, BlockState state, Fluid fluid, CallbackInfoReturnable<Boolean> cir) {
        if (state.is(Blocks.PLAYER_HEAD) && level instanceof Level world && Graves.isGrave(world, pos)) {
            cir.setReturnValue(false);
        }
    }
}
