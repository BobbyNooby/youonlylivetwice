package bobbynooby.dev.mixin;


import bobbynooby.dev.features.RandomSpawn;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.PlayerSpawnFinder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerSpawnFinder.class)
public abstract class SpawnLocatingMixin {


    // Only the overworld spawn is randomised, other dimensions keep vanilla spawning
    @Inject(method = "getLevelRespawnPos", at = @At("HEAD"), cancellable = true)
    private static void findOverworldSpawn(ServerLevel level, int x, int z, CallbackInfoReturnable<BlockPos> cir) {
        if (level.dimension() == Level.OVERWORLD) {
            cir.setReturnValue(RandomSpawn.findRandomSpawn(level));
        }
    }

}
