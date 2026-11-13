package bobbynooby.dev.mixin;

import bobbynooby.dev.hardcore.BedRest;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.attribute.BedRule;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Player.class)
public abstract class HardcoreBedWakeMixin {
    // The server wakes a sleeper as soon as the bed rule says no, at dawn for one; vanilla reads it only on the server
    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/attribute/BedRule;canSleep(Lnet/minecraft/world/level/Level;)Z"))
    private boolean stayInBedInDaytime(BedRule rule, Level level, Operation<Boolean> original) {
        return original.call(rule, level) || BedRest.allowsDaytime(rule);
    }
}
