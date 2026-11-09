package bobbynooby.dev.mixin;

import bobbynooby.dev.chat.Whispers;
import bobbynooby.dev.config.Settings;
import com.mojang.brigadier.ParseResults;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
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
        ServerPlayer player = parseResults.getContext().getSource().getPlayer();
        if (command.isEmpty() || player == null || !Whispers.restricted(player)) {
            return;
        }
        String commandRoot = command.split(" ")[0];

        // /w is our own command (Whispers), so it runs normally and reads the signed message
        if (commandRoot.equals("w") && Settings.get().chat.allowWhispers) {
            return;
        }

        if (commandRoot.equals("help")) {
            player.sendSystemMessage(Component.literal("But nobody answered....").withStyle(ChatFormatting.RED));
        }

        ci.cancel();
    }

    @Inject(method = "sendCommands", at = @At("HEAD"), cancellable = true)
    private void sendPatchedCommandTree(ServerPlayer player, CallbackInfo ci) {
        if (Whispers.restricted(player)) {
            player.connection.send(new ClientboundCommandsPacket(
                    Whispers.nonOpTree(Settings.get().chat.allowWhispers), COMMAND_NODE_INSPECTOR));
            ci.cancel();
        }
    }
}
