package bobbynooby.dev.coords.mixin;

import bobbynooby.dev.coords.TypedCoordinates;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.ColumnPosArgument;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.commands.arguments.coordinates.Vec2Argument;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin({BlockPosArgument.class, ColumnPosArgument.class, Vec2Argument.class})
public abstract class CoordinateOffsetCommandMixin {
    // Block, column and 2D arguments always name a spot. Minecraft has no 2D vector, so a 2D argument that keeps a typed
    // 100 at 100 instead of centring it, as a host's command may (YOLT's /yolt center does), still names a spot.
    @ModifyReturnValue(method = "parse(Lcom/mojang/brigadier/StringReader;)Lnet/minecraft/commands/arguments/coordinates/Coordinates;",
            at = @At("RETURN"))
    private Coordinates typedSpot(Coordinates parsed) {
        return TypedCoordinates.spot(parsed);
    }
}
