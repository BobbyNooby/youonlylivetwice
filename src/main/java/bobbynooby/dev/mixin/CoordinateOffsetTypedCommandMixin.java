package bobbynooby.dev.mixin;

import bobbynooby.dev.coords.TypedCoordinates;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class CoordinateOffsetTypedCommandMixin {
    @Shadow
    public ServerPlayer player;

    // Both chat command packets are parsed here and nothing else is, so whatever is parsed now the player typed
    @WrapMethod(method = "parseCommand")
    private ParseResults<CommandSourceStack> typedInTheirFrame(String command, Operation<ParseResults<CommandSourceStack>> original) {
        return TypedCoordinates.parse(this.player, () -> original.call(command));
    }
}
