package bobbynooby.dev.mixin;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import net.minecraft.command.CommandSource;
import net.minecraft.network.packet.s2c.play.CommandTreeS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CommandManager.class)
public class CommandManagerMixin {

    // Restrict non-op players to only using help
    @Inject(method = "execute", at = @At("HEAD"), cancellable = true)
    private void executeCallback(ParseResults<ServerCommandSource> parseResults, String command, CallbackInfo ci) {

        // Basic command checking
        if (!command.isEmpty()) {
            String commandRoot = command.split(" ")[0];
            ServerPlayerEntity player = parseResults.getContext().getSource().getPlayer();
            PlayerManager playerManager = parseResults.getContext().getSource().getServer().getPlayerManager();

            // Check if player is not op
            if (player != null && playerManager != null && !playerManager.isOperator(player.getGameProfile())) {

                // Check if command is help
                if (commandRoot.equals("help")) {

                    // Send help message to non-op players
                    player.sendMessage(Text.literal("But nobody answered....").formatted(Formatting.DARK_RED));
                }

                // Just ignore the command if it is not help
                ci.cancel();
            }

            // Continue the rest of the original method if player is op
        }
    }

    // Send restricted command tree to non-op players
    @Inject(method = "sendCommandTree", at = @At("HEAD"), cancellable = true)
    private void sendPatchedCommandTree(ServerPlayerEntity player, CallbackInfo ci) {
        // Check if player is op
        MinecraftServer server = player.getServer();
        if (server != null) {
            PlayerManager playerManager = player.server.getPlayerManager();
            if (playerManager != null) {
                boolean isOp = player.getServer().getPlayerManager().isOperator(player.getGameProfile());
                if (!isOp) {

                    // Create restricted command tree
                    RootCommandNode<CommandSource> root = new RootCommandNode<>();

                    // Create restricted help command ( funtionality is in previous inject )
                    LiteralCommandNode<CommandSource> help = LiteralArgumentBuilder.<CommandSource>literal("help").executes((context) -> {
                        return 1;
                    }).build();

                    // Add help command to restricted command tree
                    root.addChild(help);

                    // Send restricted command tree
                    player.networkHandler.sendPacket(new CommandTreeS2CPacket(root));

                    // Cancel the rest of the original method.
                    ci.cancel();

                }
            }
        }

        // If player is op just continue as usual
    }
}
