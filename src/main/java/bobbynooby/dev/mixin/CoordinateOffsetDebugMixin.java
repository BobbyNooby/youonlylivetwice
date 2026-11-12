package bobbynooby.dev.mixin;

import bobbynooby.dev.coords.CoordinateOffsets;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.debug.DebugSubscription;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Set;

@Mixin(ServerPlayer.class)
public abstract class CoordinateOffsetDebugMixin {
    // Every debug packet a player gets is for a subscription read here, so none go to a player with an offset
    @ModifyReturnValue(method = "debugSubscriptions", at = @At("RETURN"))
    private Set<DebugSubscription<?>> noDebugUnderAnOffset(Set<DebugSubscription<?>> vanilla) {
        return CoordinateOffsets.debugSubscriptions((ServerPlayer) (Object) this, vanilla);
    }
}
