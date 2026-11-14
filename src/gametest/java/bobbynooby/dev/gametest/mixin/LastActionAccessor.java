package bobbynooby.dev.gametest.mixin;

import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerPlayer.class)
public interface LastActionAccessor {
    // When the player last moved, looked or clicked, which only their client's packets set
    @Accessor("lastActionTime")
    void yoltSetLastActionTime(long millis);
}
