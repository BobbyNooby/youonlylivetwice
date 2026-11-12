package bobbynooby.dev.coords;

import net.minecraft.network.protocol.PacketType;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One connection's offsets and what the client was told in them. It lives as long as the connection, across respawns.
 * The server thread starts frames; the network threads only read them.
 */
public final class OffsetState {
    final AtomicReference<Frames> frames = new AtomicReference<>(Frames.NONE);
    // The dimension the frame started in
    volatile @Nullable ResourceKey<Level> dimension;
    // Real ChunkPos.pack values of the chunks the client holds, kept whatever the frame, so a new one can forget them
    final Set<Long> heldChunks = ConcurrentHashMap.newKeySet();
    // The real position the client was last told
    volatile @Nullable Vec3 lastPosition;
    // Packet types whose translation failed for this connection, so each is logged once
    final Set<PacketType<?>> failed = ConcurrentHashMap.newKeySet();

    public OffsetState() {}
}
