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
 * skin they came with, else the account with their name. A picked account that no longer exists gives way to the
 * next of those. When Mojang has to be asked, the login waits for the answer, a tick at a time, for up to
 * skins.lookupTimeoutSeconds in all. A login never fails because of a skin: a slow or failing Mojang means the skin
 * saved before, or none.
 */
public final class LoginSkins {
    private final MinecraftServer server;
    private final SocketAddress address;
    private final GameProfile profile;
    private final int ticksAllowed;
    private int ticksWaited;
    // Set once Mojang has failed or run out of time, so from then on only saved answers are used
    private boolean savedOnly;
    // The name Mojang is being asked about, whether the player picked it, and what Mojang said about it before
    private @Nullable String asked;
    private boolean askedForPick;
    private @Nullable CompletableFuture<CachedSkin> answer;
    private @Nullable CachedSkin earlier;
    private @Nullable GameProfile ready;

    private LoginSkins(MinecraftServer server, SocketAddress address, GameProfile profile) {
        this.server = server;
        this.address = address;
        this.profile = profile;
        // Ticks, like vanilla's own limit on a login, so the wait stays inside it even when the server lags
        this.ticksAllowed = Settings.get().skins.lookupTimeoutSeconds * SharedConstants.TICKS_PER_SECOND;
    }

    public static LoginSkins start(MinecraftServer server, SocketAddress address, GameProfile profile) {
        LoginSkins skins = new LoginSkins(server, address, profile);
        try {
            skins.begin();
        } catch (RuntimeException e) {
            Yolt.LOGGER.error("Could not work out {}'s skin, so they join with the one they came with", profile.name(), e);
            skins.ready = profile;
        }
        return skins;
    }

    private void begin() {
        if (!Skins.on()) {
            ready = profile;
            return;
        }
        // The account the player picked wins over everything, a skin they came with included
        String chosen = SkinStore.get(server).choice(profile.id());
        if (chosen != null) {
            skinOf(chosen, true);
        } else {
            ownSkin();
        }
    }

    // The skin the profile came with (online mode, a forwarding proxy or another mod), else the account with their name
    private void ownSkin() {
        if (Skins.textures(profile) != null) {
            ready = Skins.settle(server, profile, null);
            return;
        }
        skinOf(profile.name(), false);
    }

    // From a fresh saved answer at once, else from Mojang
    private void skinOf(String name, boolean pick) {
        CachedSkin saved = SkinStore.get(server).skin(name);
        if (savedOnly || (saved != null && saved.fresh(System.currentTimeMillis(), Settings.get().skins.refreshHours))) {
            use(name, pick, saved);
            return;
        }
        // A login vanilla is about to refuse (a ban, the whitelist, a lockout, a full server) sends no name to Mojang
        if (server.getPlayerList().canPlayerLogin(address, new NameAndId(profile)) != null) {
            ready = profile;
            return;
        }
        asked = name;
        askedForPick = pick;
        earlier = saved;
        answer = Skins.lookup().find(name);
    }

    // A pick with no skin to give, its account gone or Mojang unable to say, gives way to the player's own skin, the
    // same as for heads built from their name
    private void use(String name, boolean pick, @Nullable CachedSkin skin) {
        if (pick && (skin == null || !skin.exists())) {
            if (skin != null) {
                Yolt.LOGGER.warn("{} picked the Mojang account {}, which no longer exists, so they join with their own skin",
                        profile.name(), name);
            }
            ownSkin();
            return;
        }
        ready = Skins.settle(server, profile, skin);
    }

    /** The profile to log in with, or null while Mojang hasn't answered and there is still time to wait. */
    public @Nullable GameProfile poll() {
        if (ready == null) {
            try {
                check();
            } catch (RuntimeException e) {
                Yolt.LOGGER.error("Could not give {} a skin, so they join with the one they came with", profile.name(), e);
                ready = profile;
            }
        }
        return ready;
    }

    private void check() {
        // An answer already there can lead straight to the next lookup, whose answer may be there too
        while (ready == null && answer != null && answer.isDone()) {
            CompletableFuture<CachedSkin> done = answer;
            String name = asked;
            boolean pick = askedForPick;
            CachedSkin before = earlier;
            clearAsked();
            CachedSkin got = answered(done, name);
            if (got == null) {
                savedOnly = true;
            }
            use(name, pick, got != null ? got : before);
        }
        if (ready != null) {
            return;
        }
        if (answer == null) {
            ready = profile;
            return;
        }
        if (ticksWaited++ < ticksAllowed) {
            return;
        }
        String name = asked;
        boolean pick = askedForPick;
        CachedSkin before = earlier;
        Yolt.LOGGER.warn("Mojang took more than {} seconds over the skin of {}, so {} joins with the skin saved before, or none",
                ticksAllowed / SharedConstants.TICKS_PER_SECOND, name, profile.name());
        // A late answer is still kept, for their next join
        answer.thenAcceptAsync(skin -> SkinStore.get(server).putSkin(skin), server);
        clearAsked();
        savedOnly = true;
        use(name, pick, before);
    }

    private void clearAsked() {
        asked = null;
        answer = null;
        earlier = null;
    }

    // Null when Mojang couldn't be asked
    private @Nullable CachedSkin answered(CompletableFuture<CachedSkin> done, String name) {
        try {
            CachedSkin skin = done.join();
            SkinStore.get(server).putSkin(skin);
            return skin;
        } catch (CompletionException | CancellationException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            Yolt.LOGGER.warn("Could not ask Mojang for the skin of {}, so {} joins with the skin saved before, or none: {}",
                    name, profile.name(), cause.toString());
            return null;
        }
    }
}
