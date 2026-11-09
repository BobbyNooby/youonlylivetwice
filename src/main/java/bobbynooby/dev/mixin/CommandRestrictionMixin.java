package bobbynooby.dev.mixin;

import bobbynooby.dev.chat.CommandRestriction;
import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Commands.class)
public abstract class CommandRestrictionMixin {

    @Shadow
    @Final
    private static ClientboundCommandsPacket.NodeInspector<CommandSourceStack> COMMAND_NODE_INSPECTOR;

    // Restrict non-op players to help, plus /w when whispers are on
    @Inject(method = "performCommand", at = @At("HEAD"), cancellable = true)
    private void executeCallback(ParseResults<CommandSourceStack> parseResults, String command, CallbackInfo ci) {
        if (CommandRestriction.blocks(parseResults.getContext().getSource().getPlayer(), command)) {
            ci.cancel();
        }
    }

    @Inject(method = "sendCommands", at = @At("HEAD"), cancellable = true)
    private void sendPatchedCommandTree(ServerPlayer player, CallbackInfo ci) {
        if (CommandRestriction.sendNonOpTree(player, COMMAND_NODE_INSPECTOR)) {
            ci.cancel();
        }
    }
}
