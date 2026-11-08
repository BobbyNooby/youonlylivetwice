package bobbynooby.dev.mixin;


import bobbynooby.dev.features.RandomSpawn;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.PlayerSpawnFinder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

@Mixin(PlayerSpawnFinder.class)
public abstract class SpawnLocatingMixin {


    // Only the overworld spawn is randomised, other dimensions keep vanilla spawning
    @Inject(method = "findSpawn", at = @At("HEAD"), cancellable = true)
    private static void findOverworldSpawn(ServerLevel level, BlockPos spawnSuggestion, CallbackInfoReturnable<CompletableFuture<Vec3>> cir) {
        if (level.dimension() == Level.OVERWORLD) {
            cir.setReturnValue(CompletableFuture.completedFuture(Vec3.atBottomCenterOf(RandomSpawn.findRandomSpawn(level))));
        }
    }

}
