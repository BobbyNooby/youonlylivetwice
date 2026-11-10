package bobbynooby.dev.gametest;

import bobbynooby.dev.skins.CachedSkin;
import bobbynooby.dev.skins.SkinLookup;
import bobbynooby.dev.skins.SkinTextures;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

// Stands in for Mojang in every test run, put in before the server starts. A name no test has set up has no account,
// so a join in any other test gets no skin and never waits. Tests set up names of their own, so they never meet.
public final class FakeSkinLookup implements SkinLookup {
    public static final FakeSkinLookup INSTANCE = new FakeSkinLookup();

    private final Map<String, Supplier<CompletableFuture<CachedSkin>>> answers = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> asked = new ConcurrentHashMap<>();

    private FakeSkinLookup() {
    }

    @Override
    public CompletableFuture<CachedSkin> find(String name) {
        asked.computeIfAbsent(key(name), k -> new AtomicInteger()).incrementAndGet();
        Supplier<CompletableFuture<CachedSkin>> answer = answers.get(key(name));
        return answer != null ? answer.get() : CompletableFuture.completedFuture(CachedSkin.noAccount(name, System.currentTimeMillis()));
    }

    // Made-up signed textures for this account, like the session server's answer. Only a client reads them.
    public static SkinTextures texturesOf(String name) {
        return new SkinTextures("textures of " + name, "signature of " + name);
    }

    // The account exists, and Mojang answers at once
    public void answer(String name) {
        answers.put(key(name), () -> CompletableFuture.completedFuture(
                CachedSkin.found(name, texturesOf(name), System.currentTimeMillis())));
    }

    // Mojang never answers about this name
    public void neverAnswer(String name) {
        answers.put(key(name), CompletableFuture::new);
    }

    public int timesAsked(String name) {
        AtomicInteger times = asked.get(key(name));
        return times == null ? 0 : times.get();
    }

    public void forget(String name) {
        answers.remove(key(name));
        asked.remove(key(name));
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
