package bobbynooby.dev.hardcore;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * YOLT Hardcore: what you carry slows you down, falls break your legs, every return from death costs a max heart, food
 * matters, mobs are tougher, some nights are blood moons and the cold can freeze you. YOLT Anvils, YOLT Mending and
 * YOLT Elytra ship inside its jar, and it hosts them.
 * <p>
 * A mod can host it in turn: it says so in its fabric.mod.json with the custom value {@code "yolt-hardcore:host": true}
 * and calls {@link #hostedBy} from its own onInitialize. The mod then sets itself up inside that call, so every hook it
 * registers lands at the place in the host's start-up where the host calls it. Fabric runs an event's listeners in the
 * order they registered, and a host may need its own hooks on an event to run first. From then on every value comes
 * from the host, read at each use. With no such mod loaded, it sets itself up in its own onInitialize and runs on its
 * defaults on any server.
 */
public final class YoltHardcore implements ModInitializer {
    public static final String MOD_ID = "yolt-hardcore";
    public static final Logger LOGGER = LoggerFactory.getLogger("YOLT Hardcore");
    // The custom value a host sets to true in its fabric.mod.json
    private static final String HOST_KEY = "yolt-hardcore:host";
    private static final String NAME = "YOLT Hardcore";

    // On its own: true while a server runs
    private static volatile boolean running;
    // On its own: the defaults, while a server runs. There are no bodies without a host, so nothing is a stand-in or a
    // body.
    static final HardcoreHost OWN = new HardcoreHost() {
        private final HardcoreConfig.HardcoreSection settings = new HardcoreConfig.HardcoreSection();

        @Override
        public String name() {
            return NAME;
        }

        @Override
        public boolean active() {
            return running;
        }

        @Override
        public HardcoreConfig.HardcoreSection settings() {
            return settings;
        }

        @Override
        public BloodMoonTexts texts() {
            return BloodMoonTexts.DEFAULTS;
        }

        @Override
        public boolean isStandIn(Entity entity) {
            return false;
        }

        @Override
        public boolean isBody(Entity entity) {
            return false;
        }

        @Override
        public Optional<UUID> bodyOwner(LivingEntity entity) {
            return Optional.empty();
        }
    };

    private static volatile boolean hosted;

    @Override
    public void onInitialize() {
        List<String> hosts = declaredHosts();
        if (!hosts.isEmpty()) {
            // The host sets the mod up. One that never does leaves every feature off, since nothing is registered and
            // the own switch never turns on.
            ServerLifecycleEvents.SERVER_STARTING.register(server -> {
                if (!hosted) {
                    LOGGER.error("{} says it hosts YOLT Hardcore but never set it up, so YOLT Hardcore is off",
                            String.join(" and ", hosts));
                }
            });
            return;
        }
        // Before Hardcore.init(), whose store opens in a SERVER_STARTING listener that asks whether the rules apply
        ServerLifecycleEvents.SERVER_STARTING.register(server -> running = true);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> running = false);
        Hardcore.init();
    }

    /**
     * Hands the layer to another mod and sets it up there and then, so call it from the host's own onInitialize at the
     * place its hooks should go. Only a mod whose fabric.mod.json declares it a host may call it, and only once.
     */
    public static void hostedBy(HardcoreHost host) {
        if (hosted) {
            throw new IllegalStateException("YOLT Hardcore is hosted already");
        }
        if (declaredHosts().isEmpty()) {
            throw new IllegalStateException("No loaded mod declares " + HOST_KEY + " in its fabric.mod.json, so YOLT"
                    + " Hardcore has set itself up already or will");
        }
        Host.use(host);
        hosted = true;
        Hardcore.init();
    }

    /** True when another mod hosts this one. */
    public static boolean hosted() {
        return hosted;
    }

    // The names of the loaded mods whose fabric.mod.json says they host this one
    private static List<String> declaredHosts() {
        List<String> hosts = new ArrayList<>();
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            ModMetadata metadata = mod.getMetadata();
            if (metadata.containsCustomValue(HOST_KEY)) {
                CustomValue value = metadata.getCustomValue(HOST_KEY);
                if (value.getType() == CustomValue.CvType.BOOLEAN && value.getAsBoolean()) {
                    hosts.add(metadata.getName());
                }
            }
        }
        return hosts;
    }
}
