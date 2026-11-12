package bobbynooby.dev.mixin;

import bobbynooby.dev.coords.CoordinateOffsets;
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

    // Every typed position and vector argument is parsed here, so a vector can be told from a spot
    @ModifyReturnValue(method = "parse(Lcom/mojang/brigadier/StringReader;)Lnet/minecraft/commands/arguments/coordinates/Coordinates;",
            at = @At("RETURN"))
    private Coordinates vectorAsTyped(Coordinates parsed) {
        return CoordinateOffsets.typedArgument(parsed, centerCorrect);
    }
}
