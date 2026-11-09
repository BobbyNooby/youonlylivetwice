package bobbynooby.dev.chat;

import bobbynooby.dev.config.Settings;
import com.mojang.brigadier.tree.RootCommandNode;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import net.minecraft.server.level.ServerPlayer;

/** Holds non-op players to /help, plus /w when whispers are on: what they may run, are sent and get suggested. */
public final class CommandRestriction {
    private CommandRestriction() {}

    /** Whether this player is held to the non-op tree: what it may run, what its client is sent and what it gets suggested. */
    public static boolean restricted(ServerPlayer player) {
        return Settings.on(c -> c.restrictCommands)
                && !player.level().getServer().getPlayerList().isOp(player.nameAndId());
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

    /** True when a typed command must not run; a restricted player's /help gets its answer here. */
    public static boolean blocks(ServerPlayer player, String command) {
        if (command.isEmpty() || player == null || !restricted(player)) {
            return false;
        }
        String commandRoot = command.split(" ")[0];

        // /w is our own command (Whispers), so it runs normally and reads the signed message
        if (commandRoot.equals("w") && Settings.get().chat.allowWhispers) {
            return false;
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
