package bobbynooby.dev.mixin;

import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// A waypoint at a chunk, a private class, whose chunk the coordinate offset reads to build a shifted one
@Mixin(targets = "net.minecraft.world.waypoints.TrackedWaypoint$ChunkWaypoint")
public interface WaypointChunkAccessor {

    @Accessor("chunkPos")
    ChunkPos yoltChunkPos();
}
