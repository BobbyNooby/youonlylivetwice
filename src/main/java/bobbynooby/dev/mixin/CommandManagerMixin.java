package bobbynooby.dev.mixin;

import bobbynooby.dev.config.Settings;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
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

    // Restrict non-op players to only using help
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
                }

                // Just ignore the command if it is not help
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

                // Create restricted command tree
                RootCommandNode<CommandSourceStack> root = new RootCommandNode<>();

                // Create restricted help command ( funtionality is in previous inject )
                LiteralCommandNode<CommandSourceStack> help = LiteralArgumentBuilder.<CommandSourceStack>literal("help").executes((context) -> {
                    return 1;
                }).build();

                // Add help command to restricted command tree
                root.addChild(help);

                // Send restricted command tree
                player.connection.send(new ClientboundCommandsPacket(root, COMMAND_NODE_INSPECTOR));

                // Cancel the rest of the original method.
                ci.cancel();

            }
        }

        // If player is op just continue as usual
    }
}
