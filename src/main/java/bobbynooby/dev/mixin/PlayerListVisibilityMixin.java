package bobbynooby.dev.mixin;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.features.NameTagHider;
import bobbynooby.dev.visibility.PlayerVisibility;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.scores.PlayerTeam;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;
import java.util.List;

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

    // The joiner gets every team with all its members, which for our team is every player who ever joined
    @WrapOperation(method = "updateEntireScoreboard", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/game/ClientboundSetPlayerTeamPacket;createAddOrModifyPacket(Lnet/minecraft/world/scores/PlayerTeam;Z)Lnet/minecraft/network/protocol/game/ClientboundSetPlayerTeamPacket;"))
    private ClientboundSetPlayerTeamPacket teamForJoiner(PlayerTeam team, boolean createNew, Operation<ClientboundSetPlayerTeamPacket> original,
                                                         @Local(argsOnly = true) ServerPlayer joiner) {
        if (Settings.active() && NameTagHider.isOurs(team)) {
            return NameTagHider.joinPacket(joiner, team);
        }
        return original.call(team, createNew);
    }

    // Joins, leaves, latency, game mode, chat sessions and team changes all go out through here
    @Inject(method = "broadcastAll(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void broadcastPerViewer(Packet<?> packet, CallbackInfo ci) {
        List<ServerPlayer> players = ((PlayerList) (Object) this).getPlayers();
        if (Settings.active() && (PlayerVisibility.broadcast(players, packet) || NameTagHider.broadcast(players, packet))) {
            ci.cancel();
        }
    }
}
