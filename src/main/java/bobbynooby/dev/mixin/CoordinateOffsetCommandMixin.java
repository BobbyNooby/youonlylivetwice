package bobbynooby.dev.mixin;

import bobbynooby.dev.coords.TypedCoordinates;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.ColumnPosArgument;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin({BlockPosArgument.class, ColumnPosArgument.class})
public abstract class CoordinateOffsetCommandMixin {
    // Block and column arguments always name a spot
    @ModifyReturnValue(method = "parse(Lcom/mojang/brigadier/StringReader;)Lnet/minecraft/commands/arguments/coordinates/Coordinates;",
            at = @At("RETURN"))
    private Coordinates typedSpot(Coordinates parsed) {
        return TypedCoordinates.spot(parsed);
    }
}
