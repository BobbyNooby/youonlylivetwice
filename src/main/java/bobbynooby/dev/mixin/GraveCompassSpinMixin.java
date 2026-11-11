package bobbynooby.dev.mixin;

import bobbynooby.dev.graves.GraveCompass;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.CompassItem;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CompassItem.class)
public abstract class GraveCompassSpinMixin {
    // Before vanilla's own tracker tick, which leaves an untracked target as it is
    @Inject(method = "inventoryTick", at = @At("HEAD"))
    private void followGrave(ItemStack itemStack, ServerLevel level, Entity owner, @Nullable EquipmentSlot slot,
                             CallbackInfo ci) {
        GraveCompass.tick(itemStack, level);
    }
}
