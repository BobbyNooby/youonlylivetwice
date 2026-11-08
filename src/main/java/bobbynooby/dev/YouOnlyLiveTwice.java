package bobbynooby.dev;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.database.DatabaseHandler;
import bobbynooby.dev.features.CustomCommands;
import bobbynooby.dev.features.NameTagHider;
import bobbynooby.dev.features.PseudoHardcore;
import bobbynooby.dev.features.WorldBorders;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.gamerules.GameRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class YouOnlyLiveTwice implements DedicatedServerModInitializer {
    public static final String MOD_ID = "you-only-live-twice";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final String TEAM_NAME = "invisible_players";


    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitializeServer() {
        Settings.load();

        ServerLifecycleEvents.SERVER_STARTING.register(Settings::onServerStarting);
        ServerLifecycleEvents.SERVER_STOPPED.register(Settings::onServerStopped);

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (!Settings.active()) {
                return;
            }
            DatabaseHandler.initialize(server);

            // The rule is saved with the world, so with the switch off we leave whatever the admin set
            if (Settings.get().immediateRespawn) {
                server.getGameRules().set(GameRules.IMMEDIATE_RESPAWN, true, server);
            }
        });

        ServerLifecycleEvents.SERVER_STARTED.register(WorldBorders::apply);

        CustomCommands.register();
        PseudoHardcore.initialize();
        NameTagHider.registerInitTeams();
        NameTagHider.registerAddPlayerToTeamOnJoin();

        LOGGER.info("You Only Live Twice started.");
    }

}
