package bobbynooby.dev.skins;

import java.util.concurrent.CompletableFuture;

/** Asks Mojang about an account name. The real one talks to Mojang; the tests put in one of their own before the server starts. */
public interface SkinLookup {
    /**
     * The signed skin of the account with this name, or a no-account answer. It must never block the caller, and it
     * fails when Mojang can't be asked, which is not the same as no account.
     */
    CompletableFuture<CachedSkin> find(String name);
}
