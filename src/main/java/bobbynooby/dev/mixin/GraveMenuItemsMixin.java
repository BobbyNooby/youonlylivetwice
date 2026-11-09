package bobbynooby.dev.mixin;

import bobbynooby.dev.graves.GraveDeath;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerMenu.class)
public abstract class GraveMenuItemsMixin {
    // Every menu hands its cursor and input items back to the player through here when it closes
    @Inject(method = "dropOrPlaceInInventory", at = @At("HEAD"), cancellable = true)
    private static void takeForGrave(Player player, ItemStack stack, CallbackInfo ci) {
        if (GraveDeath.takeReturnedItem(player, stack)) {
            ci.cancel();
        }
    }
}
