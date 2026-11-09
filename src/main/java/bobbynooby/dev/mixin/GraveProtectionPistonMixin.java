package bobbynooby.dev.mixin;

import bobbynooby.dev.graves.Graves;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PistonBaseBlock.class)
public abstract class GraveProtectionPistonMixin {
    // A grave further along a pushed line now stops the piston instead of being popped, and is never pulled
    @Inject(method = "isPushable", at = @At("HEAD"), cancellable = true)
    private static void keepGraves(BlockState state, Level level, BlockPos pos, Direction direction, boolean allowDestroyable,
                                   Direction connectionDirection, CallbackInfoReturnable<Boolean> cir) {
        if (Graves.isGrave(level, pos)) {
            cir.setReturnValue(false);
        }
    }
}
