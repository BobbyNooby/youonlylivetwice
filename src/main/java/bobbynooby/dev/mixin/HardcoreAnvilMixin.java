package bobbynooby.dev.mixin;

import bobbynooby.dev.hardcore.Anvils;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.ItemCombinerMenu;
import net.minecraft.world.inventory.ItemCombinerMenuSlotDefinition;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BiConsumer;

// Extends the anvil's parent only to reach the menu's player
@Mixin(AnvilMenu.class)
public abstract class HardcoreAnvilMixin extends ItemCombinerMenu {
    @Shadow
    @Final
    private DataSlot cost;

    @Shadow
    private boolean onlyRenaming;

    private HardcoreAnvilMixin(MenuType<?> menuType, int containerId, Inventory inventory, ContainerLevelAccess access,
                               ItemCombinerMenuSlotDefinition slots) {
        super(menuType, containerId, inventory, access, slots);
    }

    // The one long in createResult is the tax: both inputs' prior-work counters, added to every price
    @ModifyVariable(method = "createResult", at = @At("STORE"), ordinal = 0)
    private long ignorePriorWork(long tax) {
        return Anvils.priorWork(this.player, tax);
    }

    // The third 40 is the "Too Expensive!" refusal. The first prices a book on a stack, which the flat price blocks itself.
    @ModifyExpressionValue(method = "createResult", at = @At(value = "CONSTANT", args = "intValue=40", ordinal = 2))
    private int neverTooExpensive(int limit) {
        return Anvils.tooExpensiveAt(this.player, limit);
    }

    // Before the sync that sends vanilla's result, so the flat price goes out with it. Core's refusal of Curse of
    // Vanishing on grave items runs at the tail, after this, so it still has the last word.
    @Inject(method = "createResult", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/AnvilMenu;broadcastChanges()V"))
    private void flatPrice(CallbackInfo ci) {
        if (Anvils.finish((AnvilMenu) (Object) this, this.player, this.cost)) {
            this.onlyRenaming = true;
        }
    }

    // The block's wear after a use, with its 12% chance
    @WrapOperation(method = "onTake", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/inventory/ContainerLevelAccess;execute(Ljava/util/function/BiConsumer;)V"))
    private void wearAtOurChance(ContainerLevelAccess access, BiConsumer<Level, BlockPos> wear, Operation<Void> original,
                                 @Local(argsOnly = true) Player player) {
        original.call(access, Anvils.wear(player, wear));
    }
}
