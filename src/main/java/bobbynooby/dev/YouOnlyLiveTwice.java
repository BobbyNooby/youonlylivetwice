package bobbynooby.dev;

import bobbynooby.dev.features.Config;
import bobbynooby.dev.features.NameTagHider;
import net.fabricmc.api.DedicatedServerModInitializer;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;

public class YouOnlyLiveTwice implements DedicatedServerModInitializer {
    public static final String MOD_ID = "you-only-live-twice";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final String TEAM_NAME = "invisible_players";


    @Override
    public void onInitializeServer() {

        LOGGER.info("You Only Live Twice started.");

        Config.initialize();

    }

}