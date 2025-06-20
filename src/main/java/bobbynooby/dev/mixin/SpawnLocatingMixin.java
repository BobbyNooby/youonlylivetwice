package bobbynooby.dev.mixin;


import bobbynooby.dev.features.RandomSpawn;
import net.minecraft.server.network.SpawnLocating;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SpawnLocating.class)
public abstract class SpawnLocatingMixin {


    @Inject(method = "findOverworldSpawn", at = @At("HEAD"), cancellable = true)
    private static void findOverworldSpawn(ServerWorld world, int x, int z, CallbackInfoReturnable<BlockPos> cir) {
        cir.setReturnValue(RandomSpawn.findRandomSpawn(world));
    }

}

