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
 * The server thread starts frames; the network thread only reads them, and moves incoming to a new one at the accept.
 */
public final class OffsetState {
    final AtomicReference<Frames> frames = new AtomicReference<>(Frames.NONE);
    // The dimension the frame started in
    volatile @Nullable ResourceKey<Level> dimension;
    // Real ChunkPos.pack values of the chunks the client holds, kept whatever the frame, so a new one can forget them
    final Set<Long> heldChunks = ConcurrentHashMap.newKeySet();
    // The real position the client was last told
    volatile @Nullable Vec3 lastPosition;
    // The walls the client was last sent, null for the whole world, so the recheck sends them only when they change
    volatile BorderWalls.@Nullable Square lastBorder;
    // Set by a teleport the client is sent, which can land it next to a wall, so its walls are checked at the tick's end
    volatile boolean borderRecheck;
    // The real spot the frame the next Respawn packet starts is anchored at
    volatile @Nullable Vec3 nextAnchor;
    // Packet types whose translation failed for this connection, and those whose notes failed, so each is logged once
    // for each kind of failure
    final Set<PacketType<?>> dropFailed = ConcurrentHashMap.newKeySet();
    final Set<PacketType<?>> noteFailed = ConcurrentHashMap.newKeySet();

    public OffsetState() {}
}
