package bobbynooby.dev.coords;

import bobbynooby.dev.coords.CoordinateOffsets.Context;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveMinecartPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.PositionPath;
import net.minecraft.world.entity.PositionStep;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.vehicle.minecart.NewMinecartBehavior;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Entities: where they spawn and move to, and the values in their entity data that hold a block, an item or a particle,
 * such as a sleeper's bed or the compass in an item frame. Steps between moves are deltas, which whole chunks don't change.
 */
public final class EntityRules {
    // Filled at mod start, before any server thread runs, and only read after
    private static final Map<EntityDataSerializer<?>, Coverage> SERIALIZERS = new HashMap<>();
    private static final Map<EntityDataSerializer<?>, BiFunction<?, Offset, ?>> SHIFTS = new HashMap<>();

    private EntityRules() {}

    static void register() {
        classifySerializers();
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_ADD_ENTITY, EntityRules::addEntity);
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_TELEPORT_ENTITY, EntityRules::teleportEntity);
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_ENTITY_POSITION_SYNC, EntityRules::positionSync);
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_MOVE_MINECART_ALONG_TRACK, EntityRules::minecartSteps);
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_SET_ENTITY_DATA, EntityRules::entityData);
    }

    /** Every entity data serializer: TRANSLATED when its values can hold a position, which this rule shifts. */
    public static Map<EntityDataSerializer<?>, Coverage> serializerCoverage() {
        return Collections.unmodifiableMap(SERIALIZERS);
    }

    // From each serializer's values in 26.3. A display's translation and scale are relative to it.
    private static void classifySerializers() {
        translated(EntityDataSerializers.BLOCK_POS, (pos, offset) -> offset.shift(pos));
        translated(EntityDataSerializers.OPTIONAL_BLOCK_POS, EntityRules::optionalBlockPos);
        translated(EntityDataSerializers.OPTIONAL_GLOBAL_POS, EntityRules::optionalGlobalPos);
        translated(EntityDataSerializers.ITEM_STACK, ItemRule::shift);
        translated(EntityDataSerializers.PARTICLE, ParticleRule::shift);
        translated(EntityDataSerializers.PARTICLES, EntityRules::particles);
        noPositions(
                EntityDataSerializers.BYTE,
                EntityDataSerializers.INT,
                EntityDataSerializers.LONG,
                EntityDataSerializers.FLOAT,
                EntityDataSerializers.STRING,
                // text, which is left as it is, coordinates typed into a name included
                EntityDataSerializers.COMPONENT,
                EntityDataSerializers.OPTIONAL_COMPONENT,
                EntityDataSerializers.BLOCK_STATE,
                EntityDataSerializers.OPTIONAL_BLOCK_STATE,
                EntityDataSerializers.BOOLEAN,
                EntityDataSerializers.ROTATIONS,
                EntityDataSerializers.DIRECTION,
                EntityDataSerializers.OPTIONAL_LIVING_ENTITY_REFERENCE,
                EntityDataSerializers.VILLAGER_DATA,
                EntityDataSerializers.OPTIONAL_UNSIGNED_INT,
                EntityDataSerializers.POSE,
                EntityDataSerializers.CAT_VARIANT,
                EntityDataSerializers.CAT_SOUND_VARIANT,
                EntityDataSerializers.CHICKEN_VARIANT,
                EntityDataSerializers.CHICKEN_SOUND_VARIANT,
                EntityDataSerializers.COW_VARIANT,
                EntityDataSerializers.COW_SOUND_VARIANT,
                EntityDataSerializers.WOLF_VARIANT,
                EntityDataSerializers.WOLF_SOUND_VARIANT,
                EntityDataSerializers.FROG_VARIANT,
                EntityDataSerializers.PIG_VARIANT,
                EntityDataSerializers.PIG_SOUND_VARIANT,
                EntityDataSerializers.ZOMBIE_NAUTILUS_VARIANT,
                EntityDataSerializers.PAINTING_VARIANT,
                EntityDataSerializers.ARMADILLO_STATE,
                EntityDataSerializers.SNIFFER_STATE,
                EntityDataSerializers.WEATHERING_COPPER_STATE,
                EntityDataSerializers.COPPER_GOLEM_STATE,
                EntityDataSerializers.VECTOR3,
                EntityDataSerializers.QUATERNION,
                EntityDataSerializers.RESOLVABLE_PROFILE,
                EntityDataSerializers.HUMANOID_ARM,
                EntityDataSerializers.DYE_COLOR);
    }

    private static <T> void translated(EntityDataSerializer<T> serializer, BiFunction<T, Offset, T> shift) {
        SERIALIZERS.put(serializer, Coverage.TRANSLATED);
        SHIFTS.put(serializer, shift);
    }

    private static void noPositions(EntityDataSerializer<?>... serializers) {
        for (EntityDataSerializer<?> serializer : serializers) {
            SERIALIZERS.put(serializer, Coverage.NO_POSITIONS);
        }
    }

    private static Optional<BlockPos> optionalBlockPos(Optional<BlockPos> pos, Offset offset) {
        return pos.isEmpty() ? pos : pos.map(at -> offset.shift(at));
    }

    // Defined, but no entity uses it in 26.3
    private static Optional<GlobalPos> optionalGlobalPos(Optional<GlobalPos> pos, Offset offset) {
        return pos.isEmpty() ? pos : pos.map(at -> offset.shift(at));
    }

    // A living entity's effect particles, in order; the very list when none moves
    private static List<ParticleOptions> particles(List<ParticleOptions> particles, Offset offset) {
        List<ParticleOptions> shifted = null;
        for (int i = 0; i < particles.size(); i++) {
            ParticleOptions particle = particles.get(i);
            ParticleOptions moved = ParticleRule.shift(particle, offset);
            if (moved != particle) {
                if (shifted == null) {
                    shifted = new ArrayList<>(particles);
                }
                shifted.set(i, moved);
            }
        }
        return shifted == null ? particles : shifted;
    }

    // Built from its fields. Its angles are kept as bytes, and the getters' degrees pack back to the same ones. The
    // movement is a velocity.
    private static Packet<?> addEntity(ClientboundAddEntityPacket packet, Context context) {
        Offset offset = context.offset();
        return new ClientboundAddEntityPacket(packet.getId(), packet.getUUID(), offset.shiftX(packet.getX()), packet.getY(),
                offset.shiftZ(packet.getZ()), packet.getXRot(), packet.getYRot(), packet.getType(), packet.getData(),
                packet.getMovement(), packet.getYHeadRot());
    }

    // A relative axis is a step from where the entity is, so it stays as it is
    private static Packet<?> teleportEntity(ClientboundTeleportEntityPacket packet, Context context) {
        Set<Relative> relatives = packet.relatives();
        boolean relativeX = relatives.contains(Relative.X);
        boolean relativeZ = relatives.contains(Relative.Z);
        if (relativeX && relativeZ) {
            return packet;
        }
        Offset offset = context.offset();
        PositionMoveRotation change = packet.change();
        Vec3 to = change.position();
        Vec3 shifted = new Vec3(relativeX ? to.x : offset.shiftX(to.x), to.y, relativeZ ? to.z : offset.shiftZ(to.z));
        return new ClientboundTeleportEntityPacket(packet.id(),
                new PositionMoveRotation(shifted, change.deltaMovement(), change.yRot(), change.xRot()), relatives, packet.onGround());
    }

    // A stepped path ends at its last step, which its constructor reads
    private static Packet<?> positionSync(ClientboundEntityPositionSyncPacket packet, Context context) {
        Offset offset = context.offset();
        PositionPath path = switch (packet.position()) {
            case PositionPath.Linear linear -> new PositionPath.Linear(offset.shift(linear.endPosition()));
            case PositionPath.Stepped stepped -> new PositionPath.Stepped(stepped.steps().stream()
                    .map(step -> new PositionStep(offset.shift(step.position()), step.tickOffset())).toList());
        };
        return new ClientboundEntityPositionSyncPacket(packet.id(), path, packet.yRot(), packet.xRot(), packet.onGround());
    }

    private static Packet<?> minecartSteps(ClientboundMoveMinecartPacket packet, Context context) {
        Offset offset = context.offset();
        return new ClientboundMoveMinecartPacket(packet.entityId(), packet.lerpSteps().stream()
                .map(step -> new NewMinecartBehavior.MinecartStep(offset.shift(step.position()), step.movement(), step.yRot(),
                        step.xRot(), step.weight()))
                .toList());
    }

    // Part of every spawn, so the very packet goes on when no value in it moves
    private static Packet<?> entityData(ClientboundSetEntityDataPacket packet, Context context) {
        Offset offset = context.offset();
        List<SynchedEntityData.DataValue<?>> values = packet.packedItems();
        List<SynchedEntityData.DataValue<?>> shifted = null;
        for (int i = 0; i < values.size(); i++) {
            SynchedEntityData.DataValue<?> value = values.get(i);
            SynchedEntityData.DataValue<?> moved = shift(value, offset);
            if (moved != value) {
                if (shifted == null) {
                    shifted = new ArrayList<>(values);
                }
                shifted.set(i, moved);
            }
        }
        return shifted == null ? packet : new ClientboundSetEntityDataPacket(packet.id(), shifted);
    }

    // Each shift was put in the table with its own serializer, so its types match the value's
    @SuppressWarnings("unchecked")
    private static <T> SynchedEntityData.DataValue<T> shift(SynchedEntityData.DataValue<T> value, Offset offset) {
        BiFunction<T, Offset, T> shift = (BiFunction<T, Offset, T>) SHIFTS.get(value.serializer());
        if (shift == null) {
            return value;
        }
        T shifted = shift.apply(value.value(), offset);
        return shifted == value.value() ? value : new SynchedEntityData.DataValue<>(value.id(), value.serializer(), shifted);
    }
}
