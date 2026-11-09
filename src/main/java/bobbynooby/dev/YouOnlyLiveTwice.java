package bobbynooby.dev;

import bobbynooby.dev.bodies.Bodies;
import bobbynooby.dev.chat.ChatDelivery;
import bobbynooby.dev.chat.Whispers;
import bobbynooby.dev.commands.YoltCommands;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.graves.Graves;
import bobbynooby.dev.lockout.DeathLockout;
import bobbynooby.dev.revive.Revive;
import bobbynooby.dev.visibility.AdminView;
import bobbynooby.dev.visibility.NameTagTeam;
import bobbynooby.dev.visibility.TabHeaders;
import bobbynooby.dev.world.LiveSettings;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Loads in any environment so client tests can host a dedicated server in the client process;
// every feature checks Settings.active(), so single-player stays vanilla.
public class YouOnlyLiveTwice implements ModInitializer {
    public static final String MOD_ID = "you-only-live-twice";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);


    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        Settings.load();

        ServerLifecycleEvents.SERVER_STARTING.register(Settings::onServerStarting);
        ServerLifecycleEvents.SERVER_STOPPED.register(Settings::onServerStopped);

        AdminView.init();
        YoltCommands.register();
        DeathLockout.init();
        Graves.init();
        Bodies.init();
        // After Graves: its use-block hook opens a clicked grave before a revive head in hand is tried
        Revive.init();
        TabHeaders.init();
        ChatDelivery.init();
        Whispers.init();
        ServerLifecycleEvents.SERVER_STARTED.register(LiveSettings::applyAll);
        NameTagTeam.init();

        LOGGER.info("You Only Live Twice started.");
    }
}
