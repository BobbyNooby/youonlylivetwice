package bobbynooby.dev.graves;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import net.minecraft.world.level.levelgen.feature.EndSpikeFeature;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The places in the End that vanilla builds again on its own, setting every block there with no event to stop it, so a
 * head in one is deleted. A new grave keeps out of them. Line numbers are from the 26.3 sources.
 */
final class EndRebuilds {
    // Every arrival through an end portal rebuilds the platform round the block under the End spawn point
    // (EndPortalBlock.java:72, 83; ServerLevel.java:188): 5 by 5, its obsidian floor and the three layers of air over it
    // (EndPlatformFeature.java:29-39)
    private static final BlockPos PLATFORM = BlockPos.containing(Vec3.atBottomCenterOf(ServerLevel.END_SPAWN_POINT)).below();
    private static final BoundingBox ARRIVAL_PLATFORM = BoundingBox.fromCorners(PLATFORM.offset(-2, -1, -2), PLATFORM.offset(2, 2, 2));
    // The fight is made at 0, 0 (ServerLevel.java:301), and its podium is there too (EnderDragonFight.java:150)
    private static final BlockPos PODIUM = EnderDragonFight.getPodiumLocation(BlockPos.ZERO);
    // Each kill and each respawn sets every block closer than 3.5 to the exit portal's spot (EndPodiumFeature.java:31-35;
    // EnderDragonFight.java:408, 572), and the first kill puts the egg on top of the podium column
    // (EnderDragonFight.java:410-415). The portal's height is a spot the fight keeps to itself, so the whole column ring
    // is kept clear, which takes in the egg at any height.
    private static final double PODIUM_RADIUS = 3.5;
    // Each spike's square, built again on a respawn (DragonRespawnStage.java:65-66): obsidian up its circle from the
    // bottom of the world, air over the rest above y 65, all the way to 10 over its top (EndSpikeFeature.java:78-86)
    private static final int SPIKE_AIR_ABOVE = 65;
    private static final int SPIKE_CLEAR_ABOVE_TOP = 10;
    // Before that, a respawn removes every block within 10 of each spike's top (DragonRespawnStage.java:55-61)
    private static final int SPIKE_TOP_REACH = 10;
    // Each kill places one of 20 gateways 96 out at y 75 (EnderDragonFight.java:433-435), setting a 3 by 5 by 3 box round
    // it (EndGatewayFeature.java:35). Which ones are left is the fight's own, so all 20 count.
    private static final List<BoundingBox> GATEWAYS = gateways();

    /** The main island just south of the podium, outside every box: the far fallback for a grave in the End. */
    static final BlockPos BESIDE_EXIT_PORTAL = PODIUM.south(4);

    private EndRebuilds() {
    }

    /** True when pos is in the End and vanilla rebuilds it, deleting whatever stands there. */
    static boolean wipes(ServerLevel level, BlockPos pos) {
        if (level.dimension() != Level.END) {
            return false;
        }
        return ARRIVAL_PLATFORM.isInside(pos) || nearPodium(pos) || nearSpike(level, pos)
                || GATEWAYS.stream().anyMatch(box -> box.isInside(pos));
    }

    private static boolean nearPodium(BlockPos pos) {
        int dx = pos.getX() - PODIUM.getX();
        int dz = pos.getZ() - PODIUM.getZ();
        return dx * dx + dz * dz < PODIUM_RADIUS * PODIUM_RADIUS;
    }

    private static boolean nearSpike(ServerLevel level, BlockPos pos) {
        int y = pos.getY();
        for (EndSpikeFeature.EndSpike spike : EndSpikeFeature.getSpikesForLevel(level)) {
            int dx = pos.getX() - spike.getCenterX();
            int dz = pos.getZ() - spike.getCenterZ();
            int top = spike.getHeight();
            if (Math.abs(dx) <= SPIKE_TOP_REACH && Math.abs(dz) <= SPIKE_TOP_REACH && Math.abs(y - top) <= SPIKE_TOP_REACH) {
                return true;
            }
            int radius = spike.getRadius();
            boolean inCircle = dx * dx + dz * dz <= radius * radius + 1;
            if (Math.abs(dx) <= radius && Math.abs(dz) <= radius && y <= top + SPIKE_CLEAR_ABOVE_TOP
                    && (inCircle || y > SPIKE_AIR_ABOVE)) {
                return true;
            }
        }
        return false;
    }

    // The fight's own sums, so each box lands on the block its gateway does
    private static List<BoundingBox> gateways() {
        List<BoundingBox> boxes = new ArrayList<>();
        for (int gateway = 0; gateway < 20; gateway++) {
            int x = Mth.floor(96.0 * Math.cos(2.0 * (-Math.PI + (Math.PI / 20) * gateway)));
            int z = Mth.floor(96.0 * Math.sin(2.0 * (-Math.PI + (Math.PI / 20) * gateway)));
            BlockPos centre = new BlockPos(x, 75, z);
            boxes.add(BoundingBox.fromCorners(centre.offset(-1, -2, -1), centre.offset(1, 2, 1)));
        }
        return List.copyOf(boxes);
    }
}
