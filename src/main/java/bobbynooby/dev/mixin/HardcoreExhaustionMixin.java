package bobbynooby.dev.mixin;

import bobbynooby.dev.hardcore.HungerTax;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Player.class)
public abstract class HardcoreExhaustionMixin {
    // Every action's hunger comes through here; healing adds to the food data itself and never does
    @ModifyVariable(method = "causeFoodExhaustion", at = @At("HEAD"), argsOnly = true)
    private float taxActionHunger(float amount) {
        return HungerTax.scale((Player) (Object) this, amount);
    }
}
