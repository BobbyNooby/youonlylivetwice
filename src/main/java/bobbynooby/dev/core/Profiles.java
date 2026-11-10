package bobbynooby.dev.core;

import bobbynooby.dev.skins.Skins;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.item.component.ResolvableProfile;

/** Builds the profiles that grave heads, revive heads and bodies carry. Resolved, so nothing looks the owner up. */
public final class Profiles {
    private Profiles() {
    }

    // Keeps the signed textures property so the skin shows without a lookup
    public static ResolvableProfile of(ServerPlayer player) {
        return of(player.getGameProfile());
    }

    // No player or body to copy, so the skin is the one YOLT last knew for them
    public static ResolvableProfile of(MinecraftServer server, NameAndId who) {
        return of(Skins.known(server, who));
    }

    public static ResolvableProfile of(GameProfile profile) {
        return ResolvableProfile.createResolved(profile);
    }
}
