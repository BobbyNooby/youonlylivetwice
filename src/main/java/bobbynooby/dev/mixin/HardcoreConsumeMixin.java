package bobbynooby.dev.mixin;

import bobbynooby.dev.hardcore.Nutrients;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Consumable.class)
public abstract class HardcoreConsumeMixin {
    // At the start, while the stack still holds the food: it shrinks at the end
    @Inject(method = "onConsume", at = @At("HEAD"))
    private void beforeConsume(Level level, LivingEntity user, ItemStack stack, CallbackInfoReturnable<ItemStack> cir) {
        if (user instanceof ServerPlayer player) {
            Nutrients.onEat(player, stack);
        }
    }
}
