package bobbynooby.dev.mixin;

import bobbynooby.dev.world.RandomSpawn;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.PlayerSpawnFinder;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerSpawnFinder.class)
public abstract class RandomSpawnMixin {

    @Inject(method = "getLevelRespawnPos", at = @At("HEAD"), cancellable = true)
    private static void findOverworldSpawn(ServerLevel level, int x, int z, CallbackInfoReturnable<BlockPos> cir) {
        if (RandomSpawn.appliesTo(level)) {
            cir.setReturnValue(RandomSpawn.pick(level));
        }
    }

}
