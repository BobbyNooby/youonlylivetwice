package bobbynooby.dev.chat;

import bobbynooby.dev.YouOnlyLiveTwice;
import bobbynooby.dev.config.Settings;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.RootCommandNode;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.MessageArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** The commands a non-op sees, and /w, which only reaches a player close enough to hear it. */
public final class Whispers {
    private Whispers() {}

    // Only sent to clients; the restriction mixin runs these commands, so the executes bodies never run
    public static RootCommandNode<CommandSourceStack> nonOpTree(boolean allowWhispers) {
        RootCommandNode<CommandSourceStack> root = new RootCommandNode<>();
        root.addChild(Commands.literal("help").executes(c -> 1).build());
        if (allowWhispers) {
            // No suggestions on target, so tab-complete can't list who is online.
            // message must be a message argument: the client only signs those, and the server
            // checks /w against vanilla's own /w, which expects a signed message.
            root.addChild(Commands.literal("w")
                    .then(Commands.argument("target", StringArgumentType.word())
                            .then(Commands.argument("message", MessageArgument.message()).executes(c -> 1)))
                    .build());
        }
        return root;
    }

    /** Runs "w <target> <message>" for a non-op. Anything missing a part is ignored, like other blocked commands. */
    public static void runCommand(ServerPlayer sender, String command) {
        String[] parts = command.split(" ", 3);
        if (parts.length == 3 && !parts[1].isEmpty() && !parts[2].isBlank()) {
            send(sender, parts[1], parts[2]);
        }
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
