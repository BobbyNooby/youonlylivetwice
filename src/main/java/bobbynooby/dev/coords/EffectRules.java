package bobbynooby.dev.coords;

import bobbynooby.dev.coords.CoordinateOffsets.Context;
import bobbynooby.dev.mixin.SoundPacketAccessor;
import bobbynooby.dev.mixin.WaypointChunkAccessor;
import bobbynooby.dev.mixin.WaypointVectorAccessor;
import net.minecraft.core.Vec3i;
import net.minecraft.core.particles.ExplosionParticleInfo;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundGameTestHighlightPosPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundTrackedWaypointPacket;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.util.random.Weighted;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.waypoints.TrackedWaypoint;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Sounds, particles, explosions, where a hit came from, the locator bar's waypoints and the test framework's marker. */
final class EffectRules {
    private EffectRules() {}

    static void register() {
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_SOUND, EffectRules::sound);
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_LEVEL_PARTICLES, EffectRules::particles);
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_EXPLODE, EffectRules::explosion);
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_DAMAGE_EVENT, EffectRules::damage);
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_WAYPOINT, EffectRules::waypoint);
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_GAME_TEST_HIGHLIGHT_POS, EffectRules::testHighlight);
    }

    // The packet keeps eighths of a block in ints and reads them out as floats, which are too coarse far out, so the
    // copy's ints are set from the original's. Whole blocks are whole eighths, so the shift is exact at any distance.
    private static Packet<?> sound(ClientboundSoundPacket packet, Context context) {
        ClientboundSoundPacket copy = new ClientboundSoundPacket(packet.getSound(), packet.getSource(), packet.getX(), packet.getY(),
                packet.getZ(), packet.getVolume(), packet.getPitch(), packet.getSeed());
        SoundPacketAccessor from = (SoundPacketAccessor) packet;
        SoundPacketAccessor to = (SoundPacketAccessor) copy;
        to.yoltSetX(context.offset().shiftFixedX(from.yoltX()));
        to.yoltSetZ(context.offset().shiftFixedZ(from.yoltZ()));
        return copy;
    }

    // The spreads and speeds are distances, not positions
    private static Packet<?> particles(ClientboundLevelParticlesPacket packet, Context context) {
        Offset offset = context.offset();
        return new ClientboundLevelParticlesPacket(ParticleRule.shift(packet.particle(), offset), packet.overrideLimiter(),
                packet.alwaysShow(), offset.shiftX(packet.x()), packet.y(), offset.shiftZ(packet.z()), packet.xDist(), packet.yDist(),
                packet.zDist(), packet.xMaxSpeed(), packet.yMaxSpeed(), packet.zMaxSpeed(), packet.count(), packet.randomizationType());
    }

    // The knockback is a push, not a position
    private static Packet<?> explosion(ClientboundExplodePacket packet, Context context) {
        Offset offset = context.offset();
        return new ClientboundExplodePacket(offset.shift(packet.center()), packet.radius(), packet.blockCount(),
                packet.playerKnockback(), ParticleRule.shift(packet.explosionParticle(), offset), packet.explosionSound(),
                blockParticles(packet.blockParticles(), offset), packet.playSound());
    }

    // The very list when no particle in it moves, with each weight kept
    private static WeightedList<ExplosionParticleInfo> blockParticles(WeightedList<ExplosionParticleInfo> particles, Offset offset) {
        List<Weighted<ExplosionParticleInfo>> entries = particles.unwrap();
        List<Weighted<ExplosionParticleInfo>> shifted = null;
        for (int i = 0; i < entries.size(); i++) {
            Weighted<ExplosionParticleInfo> entry = entries.get(i);
            ExplosionParticleInfo info = entry.value();
            ParticleOptions particle = ParticleRule.shift(info.particle(), offset);
            if (particle != info.particle()) {
                if (shifted == null) {
                    shifted = new ArrayList<>(entries);
                }
                shifted.set(i, new Weighted<>(new ExplosionParticleInfo(particle, info.scaling(), info.speed()), entry.weight()));
            }
        }
        return shifted == null ? particles : WeightedList.of(shifted);
    }

    // The spot a hit came from, such as a bed that blew up, which tilts the hurt camera towards it
    private static Packet<?> damage(ClientboundDamageEventPacket packet, Context context) {
        if (packet.sourcePosition().isEmpty()) {
            return packet;
        }
        Offset offset = context.offset();
        return new ClientboundDamageEventPacket(packet.entityId(), packet.sourceType(), packet.sourceCauseId(),
                packet.sourceDirectId(), packet.sourcePosition().map(position -> offset.shift(position)));
    }

    // A waypoint near the player is a block, a farther one a chunk; a far one is an angle, and an empty one has nothing
    private static Packet<?> waypoint(ClientboundTrackedWaypointPacket packet, Context context) {
        Offset offset = context.offset();
        TrackedWaypoint waypoint = packet.waypoint();
        TrackedWaypoint shifted;
        if (waypoint instanceof WaypointVectorAccessor vector) {
            Vec3i at = vector.yoltVector();
            shifted = TrackedWaypoint.setPosition(uuid(waypoint), waypoint.icon(), new Vec3i(offset.x() + at.getX(), at.getY(),
                    offset.z() + at.getZ()));
        } else if (waypoint instanceof WaypointChunkAccessor chunk) {
            shifted = TrackedWaypoint.setChunk(uuid(waypoint), waypoint.icon(), offset.shift(chunk.yoltChunkPos()));
        } else {
            return packet;
        }
        return new ClientboundTrackedWaypointPacket(packet.operation(), shifted);
    }

    // The server names every waypoint it tracks by the UUID of what it follows; a named one comes only from a client
    private static UUID uuid(TrackedWaypoint waypoint) {
        return waypoint.id().left().orElseThrow(() -> new IllegalStateException("A waypoint with no UUID: " + waypoint.id()));
    }

    // The relative spot is inside the test's structure
    private static Packet<?> testHighlight(ClientboundGameTestHighlightPosPacket packet, Context context) {
        return new ClientboundGameTestHighlightPosPacket(context.offset().shift(packet.absolutePos()), packet.relativePos());
    }
}
