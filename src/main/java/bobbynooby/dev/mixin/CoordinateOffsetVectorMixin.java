package bobbynooby.dev.mixin;

import bobbynooby.dev.coords.TypedCoordinates;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.commands.arguments.coordinates.Vec2Argument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin({Vec3Argument.class, Vec2Argument.class})
public abstract class CoordinateOffsetVectorMixin {
    // Mixin refuses a mapped shadow in a mixin with two targets, and the names aren't obfuscated anyway
    @Shadow(remap = false)
    @Final
    private boolean centerCorrect;

    // Both centre whole numbers only for a spot, so a vector such as a particle spread can be told from one
    @ModifyReturnValue(method = "parse(Lcom/mojang/brigadier/StringReader;)Lnet/minecraft/commands/arguments/coordinates/Coordinates;",
            at = @At("RETURN"))
    private Coordinates spotOrVector(Coordinates parsed) {
        return TypedCoordinates.spotOrVector(parsed, centerCorrect);
    }
}
