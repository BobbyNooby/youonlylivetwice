package bobbynooby.dev;

import bobbynooby.dev.chat.ChatDelivery;
import bobbynooby.dev.chat.Whispers;
import bobbynooby.dev.commands.YoltCommands;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.features.NameTagHider;
import bobbynooby.dev.features.TabHeaders;
import bobbynooby.dev.graves.Graves;
import bobbynooby.dev.lockout.DeathLockout;
import bobbynooby.dev.features.WorldBorders;
import bobbynooby.dev.revive.Revive;
import bobbynooby.dev.visibility.AdminView;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.gamerules.GameRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Loads in any environment so client tests can host a dedicated server in the client process;
// every feature checks Settings.active(), so single-player stays vanilla.
public class YouOnlyLiveTwice implements ModInitializer {
    public static final String MOD_ID = "you-only-live-twice";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final String TEAM_NAME = "invisible_players";


    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        Settings.load();

        ServerLifecycleEvents.SERVER_STARTING.register(Settings::onServerStarting);
        ServerLifecycleEvents.SERVER_STOPPED.register(Settings::onServerStopped);

        ServerLifecycleEvents.SERVER_STARTED.register(YouOnlyLiveTwice::applyImmediateRespawn);
        ServerLifecycleEvents.SERVER_STARTED.register(YouOnlyLiveTwice::applyLocatorBar);
        ServerLifecycleEvents.SERVER_STARTED.register(WorldBorders::apply);

        AdminView.init();
        YoltCommands.register();
        DeathLockout.init();
        Graves.init();
        Revive.init();
        TabHeaders.init();
        ChatDelivery.init();
        Whispers.init();
        NameTagHider.registerInitTeams();
        NameTagHider.registerAddPlayerToTeamOnJoin();

        LOGGER.info("You Only Live Twice started.");
    }

    // Also run by /yolt reload
    public static void applyImmediateRespawn(MinecraftServer server) {
        if (!Settings.active()) {
            return;
        }
        // The rule is saved with the world, so with the switch off we leave whatever the admin set
        if (Settings.get().immediateRespawn) {
            server.getGameRules().set(GameRules.IMMEDIATE_RESPAWN, true, server);
        }
    }

    // Also run by /yolt reload. The locator bar points at players far beyond sight, so it tells who is online.
    public static void applyLocatorBar(MinecraftServer server) {
        if (!Settings.active()) {
            return;
        }
        // Saved with the world too, so with the switch off we leave whatever the admin set
        if (Settings.get().hide.locatorBar) {
            // Passing the server drops the waypoints clients already have
            server.getGameRules().set(GameRules.LOCATOR_BAR, false, server);
        }
    }

}
