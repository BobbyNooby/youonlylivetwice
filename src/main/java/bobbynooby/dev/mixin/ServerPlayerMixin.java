package bobbynooby.dev.mixin;

import bobbynooby.dev.chat.ChatDelivery;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.graves.Graves;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public class ServerPlayerMixin {


    @Inject(method = "getRespawnConfig", at = @At("HEAD"), cancellable = true)
    private void getRespawnPos(CallbackInfoReturnable<ServerPlayer.RespawnConfig> cir) {
        if (Settings.active() && Settings.get().sleep.noBedRespawn) {
            cir.setReturnValue(null);
        }
    }

    // Every signed send to one player ends here: chat, /say, /me, /msg, /tell and /teammsg
    @Inject(method = "sendChatMessage(Lnet/minecraft/network/chat/OutgoingChatMessage;ZLnet/minecraft/network/chat/ChatType$Bound;)V",
            at = @At("HEAD"), cancellable = true)
    private void textForUnknownSender(OutgoingChatMessage message, boolean filtered, ChatType.Bound chatType, CallbackInfo ci) {
        if (Settings.active() && Settings.get().hide.distantPlayers
                && ChatDelivery.sendAsTextIfSenderUnknown((ServerPlayer) (Object) this, message, filtered, chatType)) {
            ci.cancel();
        }
    }

    // Trade and beacon menus throw their items on the ground when a dead player closes them
    @Inject(method = "drop(Lnet/minecraft/world/item/ItemStack;ZLnet/minecraft/util/Prediction;)Lnet/minecraft/world/entity/item/ItemEntity;",
            at = @At("HEAD"), cancellable = true)
    private void takeDropForGrave(ItemStack stack, boolean thrownFromHand, Prediction prediction, CallbackInfoReturnable<ItemEntity> cir) {
        if (Graves.takeReturnedItem((ServerPlayer) (Object) this, stack)) {
            cir.setReturnValue(null);
        }
    }
}
