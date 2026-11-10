package bobbynooby.dev.chat;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.mixin.CommandNodeAccessor;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.MessageArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Our /w, which only reaches a player close enough to hear it, or anyone in global chat mode. */
public final class Whispers {
    static final String TURNED_OFF = "Whispers are turned off.";

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

    // No suggestions on target, so tab-complete can't list who is online.
    // message must be a message argument: the client only signs those.
    static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("w")
                .then(Commands.argument("target", StringArgumentType.word())
                        .then(Commands.argument("message", MessageArgument.message())
                                .executes(Whispers::run)));
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
        if (target != null && ChatRules.whisperArrives(ChatDelivery.listener(sender), ChatDelivery.listener(target),
                chat.mode, chat.radius)) {
            target.sendSystemMessage(whisper(senderName + " whispers to you: " + message));
        }
    }

    private static Component whisper(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
    }
}
