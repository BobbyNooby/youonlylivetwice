package bobbynooby.dev.anvils;

import bobbynooby.dev.anvils.shared.SettingsFile;
import bobbynooby.dev.anvils.shared.TinyMod;
import net.fabricmc.api.ModInitializer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * YOLT Anvils: flat anvil prices that never grow with use and are never too expensive.
 * <p>
 * On its own, it reads config/yolt-anvils.json at each server start and on /yolt-anvils reload, and players in
 * creative or spectator keep vanilla's anvil. A mod that ships it inside its own jar can take over instead: it calls
 * {@link #hostedBy} from its own onInitialize, and from then on the settings and who is left out come from that mod, the
 * file is never read or written and the command isn't there. Every mod's onInitialize runs before any server starts,
 * so the order the two mods load in doesn't matter. {@link TinyMod} does all of this; the methods here hand it on.
 */
public final class YoltAnvils implements ModInitializer {
    public static final String MOD_ID = "yolt-anvils";
    static final TinyMod<AnvilSettings> MOD = new TinyMod<>(MOD_ID, "YOLT Anvils", AnvilConfig.FORMAT,
            AnvilSettings.DEFAULTS, AnvilSettings::enabled,
            settings -> settings.prices() + ", break chance " + settings.breakChance(), AnvilSettings::describe);

    @Override
    public void onInitialize() {
        MOD.init();
    }

    /**
     * Hands this mod's settings to another mod. settings is read at every anvil use, so it can hand out its live config,
     * and exempt names the players who keep vanilla's anvil. Called once, from the host's own onInitialize.
     */
    public static void hostedBy(String name, Supplier<AnvilSettings> settings, Predicate<ServerPlayer> exempt) {
        MOD.hostedBy(name, settings, exempt);
    }

    /** The settings in use now. */
    public static AnvilSettings settings() {
        return MOD.settings();
    }

    /** True when this player keeps vanilla's anvil: on its own, anyone in creative or spectator. */
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
    public static SettingsFile.Result<AnvilSettings> reload() {
        return MOD.reload();
    }

    static Path configFile() {
        return MOD.configFile();
    }

    // For the mod's own GameTests: swaps the settings in for one test, which puts them back within the same tick
    static AnvilSettings swapForTesting(AnvilSettings settings) {
        return MOD.swapForTesting(settings);
    }
}
