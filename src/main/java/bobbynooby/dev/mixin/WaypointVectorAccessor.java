package bobbynooby.dev.mixin;

import net.minecraft.core.Vec3i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// A waypoint at a block, a private class, whose spot the coordinate offset reads to build a shifted one
@Mixin(targets = "net.minecraft.world.waypoints.TrackedWaypoint$Vec3iWaypoint")
public interface WaypointVectorAccessor {

    @Accessor("vector")
    Vec3i yoltVector();
}
