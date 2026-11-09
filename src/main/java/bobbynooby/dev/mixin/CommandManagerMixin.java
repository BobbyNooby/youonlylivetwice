package bobbynooby.dev.mixin;

import bobbynooby.dev.chat.Whispers;
import bobbynooby.dev.config.Settings;
import com.mojang.brigadier.ParseResults;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Commands.class)
public class CommandManagerMixin {

    @Shadow
    @Final
    private static ClientboundCommandsPacket.NodeInspector<CommandSourceStack> COMMAND_NODE_INSPECTOR;

    // Restrict non-op players to help, plus /w when whispers are on
    @Inject(method = "performCommand", at = @At("HEAD"), cancellable = true)
    private void executeCallback(ParseResults<CommandSourceStack> parseResults, String command, CallbackInfo ci) {
        if (!Settings.active() || !Settings.get().restrictCommands) {
            return;
        }

        // Basic command checking
        if (!command.isEmpty()) {
            String commandRoot = command.split(" ")[0];
            ServerPlayer player = parseResults.getContext().getSource().getPlayer();
            PlayerList playerList = parseResults.getContext().getSource().getServer().getPlayerList();

            // Check if player is not op
            if (player != null && playerList != null && !playerList.isOp(player.nameAndId())) {

                // Check if command is help
                if (commandRoot.equals("help")) {

                    // Send help message to non-op players
                    player.sendSystemMessage(Component.literal("But nobody answered....").withStyle(ChatFormatting.RED));
                } else if (commandRoot.equals("w") && Settings.get().chat.allowWhispers) {
                    Whispers.runCommand(player, command);
                }

                // Vanilla never runs a non-op's command
                ci.cancel();
            }

            // Continue the rest of the original method if player is op
        }
    }

    // Send restricted command tree to non-op players
    @Inject(method = "sendCommands", at = @At("HEAD"), cancellable = true)
    private void sendPatchedCommandTree(ServerPlayer player, CallbackInfo ci) {
        if (!Settings.active() || !Settings.get().restrictCommands) {
            return;
        }
        // Check if player is op
        MinecraftServer server = player.level().getServer();
        PlayerList playerList = server.getPlayerList();
        if (playerList != null) {
            boolean isOp = playerList.isOp(player.nameAndId());
            if (!isOp) {
                player.connection.send(new ClientboundCommandsPacket(
                        Whispers.nonOpTree(Settings.get().chat.allowWhispers), COMMAND_NODE_INSPECTOR));

                // Cancel the rest of the original method.
                ci.cancel();

            }
        }

        // If player is op just continue as usual
    }
}
