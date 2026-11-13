package bobbynooby.dev.mixin;

import bobbynooby.dev.hardcore.BedRest;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.attribute.BedRule;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ServerPlayer.class)
public abstract class HardcoreBedRuleMixin {
    // Only the time-of-day read of the bed rule; the monster check after it and the spawn point stay vanilla
    @WrapOperation(method = "startSleepInBed", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/attribute/BedRule;canSleep(Lnet/minecraft/world/level/Level;)Z"))
    private boolean lieDownInDaytime(BedRule rule, Level level, Operation<Boolean> original) {
        return original.call(rule, level) || BedRest.allowsDaytime(rule);
    }
}
