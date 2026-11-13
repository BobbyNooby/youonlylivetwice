package bobbynooby.dev.mixin;

import bobbynooby.dev.hardcore.Relentless;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.BreakDoorGoal;
import net.minecraft.world.entity.ai.goal.DoorInteractGoal;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// Extends the goal's own parent so the mob it acts for can be read; the constructor is never run
@Mixin(BreakDoorGoal.class)
public abstract class HardcoreDoorMixin extends DoorInteractGoal {
    private HardcoreDoorMixin(Mob mob) {
        super(mob);
    }

    // The one check behind starting, keeping on and finishing the break, so all three pass on any difficulty
    @ModifyReturnValue(method = "isValidDifficulty", at = @At("RETURN"))
    private boolean breakOnAnyDifficulty(boolean valid) {
        return valid || Relentless.breaksDoorsOnAnyDifficulty(this.mob);
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"))
    private boolean breakTheDoor(Level level, BlockPos pos, boolean movedByPiston, Operation<Boolean> original) {
        if (Relentless.handlesBrokenDoor(this.mob)) {
            return Relentless.breakDoor(level, pos, this.mob);
        }
        return original.call(level, pos, movedByPiston);
    }
}
