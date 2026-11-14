package bobbynooby.dev.chat;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.core.Yolt;
import bobbynooby.dev.mixin.CommandNodeAccessor;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.MessageArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Our /w, /msg and /tell, one whisper that only reaches a player close enough to hear it, or anyone in global chat mode.
 * Sources that aren't players keep vanilla's /msg.
 */
public final class Whispers {
    static final String TURNED_OFF = "Whispers are turned off.";
    /** The commands that whisper. Vanilla's /msg and its two redirects are all replaced, so none skips the range. */
    public static final List<String> NAMES = List.of("w", "msg", "tell");

    private Whispers() {}

    public static void init() {
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> {
            // Single-player and LAN worlds keep vanilla /w; Settings.active() isn't known this early
            if (selection != Commands.CommandSelection.INTEGRATED) {
                replaceVanillaWhisper(dispatcher);
            }
        });
    }

    static boolean isWhisper(String commandRoot) {
        return NAMES.contains(commandRoot);
    }

    // The server checks a signed /w against its own tree, so the server's /w has to be the one non-ops get. Only players
    // whisper: the console, command blocks and functions keep vanilla's message, selectors and all.
    private static void replaceVanillaWhisper(CommandDispatcher<CommandSourceStack> dispatcher) {
        CommandNode<CommandSourceStack> vanillaMsg = dispatcher.getRoot().getChild("msg");
        CommandNode<CommandSourceStack> vanillaMessage = vanillaMsg == null || vanillaMsg.getChild("targets") == null
                ? null : vanillaMsg.getChild("targets").getChild("message");
        CommandNodeAccessor root = (CommandNodeAccessor) dispatcher.getRoot();
        for (String name : NAMES) {
            root.yoltChildren().remove(name);
            root.yoltLiterals().remove(name);
        }
        for (String name : NAMES) {
            dispatcher.register(serverCommand(name, vanillaMessage));
        }
    }

    // No suggestions on target, so tab-complete can't list who is online.
    // message must be a message argument: the client only signs those.
    static LiteralArgumentBuilder<CommandSourceStack> command(String name) {
        return Commands.literal(name).then(whisper());
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> whisper() {
        return Commands.argument("target", StringArgumentType.word())
                .then(Commands.argument("message", MessageArgument.message())
                        .executes(Whispers::run));
    }

    // Two branches that never meet: a player gets the whisper (and is sent only that branch), anything else gets
    // vanilla's targets with vanilla's own message node, which runs vanilla's /msg as it was
    private static LiteralArgumentBuilder<CommandSourceStack> serverCommand(String name,
                                                                          @Nullable CommandNode<CommandSourceStack> vanillaMessage) {
        LiteralArgumentBuilder<CommandSourceStack> command = Commands.literal(name).then(whisper().requires(CommandSourceStack::isPlayer));
        if (vanillaMessage != null) {
            command.then(Commands.argument("targets", EntityArgument.players())
                    .requires(source -> !source.isPlayer())
                    // Suggestions don't check requires, so a player asking about this branch is told no names
                    .suggests((context, builder) -> context.getSource().isPlayer()
                            ? builder.buildFuture() : EntityArgument.players().listSuggestions(context, builder))
                    .then(vanillaMessage));
        }
        return command;
    }

    private static int run(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer sender = context.getSource().getPlayerOrException();
        // The node stays even with whispers off, so the client's tree and the server's signing tree still match.
        // With restrictCommands off nothing else stops a non-op, so the refusal is here. Only a gamemaster op is exempt,
        // going by the player's own permissions, so a sign an op wrote can't whisper for a non-op.
        if (!Settings.get().chat.allowWhispers && !Commands.LEVEL_GAMEMASTERS.check(sender.permissions())) {
            context.getSource().sendFailure(Component.literal(TURNED_OFF));
            return 0;
        }
        String target = StringArgumentType.getString(context, "target");
        // "w  hi" reads as an empty name. Refused here, not in the parse: the client has signed the message, and a parse
        // error would leave that signature unmatched and break the sender's chat.
        if (target.isEmpty()) {
            context.getSource().sendFailure(Component.literal("Name a player to whisper to."));
            return 0;
        }
        // Read like vanilla's /w so a signed message stays in the player's chat chain; it goes out as our own text
        MessageArgument.resolveChatMessage(context, "message", message -> send(sender, target, message.signedContent()));
        return 1;
    }

    private static void send(ServerPlayer sender, String targetName, String message) {
        String senderName = sender.nameAndId().name();
        // The same reply whether or not it arrived, so it never tells who is online or nearby
        sender.sendSystemMessage(whisper("You whisper to " + targetName + ": " + message));

        ServerPlayer target = sender.level().getServer().getPlayerList().getPlayerByName(targetName);
        YoltConfig.ChatSection chat = Settings.get().chat;
        boolean heard = target != null && ChatRules.whisperArrives(ChatDelivery.listener(sender), ChatDelivery.listener(target),
                chat.mode, ChatRules.whisperRange(chat.radius, chat.whisperRadius));
        if (heard) {
            target.sendSystemMessage(whisper(senderName + " whispers to you: " + message));
        }
        // Only the server log, so admins can look into abuse; no player is told whether it arrived
        Yolt.LOGGER.info("Whisper from {} to {} ({}): {}", senderName, targetName, heard ? "heard" : "not heard", message);
    }

    private static Component whisper(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
    }
}
