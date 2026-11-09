package bobbynooby.dev.mixin;

import bobbynooby.dev.graves.Graves;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BucketItem.class)
public abstract class GraveProtectionBucketMixin {
    // A head gives way to any poured fluid. Player buckets, dispensers and fish buckets all pour through here.
    @Inject(method = "emptyContents", at = @At("HEAD"), cancellable = true)
    private void keepGraves(@Nullable LivingEntity user, Level level, BlockPos pos, @Nullable BlockHitResult hitResult,
                            CallbackInfoReturnable<Boolean> cir) {
        if (Graves.isGrave(level, pos)) {
            cir.setReturnValue(false);
        }
    }
}
