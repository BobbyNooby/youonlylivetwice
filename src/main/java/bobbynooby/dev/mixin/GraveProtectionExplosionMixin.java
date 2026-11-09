package bobbynooby.dev.mixin;

import bobbynooby.dev.graves.Graves;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

@Mixin(ServerExplosion.class)
public abstract class GraveProtectionExplosionMixin {
    @Shadow
    @Final
    private ServerLevel level;

    // Every explosion breaks exactly the blocks in this list, so graves are taken out of it
    @ModifyReturnValue(method = "calculateExplodedPositions", at = @At("RETURN"))
    private List<BlockPos> keepGraves(List<BlockPos> positions) {
        positions.removeIf(pos -> Graves.isGrave(level, pos));
        return positions;
    }
}
