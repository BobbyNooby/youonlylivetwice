package bobbynooby.dev.visibility;

import bobbynooby.dev.YouOnlyLiveTwice;
import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.server.level.ServerPlayer;

/** Whether an op sees everyone and every hidden message. Saved with the player, so it survives relogs and restarts. */
public final class AdminView {
    // Copied on death as well, since a respawn makes a new player object
    public static final AttachmentType<Boolean> ATTACHMENT = AttachmentRegistry.create(YouOnlyLiveTwice.id("admin_view"),
            builder -> builder.persistent(Codec.BOOL).copyOnDeath());

    private AdminView() {}

    // Loads the class, so the type is registered before any player save is read; saves with an unknown type lose it
    public static void init() {
    }

    public static boolean isOn(ServerPlayer player) {
        return player.getAttachedOrElse(ATTACHMENT, false);
    }

    /** Flips admin view and brings the player's client in line. Returns the new state. */
    public static boolean toggle(ServerPlayer player) {
        boolean on = !isOn(player);
        set(player, on);
        // Entries, the tab list and the nametag rule all depend on it; this also resends the nametag team
        PlayerVisibility.refresh(player);
        return on;
    }

    /** Only writes the flag: the caller refreshes what the player's client has. */
    public static void set(ServerPlayer player, boolean on) {
        if (on) {
            player.setAttached(ATTACHMENT, true);
        } else {
            // Off is the default, so only admins carry it in their save
            player.removeAttached(ATTACHMENT);
        }
    }
}
