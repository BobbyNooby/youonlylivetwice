package bobbynooby.dev.chat;

import bobbynooby.dev.config.Settings;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.RootCommandNode;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;

/** Holds non-op players to /help, plus /w when whispers are on: what they may run, are sent and get suggested. */
public final class CommandRestriction {
    private CommandRestriction() {}

    /** Whether this player is held to the non-op tree: what it may run, what its client is sent and what it gets suggested. */
    public static boolean restricted(ServerPlayer player) {
        return heldBelowGamemaster(player.permissions());
    }

    /**
     * Whether a command from this source must stay inside the non-op tree. The source's own permissions count, so the
     * command on a sign an op wrote still runs when a non-op clicks it, since the sign lends it gamemaster permissions.
     */
    public static boolean restricts(CommandSourceStack source) {
        return source.getPlayer() != null && heldBelowGamemaster(source.permissions());
    }

    // The same line for what a player may run and what it is shown, so a level 1 op isn't sent commands it can't run
    private static boolean heldBelowGamemaster(PermissionSet permissions) {
        return Settings.on(c -> c.restrictCommands) && !Commands.LEVEL_GAMEMASTERS.check(permissions);
    }

    public static RootCommandNode<CommandSourceStack> nonOpTree(boolean allowWhispers) {
        RootCommandNode<CommandSourceStack> root = new RootCommandNode<>();
        // help is answered in blocks(), so its body never runs
        root.addChild(Commands.literal("help").executes(c -> 1).build());
        if (allowWhispers) {
            root.addChild(Whispers.command().build());
        }
        return root;
    }

    /** The tree a restricted player's client was sent, to read its commands and suggestions against. */
    public static CommandDispatcher<CommandSourceStack> nonOpDispatcher() {
        return new CommandDispatcher<>(nonOpTree(Settings.get().chat.allowWhispers));
    }

    /** True when a typed command must not run; a restricted player's /help gets its answer here. */
    public static boolean blocks(CommandSourceStack source, String command) {
        if (command.isEmpty() || !restricts(source)) {
            return false;
        }
        ServerPlayer player = source.getPlayer();
        String commandRoot = command.split(" ")[0];

        // /w is our own command (Whispers), so it runs normally and reads the signed message. With whispers off the
        // player's tree has no /w to run, so the answer /w itself would give comes from here.
        if (commandRoot.equals("w")) {
            if (Settings.get().chat.allowWhispers) {
                return false;
            }
            source.sendFailure(Component.literal(Whispers.TURNED_OFF));
        }

        if (commandRoot.equals("help")) {
            player.sendSystemMessage(Component.literal("But nobody answered....").withStyle(ChatFormatting.RED));
        }

        return true;
    }

    /** Sends a restricted player the non-op tree in place of vanilla's; false when the player gets vanilla's. */
    public static boolean sendNonOpTree(ServerPlayer player, ClientboundCommandsPacket.NodeInspector<CommandSourceStack> inspector) {
        if (restricted(player)) {
            player.connection.send(new ClientboundCommandsPacket(
                    nonOpTree(Settings.get().chat.allowWhispers), inspector));
            return true;
        }
        return false;
    }
}
