package bobbynooby.dev.skins;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.Yolt;
import com.mojang.authlib.GameProfile;
import net.minecraft.SharedConstants;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;
import org.jspecify.annotations.Nullable;

import java.net.SocketAddress;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * One joining player's skin, worked out as vanilla starts checking their login: the account they picked, else the
 * skin they came with, else the account with their name. When Mojang has to be asked, the login waits for the answer,
 * a tick at a time, for up to skins.lookupTimeoutSeconds. A login never fails because of a skin: a slow or failing
 * Mojang means the skin saved before, or none.
 */
public final class LoginSkins {
    private final MinecraftServer server;
    private final GameProfile profile;
    private final @Nullable String asked;
    private final @Nullable CompletableFuture<CachedSkin> answer;
    // What Mojang said the last time, kept for when it can't answer now
    private final @Nullable CachedSkin earlier;
    private final int ticksAllowed;
    private int ticksWaited;
    private @Nullable GameProfile ready;

    private LoginSkins(MinecraftServer server, GameProfile profile, String asked, CompletableFuture<CachedSkin> answer,
                       @Nullable CachedSkin earlier) {
        this.server = server;
        this.profile = profile;
        this.asked = asked;
        this.answer = answer;
        this.earlier = earlier;
        // Ticks, like vanilla's own 30 second limit on a login, so the wait stays inside it even when the server lags
        this.ticksAllowed = Settings.get().skins.lookupTimeoutSeconds * SharedConstants.TICKS_PER_SECOND;
    }

    private LoginSkins(MinecraftServer server, GameProfile ready) {
        this.server = server;
        this.profile = ready;
        this.asked = null;
        this.answer = null;
        this.earlier = null;
        this.ticksAllowed = 0;
        this.ready = ready;
    }

    public static LoginSkins start(MinecraftServer server, SocketAddress address, GameProfile profile) {
        try {
            return plan(server, address, profile);
        } catch (RuntimeException e) {
            Yolt.LOGGER.error("Could not work out {}'s skin, so they join with the one they came with", profile.name(), e);
            return new LoginSkins(server, profile);
        }
    }

    private static LoginSkins plan(MinecraftServer server, SocketAddress address, GameProfile profile) {
        if (!Skins.on()) {
            return new LoginSkins(server, profile);
        }
        SkinStore store = SkinStore.get(server);
        // The account the player picked wins over everything, a skin they came with included
        String chosen = store.choice(profile.id());
        // Online mode, a forwarding proxy or another mod has given the skin already
        if (chosen == null && Skins.textures(profile) != null) {
            return new LoginSkins(server, Skins.settle(server, profile, null));
        }
        String name = chosen != null ? chosen : profile.name();
        CachedSkin earlier = store.skin(name);
        if (earlier != null && earlier.fresh(System.currentTimeMillis(), Settings.get().skins.refreshHours)) {
            return new LoginSkins(server, Skins.settle(server, profile, earlier));
        }
        // A login vanilla is about to refuse (a ban, the whitelist, a lockout, a full server) sends no name to Mojang
        if (server.getPlayerList().canPlayerLogin(address, new NameAndId(profile)) != null) {
            return new LoginSkins(server, profile);
        }
        return new LoginSkins(server, profile, name, Skins.lookup().find(name), earlier);
    }

    /** The profile to log in with, or null while Mojang hasn't answered and there is still time to wait. */
    public @Nullable GameProfile poll() {
        if (ready == null) {
            try {
                ready = check();
            } catch (RuntimeException e) {
                Yolt.LOGGER.error("Could not give {} a skin, so they join with the one they came with", profile.name(), e);
                ready = profile;
            }
        }
        return ready;
    }

    private @Nullable GameProfile check() {
        if (answer == null || asked == null) {
            return profile;
        }
        if (answer.isDone()) {
            CachedSkin answered = answered();
            return Skins.settle(server, profile, answered != null ? answered : earlier);
        }
        if (ticksWaited++ < ticksAllowed) {
            return null;
        }
        Yolt.LOGGER.warn("Mojang took more than {} seconds over the skin of {}, so {} joins with {}",
                ticksAllowed / SharedConstants.TICKS_PER_SECOND, asked, profile.name(), withWhat());
        // A late answer is still kept, for their next join
        answer.thenAcceptAsync(skin -> SkinStore.get(server).putSkin(skin), server);
        return Skins.settle(server, profile, earlier);
    }

    // Null when Mojang couldn't be asked
    private @Nullable CachedSkin answered() {
        try {
            CachedSkin skin = answer.join();
            SkinStore.get(server).putSkin(skin);
            return skin;
        } catch (CompletionException | CancellationException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            Yolt.LOGGER.warn("Could not ask Mojang for the skin of {}, so {} joins with {}: {}", asked, profile.name(),
                    withWhat(), cause.toString());
            return null;
        }
    }

    private String withWhat() {
        return earlier != null && earlier.exists() ? "the skin saved before" : "none";
    }
}
