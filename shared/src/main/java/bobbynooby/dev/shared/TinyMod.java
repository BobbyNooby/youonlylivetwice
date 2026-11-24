package bobbynooby.dev.shared;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * What every tiny mod does with its settings, whether it runs on its own or inside another mod.
 * <p>
 * On its own, it reads {@code config/<file name>} at each server start and on {@code /<id> reload}, and players in
 * creative or spectator are left out. A mod that ships it inside its own jar can take over instead: it calls
 * {@link #hostedBy} from its own onInitialize, and from then on the settings and who is left out come from that mod, the
 * file is never read or written and the command isn't there. Every mod's onInitialize runs before any server starts,
 * so the order the two mods load in doesn't matter.
 *
 * @param <S> the mod's settings record
 */
public final class TinyMod<S> {
    private record Host<S>(String name, Supplier<S> settings, Predicate<ServerPlayer> exempt) {}

    private final String id;
    private final String name;
    private final SettingsFile<S> file;
    private final S defaults;
    private final Predicate<S> on;
    private final Function<S, String> summary;
    private final Function<S, String> describe;
    private final Logger logger;

    private volatile @Nullable Host<S> host;
    // The file's settings, once a server has started; never read while hosted
    private volatile S own;

    /**
     * id names the command, and name the logger and the start-up line. on says whether settings switch the mod on.
     * summary is what the start-up line says after the switch, and describe the line the command shows.
     */
    public TinyMod(String id, String name, SettingsFile<S> file, S defaults, Predicate<S> on,
            Function<S, String> summary, Function<S, String> describe) {
        this.id = id;
        this.name = name;
        this.file = file;
        this.defaults = defaults;
        this.on = on;
        this.summary = summary;
        this.describe = describe;
        this.logger = LoggerFactory.getLogger(name);
        this.own = defaults;
    }

    /** Hooks the read at each server start and the command. Called from the mod's own onInitialize. */
    public void init() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> start());
        // Commands are built after every onInitialize, so a host is known by then
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> {
            if (host == null) {
                register(dispatcher);
            }
        });
    }

    /**
     * Hands this mod's settings to another mod. settings is read at every use, so it can hand out its live config, and
     * exempt names the players who are left out. Called once, from the host's own onInitialize: a second call throws,
     * so two mods can't both think they set it.
     */
    public synchronized void hostedBy(String host, Supplier<S> settings, Predicate<ServerPlayer> exempt) {
        Host<S> current = this.host;
        if (current != null) {
            throw new IllegalStateException(name + " is hosted by " + current.name() + " already, so " + host
                    + " can't host it too");
        }
        this.host = new Host<>(host, settings, exempt);
    }

    /** The settings in use now. */
    public S settings() {
        Host<S> current = host;
        return current != null ? current.settings().get() : own;
    }

    /** True when this player is left out: on its own, anyone in creative or spectator. */
    public boolean exempt(ServerPlayer player) {
        Host<S> current = host;
        return current != null ? current.exempt().test(player) : !player.gameMode().isSurvival();
    }

    /** True when another mod hands this one its settings. */
    public boolean hosted() {
        return host != null;
    }

    /**
     * Reads the file again. A key that is missing or refused keeps the value in use, and a file that can't be read at
     * all changes nothing. While hosted it does nothing, since the host's config is the one in use.
     */
    public SettingsFile.Result<S> reload() {
        Host<S> current = host;
        if (current != null) {
            return new SettingsFile.Result<>(current.settings().get(),
                    List.of("the settings come from " + current.name()), true);
        }
        SettingsFile.Result<S> result = file.load(configFile(), own);
        own = result.settings();
        return result;
    }

    /** Where the file is read from on its own: {@code config/<file name>}. */
    public Path configFile() {
        return FabricLoader.getInstance().getConfigDir().resolve(file.name());
    }

    /**
     * For a mod's own GameTests: swaps the settings in for one test, which puts them back within the same tick. Returns
     * the settings it replaced.
     */
    public S swapForTesting(S settings) {
        S before = own;
        own = settings;
        return before;
    }

    // Every start reads the file afresh, with the defaults for whatever it lacks
    private void start() {
        Host<S> current = host;
        if (current != null) {
            logger.info("{} takes its settings from {}", name, current.name());
            return;
        }
        SettingsFile.Result<S> result = file.load(configFile(), defaults);
        own = result.settings();
        for (String note : result.notes()) {
            if (result.failed()) {
                logger.error("{}; using the defaults", note);
            } else {
                logger.warn("{}", note);
            }
        }
        S settings = own;
        logger.info("{} {}: {}", name, on.test(settings) ? "on" : "off", summary.apply(settings));
    }

    // /<id> shows the settings in use, and /<id> reload reads the file again. Ops only, and only while the mod runs on
    // its own: a host's settings come from its own config.
    private void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(id)
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal(describe.apply(settings())), false);
                    return 1;
                })
                .then(Commands.literal("reload").executes(context -> {
                    CommandSourceStack source = context.getSource();
                    SettingsFile.Result<S> result = reload();
                    for (String note : result.notes()) {
                        source.sendSuccess(() -> Component.literal(note), false);
                    }
                    if (result.failed()) {
                        source.sendFailure(Component.literal("Nothing changed: " + file.name() + " couldn't be read"));
                        return 0;
                    }
                    source.sendSuccess(() -> Component.literal("Reloaded " + file.name() + ". "
                            + describe.apply(result.settings())), true);
                    return 1;
                })));
    }
}
