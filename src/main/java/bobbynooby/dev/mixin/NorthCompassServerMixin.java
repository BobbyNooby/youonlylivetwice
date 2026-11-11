package bobbynooby.dev.mixin;

import bobbynooby.dev.world.NorthCompass;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(MinecraftServer.class)
public abstract class NorthCompassServerMixin {
    // A moved world spawn, from /setworldspawn or any other caller, goes to everyone as one packet. While compasses point
    // north each player gets their own instead.
    @WrapOperation(method = "setRespawnData", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/players/PlayerList;broadcastAll(Lnet/minecraft/network/protocol/Packet;)V"))
    private void eachTheirOwn(PlayerList playerList, Packet<?> packet, Operation<Void> original) {
        if (!NorthCompass.sendEach(playerList, packet)) {
            original.call(playerList, packet);
        }
    }
}
