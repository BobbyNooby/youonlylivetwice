package bobbynooby.dev.mixin;

import bobbynooby.dev.coords.TypedCoordinates;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Vec3Argument.class)
public abstract class CoordinateOffsetVectorMixin {
    @Shadow
    @Final
    private boolean centerCorrect;

    // A 3D argument centres whole numbers only for a spot, so a vector such as a particle spread can be told from one
    @ModifyReturnValue(method = "parse(Lcom/mojang/brigadier/StringReader;)Lnet/minecraft/commands/arguments/coordinates/Coordinates;",
            at = @At("RETURN"))
    private Coordinates spotOrVector(Coordinates parsed) {
        return TypedCoordinates.spotOrVector(parsed, centerCorrect);
    }
}
