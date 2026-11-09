package bobbynooby.dev.chat;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.visibility.AdminView;
import bobbynooby.dev.visibility.PlayerVisibility;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.ChatVisiblity;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/** Sends chat, game messages and command announcements to the players allowed to get them. */
public final class ChatDelivery {
    private ChatDelivery() {}

    public static void init() {
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(ChatDelivery::onChat);
        ServerMessageEvents.ALLOW_GAME_MESSAGE.register(ChatDelivery::onGameMessage);
        ServerMessageEvents.ALLOW_COMMAND_MESSAGE.register(ChatDelivery::onCommandMessage);
    }

    public static Set<ServerPlayer> chatRecipients(ServerPlayer sender) {
        YoltConfig.ChatSection chat = Settings.get().chat;
        List<ServerPlayer> online = sender.level().getServer().getPlayerList().getPlayers();
        List<ChatRules.Listener> listeners = new ArrayList<>(online.size());
        for (ServerPlayer player : online) {
            listeners.add(listener(player));
        }
        Set<UUID> heard = ChatRules.chatRecipients(listener(sender), listeners, chat.mode, chat.radius);
        Set<ServerPlayer> recipients = new LinkedHashSet<>();
        for (ServerPlayer player : online) {
            if (heard.contains(player.getUUID())) {
                recipients.add(player);
            }
        }
        return recipients;
    }

    public static Set<ServerPlayer> gameMessageRecipients(MinecraftServer server) {
        boolean hidden = Settings.get().hide.gameMessages;
        Set<ServerPlayer> recipients = new LinkedHashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!hidden || AdminView.isOn(player)) {
                recipients.add(player);
            }
        }
        return recipients;
    }

    public static Set<ServerPlayer> commandRecipients(MinecraftServer server) {
        return new LinkedHashSet<>(server.getPlayerList().getPlayers());
    }

    private static boolean onChat(PlayerChatMessage message, ServerPlayer sender, ChatType.Bound chatType) {
        if (!Settings.active()) {
            return true;
        }
        // Fabric fires this for a player's /say and /me too; those are announcements that reach everyone
        if (!chatType.chatType().is(ChatType.CHAT)) {
            return true;
        }
        MinecraftServer server = sender.level().getServer();
        Set<ServerPlayer> recipients;
        if (Settings.get().chat.mode == YoltConfig.ChatMode.GLOBAL) {
            // Global chat still has to reach clients that don't know the sender
            if (!Settings.get().hide.distantPlayers) {
                return true;
            }
            recipients = new LinkedHashSet<>(server.getPlayerList().getPlayers());
        } else {
            recipients = chatRecipients(sender);
        }
        deliver(server, message, sender::shouldFilterMessageTo, sender, chatType, recipients);
        return false;
    }

    private static boolean onGameMessage(MinecraftServer server, Component message, boolean overlay) {
        if (!Settings.active() || overlay || !Settings.get().hide.gameMessages) {
            return true;
        }
        // The console still logs it, like vanilla's broadcast
        server.sendSystemMessage(message);
        for (ServerPlayer player : gameMessageRecipients(server)) {
            player.sendSystemMessage(message);
        }
        return false;
    }

    private static boolean onCommandMessage(PlayerChatMessage message, CommandSourceStack source, ChatType.Bound chatType) {
        if (!Settings.active() || !Settings.get().hide.distantPlayers) {
            return true;
        }
        MinecraftServer server = source.getServer();
        deliver(server, message, source::shouldFilterMessageTo, source.getPlayer(), chatType, commandRecipients(server));
        return false;
    }

    // Vanilla's broadcast steps, limited to the given players
    private static void deliver(MinecraftServer server, PlayerChatMessage message, Predicate<ServerPlayer> isFiltered,
                                @Nullable ServerPlayer sender, ChatType.Bound chatType, Set<ServerPlayer> recipients) {
        boolean trusted = message.hasSignature() && !message.hasExpiredServer(Instant.now());
        server.logChatMessage(message.decoratedContent(), chatType, trusted ? null : "Not Secure");
        OutgoingChatMessage outgoing = OutgoingChatMessage.create(message);
        boolean fullyFiltered = false;
        for (ServerPlayer recipient : recipients) {
            boolean filtered = isFiltered.test(recipient);
            if (message.isSystem() || PlayerVisibility.known(recipient).contains(message.sender())) {
                recipient.sendChatMessage(outgoing, filtered, chatType);
            } else {
                sendAsText(recipient, message.filter(filtered), chatType);
            }
            fullyFiltered |= filtered && message.isFullyFiltered();
        }
        if (fullyFiltered && sender != null) {
            sender.sendSystemMessage(PlayerList.CHAT_FILTERED_FULL);
        }
    }

    // A client with no entry for the sender throws its player chat away, so it gets the same line as plain text
    private static void sendAsText(ServerPlayer recipient, PlayerChatMessage message, ChatType.Bound chatType) {
        // Filtered words are blanked the way the client would have shown them
        Component content = message.filterMask().isEmpty()
                ? message.decoratedContent() : message.filterMask().applyWithFormatting(message.signedContent());
        if (recipient.getChatVisibility() == ChatVisiblity.FULL && content != null) {
            recipient.sendSystemMessage(chatType.decorate(content));
        }
    }

    static ChatRules.Listener listener(ServerPlayer player) {
        return new ChatRules.Listener(player.getUUID(), player.level().dimension().identifier().toString(),
                player.getX(), player.getY(), player.getZ(), AdminView.isOn(player));
    }
}
