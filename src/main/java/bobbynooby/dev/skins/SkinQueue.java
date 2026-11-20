package bobbynooby.dev.skins;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Keeps the lookups waiting on Mojang few: a name already being asked about shares that lookup's answer, and past the
 * limit a new name fails at once, as it does when Mojang can't be asked, so the join goes on with no skin.
 */
final class SkinQueue {
    static final int DEFAULT_LIMIT = 16;

    private final int limit;
    private final Map<String, CompletableFuture<CachedSkin>> waiting = new ConcurrentHashMap<>();

    SkinQueue(int limit) {
        this.limit = limit;
    }

    CompletableFuture<CachedSkin> ask(String name, Supplier<CompletableFuture<CachedSkin>> lookup) {
        String key = name.toLowerCase(Locale.ROOT);
        CompletableFuture<CachedSkin> shared = waiting.get(key);
        if (shared != null) {
            return shared;
        }
        if (waiting.size() >= limit) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    waiting.size() + " skin lookups are waiting on Mojang already, so " + name + " is not asked about"));
        }
        CompletableFuture<CachedSkin> asked = lookup.get();
        waiting.put(key, asked);
        // Whatever the answer, the name is free to be asked about again
        asked.whenComplete((skin, failure) -> waiting.remove(key, asked));
        return asked;
    }

    int waiting() {
        return waiting.size();
    }
}
