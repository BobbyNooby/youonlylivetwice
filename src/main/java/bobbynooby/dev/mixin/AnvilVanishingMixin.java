package bobbynooby.dev.mixin;

import bobbynooby.dev.graves.GraveItems;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AnvilMenu.class)
public abstract class AnvilVanishingMixin {
    @Shadow
    @Final
    private DataSlot cost;

    // After vanilla has made its result. The client works the result out on its own as well, so the empty slot has to
    // reach it: setting the slot sends it, with the cost, at once.
    @Inject(method = "createResult", at = @At("TAIL"))
    private void refuseVanishingOnHeads(CallbackInfo ci) {
        AnvilMenu menu = (AnvilMenu) (Object) this;
        Slot result = menu.getSlot(AnvilMenu.RESULT_SLOT);
        if (GraveItems.anvilAddsVanishing(menu.getSlot(AnvilMenu.INPUT_SLOT).getItem(), result.getItem())) {
            cost.set(0);
            result.set(ItemStack.EMPTY);
        }
    }
}
