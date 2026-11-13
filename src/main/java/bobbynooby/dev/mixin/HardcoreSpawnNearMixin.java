package bobbynooby.dev.mixin;

import bobbynooby.dev.hardcore.BloodMoon;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LocalMobCapCalculator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LocalMobCapCalculator.class)
public abstract class HardcoreSpawnNearMixin {
    // Each nearby player's counts are looked up just before their cap is read, so this names the player it is for
    @Inject(method = "canSpawn", at = @At(value = "INVOKE", target = "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;"))
    private void capFor(MobCategory category, ChunkPos pos, CallbackInfoReturnable<Boolean> cir, @Local ServerPlayer player) {
        BloodMoon.spawningNear(player);
    }

    @Inject(method = "canSpawn", at = @At("RETURN"))
    private void doneCapFor(MobCategory category, ChunkPos pos, CallbackInfoReturnable<Boolean> cir) {
        BloodMoon.spawningNear(null);
    }
}
