package bobbynooby.dev.world;

import bobbynooby.dev.visibility.NameTagTeam;
import bobbynooby.dev.visibility.PlayerVisibility;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Everything the config changes in the live world; run at startup and by /yolt reload. */
public class LiveSettings {
    public static void applyAll(MinecraftServer server) {
        // Each of these checks its own switch
        WorldBorders.apply(server);
        NameTagTeam.apply(server);
        GameRuleSwitches.applyImmediateRespawn(server);
        GameRuleSwitches.applyLocatorBar(server);
        // Which commands a player sees depends on restrictCommands and chat.allowWhispers,
        // and which players their client knows about depends on the hide switches
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            server.getCommands().sendCommands(player);
            PlayerVisibility.refresh(player);
        }
    }
}
