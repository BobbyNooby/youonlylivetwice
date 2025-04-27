package bobbynooby.dev.mixin;

import net.minecraft.entity.player.PlayerModelPart;
import net.minecraft.network.encryption.PublicPlayerSession;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Nullables;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.*;

@Mixin(PlayerListS2CPacket.class)
public class PlayerListS2CPacketMixin {

    /*
    Note: This is a modified version of the original PlayerListS2CPacket

    To disable showing the player on the screen you have to disable the
    listed boolean inside the PlayerListS2CPacket.Entry

    This code prevents all players from being listed on the tab list.
    The original intention was to hide every other player but yourself
    from the list but that took too long and too much fiddling with the
    PlayerManager class.

    For future reference if anyone wants to take this up, refer to the
    PlayerManager's sendToAll and onPlayerConnect methods as those are
    where I left off.

    Originally I modified the sendToAll method to only show yourself but
    the issue arose where when a new player joins, they see everyone, else
    that has connected in the tablist.

    I gave up on trying to fix that and resorted to this which means no one
    can be seen on the tablist.

     */
    @Inject(method = "entryFromPlayer", at = @At("HEAD"), cancellable = true)
    private static void entryFromPlayer(Collection<ServerPlayerEntity> players, CallbackInfoReturnable<PlayerListS2CPacket> cir) {
        EnumSet<PlayerListS2CPacket.Action> enumSet = EnumSet.of(PlayerListS2CPacket.Action.ADD_PLAYER, PlayerListS2CPacket.Action.INITIALIZE_CHAT, PlayerListS2CPacket.Action.UPDATE_GAME_MODE, PlayerListS2CPacket.Action.UPDATE_LISTED, PlayerListS2CPacket.Action.UPDATE_LATENCY, PlayerListS2CPacket.Action.UPDATE_DISPLAY_NAME, PlayerListS2CPacket.Action.UPDATE_HAT, PlayerListS2CPacket.Action.UPDATE_LIST_ORDER);

        List<PlayerListS2CPacket.Entry> spoofedEntries = new ArrayList<>();

        for (ServerPlayerEntity player : players) {
            spoofedEntries.add(new PlayerListS2CPacket.Entry(
                    player.getUuid(),
                    player.getGameProfile(),
                    false,
                    player.networkHandler.getLatency(),
                    player.getGameMode(),
                    player.getPlayerListName(),
                    player.isPartVisible(PlayerModelPart.HAT),
                    player.getPlayerListOrder(),
                    (PublicPlayerSession.Serialized) Nullables.map(player.getSession(), PublicPlayerSession::toSerialized)
            ));
        }

        PlayerListS2CPacket spoofedPacket = new PlayerListS2CPacket(enumSet, players);
        ((PlayerListS2CPacketAccessor) spoofedPacket).setEntries(spoofedEntries);

        cir.setReturnValue(spoofedPacket);
    }
}
