package bobbynooby.dev.mixin;

import bobbynooby.dev.features.CustomTabHeaders;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.function.Predicate;

@Mixin(PlayerList.class)
public abstract class PlayerManagerMixin {

    @Shadow
    @Final
    private MinecraftServer server;
    @Shadow
    @Final
    private List<ServerPlayer> players;

    @Shadow
    protected abstract boolean verifyChatTrusted(PlayerChatMessage message);

    // Rewrite the broadcast method for chat messages
    @Inject(method = "broadcastChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;Ljava/util/function/Predicate;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/network/chat/ChatType$Bound;)V", at = @At("HEAD"), cancellable = true)
    private void chatBroadcast(PlayerChatMessage message, Predicate<ServerPlayer> isFiltered, ServerPlayer sender, ChatType.Bound chatType, CallbackInfo ci) {

        boolean verifiedMessage = this.verifyChatTrusted(message);
        this.server.logChatMessage(message.decoratedContent(), chatType, verifiedMessage ? null : "Not Secure");
        OutgoingChatMessage outgoingMessage = OutgoingChatMessage.create(message);
        boolean filtered = isFiltered.test(sender);

        if (sender != null) {
            sender.sendChatMessage(outgoingMessage, filtered, chatType);
        }

        // Cancel the original method completely with ci.cancel()
        ci.cancel();
    }


    // Block join/leave/achievement/general server2client messages
    @Inject(method = "broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V", at = @At("HEAD"), cancellable = true)
    private void joinLeaveDeathBroadcast(Component message, boolean overlay, CallbackInfo ci) {

        // Send the message to the server only
        this.server.sendSystemMessage(message);
        // Cancel the original method completely with ci.cancel()
        ci.cancel();
    }


    /*

        Custom Tab Headers Section

     */

    // Generate custom tab header each time a player joins
    @Inject(method = "placeNewPlayer", at = @At("HEAD"))
    public void sendHeader(Connection connection, ServerPlayer player, CommonListenerCookie cookie, CallbackInfo ci) {
        CustomTabHeaders.onPlayerJoin(player);
    }

    // Called every tick. Update the custom tab header
    @Inject(method = "tick", at = @At("HEAD"))
    public void sendHeader(CallbackInfo ci) {
        for (ServerPlayer player : players) {
            CustomTabHeaders.HeaderFooter headerFooter = CustomTabHeaders.getPlayerTab(player);
            player.connection.send(new ClientboundTabListPacket(Component.literal(headerFooter.HEADER_STRING), Component.literal(headerFooter.FOOTER_STRING)));
        }
    }

}
