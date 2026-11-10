package bobbynooby.dev.skins;

import org.jspecify.annotations.Nullable;

/**
 * What Mojang said about one account name, and when. No textures means no account has the name, which is kept too, so
 * a name nobody owns isn't asked about again on every join.
 */
public record CachedSkin(String name, @Nullable SkinTextures textures, long fetchedAt) {
    private static final long MILLIS_PER_HOUR = 60L * 60 * 1000;

    public static CachedSkin found(String name, SkinTextures textures, long now) {
        return new CachedSkin(name, textures, now);
    }

    public static CachedSkin noAccount(String name, long now) {
        return new CachedSkin(name, null, now);
    }

    public boolean exists() {
        return textures != null;
    }

    /**
     * Younger than refreshHours, so Mojang needn't be asked again. A time after now means the clock was moved back, and
     * the entry is asked again rather than trusted for however long that was.
     */
    public boolean fresh(long now, int refreshHours) {
        long age = now - fetchedAt;
        return age >= 0 && age < refreshHours * MILLIS_PER_HOUR;
    }
}
