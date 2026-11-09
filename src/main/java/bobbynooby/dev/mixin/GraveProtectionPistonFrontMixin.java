package bobbynooby.dev.mixin;

import bobbynooby.dev.graves.Graves;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PistonStructureResolver.class)
public abstract class GraveProtectionPistonFrontMixin {
    @Shadow
    @Final
    private Level level;

    @Shadow
    @Final
    private BlockPos startPos;

    // A head right in front of a piston is popped even though it can't be pushed, so the piston must not move at all
    @Inject(method = "resolve", at = @At("HEAD"), cancellable = true)
    private void keepGraves(CallbackInfoReturnable<Boolean> cir) {
        if (Graves.isGrave(level, startPos)) {
            cir.setReturnValue(false);
        }
    }
}
