package bobbynooby.dev.chat;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.visibility.AdminView;
import bobbynooby.dev.visibility.PlayerVisibility;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
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

    private static boolean onChat(PlayerChatMessage message, ServerPlayer sender, ChatType.Bound chatType) {
        if (!Settings.active() || Settings.get().chat.mode == YoltConfig.ChatMode.GLOBAL) {
            return true;
        }
        // Fabric also fires this for the /say and /me that onCommandMessage let through: op announcements, which reach everyone
        if (!chatType.chatType().is(ChatType.CHAT)) {
            return true;
        }
        deliver(sender.level().getServer(), message, sender::shouldFilterMessageTo, sender, chatType, chatRecipients(sender));
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

    // Op and console announcements go out through vanilla's broadcast; ChatFallbackMixin turns them to text where needed
    private static boolean onCommandMessage(PlayerChatMessage message, CommandSourceStack source, ChatType.Bound chatType) {
        if (!Settings.active()) {
            return true;
        }
        ServerPlayer player = source.getPlayer();
        // Only a gamemaster's /me is an announcement; anyone else's is chat, heard like their chat.
        // The source's permissions, so an op's "execute as" stays an announcement.
        if (player == null || Commands.LEVEL_GAMEMASTERS.check(source.permissions())
                || Settings.get().chat.mode == YoltConfig.ChatMode.GLOBAL) {
            return true;
        }
        deliver(source.getServer(), message, source::shouldFilterMessageTo, player, chatType, chatRecipients(player));
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
            recipient.sendChatMessage(outgoing, filtered, chatType);
            fullyFiltered |= filtered && message.isFullyFiltered();
        }
        if (fullyFiltered && sender != null) {
            sender.sendSystemMessage(PlayerList.CHAT_FILTERED_FULL);
        }
    }

    /**
     * A client with no entry for the sender throws its player chat away, so it gets the same line as plain text.
     * Returns false, sending nothing, when the normal send can go ahead.
     */
    public static boolean sendAsTextIfSenderUnknown(ServerPlayer recipient, OutgoingChatMessage message, boolean filtered,
                                                    ChatType.Bound chatType) {
        // A disguised message has no sender for the client to check
        if (!(message instanceof OutgoingChatMessage.Player player)
                || PlayerVisibility.known(recipient).contains(player.message().sender())) {
            return false;
        }
        PlayerChatMessage shown = player.message().filter(filtered);
        // Filtered words are blanked the way the client would have shown them
        Component content = shown.filterMask().isEmpty()
                ? shown.decoratedContent() : shown.filterMask().applyWithFormatting(shown.signedContent());
        // sendChatMessage would check the same setting; sendSystemMessage doesn't
        if (recipient.getChatVisibility() == ChatVisiblity.FULL && content != null) {
            recipient.sendSystemMessage(chatType.decorate(content));
        }
        return true;
    }

    static ChatRules.Listener listener(ServerPlayer player) {
        return new ChatRules.Listener(player.getUUID(), player.level().dimension().identifier().toString(),
                player.getX(), player.getY(), player.getZ(), AdminView.isOn(player));
    }
}
