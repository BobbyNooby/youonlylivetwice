package bobbynooby.dev.graves;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.feature.EndSpikeFeature;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The places in the End that vanilla builds again on its own, setting every block there with no event to stop it, so a
 * head in one is deleted. A new grave keeps out of them. Line numbers are from the 26.3 sources.
 */
final class EndRebuilds {
    // ServerLevel.END_SPAWN_POINT (ServerLevel.java:188), written out so this class loads without the game's
    // registries, which its unit test has none of. The arrival platform GameTest builds its box from vanilla's own.
    private static final BlockPos END_SPAWN = new BlockPos(100, 50, 0);
    // Every arrival through an end portal rebuilds the platform round the block under the End spawn point
    // (EndPortalBlock.java:72, 83): 5 by 5, its obsidian floor and the three layers of air over it
    // (EndPlatformFeature.java:29-39)
    private static final BlockPos PLATFORM = END_SPAWN.offset(0, -1, 0);
    private static final BoundingBox ARRIVAL_PLATFORM = BoundingBox.fromCorners(PLATFORM.offset(-2, -1, -2), PLATFORM.offset(2, 2, 2));
    // The fight is made at 0, 0 (ServerLevel.java:301), and its podium is there too: getPodiumLocation(BlockPos.ZERO)
    // (EnderDragonFight.java:79, 150). Written out like the spawn point; the exit portal GameTest uses vanilla's.
    private static final BlockPos PODIUM = BlockPos.ZERO;
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
    static final BlockPos BESIDE_EXIT_PORTAL = PODIUM.offset(0, 0, 4);
    /**
     * How far a spike's rebuild reaches out from its centre on x and z. The reach round its top is the wider, since no
     * spike's radius is more than 5 (EndSpikeFeature.java:195).
     */
    static final int SPIKE_REACH = SPIKE_TOP_REACH;

    private EndRebuilds() {}

    /** True when pos is in the End and vanilla rebuilds it, deleting whatever stands there. */
    static boolean wipes(ServerLevel level, BlockPos pos) {
        return level.dimension() == Level.END && (inFixedBox(pos) || spikeAround(level, pos).isPresent());
    }

    /** The boxes at the same place in every End: the arrival platform, the exit portal and the gateways. */
    static boolean inFixedBox(BlockPos pos) {
        return ARRIVAL_PLATFORM.isInside(pos) || nearPodium(pos) || GATEWAYS.stream().anyMatch(box -> box.isInside(pos));
    }

    /** The centre of the spike whose rebuild takes in pos, at pos's height, when pos is in the End and in one. */
    static Optional<BlockPos> spikeAround(ServerLevel level, BlockPos pos) {
        if (level.dimension() != Level.END) {
            return Optional.empty();
        }
        for (EndSpikeFeature.EndSpike spike : EndSpikeFeature.getSpikesForLevel(level)) {
            if (inSpikeBox(pos, spike.getCenterX(), spike.getCenterZ(), spike.getRadius(), spike.getHeight())) {
                return Optional.of(new BlockPos(spike.getCenterX(), pos.getY(), spike.getCenterZ()));
            }
        }
        return Optional.empty();
    }

    /** True when a respawn rebuilds pos as part of the spike with this centre, radius and top. */
    static boolean inSpikeBox(BlockPos pos, int centreX, int centreZ, int radius, int top) {
        long dx = pos.getX() - centreX;
        long dz = pos.getZ() - centreZ;
        int y = pos.getY();
        if (Math.abs(dx) <= SPIKE_TOP_REACH && Math.abs(dz) <= SPIKE_TOP_REACH && Math.abs(y - top) <= SPIKE_TOP_REACH) {
            return true;
        }
        return Math.abs(dx) <= radius && Math.abs(dz) <= radius && y <= top + SPIKE_CLEAR_ABOVE_TOP
                && (dx * dx + dz * dz <= (long) radius * radius + 1 || y > SPIKE_AIR_ABOVE);
    }

    // In longs: far out, a distance squared is past what an int holds, and wraps round to a small one
    private static boolean nearPodium(BlockPos pos) {
        long dx = pos.getX() - PODIUM.getX();
        long dz = pos.getZ() - PODIUM.getZ();
        return dx * dx + dz * dz < PODIUM_RADIUS * PODIUM_RADIUS;
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
