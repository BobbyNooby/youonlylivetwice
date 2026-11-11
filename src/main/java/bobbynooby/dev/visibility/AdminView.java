package bobbynooby.dev.visibility;

import bobbynooby.dev.core.Yolt;
import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;

/**
 * Whether an op sees everyone, every hidden message, the full debug screen and the real world spawn. Saved with the
 * player, so it survives relogs and restarts.
 */
public final class AdminView {
    // Copied on death as well, since a respawn makes a new player object. Saved in player files as a bare boolean
    // with no version, so a different shape needs a new id and a one-time copy from this one.
    public static final AttachmentType<Boolean> ATTACHMENT = AttachmentRegistry.create(Yolt.id("admin_view"),
            builder -> builder.persistent(Codec.BOOL).copyOnDeath());

    private AdminView() {}

    // Loads the class, so the type is registered before any player save is read; saves with an unknown type lose it
    public static void init() {
    }

    // Only while they are still an op at the level /yolt needs, so a de-opped player stops seeing hidden things
    public static boolean isOn(ServerPlayer player) {
        return player.getAttachedOrElse(ATTACHMENT, false) && Commands.LEVEL_GAMEMASTERS.check(player.permissions());
    }

    /** Flips the saved flag and brings the player's client in line. Returns the new flag, which only counts for ops. */
    public static boolean toggle(ServerPlayer player) {
        boolean on = !player.getAttachedOrElse(ATTACHMENT, false);
        set(player, on);
        // Entries, the tab list and the nametag rule all depend on it; this also resends the nametag team
        PlayerVisibility.refresh(player);
        // So do the full debug screen and the real world spawn
        DebugPrivacy.apply(player);
        NorthCompass.apply(player);
        return on;
    }

    /** Runs after an op or deop. The flag stays, so opping the player again brings admin view back. */
    public static void onOpChange(PlayerList playerList, NameAndId who) {
        ServerPlayer player = playerList.getPlayer(who.id());
        if (player != null && player.hasAttached(ATTACHMENT)) {
            PlayerVisibility.refresh(player);
            DebugPrivacy.apply(player);
            NorthCompass.apply(player);
        }
    }

    /** Runs after the whole op list is emptied at once, which skips the deop vanilla does for each player. */
    public static void onOpListCleared(PlayerList playerList) {
        for (ServerPlayer player : playerList.getPlayers()) {
            // What a deop sends, so the client's permission level and command tree match too
            playerList.sendPlayerPermissionLevel(player);
            onOpChange(playerList, player.nameAndId());
        }
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
