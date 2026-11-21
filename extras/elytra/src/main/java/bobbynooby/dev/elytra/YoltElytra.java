package bobbynooby.dev.elytra;

import bobbynooby.dev.elytra.shared.SettingsFile;
import bobbynooby.dev.elytra.shared.TinyMod;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * YOLT Elytra: gliding is slower and floatier, and rockets stop pushing past a speed.
 * <p>
 * On its own, it reads config/yolt-elytra.json at each server start and on /yolt-elytra reload, and players in creative
 * or spectator keep vanilla's elytra. A mod that ships it inside its own jar can take over instead: it calls
 * {@link #hostedBy} from its own onInitialize, and from then on the settings and who is left out come from that mod, the
 * file is never read or written and the command isn't there. Every mod's onInitialize runs before any server starts,
 * so the order the two mods load in doesn't matter. {@link TinyMod} does all of this; the methods here hand it on.
 * <p>
 * Either way the mod ticks its own players, so the glide follows these settings whoever hands them out.
 */
public final class YoltElytra implements ModInitializer {
    public static final String MOD_ID = "yolt-elytra";
    /** The gravity change a glider carries. Transient, so it is never saved with the player. */
    public static final Identifier GRAVITY_MODIFIER = Identifier.fromNamespaceAndPath(MOD_ID, "glide_gravity");
    static final TinyMod<ElytraSettings> MOD = new TinyMod<>(MOD_ID, "YOLT Elytra", ElytraConfig.FORMAT,
            ElytraSettings.DEFAULTS, ElytraSettings::enabled, ElytraSettings::summary, ElytraSettings::describe);

    @Override
    public void onInitialize() {
        MOD.init();
        ServerTickEvents.END_SERVER_TICK.register(server -> server.getPlayerList().getPlayers().forEach(Glide::tick));
    }

    /**
     * Hands this mod's settings to another mod. settings is read at every tick and every rocket, so it can hand out its
     * live config, and exempt names the players who keep vanilla's elytra. Called once, from the host's onInitialize.
     */
    public static void hostedBy(String name, Supplier<ElytraSettings> settings, Predicate<ServerPlayer> exempt) {
        MOD.hostedBy(name, settings, exempt);
    }

    /** The settings in use now. */
    public static ElytraSettings settings() {
        return MOD.settings();
    }

    /** True when this player keeps vanilla's elytra: on its own, anyone in creative or spectator. */
    public static boolean exempt(ServerPlayer player) {
        return MOD.exempt(player);
    }

    /** True when another mod hands this one its settings. */
    public static boolean hosted() {
        return MOD.hosted();
    }

    /**
     * Reads the file again. A key that is missing or refused keeps the value in use, and a file that can't be read at
     * all changes nothing. While hosted it does nothing, since the host's config is the one in use.
     */
    public static SettingsFile.Result<ElytraSettings> reload() {
        return MOD.reload();
    }

    /** True while the player glides faster than the rocket cut-off. */
    public static boolean pastCutoff(ServerPlayer player) {
        return Glide.pastCutoff(player);
    }

    /** True when a rocket fired now would only push for a tick before it is cut, so it isn't fired at all. */
    public static boolean refusesRocket(ServerPlayer player) {
        return Glide.pastCutoff(player);
    }

    static Path configFile() {
        return MOD.configFile();
    }

    // For the mod's own GameTests: swaps the settings in for one test, which puts them back within the same tick
    static ElytraSettings swapForTesting(ElytraSettings settings) {
        return MOD.swapForTesting(settings);
    }
}
