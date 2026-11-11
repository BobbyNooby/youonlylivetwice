package bobbynooby.dev.skins;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;
import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Skins on offline-mode servers. An offline login profile has no skin, so YOLT asks Mojang for the skin of the account
 * with the player's name, or of the account they picked with /yolt skin, and puts it in the profile before the player
 * exists. The player, their body and their grave and revive heads all copy that profile, so they all show it.
 */
public final class Skins {
    static final String TEXTURES = "textures";

    private static volatile @Nullable SkinLookup lookup;
    private static volatile boolean lookupFromTests;
    // The two skin mods for Fabric that give skins at login; YOLT leaves skins to them
    private static volatile BooleanSupplier skinModLoaded = () -> FabricLoader.getInstance().isModLoaded("skinsrestorer")
            || FabricLoader.getInstance().isModLoaded("skinrestorer");

    private Skins() {}

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTING.register(Skins::onServerStarting);
        SkinOwners.init();
    }

    // The only place the Mojang lookup is made, so a lookup the tests put in before the server started is never replaced
    private static void onServerStarting(MinecraftServer server) {
        if (!lookupFromTests) {
            lookup = new MojangSkinLookup(server.services());
        }
    }

    /** For the tests, before any server starts: every lookup from then on goes to this one, and none to Mojang. */
    public static void useLookupForTesting(SkinLookup testLookup) {
        lookupFromTests = true;
        lookup = testLookup;
    }

    /** For the tests: stands in for the check whether another skin mod is loaded. Returns the check it replaced. */
    public static BooleanSupplier useSkinModCheckForTesting(BooleanSupplier check) {
        BooleanSupplier before = skinModLoaded;
        skinModLoaded = check;
        return before;
    }

    /** True while YOLT gives skins: the mode is auto and no other skin mod is there to do it. Off, nothing goes to Mojang. */
    public static boolean on() {
        return Settings.on(c -> c.skins.mode == YoltConfig.SkinMode.AUTO) && !skinModLoaded.getAsBoolean();
    }

    /** Why YOLT gives no skins right now, for a command's reply, or empty while it does. */
    public static Optional<String> offReason() {
        if (!Settings.on(c -> c.skins.mode == YoltConfig.SkinMode.AUTO)) {
            return Optional.of("Skins are turned off.");
        }
        if (skinModLoaded.getAsBoolean()) {
            return Optional.of("Skins are left to the other skin mod on this server.");
        }
        return Optional.empty();
    }

    /**
     * What Mojang says about an account name, for a command: the saved answer while it is fresh, else Mojang's own,
     * failing after skins.lookupTimeoutSeconds. A command waits in no tick, so the timeout is plain time.
     */
    public static CompletableFuture<CachedSkin> lookUp(MinecraftServer server, String name) {
        CachedSkin cached = SkinStore.get(server).skin(name);
        // An account saved before ids were kept is asked again, since a pick checks whether its id has played here
        if (cached != null && cached.fresh(System.currentTimeMillis(), Settings.get().skins.refreshHours)
                && (cached.id() != null || !cached.exists())) {
            return CompletableFuture.completedFuture(cached);
        }
        return lookup().find(name).orTimeout(Settings.get().skins.lookupTimeoutSeconds, TimeUnit.SECONDS);
    }

    static SkinLookup lookup() {
        SkinLookup current = lookup;
        if (current == null) {
            throw new IllegalStateException("No skin lookup, since no server has started");
        }
        return current;
    }

    static @Nullable SkinTextures textures(GameProfile profile) {
        return profile.properties().get(TEXTURES).stream().findFirst()
                .map(property -> new SkinTextures(property.value(), property.signature()))
                .orElse(null);
    }

    /** A copy of the profile with these textures in place of its own. Its other properties stay. */
    public static GameProfile withTextures(GameProfile profile, SkinTextures textures) {
        Multimap<String, Property> properties = LinkedHashMultimap.create(profile.properties());
        properties.removeAll(TEXTURES);
        properties.put(TEXTURES, new Property(TEXTURES, textures.value(), textures.signature()));
        return new GameProfile(profile.id(), profile.name(), new PropertyMap(properties));
    }

    /**
     * A profile from a name and id alone, as for a grave head with no player or body to copy. It gets the skin of the
     * account they picked, else the one they last joined with, else the one Mojang gave for their name. Nothing is
     * asked of Mojang here.
     */
    public static GameProfile known(MinecraftServer server, NameAndId who) {
        GameProfile bare = new GameProfile(who.id(), who.name());
        if (!on()) {
            return bare;
        }
        SkinStore store = SkinStore.get(server);
        String chosen = store.choice(who.id());
        CachedSkin picked = chosen != null ? store.skin(chosen) : null;
        SkinTextures textures = picked != null ? picked.textures() : null;
        if (textures == null) {
            textures = store.applied(who.id());
        }
        if (textures == null) {
            CachedSkin byName = store.skin(who.name());
            textures = byName != null ? byName.textures() : null;
        }
        return textures != null ? withTextures(bare, textures) : bare;
    }

    /**
     * The profile a player joins with: the skin's textures if it has some, else the profile as it came. What it ends
     * with is kept as the skin they last joined with, for heads built later from their name alone.
     */
    static GameProfile settle(MinecraftServer server, GameProfile profile, @Nullable CachedSkin skin) {
        GameProfile result = skin != null && skin.textures() != null ? withTextures(profile, skin.textures()) : profile;
        SkinStore.get(server).setApplied(profile.id(), textures(result));
        return result;
    }
}
