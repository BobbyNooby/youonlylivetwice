package bobbynooby.dev.mixin;

import net.minecraft.Optionull;
import net.minecraft.network.chat.RemoteChatSession;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.PlayerModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;

@Mixin(ClientboundPlayerInfoUpdatePacket.class)
public class PlayerListS2CPacketMixin {

    /*
    Note: This is a modified version of the original ClientboundPlayerInfoUpdatePacket

    To disable showing the player on the screen you have to disable the
    listed boolean inside the ClientboundPlayerInfoUpdatePacket.Entry

    This code prevents all players from being listed on the tab list.
    The original intention was to hide every other player but yourself
    from the list but that took too long and too much fiddling with the
    PlayerList class.

    For future reference if anyone wants to take this up, refer to the
    PlayerList's broadcastAll and placeNewPlayer methods as those are
    where I left off.

    Originally I modified the broadcastAll method to only show yourself but
    the issue arose where when a new player joins, they see everyone, else
    that has connected in the tablist.

    I gave up on trying to fix that and resorted to this which means no one
    can be seen on the tablist.

     */

    @Inject(method = "createPlayerInitializing", at = @At("HEAD"), cancellable = true)
    private static void entryFromPlayer(Collection<ServerPlayer> players, CallbackInfoReturnable<ClientboundPlayerInfoUpdatePacket> cir) {
        EnumSet<ClientboundPlayerInfoUpdatePacket.Action> enumSet = EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER, ClientboundPlayerInfoUpdatePacket.Action.INITIALIZE_CHAT, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_HAT, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LIST_ORDER);

        List<ClientboundPlayerInfoUpdatePacket.Entry> spoofedEntries = new ArrayList<>();

        for (ServerPlayer player : players) {
            spoofedEntries.add(new ClientboundPlayerInfoUpdatePacket.Entry(
                    player.getUUID(),
                    player.getGameProfile(),
                    false,
                    player.connection.latency(),
                    player.gameMode(),
                    player.getTabListDisplayName(),
                    player.isModelPartShown(PlayerModelPart.HAT),
                    player.getTabListOrder(),
                    Optionull.map(player.getChatSession(), RemoteChatSession::asData)
            ));
        }

        ClientboundPlayerInfoUpdatePacket spoofedPacket = new ClientboundPlayerInfoUpdatePacket(enumSet, players);
        ((PlayerListS2CPacketAccessor) spoofedPacket).setEntries(spoofedEntries);

        cir.setReturnValue(spoofedPacket);
    }
}
