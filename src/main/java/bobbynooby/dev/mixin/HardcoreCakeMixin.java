package bobbynooby.dev.mixin;

import bobbynooby.dev.hardcore.Nutrients;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CakeBlock.class)
public abstract class HardcoreCakeMixin {
    // Cake never goes through an item's eating, so its slice is counted where the food bar takes it
    @Inject(method = "eat", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/food/FoodData;eat(IF)V", shift = At.Shift.AFTER))
    private static void afterSlice(LevelAccessor level, BlockPos pos, BlockState state, Player player,
                                   CallbackInfoReturnable<InteractionResult> cir) {
        if (player instanceof ServerPlayer serverPlayer) {
            Nutrients.onEatCake(serverPlayer);
        }
    }
}
