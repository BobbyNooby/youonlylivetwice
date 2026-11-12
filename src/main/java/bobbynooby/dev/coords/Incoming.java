package bobbynooby.dev.coords;

import bobbynooby.dev.coords.CoordinateOffsets.Context;
import bobbynooby.dev.coords.CoordinateOffsets.Translator;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * The table for what the client sends. It runs on the network thread, before the server sees the packet, so its
 * translators only read the frames, never the world.
 */
final class Incoming {
    // Filled at mod start, before any network thread runs, and only read after
    private static final Map<PacketType<?>, Translator<?>> TRANSLATORS = new HashMap<>();
    private static final Map<PacketType<?>, Coverage> COVERAGE = new HashMap<>();

    private Incoming() {}

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
     * The packet the server handles in place of this one, translated with the incoming offset. With both offsets zero it
     * is the very packet. A translator that throws drops it, since as sent it would be read in the wrong frame.
     */
    static @Nullable Packet<?> apply(OffsetState state, ServerGamePacketListenerImpl listener, Packet<?> packet) {
        try {
            Frames frames = state.frames.get();
            if (frames.out().isZero() && frames.in().isZero()) {
                return packet;
            }
            Translator<?> translator = TRANSLATORS.get(packet.type());
            return translator == null ? packet : run(translator, packet, new Context(frames.in(), state, listener));
        } catch (RuntimeException e) {
            CoordinateOffsets.dropped(state, listener, packet, e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static <P extends Packet<?>> @Nullable Packet<?> run(Translator<P> translator, Packet<?> packet, Context context) {
        return translator.apply((P) packet, context);
    }
}
