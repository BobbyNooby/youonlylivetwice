package bobbynooby.dev.coords;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.StandIn;
import bobbynooby.dev.core.Yolt;
import bobbynooby.dev.mixin.ServerCommonPacketListenerAccessor;
import io.netty.buffer.Unpooled;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.HashedStack;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.common.CommonPacketTypes;
import net.minecraft.network.protocol.cookie.CookiePacketTypes;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.network.protocol.ping.PingPacketTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.debug.DebugSubscription;
import net.minecraft.util.debug.DebugSubscriptions;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Gives each player's client its own shift of X and Z. Every game packet the server sends goes through outgoing, which
 * adds the player's offset to the positions in it, and every one the client sends goes through incoming, which takes it
 * off again. A frame, the offset a client sees, starts at each login, respawn and dimension change.
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
        BlockRules.register();
        ItemRule.register();
        ParticleRule.register();
        EffectRules.register();
        EntityRules.register();
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

    /**
     * Anchors the frame the next Respawn packet on the player's connection starts at this real spot, where the player
     * arrives. The spot is passed in because during a respawn the connection still holds the player who died.
     */
    public static void anchorNextFrame(ServerPlayer player, Vec3 realPosition) {
        if (!Settings.active() || player.connection == null) {
            return;
        }
        Connection connection = connectionOf(player);
        if (connection != null) {
            state(connection).nextAnchor = realPosition;
        }
    }

    /** Anchors the frame a dimension change starts where the teleport takes the player. */
    public static void anchorDimensionChange(ServerPlayer player, TeleportTransition transition) {
        anchorNextFrame(player, PositionMoveRotation.calculateAbsolute(PositionMoveRotation.of(player),
                PositionMoveRotation.of(transition), transition.relatives()).position());
    }

    /** The offset the player's client is sent positions in now. Zero with no frame, and for a stand-in, which has no client. */
    public static Offset offset(ServerPlayer player) {
        if (StandIn.is(player) || player.connection == null) {
            return Offset.ZERO;
        }
        Connection connection = connectionOf(player);
        return connection == null ? Offset.ZERO : state(connection).frames.get().out();
    }

    /**
     * The debug subscriptions the player is sent values for. With an offset only the server's tick times, as the others'
     * values are full of positions and only ops get them, who have admin view for real coordinates.
     */
    public static Set<DebugSubscription<?>> debugSubscriptions(ServerPlayer player, Set<DebugSubscription<?>> vanilla) {
        if (offset(player).isZero()) {
            return vanilla;
        }
        return vanilla.contains(DebugSubscriptions.DEDICATED_SERVER_TICK_TIME)
                ? Set.of(DebugSubscriptions.DEDICATED_SERVER_TICK_TIME) : Set.of();
    }

    /**
     * The hash a clicked slot is matched by. The client hashes the items it was sent, which are shifted, so the hash
     * also matches the server's stack once shifted the way the client reads; otherwise every click on a compass would
     * have it sent again. The very hash with no offset to read by.
     */
    public static HashedStack clientHash(ServerPlayer player, HashedStack sent) {
        Connection connection = player.connection == null ? null : connectionOf(player);
        Offset in = connection == null ? Offset.ZERO : state(connection).frames.get().in();
        if (in.isZero()) {
            return sent;
        }
        return (stack, hasher) -> sent.matches(stack, hasher) || sent.matches(ItemRule.shift(stack, in), hasher);
    }

    /** Whether the player's client holds the chunk at this real position, by what it has been sent. */
    public static boolean holdsChunk(ServerPlayer player, ChunkPos chunk) {
        Connection connection = player.connection == null ? null : connectionOf(player);
        return connection != null && state(connection).heldChunks.contains(chunk.pack());
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

    /** The offset a frame starting now gives the player at this real spot. A random one when there is no spot to go by. */
    static Offset frameOffset(ServerPlayer player, @Nullable Vec3 anchor) {
        if (!on() || StandIn.is(player) || realCoordinates.test(player)) {
            return Offset.ZERO;
        }
        return anchor == null ? Offset.random(ThreadLocalRandom.current()) : Offset.at(anchor.x, anchor.z);
    }

    /** A copy of a packet with no constructor to copy it by, made by writing it out and reading it back. */
    static <P> P copy(StreamCodec<FriendlyByteBuf, P> codec, P packet) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            codec.encode(buffer, packet);
            return codec.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    /**
     * The list with each element mapped, in order: a copy made at the first element that changes, or the very list when
     * none does. Never changes the list it is given.
     */
    static <T> List<T> mapEach(List<T> list, UnaryOperator<T> map) {
        List<T> copy = null;
        for (int i = 0; i < list.size(); i++) {
            T element = list.get(i);
            T mapped = map.apply(element);
            if (mapped != element) {
                if (copy == null) {
                    copy = new ArrayList<>(list);
                }
                copy.set(i, mapped);
            }
        }
        return copy == null ? list : copy;
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

    /**
     * A packet that goes as it is couldn't be noted, so it goes on, but the chunks the client holds or the teleport that
     * ends a frame switch may now be wrong. Logged once per packet type for each connection.
     */
    static void unnoted(OffsetState state, ServerGamePacketListenerImpl listener, Packet<?> packet, RuntimeException e) {
        if (state.failed.add(packet.type())) {
            Yolt.LOGGER.error("Could not note what a {} packet for {} tells the client, so it was sent as it is, as are later "
                    + "ones of that type that fail", packet.type(), listener.player.getPlainTextName(), e);
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
        // A player with an offset has no debug subscriptions, so is sent none of these
        Outgoing.elsewhere(GamePacketTypes.CLIENTBOUND_DEBUG_BLOCK_VALUE, GamePacketTypes.CLIENTBOUND_DEBUG_CHUNK_VALUE,
                GamePacketTypes.CLIENTBOUND_DEBUG_ENTITY_VALUE, GamePacketTypes.CLIENTBOUND_DEBUG_EVENT);
    }
}
