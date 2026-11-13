package bobbynooby.dev.gametest.mixin;

import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(FoodData.class)
public interface FoodDataAccessor {
    // Hunger spent but not yet taken off food, which vanilla keeps to itself
    @Accessor("exhaustionLevel")
    float yoltExhaustion();

    @Accessor("exhaustionLevel")
    void yoltSetExhaustion(float exhaustion);
}
