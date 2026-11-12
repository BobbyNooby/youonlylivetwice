package bobbynooby.dev.coords;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.StandIn;
import bobbynooby.dev.core.Yolt;
import bobbynooby.dev.mixin.ServerCommonPacketListenerAccessor;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.common.CommonPacketTypes;
import net.minecraft.network.protocol.cookie.CookiePacketTypes;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.network.protocol.ping.PingPacketTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.function.Predicate;

/**
 * Gives each player's client its own shift of X and Z. Every game packet the server sends goes through outgoing, which
 * adds the player's offset to the positions in it, and every one the client sends goes through incoming, which takes it
 * off again. A frame, the offset a client sees, starts at each login.
 */
public final class CoordinateOffsets {
    // Admin view: a player it holds for when a frame starts sees real coordinates
    private static Predicate<ServerPlayer> realCoordinates = player -> false;

    private CoordinateOffsets() {}

    /** Builds the packet that goes on in place of one: the very same packet when nothing in it changes, null for none. */
    interface Translator<P> {
        @Nullable Packet<?> apply(P packet, Context context);
    }

    /** What a translator works with: the offset for its direction, the connection's state and its listener. */
    record Context(Offset offset, OffsetState state, ServerGamePacketListenerImpl listener) {}

    public static void init(Predicate<ServerPlayer> realCoordinates) {
        CoordinateOffsets.realCoordinates = realCoordinates;
        OwnPosition.register();
        ChunkRules.register();
        classifyTheRest();
    }

    /** True when frames that start now get an offset. Read only as a frame starts, so a change waits for a rejoin. */
    public static boolean on() {
        return Settings.on(c -> c.hide.coordinates);
    }

    /** The packet the client gets in place of the one the server sends: the same, a translated copy, or null for none. */
    public static @Nullable Packet<?> outgoing(Connection connection, Packet<?> packet) {
        if (!Settings.active() || !(connection.getPacketListener() instanceof ServerGamePacketListenerImpl listener)) {
            return packet;
        }
        return Outgoing.apply(state(connection), listener, packet);
    }

    /** The packet the server handles in place of the one the client sent: the same, a translated copy, or null for none. */
    public static @Nullable Packet<?> incoming(Connection connection, Packet<?> packet) {
        if (!Settings.active() || !(connection.getPacketListener() instanceof ServerGamePacketListenerImpl listener)) {
            return packet;
        }
        return Incoming.apply(state(connection), listener, packet);
    }

    /** The offset the player's client is sent positions in now. Zero with no frame, and for a stand-in, which has no client. */
    public static Offset offset(ServerPlayer player) {
        if (StandIn.is(player) || player.connection == null) {
            return Offset.ZERO;
        }
        Connection connection = connectionOf(player);
        return connection == null ? Offset.ZERO : state(connection).frames.get().out();
    }

    public static Map<PacketType<?>, Coverage> clientboundCoverage() {
        return Outgoing.coverage();
    }

    public static Map<PacketType<?>, Coverage> serverboundCoverage() {
        return Incoming.coverage();
    }

    static OffsetState state(Connection connection) {
        return ((OffsetHolder) connection).yoltOffsetState();
    }

    static @Nullable Connection connectionOf(ServerPlayer player) {
        return ((ServerCommonPacketListenerAccessor) player.connection).yoltConnection();
    }

    /** The offset a frame starting now gives the player, whose real spot is anchorX, anchorZ. */
    static Offset frameOffset(ServerPlayer player, double anchorX, double anchorZ) {
        if (!on() || StandIn.is(player) || realCoordinates.test(player)) {
            return Offset.ZERO;
        }
        return Offset.at(anchorX, anchorZ);
    }

    /**
     * A translation failed, so the packet goes nowhere: as it is, it carries real coordinates out, or the client's own
     * in. Logged once per packet type for each connection, so a broken translator can't flood the log.
     */
    static void dropped(OffsetState state, ServerGamePacketListenerImpl listener, Packet<?> packet, RuntimeException e) {
        if (state.failed.add(packet.type())) {
            Yolt.LOGGER.error("Could not translate the coordinates in a {} packet for {}, so it was dropped, as are later ones "
                    + "of that type that fail", packet.type(), listener.player.getPlainTextName(), e);
        }
    }

    // Packets the translators leave alone. The coverage test fails on any type that is in no table.
    private static void classifyTheRest() {
        Outgoing.noPositions(
                GamePacketTypes.CLIENTBOUND_ANIMATE,
                GamePacketTypes.CLIENTBOUND_AWARD_STATS,
                GamePacketTypes.CLIENTBOUND_BLOCK_CHANGED_ACK,
                GamePacketTypes.CLIENTBOUND_BOSS_EVENT,
                GamePacketTypes.CLIENTBOUND_BUNDLE_DELIMITER,
                GamePacketTypes.CLIENTBOUND_CHANGE_DIFFICULTY,
                GamePacketTypes.CLIENTBOUND_CHUNK_BATCH_FINISHED,
                GamePacketTypes.CLIENTBOUND_CHUNK_BATCH_START,
                CommonPacketTypes.CLIENTBOUND_CLEAR_DIALOG,
                GamePacketTypes.CLIENTBOUND_CLEAR_TITLES,
                GamePacketTypes.CLIENTBOUND_COMMANDS,
                GamePacketTypes.CLIENTBOUND_COMMAND_SUGGESTIONS,
                GamePacketTypes.CLIENTBOUND_CONTAINER_CLOSE,
                GamePacketTypes.CLIENTBOUND_CONTAINER_SET_DATA,
                CookiePacketTypes.CLIENTBOUND_COOKIE_REQUEST,
                GamePacketTypes.CLIENTBOUND_COOLDOWN,
                GamePacketTypes.CLIENTBOUND_CUSTOM_CHAT_COMPLETIONS,
                CommonPacketTypes.CLIENTBOUND_CUSTOM_PAYLOAD,
                CommonPacketTypes.CLIENTBOUND_CUSTOM_REPORT_DETAILS,
                GamePacketTypes.CLIENTBOUND_DEBUG_SAMPLE,
                GamePacketTypes.CLIENTBOUND_DELETE_CHAT,
                CommonPacketTypes.CLIENTBOUND_DISCONNECT,
                GamePacketTypes.CLIENTBOUND_DISGUISED_CHAT,
                GamePacketTypes.CLIENTBOUND_ENTITY_EVENT,
                GamePacketTypes.CLIENTBOUND_GAME_EVENT,
                GamePacketTypes.CLIENTBOUND_GAME_RULE_VALUES,
                GamePacketTypes.CLIENTBOUND_HURT_ANIMATION,
                CommonPacketTypes.CLIENTBOUND_KEEP_ALIVE,
                GamePacketTypes.CLIENTBOUND_LOW_DISK_SPACE_WARNING,
                GamePacketTypes.CLIENTBOUND_MAP_ITEM_DATA,
                GamePacketTypes.CLIENTBOUND_MOUNT_SCREEN_OPEN,
                GamePacketTypes.CLIENTBOUND_MOVE_ENTITY_POS,
                GamePacketTypes.CLIENTBOUND_MOVE_ENTITY_POS_ROT,
                GamePacketTypes.CLIENTBOUND_MOVE_ENTITY_ROT,
                GamePacketTypes.CLIENTBOUND_OPEN_BOOK,
                GamePacketTypes.CLIENTBOUND_OPEN_SCREEN,
                CommonPacketTypes.CLIENTBOUND_PING,
                GamePacketTypes.CLIENTBOUND_PLACE_GHOST_RECIPE,
                GamePacketTypes.CLIENTBOUND_PLAYER_ABILITIES,
                GamePacketTypes.CLIENTBOUND_PLAYER_CHAT,
                GamePacketTypes.CLIENTBOUND_PLAYER_COMBAT_END,
                GamePacketTypes.CLIENTBOUND_PLAYER_COMBAT_ENTER,
                GamePacketTypes.CLIENTBOUND_PLAYER_COMBAT_KILL,
                GamePacketTypes.CLIENTBOUND_PLAYER_INFO_REMOVE,
                GamePacketTypes.CLIENTBOUND_PLAYER_INFO_UPDATE,
                GamePacketTypes.CLIENTBOUND_PLAYER_ROTATION,
                PingPacketTypes.CLIENTBOUND_PONG_RESPONSE,
                CommonPacketTypes.CLIENTBOUND_POST_EFFECTS,
                GamePacketTypes.CLIENTBOUND_PROJECTILE_POWER,
                GamePacketTypes.CLIENTBOUND_RECIPE_BOOK_ADD,
                GamePacketTypes.CLIENTBOUND_RECIPE_BOOK_REMOVE,
                GamePacketTypes.CLIENTBOUND_RECIPE_BOOK_SETTINGS,
                GamePacketTypes.CLIENTBOUND_REMOVE_ENTITIES,
                GamePacketTypes.CLIENTBOUND_REMOVE_MOB_EFFECT,
                GamePacketTypes.CLIENTBOUND_RESET_SCORE,
                CommonPacketTypes.CLIENTBOUND_RESOURCE_PACK_POP,
                CommonPacketTypes.CLIENTBOUND_RESOURCE_PACK_PUSH,
                GamePacketTypes.CLIENTBOUND_ROTATE_HEAD,
                GamePacketTypes.CLIENTBOUND_SELECT_ADVANCEMENTS_TAB,
                GamePacketTypes.CLIENTBOUND_SERVER_DATA,
                CommonPacketTypes.CLIENTBOUND_SERVER_LINKS,
                GamePacketTypes.CLIENTBOUND_SET_ACTION_BAR_TEXT,
                GamePacketTypes.CLIENTBOUND_SET_BORDER_WARNING_DELAY,
                GamePacketTypes.CLIENTBOUND_SET_BORDER_WARNING_DISTANCE,
                GamePacketTypes.CLIENTBOUND_SET_CAMERA,
                GamePacketTypes.CLIENTBOUND_SET_CHUNK_CACHE_RADIUS,
                GamePacketTypes.CLIENTBOUND_SET_DISPLAY_OBJECTIVE,
                GamePacketTypes.CLIENTBOUND_SET_ENTITY_LINK,
                GamePacketTypes.CLIENTBOUND_SET_ENTITY_MOTION,
                GamePacketTypes.CLIENTBOUND_SET_EXPERIENCE,
                GamePacketTypes.CLIENTBOUND_SET_HEALTH,
                GamePacketTypes.CLIENTBOUND_SET_HELD_SLOT,
                GamePacketTypes.CLIENTBOUND_SET_OBJECTIVE,
                GamePacketTypes.CLIENTBOUND_SET_PASSENGERS,
                GamePacketTypes.CLIENTBOUND_SET_PLAYER_TEAM,
                GamePacketTypes.CLIENTBOUND_SET_SCORE,
                GamePacketTypes.CLIENTBOUND_SET_SIMULATION_DISTANCE,
                GamePacketTypes.CLIENTBOUND_SET_SUBTITLE_TEXT,
                GamePacketTypes.CLIENTBOUND_SET_TIME,
                GamePacketTypes.CLIENTBOUND_SET_TITLES_ANIMATION,
                GamePacketTypes.CLIENTBOUND_SET_TITLE_TEXT,
                CommonPacketTypes.CLIENTBOUND_SHOW_DIALOG,
                GamePacketTypes.CLIENTBOUND_SOUND_ENTITY,
                GamePacketTypes.CLIENTBOUND_START_CONFIGURATION,
                GamePacketTypes.CLIENTBOUND_STOP_SOUND,
                CommonPacketTypes.CLIENTBOUND_STORE_COOKIE,
                GamePacketTypes.CLIENTBOUND_SWING_ANIMATION,
                GamePacketTypes.CLIENTBOUND_SYSTEM_CHAT,
                GamePacketTypes.CLIENTBOUND_TAB_LIST,
                GamePacketTypes.CLIENTBOUND_TAKE_ITEM_ENTITY,
                GamePacketTypes.CLIENTBOUND_TEST_INSTANCE_BLOCK_STATUS,
                GamePacketTypes.CLIENTBOUND_TICKING_STATE,
                GamePacketTypes.CLIENTBOUND_TICKING_STEP,
                CommonPacketTypes.CLIENTBOUND_TRANSFER,
                GamePacketTypes.CLIENTBOUND_UPDATE_ADVANCEMENTS,
                GamePacketTypes.CLIENTBOUND_UPDATE_ATTRIBUTES,
                GamePacketTypes.CLIENTBOUND_UPDATE_MOB_EFFECT,
                GamePacketTypes.CLIENTBOUND_UPDATE_RECIPES,
                CommonPacketTypes.CLIENTBOUND_UPDATE_TAGS);
        Incoming.noPositions(
                GamePacketTypes.SERVERBOUND_ATTACK,
                GamePacketTypes.SERVERBOUND_BUNDLE_ITEM_SELECTED,
                GamePacketTypes.SERVERBOUND_CHANGE_DIFFICULTY,
                GamePacketTypes.SERVERBOUND_CHANGE_GAME_MODE,
                GamePacketTypes.SERVERBOUND_CHAT,
                GamePacketTypes.SERVERBOUND_CHAT_ACK,
                GamePacketTypes.SERVERBOUND_CHAT_SESSION_UPDATE,
                GamePacketTypes.SERVERBOUND_CHUNK_BATCH_RECEIVED,
                GamePacketTypes.SERVERBOUND_CLIENT_COMMAND,
                CommonPacketTypes.SERVERBOUND_CLIENT_INFORMATION,
                GamePacketTypes.SERVERBOUND_CLIENT_TICK_END,
                GamePacketTypes.SERVERBOUND_COMMAND_SUGGESTION,
                GamePacketTypes.SERVERBOUND_CONFIGURATION_ACKNOWLEDGED,
                GamePacketTypes.SERVERBOUND_CONTAINER_BUTTON_CLICK,
                GamePacketTypes.SERVERBOUND_CONTAINER_CLOSE,
                GamePacketTypes.SERVERBOUND_CONTAINER_SLOT_STATE_CHANGED,
                CookiePacketTypes.SERVERBOUND_COOKIE_RESPONSE,
                CommonPacketTypes.SERVERBOUND_CUSTOM_CLICK_ACTION,
                CommonPacketTypes.SERVERBOUND_CUSTOM_PAYLOAD,
                GamePacketTypes.SERVERBOUND_DEBUG_SUBSCRIPTION_REQUEST,
                GamePacketTypes.SERVERBOUND_EDIT_BOOK,
                GamePacketTypes.SERVERBOUND_ENTITY_TAG_QUERY,
                // Its location is relative to the entity
                GamePacketTypes.SERVERBOUND_INTERACT,
                CommonPacketTypes.SERVERBOUND_KEEP_ALIVE,
                GamePacketTypes.SERVERBOUND_LOCK_DIFFICULTY,
                GamePacketTypes.SERVERBOUND_MOVE_PLAYER_ROT,
                GamePacketTypes.SERVERBOUND_MOVE_PLAYER_STATUS_ONLY,
                GamePacketTypes.SERVERBOUND_PADDLE_BOAT,
                GamePacketTypes.SERVERBOUND_PICK_ITEM_FROM_ENTITY,
                PingPacketTypes.SERVERBOUND_PING_REQUEST,
                GamePacketTypes.SERVERBOUND_PLACE_RECIPE,
                GamePacketTypes.SERVERBOUND_PLAYER_ABILITIES,
                GamePacketTypes.SERVERBOUND_PLAYER_COMMAND,
                GamePacketTypes.SERVERBOUND_PLAYER_INPUT,
                GamePacketTypes.SERVERBOUND_PLAYER_LOADED,
                CommonPacketTypes.SERVERBOUND_PONG,
                GamePacketTypes.SERVERBOUND_PUNCH,
                GamePacketTypes.SERVERBOUND_RECIPE_BOOK_CHANGE_SETTINGS,
                GamePacketTypes.SERVERBOUND_RECIPE_BOOK_SEEN_RECIPE,
                GamePacketTypes.SERVERBOUND_RENAME_ITEM,
                CommonPacketTypes.SERVERBOUND_RESOURCE_PACK,
                GamePacketTypes.SERVERBOUND_SEEN_ADVANCEMENTS,
                GamePacketTypes.SERVERBOUND_SELECT_TRADE,
                GamePacketTypes.SERVERBOUND_SET_BEACON,
                GamePacketTypes.SERVERBOUND_SET_CARRIED_ITEM,
                GamePacketTypes.SERVERBOUND_SET_COMMAND_MINECART,
                GamePacketTypes.SERVERBOUND_SET_GAME_RULE,
                GamePacketTypes.SERVERBOUND_SPECTATOR_ACTION,
                GamePacketTypes.SERVERBOUND_TELEPORT_TO_ENTITY,
                GamePacketTypes.SERVERBOUND_USE_ITEM);
        // Coordinates typed in a command are read where the command uses them
        Incoming.elsewhere(GamePacketTypes.SERVERBOUND_CHAT_COMMAND, GamePacketTypes.SERVERBOUND_CHAT_COMMAND_SIGNED);
    }
}
