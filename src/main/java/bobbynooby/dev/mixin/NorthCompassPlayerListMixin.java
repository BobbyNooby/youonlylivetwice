package bobbynooby.dev.mixin;

import bobbynooby.dev.visibility.NorthCompass;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(PlayerList.class)
public abstract class NorthCompassPlayerListMixin {
    // The spawn packet a join, a respawn or a dimension change sends is built in these two methods, so it is swapped where
    // it goes out and the real spawn never reaches the client. Every other packet they send passes through.
    @ModifyArg(method = "sendLevelInfo", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V"))
    private Packet<?> northInLevelInfo(Packet<?> packet, @Local(argsOnly = true) ServerPlayer player,
                                       @Local(argsOnly = true) ServerLevel level) {
        return NorthCompass.replace(player, level, packet);
    }

    // The first player here is the one who died; the second is the new one, already in the level they respawn in
    @ModifyArg(method = "respawn", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V"))
    private Packet<?> northAtRespawn(Packet<?> packet, @Local(ordinal = 1) ServerPlayer player) {
        return NorthCompass.replace(player, player.level(), packet);
    }
}
