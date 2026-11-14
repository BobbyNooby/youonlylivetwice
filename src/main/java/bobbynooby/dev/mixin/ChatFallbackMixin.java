package bobbynooby.dev.mixin;

import bobbynooby.dev.chat.ChatDelivery;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class ChatFallbackMixin {
    // Every signed send to one player ends here: chat, /say, /me and /teammsg
    @Inject(method = "sendChatMessage(Lnet/minecraft/network/chat/OutgoingChatMessage;ZLnet/minecraft/network/chat/ChatType$Bound;)V",
            at = @At("HEAD"), cancellable = true)
    private void textForUnknownSender(OutgoingChatMessage message, boolean filtered, ChatType.Bound chatType, CallbackInfo ci) {
        if (ChatDelivery.sendAsTextIfSenderUnknown((ServerPlayer) (Object) this, message, filtered, chatType)) {
            ci.cancel();
        }
    }
}
