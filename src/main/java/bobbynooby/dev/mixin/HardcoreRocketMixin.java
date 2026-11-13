package bobbynooby.dev.mixin;

import bobbynooby.dev.hardcore.Elytra;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.FireworkRocketItem;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FireworkRocketItem.class)
public abstract class HardcoreRocketMixin {
    // Before the rocket is spawned and the stack used up, both of which only the server does, so the client's copy of
    // the stack never changed and needs no resync
    @Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void refusePastCutoff(Level level, Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        if (player instanceof ServerPlayer serverPlayer && Elytra.refusesRocket(serverPlayer)) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }
}
