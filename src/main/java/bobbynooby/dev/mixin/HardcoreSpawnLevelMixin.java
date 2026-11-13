package bobbynooby.dev.mixin;

import bobbynooby.dev.hardcore.BloodMoon;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerChunkCache.class)
public abstract class HardcoreSpawnLevelMixin {
    @Shadow
    @Final
    private ServerLevel level;

    // Natural spawning for one level happens inside this, on the server thread, and the cap has no level of its own
    @Inject(method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;)V", at = @At("HEAD"))
    private void spawningHere(ProfilerFiller profiler, CallbackInfo ci) {
        BloodMoon.spawning(this.level);
    }

    @Inject(method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;)V", at = @At("RETURN"))
    private void doneSpawning(ProfilerFiller profiler, CallbackInfo ci) {
        BloodMoon.spawning(null);
    }
}
