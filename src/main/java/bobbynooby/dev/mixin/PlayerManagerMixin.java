package bobbynooby.dev.mixin;

import bobbynooby.dev.features.CustomTabHeaders;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.message.MessageType;
import net.minecraft.network.message.SentMessage;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.PlayerListHeaderS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.function.Predicate;

@Mixin(PlayerManager.class)
public abstract class PlayerManagerMixin {

    @Shadow
    @Final
    private MinecraftServer server;
    @Shadow
    private int latencyUpdateTimer;
    @Shadow
    @Final
    private List<ServerPlayerEntity> players;

    @Shadow
    protected abstract boolean verify(SignedMessage message);

    @Shadow
    public abstract void sendToAll(Packet<?> packet);

    // Rewrite the broadcast method for chat messages
    @Inject(method = "broadcast(Lnet/minecraft/network/message/SignedMessage;Ljava/util/function/Predicate;Lnet/minecraft/server/network/ServerPlayerEntity;Lnet/minecraft/network/message/MessageType$Parameters;)V", at = @At("HEAD"), cancellable = true)
    private void chatBroadcast(SignedMessage message, Predicate<ServerPlayerEntity> shouldSendFiltered, ServerPlayerEntity sender, MessageType.Parameters params, CallbackInfo ci) {

        boolean verifiedMessage = this.verify(message);
        this.server.logChatMessage(message.getContent(), params, verifiedMessage ? null : "Not Secure");
        SentMessage sentMessage = SentMessage.of(message);
        boolean bl3 = shouldSendFiltered.test(sender);

        if (sender != null) {
            sender.sendChatMessage(sentMessage, bl3, params);
        }

        // Cancel the original method completely with ci.cancel()
        ci.cancel();
    }


    // Block join/leave/achievement/general server2client messages
    @Inject(method = "broadcast(Lnet/minecraft/text/Text;Z)V", at = @At("HEAD"), cancellable = true)
    private void joinLeaveDeathBroadcast(Text message, boolean overlay, CallbackInfo ci) {

        // Send the message to the server only
        this.server.sendMessage(message);
        // Cancel the original method completely with ci.cancel()
        ci.cancel();
    }


    /*

        Custom Tab Headers Section

     */

    // Generate custom tab header each time a player joins
    @Inject(method = "onPlayerConnect", at = @At("HEAD"))
    public void sendHeader(ClientConnection connection, ServerPlayerEntity player, ConnectedClientData clientData, CallbackInfo ci) {
        CustomTabHeaders.onPlayerJoin(player);
    }

    // Basically called every tick. Update the custom tab header
    @Inject(method = "updatePlayerLatency", at = @At("HEAD"))
    public void sendHeader(CallbackInfo ci) {
        for (ServerPlayerEntity player : players) {
            CustomTabHeaders.HeaderFooter headerFooter = CustomTabHeaders.getPlayerTab(player);
            player.networkHandler.sendPacket(new PlayerListHeaderS2CPacket(Text.literal(headerFooter.HEADER_STRING), Text.literal(headerFooter.FOOTER_STRING)));
        }
    }

}
