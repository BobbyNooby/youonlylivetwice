package bobbynooby.dev.core;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.component.ResolvableProfile;

/** Builds the profiles that grave heads, revive heads and bodies carry. Resolved, so nothing looks the owner up. */
public final class Profiles {
    private Profiles() {}

    // Keeps the signed textures property so the skin shows without a lookup
    public static ResolvableProfile of(ServerPlayer player) {
        return of(player.getGameProfile());
    }

    // With no player or body to copy, the caller passes Skins.known, the skin YOLT last knew for them
    public static ResolvableProfile of(GameProfile profile) {
        return ResolvableProfile.createResolved(profile);
    }
}
