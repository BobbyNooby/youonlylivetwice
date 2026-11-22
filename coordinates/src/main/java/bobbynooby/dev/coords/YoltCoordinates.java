package bobbynooby.dev.coords;

import bobbynooby.dev.coords.shared.SettingsFile;
import bobbynooby.dev.coords.shared.TinyMod;
import net.fabricmc.api.ModInitializer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * YOLT Coordinates: each player's client is sent its own shift of X and Z, which {@link CoordinateOffsets} does.
 * <p>
 * On its own, it reads config/yolt-coordinates.json at each server start and on /yolt-coordinates reload. A mod that
 * ships it inside its own jar can take over instead: it calls {@link #hostedBy} from its own onInitialize, and from
 * then on the switch and the {@link Hooks} come from that mod, the file is never read or written and the command isn't
 * there. Every mod's onInitialize runs before any server starts, and the hooks are read at each use, never copied at
 * init, so the order the two mods load in doesn't matter. {@link TinyMod} does the settings; the methods here hand it
 * on.
 */
public final class YoltCoordinates implements ModInitializer {
    public static final String MOD_ID = "yolt-coordinates";
    public static final Logger LOGGER = LoggerFactory.getLogger("YOLT Coordinates");
    static final TinyMod<CoordinateSettings> MOD = new TinyMod<>(MOD_ID, "YOLT Coordinates", CoordinateConfig.FORMAT,
            CoordinateSettings.DEFAULTS, CoordinateSettings::enabled,
            settings -> settings.enabled() ? "each player's X and Z are shifted" : "nobody is shifted",
            CoordinateSettings::describe);
    // On its own: always active, and nobody sees real coordinates, is sent a north target, hides their position or is a
    // stand-in
    private static final Hooks OWN = new Hooks(() -> true, player -> false, pos -> false, player -> false,
            entity -> false);

    private static volatile Hooks hooks = OWN;

    /**
     * What a host hands this mod, each read at every use. active says whether the mod runs on this server at all.
     * realCoordinates names a player who sees real coordinates in a frame that starts while it holds for them.
     * northTarget says whether a spawn is the host's north compass target, whose Z is the same for everyone.
     * hidesPosition names a player whose F3 screen is kept from showing where they are. standIn names an entity that
     * stands in for a player who isn't online, which has no client.
     */
    public record Hooks(BooleanSupplier active, Predicate<ServerPlayer> realCoordinates,
                        Predicate<BlockPos> northTarget, Predicate<ServerPlayer> hidesPosition,
                        Predicate<Entity> standIn) {}

    @Override
    public void onInitialize() {
        MOD.init();
        CoordinateOffsets.init();
    }

    /**
     * Hands this mod's switch and hooks to another mod. settings is read at every use, so it can hand out its live
     * config. Called once, from the host's own onInitialize.
     */
    public static void hostedBy(String name, Supplier<CoordinateSettings> settings, Hooks hooks) {
        YoltCoordinates.hooks = hooks;
        // TinyMod wants the players it leaves out, which here are the ones who see real coordinates. Its own rule for
        // them, creative and spectator, isn't this mod's, so it is never asked.
        MOD.hostedBy(name, settings, hooks.realCoordinates());
    }

    /** The settings in use now. */
    public static CoordinateSettings settings() {
        return MOD.settings();
    }

    /** True when another mod hands this one its settings. */
    public static boolean hosted() {
        return MOD.hosted();
    }

    /**
     * Reads the file again. A key that is missing or refused keeps the value in use, and a file that can't be read at
     * all changes nothing. While hosted it does nothing, since the host's config is the one in use.
     */
    public static SettingsFile.Result<CoordinateSettings> reload() {
        return MOD.reload();
    }

    /** True when the mod runs on this server at all. */
    static boolean active() {
        return hooks.active().getAsBoolean();
    }

    /** True when the mod runs and its switch is on. */
    static boolean on() {
        return active() && settings().enabled();
    }

    /** True when a frame that starts now gives this player real coordinates. */
    static boolean seesReal(ServerPlayer player) {
        return hooks.realCoordinates().test(player);
    }

    /** True when a spawn is the north compass target, whose Z is the same for everyone. */
    static boolean isNorthTarget(BlockPos pos) {
        return hooks.northTarget().test(pos);
    }

    /** True when this player's F3 screen is kept from showing where they are. */
    static boolean hidesPosition(ServerPlayer player) {
        return hooks.hidesPosition().test(player);
    }

    /** True when this entity stands in for a player who isn't online, so it has no client. */
    static boolean isStandIn(Entity entity) {
        return hooks.standIn().test(entity);
    }

    static Path configFile() {
        return MOD.configFile();
    }

    // For the mod's own GameTests: swaps the settings in for one test, which puts them back within the same tick
    static CoordinateSettings swapForTesting(CoordinateSettings settings) {
        return MOD.swapForTesting(settings);
    }
}
