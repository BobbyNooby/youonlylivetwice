package bobbynooby.dev.mixin;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.visibility.PlayerVisibility;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;

@Mixin(PlayerList.class)
public abstract class PlayerListVisibilityMixin {

    // The first of the two join packets: everyone already online, sent to the joiner
    @WrapOperation(method = "placeNewPlayer", at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/network/protocol/game/ClientboundPlayerInfoUpdatePacket;createPlayerInitializing(Ljava/util/Collection;)Lnet/minecraft/network/protocol/game/ClientboundPlayerInfoUpdatePacket;"))
    private ClientboundPlayerInfoUpdatePacket entriesForJoiner(Collection<ServerPlayer> online, Operation<ClientboundPlayerInfoUpdatePacket> original,
                                                               @Local(argsOnly = true) ServerPlayer joiner) {
        if (!Settings.active()) {
            return original.call(online);
        }
        return PlayerVisibility.joinPacket(joiner, online);
    }

    // Joins, leaves, latency, game mode and chat sessions all go out through here
    @Inject(method = "broadcastAll(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void broadcastPerViewer(Packet<?> packet, CallbackInfo ci) {
        if (Settings.active() && PlayerVisibility.broadcast(((PlayerList) (Object) this).getPlayers(), packet)) {
            ci.cancel();
        }
    }
}
