package bobbynooby.dev.coords;

import bobbynooby.dev.coords.CoordinateOffsets.Context;
import bobbynooby.dev.coords.CoordinateOffsets.Translator;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.common.CommonPacketTypes;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The table for what the server sends: each packet type's translator, run on the thread that sends it. */
final class Outgoing {
    // Filled at mod start, before any server thread runs, and only read after
    private static final Map<PacketType<?>, Translator<?>> TRANSLATORS = new HashMap<>();
    private static final Map<PacketType<?>, Coverage> COVERAGE = new HashMap<>();

    static {
        translate(GamePacketTypes.CLIENTBOUND_BUNDLE, Outgoing::bundle);
    }

    private Outgoing() {}

    static <P extends Packet<?>> void translate(PacketType<P> type, Translator<P> translator) {
        TRANSLATORS.put(type, translator);
        COVERAGE.put(type, Coverage.TRANSLATED);
    }

    static void noPositions(PacketType<?>... types) {
        for (PacketType<?> type : types) {
            COVERAGE.put(type, Coverage.NO_POSITIONS);
        }
    }

    static void elsewhere(PacketType<?>... types) {
        for (PacketType<?> type : types) {
            COVERAGE.put(type, Coverage.ELSEWHERE);
        }
    }

    static Map<PacketType<?>, Coverage> coverage() {
        return Collections.unmodifiableMap(COVERAGE);
    }

    /**
     * The packet to send in place of this one. A login or a respawn always starts a new frame. Otherwise, with no
     * outgoing offset the very packet goes on, whatever the incoming one still is. Either way the chunks the client holds
     * and the teleports it is sent are noted. A translator that throws drops the packet, apart from a disconnect, and the
     * exception never reaches the sender.
     */
    static @Nullable Packet<?> apply(OffsetState state, ServerGamePacketListenerImpl listener, Packet<?> packet) {
        Frames frames = state.frames.get();
        PacketType<?> type = packet.type();
        boolean asItIs = type != GamePacketTypes.CLIENTBOUND_LOGIN && type != GamePacketTypes.CLIENTBOUND_RESPAWN
                && frames.out().isZero();
        try {
            Packet<?> sent = asItIs ? packet : translateOne(packet, new Context(frames.out(), state, listener));
            ChunkRules.keepHeld(state, packet);
            OwnPosition.keepTeleport(state, packet);
            return sent;
        } catch (RuntimeException e) {
            // Nothing in a packet that goes as it is can leak, so one only the notes can't read goes on, to fail where it
            // would in vanilla
            if (asItIs) {
                CoordinateOffsets.unnoted(state, listener, packet, e);
                return packet;
            }
            if (type == CommonPacketTypes.CLIENTBOUND_DISCONNECT) {
                CoordinateOffsets.sentUntranslated(state, listener, packet, e);
                return packet;
            }
            CoordinateOffsets.dropped(state, listener, packet, e);
            return null;
        }
    }

    // Types with no translator go on as they are
    private static @Nullable Packet<?> translateOne(Packet<?> packet, Context context) {
        Translator<?> translator = TRANSLATORS.get(packet.type());
        return translator == null ? packet : run(translator, packet, context);
    }

    @SuppressWarnings("unchecked")
    private static <P> @Nullable Packet<?> run(Translator<P> translator, Packet<?> packet, Context context) {
        return translator.apply((P) packet, context);
    }

    // The client runs a bundle's packets in order, so the copy keeps it. Only a part whose translation fails is left out.
    @SuppressWarnings("unchecked")
    private static @Nullable Packet<?> bundle(ClientboundBundlePacket bundle, Context context) {
        List<Packet<? super ClientGamePacketListener>> parts = new ArrayList<>();
        boolean changed = false;
        for (Packet<? super ClientGamePacketListener> part : bundle.subPackets()) {
            Packet<?> translated;
            try {
                translated = translateOne(part, context);
            } catch (RuntimeException e) {
                CoordinateOffsets.dropped(context.state(), context.listener(), part, e);
                translated = null;
            }
            changed |= translated != part;
            if (translated != null) {
                parts.add((Packet<? super ClientGamePacketListener>) translated);
            }
        }
        if (!changed) {
            return bundle;
        }
        return parts.isEmpty() ? null : new ClientboundBundlePacket(parts);
    }
}
