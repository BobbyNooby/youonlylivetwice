package bobbynooby.dev;

import bobbynooby.dev.features.Config;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.minecraft.resources.Identifier;
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


        LOGGER.info("You Only Live Twice started.");

        Config.initialize();

    }

}
