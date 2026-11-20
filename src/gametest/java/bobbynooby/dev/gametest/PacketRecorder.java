package bobbynooby.dev.gametest;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import net.minecraft.network.Connection;
import net.minecraft.network.HandlerNames;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Records what a real client is sent, from inside its own connection: every frame's bytes as they arrive, decompressed
 * but before they are decoded, and every packet once it is decoded. A client test arms a session before its client
 * connects, and the session hooks into every client connection opened while it is armed, the server list's pings
 * included. Nothing a server-side check could miss gets past it: what the client's network stack holds is what it got.
 * <p>
 * The bytes are searched for needles, such as a hidden player's name or UUID, so a leak is found whatever packet, field
 * or encoding it hides in. Each decoded packet goes to the session's consumer, such as the coordinate leak scanner.
 * Both run on the connection's network thread; the session's results are read once the client is done with them.
 */
public final class PacketRecorder {
    private static final String RAW = "yolt_test_raw_recorder";
    private static final String DECODED = "yolt_test_packet_recorder";
    private static volatile @Nullable Session armed;

    private PacketRecorder() {}

    /** Records every client connection opened from now until {@link #disarm()}. */
    public static Session arm(Session session) {
        armed = session;
        return session;
    }

    public static void disarm() {
        armed = null;
    }

    /** From the connection's channelActive: only a client's end of a connection is recorded, never the server's. */
    public static void attach(Connection connection, Channel channel) {
        Session session = armed;
        if (session == null || connection.getReceiving() != PacketFlow.CLIENTBOUND) {
            return;
        }
        ChannelPipeline pipeline = channel.pipeline();
        // Right after the frame splitter, so each message is one whole frame. Decompression, once the login turns it on,
        // goes in straight after the splitter too, which puts it ahead of this.
        if (pipeline.get(HandlerNames.SPLITTER) == null || pipeline.get(HandlerNames.PACKET_HANDLER) == null) {
            session.problem("a client connection had no " + HandlerNames.SPLITTER + " or " + HandlerNames.PACKET_HANDLER
                    + ": " + pipeline.names());
            return;
        }
        pipeline.addAfter(HandlerNames.SPLITTER, RAW, new RawHandler(session));
        pipeline.addBefore(HandlerNames.PACKET_HANDLER, DECODED, new DecodedHandler(session));
        session.connected();
    }

    /** The bytes of a UUID as every binary encoding of one holds them: the high long, then the low, both big-endian. */
    public static byte[] uuidBytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    /** A player's name and UUID as needles, in each form a packet could carry them. */
    public static Map<String, byte[]> playerNeedles(String name, UUID id) {
        Map<String, byte[]> needles = new LinkedHashMap<>();
        needles.put(name + " (name)", name.getBytes(StandardCharsets.UTF_8));
        needles.put(name + " (uuid bytes)", uuidBytes(id));
        needles.put(name + " (uuid text)", id.toString().getBytes(StandardCharsets.UTF_8));
        return needles;
    }

    /** What one armed stretch recorded. Thread-safe: the network threads write it, the test thread reads it. */
    public static final class Session {
        private final Map<String, byte[]> needles;
        private final @Nullable Consumer<Packet<?>> packets;
        private final Map<String, Integer> hits = new TreeMap<>();
        private final Map<String, Integer> types = new TreeMap<>();
        private final List<String> problems = new ArrayList<>();
        private int connections;
        private long frames;
        private long bytes;

        /**
         * @param needles byte strings to count in every frame, by a name for each
         * @param packets what to do with each decoded packet, or null for nothing
         */
        public Session(Map<String, byte[]> needles, @Nullable Consumer<Packet<?>> packets) {
            this.needles = Map.copyOf(needles);
            this.packets = packets;
        }

        /** How many frames held each needle, by its name. A needle never found isn't listed. */
        public synchronized Map<String, Integer> hits() {
            return Map.copyOf(hits);
        }

        public synchronized int hits(String needle) {
            return hits.getOrDefault(needle, 0);
        }

        /** How many of each packet type were decoded, by the type's id. */
        public synchronized Map<String, Integer> types() {
            return Map.copyOf(types);
        }

        public synchronized int connections() {
            return connections;
        }

        public synchronized long frames() {
            return frames;
        }

        public synchronized long bytes() {
            return bytes;
        }

        /** Anything that kept the recording from being whole: a connection it couldn't hook into, a consumer that threw. */
        public synchronized List<String> problems() {
            return List.copyOf(problems);
        }

        /** Adds this session's needles to a failure message, so a failed check says what was and wasn't found. */
        public synchronized String summary() {
            return connections + " connections, " + frames + " frames, " + bytes + " bytes, needles found " + hits
                    + ", problems " + problems;
        }

        private synchronized void connected() {
            connections++;
        }

        private synchronized void problem(String problem) {
            problems.add(problem);
        }

        private void frame(ByteBuf frame) {
            byte[] data = new byte[frame.readableBytes()];
            frame.getBytes(frame.readerIndex(), data);
            List<String> found = new ArrayList<>();
            for (Map.Entry<String, byte[]> needle : needles.entrySet()) {
                if (contains(data, needle.getValue())) {
                    found.add(needle.getKey());
                }
            }
            synchronized (this) {
                frames++;
                bytes += data.length;
                for (String name : found) {
                    hits.merge(name, 1, Integer::sum);
                }
            }
        }

        private void packet(Packet<?> packet) {
            synchronized (this) {
                types.merge(packet.type().toString(), 1, Integer::sum);
            }
            if (packets == null) {
                return;
            }
            try {
                // The network thread is the only caller, so the consumer needs no lock of its own
                packets.accept(packet);
            } catch (RuntimeException | Error e) {
                problem("reading " + packet.type() + " threw " + e);
            }
        }

        private static boolean contains(byte[] data, byte[] needle) {
            outer:
            for (int i = 0; i <= data.length - needle.length; i++) {
                for (int j = 0; j < needle.length; j++) {
                    if (data[i + j] != needle[j]) {
                        continue outer;
                    }
                }
                return true;
            }
            return false;
        }
    }

    // Each message here is one frame, decompressed; it is only read, never consumed, and goes on as it came
    private static final class RawHandler extends ChannelInboundHandlerAdapter {
        private final Session session;

        private RawHandler(Session session) {
            this.session = session;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object message) {
            if (message instanceof ByteBuf frame) {
                session.frame(frame);
            }
            ctx.fireChannelRead(message);
        }
    }

    private static final class DecodedHandler extends ChannelInboundHandlerAdapter {
        private final Session session;

        private DecodedHandler(Session session) {
            this.session = session;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object message) {
            if (message instanceof Packet<?> packet) {
                session.packet(packet);
            }
            ctx.fireChannelRead(message);
        }
    }
}
