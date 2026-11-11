package bobbynooby.dev.visibility;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.StandIn;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * Decides what each player's own debug screen (F3) gets; nothing else does, so later changes to what F3 gives away belong
 * here. The client applies the one flag to the whole screen at once, so no single line can be hidden on its own.
 */
public final class DebugPrivacy {
    private DebugPrivacy() {}

    // Each of these gives the client a new player object. The client copies the flag over, but sending it again doesn't
    // rely on that.
    public static void init() {
        ServerPlayerEvents.JOIN.register(DebugPrivacy::apply);
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> apply(newPlayer));
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, origin, destination) -> apply(player));
    }

    /** True when the player gets the reduced screen. Vanilla's game rule reduces it for everyone, admin view or not. */
    public static boolean reduced(ServerPlayer player) {
        if (player.level().getGameRules().get(GameRules.REDUCED_DEBUG_INFO)) {
            return true;
        }
        return Settings.on(c -> c.hide.debugScreen) && !AdminView.isOn(player);
    }

    /** Sends the player's client the flag it should have now. */
    public static void apply(ServerPlayer player) {
        if (!Settings.active() || StandIn.is(player)) {
            return;
        }
        byte event = reduced(player) ? EntityEvent.REDUCED_DEBUG_INFO : EntityEvent.FULL_DEBUG_INFO;
        player.connection.send(new ClientboundEntityEventPacket(player, event));
    }

    /** The same for every online player, after the switch or vanilla's game rule changed. */
    public static void applyAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            apply(player);
        }
    }
}
