package bobbynooby.dev.chat;

import bobbynooby.dev.YouOnlyLiveTwice;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.mixin.CommandNodeAccessor;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.RootCommandNode;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.MessageArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Our /w, which only reaches a player close enough to hear it, and the command tree non-ops see. */
public final class Whispers {
    private Whispers() {}

    public static void init() {
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> {
            // Single-player and LAN worlds keep vanilla /w; Settings.active() isn't known this early
            if (selection != Commands.CommandSelection.INTEGRATED) {
                replaceVanillaWhisper(dispatcher);
            }
        });
    }

    // The server checks a signed /w against its own tree, so the server's /w has to be the one non-ops get
    private static void replaceVanillaWhisper(CommandDispatcher<CommandSourceStack> dispatcher) {
        CommandNodeAccessor root = (CommandNodeAccessor) dispatcher.getRoot();
        root.yoltChildren().remove("w");
        root.yoltLiterals().remove("w");
        dispatcher.register(command());
    }

    public static RootCommandNode<CommandSourceStack> nonOpTree(boolean allowWhispers) {
        RootCommandNode<CommandSourceStack> root = new RootCommandNode<>();
        // help is answered by the restriction mixin, so its body never runs
        root.addChild(Commands.literal("help").executes(c -> 1).build());
        if (allowWhispers) {
            root.addChild(command().build());
        }
        return root;
    }

    // No suggestions on target, so tab-complete can't list who is online.
    // message must be a message argument: the client only signs those.
    private static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("w")
                .then(Commands.argument("target", StringArgumentType.word())
                        .then(Commands.argument("message", MessageArgument.message())
                                .executes(Whispers::run)));
    }

    private static int run(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer sender = context.getSource().getPlayerOrException();
        String target = StringArgumentType.getString(context, "target");
        // Read like vanilla's /w so a signed message stays in the player's chat chain; it goes out as our own text
        MessageArgument.resolveChatMessage(context, "message", message -> send(sender, target, message.signedContent()));
        return 1;
    }

    public static void send(ServerPlayer sender, String targetName, String message) {
        String senderName = sender.nameAndId().name();
        // The same reply whether or not it arrived, so it never tells who is online or nearby
        sender.sendSystemMessage(whisper("You whisper to " + targetName + ": " + message));

        ServerPlayer target = sender.level().getServer().getPlayerList().getPlayerByName(targetName);
        if (target != null && ChatRules.whisperArrives(ChatDelivery.listener(sender), ChatDelivery.listener(target),
                Settings.get().chat.radius)) {
            target.sendSystemMessage(whisper(senderName + " whispers to you: " + message));
        }
        YouOnlyLiveTwice.LOGGER.info("[whisper] {} -> {}: {}", senderName, targetName, message);
    }

    private static Component whisper(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
    }
}
