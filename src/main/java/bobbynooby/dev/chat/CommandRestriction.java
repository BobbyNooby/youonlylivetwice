package bobbynooby.dev.chat;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.hardcore.Hardcore;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Holds non-op players to /help, plus /w, /msg and /tell when whispers are on, /status while a hardcore feature is on, /yolt skin when
 * players may pick their skin and the commands allowedCommands lists: what they may run, are sent and get suggested.
 */
public final class CommandRestriction {
    // The commands YOLT puts in the non-op tree itself, each by a setting of its own, which allowedCommands can't override
    private static final Set<String> OWN = Set.of("help", "w", "msg", "tell", "status", "yolt");
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
            for (String name : Whispers.NAMES) {
                root.addChild(Whispers.command(name).build());
            }
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

    /**
     * The same, with each command allowed listed copied in from the server's own tree for this source, as vanilla copies
     * the tree it sends a player: only the branches the source may use, so a gamemaster command listed here still never
     * reaches a non-op. A redirect to the server's root lands on this root instead, so a listed command that runs other
     * commands, as /execute run does, reaches only the ones a non-op has. A name YOLT decides itself, or one the server
     * has no command for, adds nothing.
     */
    public static RootCommandNode<CommandSourceStack> nonOpTree(boolean allowWhispers, boolean allowSkinChoice, boolean status,
                                                               List<String> allowed, CommandDispatcher<CommandSourceStack> full,
                                                               CommandSourceStack source) {
        RootCommandNode<CommandSourceStack> root = nonOpTree(allowWhispers, allowSkinChoice, status);
        Map<CommandNode<CommandSourceStack>, CommandNode<CommandSourceStack>> copies = new HashMap<>();
        copies.put(full.getRoot(), root);
        for (String name : allowed) {
            CommandNode<CommandSourceStack> command = full.getRoot().getChild(name);
            // A name listed twice is already in
            if (command == null || OWN.contains(name) || root.getChild(name) != null || !command.canUse(source)) {
                continue;
            }
            CommandNode<CommandSourceStack> copy = copy(command, source, copies, new HashSet<>());
            if (copy != null) {
                root.addChild(copy);
            }
        }
        return root;
    }

    // Built before its children, so a child's redirect back up to it, as /execute's are, finds the copy. Null only for a
    // node whose redirects lead back to it before reaching one that is built, which brigadier couldn't parse either.
    private static @Nullable CommandNode<CommandSourceStack> copy(CommandNode<CommandSourceStack> node, CommandSourceStack source,
                                                                 Map<CommandNode<CommandSourceStack>, CommandNode<CommandSourceStack>> copies,
                                                                 Set<CommandNode<CommandSourceStack>> building) {
        CommandNode<CommandSourceStack> made = copies.get(node);
        if (made != null) {
            return made;
        }
        if (!building.add(node)) {
            return null;
        }
        ArgumentBuilder<CommandSourceStack, ?> builder = node.createBuilder();
        if (node.getRedirect() != null) {
            // The modifier and fork go with it, since the copy is what the command runs through, not only what is sent
            builder.forward(copy(node.getRedirect(), source, copies, building), node.getRedirectModifier(), node.isFork());
        }
        CommandNode<CommandSourceStack> copy = builder.build();
        copies.put(node, copy);
        building.remove(node);
        for (CommandNode<CommandSourceStack> child : node.getChildren()) {
            if (child.canUse(source)) {
                CommandNode<CommandSourceStack> childCopy = copy(child, source, copies, building);
                if (childCopy != null) {
                    copy.addChild(childCopy);
                }
            }
        }
        return copy;
    }

    /** The tree a restricted player's client was sent, to read its commands and suggestions against. */
    public static CommandDispatcher<CommandSourceStack> nonOpDispatcher(ServerPlayer player) {
        return new CommandDispatcher<>(liveNonOpTree(player));
    }

    /** The same without allowedCommands, whose commands come from the server's own tree. */
    public static CommandDispatcher<CommandSourceStack> nonOpDispatcher() {
        return new CommandDispatcher<>(liveNonOpTree());
    }

    private static RootCommandNode<CommandSourceStack> liveNonOpTree() {
        return nonOpTree(Settings.get().chat.allowWhispers, Settings.get().skins.allowPlayerChoice, Hardcore.anyOn());
    }

    private static RootCommandNode<CommandSourceStack> liveNonOpTree(ServerPlayer player) {
        YoltConfig config = Settings.get();
        return nonOpTree(config.chat.allowWhispers, config.skins.allowPlayerChoice, Hardcore.anyOn(), config.allowedCommands,
                player.level().getServer().getCommands().getDispatcher(), player.createCommandSourceStack());
    }

    /** True when allowedCommands gives non-ops this root command. */
    private static boolean allowed(String commandRoot) {
        return !OWN.contains(commandRoot) && Settings.get().allowedCommands.contains(commandRoot);
    }

    /**
     * What an admin should hear about allowedCommands: a name the server has no command for, and a name YOLT decides
     * itself, both of which add nothing. Not refused, since a mod's command comes and goes with the mod. Said at startup
     * and on /yolt reload.
     */
    public static List<String> allowedCommandNotes(MinecraftServer server) {
        List<String> notes = new ArrayList<>();
        RootCommandNode<CommandSourceStack> root = server.getCommands().getDispatcher().getRoot();
        for (String name : Settings.get().allowedCommands) {
            if (OWN.contains(name)) {
                notes.add("allowedCommands: /" + name + " follows YOLT's own settings, so listing it changes nothing");
            } else if (root.getChild(name) == null) {
                notes.add("allowedCommands: the server has no /" + name + " command, so listing it does nothing");
            }
        }
        return notes;
    }

    /** True when a typed command must not run; a restricted player's /help gets its answer here. */
    public static boolean blocks(CommandSourceStack source, String command) {
        if (command.isEmpty() || !restricts(source)) {
            return false;
        }
        ServerPlayer player = source.getPlayer();
        String commandRoot = command.split(" ")[0];

        // /w, /msg and /tell are our own command (Whispers), so they run normally and read the signed message. With
        // whispers off the player's tree has none of them, so the answer the whisper itself would give comes from here.
        if (Whispers.isWhisper(commandRoot)) {
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

        // Read against the player's own tree too, which holds only the branches of it they may use
        if (allowed(commandRoot)) {
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
            player.connection.send(new ClientboundCommandsPacket(liveNonOpTree(player), inspector));
            return true;
        }
        return false;
    }
}
