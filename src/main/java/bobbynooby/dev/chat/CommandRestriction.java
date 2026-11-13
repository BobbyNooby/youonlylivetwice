package bobbynooby.dev.chat;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.hardcore.Hardcore;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.RootCommandNode;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import org.jspecify.annotations.Nullable;

import java.util.function.Supplier;

/**
 * Holds non-op players to /help, plus /w when whispers are on, /status while a hardcore feature is on and /yolt skin when
 * players may pick their skin: what they may run, are sent and get suggested.
 */
public final class CommandRestriction {
    // The /yolt a non-op may run for their own skin. The commands hand it in at startup, so chat needn't import them.
    private static @Nullable Supplier<LiteralArgumentBuilder<CommandSourceStack>> ownSkinCommand;
    // The /status everyone may run, handed in the same way
    private static @Nullable Supplier<LiteralArgumentBuilder<CommandSourceStack>> statusCommand;

    private CommandRestriction() {}

    /** Takes the /yolt that sets and clears a non-op's own skin, for the non-op tree. */
    public static void useOwnSkinCommand(Supplier<LiteralArgumentBuilder<CommandSourceStack>> command) {
        ownSkinCommand = command;
    }

    /** Takes the /status a player runs for their own body, for the non-op tree. */
    public static void useStatusCommand(Supplier<LiteralArgumentBuilder<CommandSourceStack>> command) {
        statusCommand = command;
    }

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

    public static RootCommandNode<CommandSourceStack> nonOpTree(boolean allowWhispers, boolean allowSkinChoice) {
        return nonOpTree(allowWhispers, allowSkinChoice, false);
    }

    public static RootCommandNode<CommandSourceStack> nonOpTree(boolean allowWhispers, boolean allowSkinChoice, boolean status) {
        RootCommandNode<CommandSourceStack> root = new RootCommandNode<>();
        // help is answered in blocks(), so its body never runs
        root.addChild(Commands.literal("help").executes(c -> 1).build());
        if (allowWhispers) {
            root.addChild(Whispers.command().build());
        }
        if (status && statusCommand != null) {
            root.addChild(statusCommand.get().build());
        }
        // Only the part for the player's own skin, so read against this tree nothing else of /yolt parses
        if (allowSkinChoice && ownSkinCommand != null) {
            root.addChild(ownSkinCommand.get().build());
        }
        return root;
    }

    /** The tree a restricted player's client was sent, to read its commands and suggestions against. */
    public static CommandDispatcher<CommandSourceStack> nonOpDispatcher() {
        return new CommandDispatcher<>(liveNonOpTree());
    }

    private static RootCommandNode<CommandSourceStack> liveNonOpTree() {
        return nonOpTree(Settings.get().chat.allowWhispers, Settings.get().skins.allowPlayerChoice, Hardcore.anyOn());
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

        // Read against the player's own tree, which has /status only while a hardcore feature is on
        if (commandRoot.equals("status") && Hardcore.anyOn()) {
            return false;
        }

        // A restricted player's command was read against their own tree, whose /yolt holds only their own skin
        if (commandRoot.equals("yolt") && Settings.get().skins.allowPlayerChoice) {
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
            player.connection.send(new ClientboundCommandsPacket(liveNonOpTree(), inspector));
            return true;
        }
        return false;
    }
}
