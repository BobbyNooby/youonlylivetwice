package bobbynooby.dev.mixin;

import net.minecraft.network.message.MessageType;
import net.minecraft.network.message.SentMessage;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Predicate;

@Mixin(PlayerManager.class)
public abstract class PlayerManagerMixin {
    @Shadow
    @Final
    private MinecraftServer server;

    @Shadow
    protected abstract boolean verify(SignedMessage message);

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
}
