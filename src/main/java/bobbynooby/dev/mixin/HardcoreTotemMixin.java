package bobbynooby.dev.mixin;

import bobbynooby.dev.hardcore.Curse;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LivingEntity.class)
public abstract class HardcoreTotemMixin {
    // True only when a death protection item saved the entity, by which point vanilla has set its health to 1. The
    // result is passed on unchanged, so the curse never decides whether the totem works.
    @ModifyReturnValue(method = "checkTotemDeathProtection", at = @At("RETURN"))
    private boolean afterTotem(boolean saved) {
        if (saved) {
            Curse.onTotem((LivingEntity) (Object) this);
        }
        return saved;
    }
}
