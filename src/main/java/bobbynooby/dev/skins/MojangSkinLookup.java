package bobbynooby.dev.skins;

import com.mojang.authlib.ProfileLookupCallback;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.services.ProfileNotFoundException;
import com.mojang.authlib.services.ProfileResult;
import net.minecraft.server.Services;
import org.jspecify.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Asks Mojang through the server's own services, so the addresses come from Mojang's discovery document like vanilla's.
 * The name lookup gives the account's id, and the session server that id's signed skin.
 */
final class MojangSkinLookup implements SkinLookup {
    // One daemon thread: never the server thread, and a stop never waits for Mojang
    private static final ExecutorService THREAD = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "YOLT skin lookup");
        thread.setDaemon(true);
        return thread;
    });

    private final Services services;

    MojangSkinLookup(Services services) {
        this.services = services;
    }

    @Override
    public CompletableFuture<CachedSkin> find(String name) {
        return CompletableFuture.supplyAsync(() -> lookUp(name), THREAD);
    }

    private CachedSkin lookUp(String name) {
        // The many-names lookup, with one name: it tells "no such account" apart from Mojang failing, which the
        // one-name lookup answers the same way
        Found found = new Found();
        services.profileRepository().findProfilesByNames(new String[] {name}, found);
        if (found.failure != null) {
            if (found.failure instanceof ProfileNotFoundException) {
                return CachedSkin.noAccount(name, System.currentTimeMillis());
            }
            throw new CompletionException(found.failure);
        }
        if (found.id == null || found.name == null) {
            throw new IllegalStateException("Mojang gave no answer for " + name);
        }
        // Signed, which other players' clients need to show a skin that isn't theirs
        ProfileResult result = services.sessionService().fetchProfile(found.id, true);
        if (result == null) {
            throw new IllegalStateException("Mojang did not give the profile of " + found.name);
        }
        Property textures = result.profile().properties().get(Skins.TEXTURES).stream().findFirst().orElse(null);
        if (textures == null) {
            throw new IllegalStateException("Mojang gave no skin for " + found.name);
        }
        return CachedSkin.found(found.name, new SkinTextures(textures.value(), textures.signature()), System.currentTimeMillis());
    }

    private static final class Found implements ProfileLookupCallback {
        private @Nullable UUID id;
        private @Nullable String name;
        private @Nullable Exception failure;

        @Override
        public void onProfileLookupSucceeded(String profileName, UUID profileId) {
            id = profileId;
            name = profileName;
        }

        @Override
        public void onProfileLookupFailed(String profileName, Exception exception) {
            failure = exception;
        }
    }
}
