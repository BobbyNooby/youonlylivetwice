package bobbynooby.dev.mixin;

import bobbynooby.dev.hardcore.BloodMoon;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Mob.class)
public abstract class HardcoreMobGearMixin {
    // Vanilla's roll is a base chance times this multiplier, which is 0 at low local difficulty, so the floor goes on
    // the multiplier: the chance becomes the higher of vanilla's and the blood moon's
    @ModifyExpressionValue(method = "populateDefaultEquipmentSlots",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/DifficultyInstance;getSpecialMultiplier()F"))
    private float bloodMoonArmour(float multiplier) {
        return BloodMoon.armourMultiplier((Mob) (Object) this, multiplier);
    }

    @ModifyExpressionValue(method = "enchantSpawnedEquipment",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/DifficultyInstance;getSpecialMultiplier()F"))
    private float bloodMoonEnchant(float multiplier, @Local(argsOnly = true) float chance) {
        return BloodMoon.enchantMultiplier((Mob) (Object) this, chance, multiplier);
    }
}
