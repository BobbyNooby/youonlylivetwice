package bobbynooby.dev.mixin;

import bobbynooby.dev.hardcore.Nutrients;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(FoodData.class)
public abstract class HardcoreFoodDataMixin {
    @Shadow
    private int foodLevel;

    // The one int max in tick is the food bar going down; saturation's own drop is a float max and is left alone
    @ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(II)I"))
    private int seeFoodPointBurned(int food, @Local(argsOnly = true) ServerPlayer player) {
        if (food < this.foodLevel) {
            Nutrients.onFoodPointBurned(player);
        }
        return food;
    }

    // The saturated regen's 10 ticks per heal
    @ModifyExpressionValue(method = "tick", at = @At(value = "CONSTANT", args = "intValue=10"))
    private int stretchSaturatedRegen(int ticks, @Local(argsOnly = true) ServerPlayer player) {
        return Nutrients.regenTicks(player, ticks);
    }

    // The fed regen's 80 ticks per heal; the second 80 is starvation's, which stays as it is
    @ModifyExpressionValue(method = "tick", at = @At(value = "CONSTANT", args = "intValue=80", ordinal = 0))
    private int stretchFedRegen(int ticks, @Local(argsOnly = true) ServerPlayer player) {
        return Nutrients.regenTicks(player, ticks);
    }
}
