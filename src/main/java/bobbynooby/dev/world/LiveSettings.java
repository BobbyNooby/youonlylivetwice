package bobbynooby.dev.world;

import bobbynooby.dev.visibility.NameTagTeam;
import bobbynooby.dev.visibility.PlayerVisibility;
import bobbynooby.dev.visibility.TabHeaders;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Everything the config changes in the live world; run at startup and by /yolt reload. */
public final class LiveSettings {
    private LiveSettings() {}

    public static void applyAll(MinecraftServer server) {
        applyAll(server, true);
    }

    /** With border false every world border stays as it is: a reload whose border would leave someone outside. */
    public static void applyAll(MinecraftServer server, boolean border) {
        // Each of these checks its own switch
        if (border) {
            WorldBorders.apply(server);
        }
        NameTagTeam.apply(server);
        GameRuleSwitches.applyImmediateRespawn(server);
        GameRuleSwitches.applyLocatorBar(server);
        GameRuleSwitches.applyGlobalSounds(server);
        TabHeaders.applyAll(server);
        // Which commands a player sees depends on restrictCommands and chat.allowWhispers,
        // and which players their client knows about depends on the hide switches
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            server.getCommands().sendCommands(player);
            PlayerVisibility.refresh(player);
        }
    }
}
