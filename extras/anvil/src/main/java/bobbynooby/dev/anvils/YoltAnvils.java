package bobbynooby.dev.anvils;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * YOLT Anvils: flat anvil prices that never grow with use and are never too expensive.
 * <p>
 * On its own, it reads config/yolt-anvils.json at each server start and on /yolt-anvils reload, and players in
 * creative or spectator keep vanilla's anvil. A mod that ships it inside its own jar can take over instead: it calls
 * {@link #hostedBy} from its own onInitialize, and from then on the settings and who is left out come from that mod, the
 * file is never read or written and the command isn't there. Every mod's onInitialize runs before any server starts,
 * so the order the two mods load in doesn't matter.
 */
public final class YoltAnvils implements ModInitializer {
    public static final String MOD_ID = "yolt-anvils";
    static final Logger LOGGER = LoggerFactory.getLogger("YOLT Anvils");

    private record Host(String name, Supplier<AnvilSettings> settings, Predicate<ServerPlayer> exempt) {}

    private static volatile @Nullable Host host;
    // The file's settings, once a server has started; never read while hosted
    private static volatile AnvilSettings own = AnvilSettings.DEFAULTS;

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> start());
        // Commands are built after every onInitialize, so a host is known by then
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> {
            if (host == null) {
                AnvilCommands.register(dispatcher);
            }
        });
    }

    /**
     * Hands this mod's settings to another mod. settings is read at every anvil use, so it can hand out its live config,
     * and exempt names the players who keep vanilla's anvil. Called once, from the host's own onInitialize.
     */
    public static void hostedBy(String name, Supplier<AnvilSettings> settings, Predicate<ServerPlayer> exempt) {
        host = new Host(name, settings, exempt);
    }

    /** The settings in use now. */
    public static AnvilSettings settings() {
        Host current = host;
        return current != null ? current.settings().get() : own;
    }

    /** True when this player keeps vanilla's anvil: on its own, anyone in creative or spectator. */
    public static boolean exempt(ServerPlayer player) {
        Host current = host;
        return current != null ? current.exempt().test(player) : !player.gameMode().isSurvival();
    }

    /** True when another mod hands this one its settings. */
    public static boolean hosted() {
        return host != null;
    }

    /**
     * Reads the file again. A key that is missing or refused keeps the value in use, and a file that can't be read at
     * all changes nothing. While hosted it does nothing, since the host's config is the one in use.
     */
    public static AnvilConfig.Result reload() {
        if (host != null) {
            return new AnvilConfig.Result(settings(), List.of("the settings come from " + host.name()), true);
        }
        AnvilConfig.Result result = AnvilConfig.load(configFile(), own);
        own = result.settings();
        return result;
    }

    static Path configFile() {
        return FabricLoader.getInstance().getConfigDir().resolve(AnvilConfig.FILE);
    }

    // Every start reads the file afresh, with the defaults for whatever it lacks
    private static void start() {
        Host current = host;
        if (current != null) {
            LOGGER.info("YOLT Anvils takes its settings from {}", current.name());
            return;
        }
        AnvilConfig.Result result = AnvilConfig.load(configFile(), AnvilSettings.DEFAULTS);
        own = result.settings();
        for (String note : result.notes()) {
            if (result.failed()) {
                LOGGER.error("{}; using the defaults", note);
            } else {
                LOGGER.warn("{}", note);
            }
        }
        LOGGER.info("YOLT Anvils {}: {}, break chance {}", own.enabled() ? "on" : "off", own.prices(), own.breakChance());
    }

    // For the mod's own GameTests: swaps the settings in for one test, which puts them back within the same tick
    static AnvilSettings swapForTesting(AnvilSettings settings) {
        AnvilSettings before = own;
        own = settings;
        return before;
    }
}
