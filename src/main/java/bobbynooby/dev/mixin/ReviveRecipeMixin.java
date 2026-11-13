package bobbynooby.dev.mixin;

import bobbynooby.dev.graves.GraveItems;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ShapelessRecipe.class)
public abstract class ReviveRecipeMixin {
    @Shadow
    @Final
    private ItemStackTemplate result;

    // A shapeless result is made from scratch, so the revive head would forget whose head it was
    @ModifyReturnValue(method = "assemble(Lnet/minecraft/world/item/crafting/CraftingInput;)Lnet/minecraft/world/item/ItemStack;",
            at = @At("RETURN"))
    private ItemStack keepTheOwner(ItemStack made, @Local(argsOnly = true) CraftingInput input) {
        return GraveItems.reviveHeadFrom(made, result, input);
    }
}
