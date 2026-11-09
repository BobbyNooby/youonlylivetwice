package bobbynooby.dev.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.PlayerSpawnFinder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.CollisionGetter;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

// Vanilla's own spawn checks, so a random spawn column passes the same rules as a vanilla one
@Mixin(PlayerSpawnFinder.class)
public interface PlayerSpawnFinderInvoker {
    @Invoker("getLevelRespawnPos")
    static @Nullable BlockPos yoltLevelRespawnPos(ServerLevel level, int x, int z) {
        throw new AssertionError();
    }

    @Invoker("noCollisionNoLiquid")
    static boolean yoltNoCollisionNoLiquid(CollisionGetter level, BlockPos pos) {
        throw new AssertionError();
    }
}
