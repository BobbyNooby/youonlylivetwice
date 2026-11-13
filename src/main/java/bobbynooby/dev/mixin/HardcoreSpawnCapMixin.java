package bobbynooby.dev.mixin;

import bobbynooby.dev.hardcore.BloodMoon;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.entity.MobCategory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(MobCategory.class)
public abstract class HardcoreSpawnCapMixin {
    // Both the level's cap and each player's nearby cap read this; the two mixins beside it say which level and player
    @ModifyReturnValue(method = "getMaxInstancesPerChunk", at = @At("RETURN"))
    private int bloodMoonCap(int vanilla) {
        return BloodMoon.spawnCap((MobCategory) (Object) this, vanilla);
    }
}
