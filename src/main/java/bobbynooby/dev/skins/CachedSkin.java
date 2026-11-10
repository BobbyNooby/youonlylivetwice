package bobbynooby.dev.skins;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * What Mojang said about one account name, and when. No textures means no account has the name, which is kept too, so
 * a name nobody owns isn't asked about again on every join. The account's id is there for answers saved since ids were
 * kept, so a pick can tell whether that account has played here.
 */
public record CachedSkin(String name, @Nullable SkinTextures textures, long fetchedAt, @Nullable UUID id) {
    private static final long MILLIS_PER_HOUR = 60L * 60 * 1000;

    /** An answer with no account id, as saved before ids were kept. */
    public CachedSkin(String name, @Nullable SkinTextures textures, long fetchedAt) {
        this(name, textures, fetchedAt, null);
    }

    public static CachedSkin found(String name, UUID id, SkinTextures textures, long now) {
        return new CachedSkin(name, textures, now, id);
    }

    /** An answer with no account id, as saved before ids were kept. */
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
