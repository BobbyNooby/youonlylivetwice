package bobbynooby.dev.mixin;

import bobbynooby.dev.coords.CoordinateOffsets;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.coordinates.WorldCoordinates;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(WorldCoordinates.class)
public abstract class CoordinateOffsetCommandMixin {
    // Block, column and position arguments all read their coordinates here; rotations read theirs elsewhere
    @ModifyReturnValue(method = "getPosition", at = @At("RETURN"))
    private Vec3 typedInTheirFrame(Vec3 position, @Local(argsOnly = true) CommandSourceStack source) {
        return CoordinateOffsets.typedPosition(source, (WorldCoordinates) (Object) this, position);
    }
}
