package bobbynooby.dev.gametest;

import net.minecraft.advancements.AdvancementRewards;
import net.minecraft.advancements.triggers.Criterion;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.locale.Language;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.StateHolder;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lists every real position in a packet a client with an offset was sent. The scene sits at a real spot far out, so
 * every real position in it falls in a narrow band around that spot, and every shifted one lands near 0, 0, far from
 * the band. The walk goes through the packet as its fields hold it, nested items, NBT, particles and texts included.
 */
final class LeakScanner {
    // Objects deep; a list, map, optional or array on the way doesn't count
    private static final int MAX_DEPTH = 12;
    // A velocity or a push, not a place
    private static final Set<String> RELATIVE = Set.of("movement", "deltaMovement", "playerKnockback");
    // Libraries whose objects are walked only through the collection interfaces, never by their fields
    private static final List<String> LEAF_PACKAGES = List.of("java.", "javax.", "jdk.", "sun.", "com.sun.", "io.netty.",
            "com.mojang.serialization.", "com.mojang.brigadier.", "com.mojang.authlib.", "com.google.gson.", "org.joml.",
            "org.slf4j.", "org.apache.");
    // What to read of each class, worked out once
    private static final Map<Class<?>, List<Part>> PARTS = new ConcurrentHashMap<>();

    /** The real spot's band: a real position has both its X and Z in it, a shifted one neither. */
    record Band(int minX, int maxX, int minZ, int maxZ) {
        boolean inX(double x) {
            return x >= minX && x <= maxX;
        }

        boolean inZ(double z) {
            return z >= minZ && z <= maxZ;
        }

        boolean in(double x, double z) {
            return inX(x) && inZ(z);
        }
    }

    private LeakScanner() {}

    /** One line per real position in the packet, naming the packet class and the path to the field. */
    static List<String> findings(Object packet, Band band) {
        Walk walk = new Walk(band);
        walk.packet(packet);
        return walk.findings;
    }

    // A field or a record component, read the same way
    private record Part(String name, Class<?> type, Field field, @Nullable Method accessor) {
        @Nullable Object read(Object owner) throws ReflectiveOperationException {
            return accessor != null ? accessor.invoke(owner) : field.get(owner);
        }
    }

    private static final class Walk {
        private final Band band;
        private final List<String> findings = new ArrayList<>();
        private final Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        private final List<String> path = new ArrayList<>();
        private String packetName = "";

        private Walk(Band band) {
            this.band = band;
        }

        // A bundle's parts are packets of their own to the client
        void packet(Object packet) {
            if (packet instanceof BundlePacket<?> bundle) {
                for (Object part : bundle.subPackets()) {
                    packet(part);
                }
                return;
            }
            packetName = packet.getClass().getSimpleName();
            path.clear();
            visit(packet, 0);
        }

        private void found(String what, Object value) {
            findings.add(packetName + String.join("", path) + ": " + what + " " + value);
        }

        private void visit(@Nullable Object value, int depth) {
            if (value == null || value instanceof Number || value instanceof CharSequence || value instanceof Boolean
                    || value instanceof Character || value instanceof Enum<?> || value instanceof Class<?> || value instanceof UUID) {
                return;
            }
            Class<?> type = value.getClass();
            if (type.isArray()) {
                if (!type.getComponentType().isPrimitive()) {
                    Object[] elements = (Object[]) value;
                    for (int i = 0; i < elements.length; i++) {
                        child("[" + i + "]", elements[i], depth);
                    }
                }
                return;
            }
            if (!seen.add(value) || position(value)) {
                return;
            }
            if (value instanceof Tag tag) {
                tag(tag);
                return;
            }
            if (isLeaf(value)) {
                return;
            }
            if (value instanceof Optional<?> optional) {
                optional.ifPresent(inner -> visit(inner, depth));
                return;
            }
            if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    child("{" + entry.getKey() + "}", entry.getKey(), depth);
                    child("{" + entry.getKey() + "}", entry.getValue(), depth);
                }
                return;
            }
            if (value instanceof Iterable<?> iterable) {
                int i = 0;
                for (Object element : iterable) {
                    child("[" + i++ + "]", element, depth);
                }
                return;
            }
            if (inLeafPackage(type) || type.isHidden() || type.isSynthetic()) {
                return;
            }
            if (special(value, depth) || depth >= MAX_DEPTH) {
                return;
            }
            for (Part part : partsOf(type)) {
                Object read;
                try {
                    read = part.read(value);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    continue;
                }
                if (part.type() == int.class || part.type() == double.class) {
                    namedNumber(value, part.name(), ((Number) read).doubleValue());
                } else if (!part.type().isPrimitive() && !RELATIVE.contains(part.name())) {
                    child("." + part.name(), read, depth + 1);
                }
            }
        }

        private void child(String segment, @Nullable Object value, int depth) {
            path.add(segment);
            visit(value, depth);
            path.removeLast();
        }

        // A place by its type: blocks and vectors by their X and Z, chunks and sections by their first block
        private boolean position(Object value) {
            if (value instanceof Vec3 vector) {
                if (band.in(vector.x, vector.z)) {
                    found("position", vector);
                }
                return true;
            }
            if (value instanceof SectionPos section) {
                if (band.in(section.x() * 16.0, section.z() * 16.0)) {
                    found("section", section);
                }
                return true;
            }
            if (value instanceof Vec3i block) {
                if (band.in(block.getX(), block.getZ())) {
                    found("block", block);
                }
                return true;
            }
            if (value instanceof ChunkPos chunk) {
                if (band.in(chunk.x() * 16.0, chunk.z() * 16.0)) {
                    found("chunk", chunk);
                }
                return true;
            }
            return false;
        }

        // An int or double named x or z is a place on its own axis: in blocks, or in a sound's eighths, or in the chunks
        // of the packets that send whole chunks or their centre
        private void namedNumber(Object owner, String name, double value) {
            boolean x = name.equals("x");
            if (!x && !name.equals("z")) {
                return;
            }
            double blocks = owner instanceof ClientboundSoundPacket ? value / 8.0
                    : owner instanceof ClientboundLevelChunkWithLightPacket || owner instanceof ClientboundLightUpdatePacket
                    || owner instanceof ClientboundSetChunkCacheCenterPacket ? value * 16.0 : value;
            if (x ? band.inX(blocks) : band.inZ(blocks)) {
                found(name, value);
            }
        }

        private void tag(Tag tag) {
            if (tag instanceof IntArrayTag array) {
                int[] ints = array.getAsIntArray();
                if (ints.length == 3 && band.in(ints[0], ints[2])) {
                    found("int array", Arrays.toString(ints));
                }
            } else if (tag instanceof ListTag list) {
                if (list.size() == 3 && list.stream().allMatch(element -> element instanceof DoubleTag)
                        && band.in(((DoubleTag) list.get(0)).value(), ((DoubleTag) list.get(2)).value())) {
                    found("double list", list);
                }
                for (int i = 0; i < list.size(); i++) {
                    path.add("[" + i + "]");
                    tag(list.get(i));
                    path.removeLast();
                }
            } else if (tag instanceof CompoundTag compound) {
                // A map marker's x and z
                if (compound.get("x") instanceof NumericTag x && compound.get("z") instanceof NumericTag z
                        && band.in(x.doubleValue(), z.doubleValue())) {
                    found("x and z", compound);
                }
                for (String key : compound.keySet()) {
                    path.add("{" + key + "}");
                    tag(compound.get(key));
                    path.removeLast();
                }
            }
        }

        // Registry entries, the world and the like hold no position the client is sent, and lead far away
        private static boolean isLeaf(Object value) {
            return value instanceof Holder<?> || value instanceof HolderSet<?> || value instanceof Registry<?>
                    || value instanceof HolderLookup.Provider || value instanceof ResourceKey<?> || value instanceof Identifier
                    || value instanceof TagKey<?> || value instanceof StateHolder<?, ?> || value instanceof Block
                    || value instanceof Item || value instanceof Fluid || value instanceof EntityType<?>
                    || value instanceof BlockEntityType<?> || value instanceof ParticleType<?> || value instanceof SoundEvent
                    || value instanceof MenuType<?> || value instanceof DataComponentType<?> || value instanceof EntityDataSerializer<?>
                    || value instanceof PacketType<?> || value instanceof StreamCodec<?, ?> || value instanceof Entity
                    || value instanceof BlockEntity || value instanceof Level || value instanceof MinecraftServer
                    || value instanceof PlayerList || value instanceof Language
                    // An advancement's criteria and rewards stay on the server
                    || value instanceof Criterion<?> || value instanceof AdvancementRewards;
        }

        private static boolean inLeafPackage(Class<?> type) {
            String name = type.getName();
            for (String prefix : LEAF_PACKAGES) {
                if (name.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }

        // What needs more than its fields read: an item by its own components, a display's vectors not at all, a teleport
        // by its absolute axes only, and a border by the walls the client puts it at
        private boolean special(Object value, int depth) {
            if (value instanceof ItemStack stack) {
                if (!stack.isEmpty()) {
                    child(".components", stack.getComponentsPatch(), depth + 1);
                }
                return true;
            }
            if (value instanceof SynchedEntityData.DataValue<?> data) {
                if (data.serializer() != EntityDataSerializers.VECTOR3 && data.serializer() != EntityDataSerializers.QUATERNION) {
                    child(".value", data.value(), depth + 1);
                }
                return true;
            }
            if (value instanceof ClientboundPlayerPositionPacket teleport) {
                teleport(teleport.change().position(), teleport.relatives());
                return true;
            }
            if (value instanceof ClientboundTeleportEntityPacket teleport) {
                teleport(teleport.change().position(), teleport.relatives());
                return true;
            }
            if (value instanceof ClientboundInitializeBorderPacket border) {
                walls(border, border.getOldSize());
                walls(border, border.getNewSize());
                return true;
            }
            return false;
        }

        // A relative axis is a step, so only the absolute ones can be a place
        private void teleport(Vec3 to, Set<Relative> relatives) {
            boolean absoluteX = !relatives.contains(Relative.X);
            boolean absoluteZ = !relatives.contains(Relative.Z);
            if (absoluteX && absoluteZ ? band.in(to.x, to.z) : absoluteX ? band.inX(to.x) : absoluteZ && band.inZ(to.z)) {
                found("teleport", to);
            }
        }

        private void walls(ClientboundInitializeBorderPacket border, double size) {
            int edge = border.getNewAbsoluteMaxSize();
            double half = size / 2.0;
            double west = Mth.clamp(border.getNewCenterX() - half, -edge, edge);
            double east = Mth.clamp(border.getNewCenterX() + half, -edge, edge);
            double north = Mth.clamp(border.getNewCenterZ() - half, -edge, edge);
            double south = Mth.clamp(border.getNewCenterZ() + half, -edge, edge);
            if (band.inX(west) || band.inX(east)) {
                found("border wall x", west + " to " + east);
            }
            if (band.inZ(north) || band.inZ(south)) {
                found("border wall z", north + " to " + south);
            }
        }
    }

    // Records by their components, anything else by its fields and its superclasses' fields, statics left out
    private static List<Part> partsOf(Class<?> type) {
        return PARTS.computeIfAbsent(type, LeakScanner::findPartsOrNone);
    }

    // A class whose fields can't even be listed is one this run has no part of
    private static List<Part> findPartsOrNone(Class<?> type) {
        try {
            return findParts(type);
        } catch (RuntimeException | LinkageError e) {
            return List.of();
        }
    }

    private static List<Part> findParts(Class<?> type) {
        List<Part> parts = new ArrayList<>();
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                Method accessor = component.getAccessor();
                try {
                    Field field = type.getDeclaredField(component.getName());
                    if (accessor.trySetAccessible()) {
                        parts.add(new Part(component.getName(), component.getType(), field, accessor));
                    }
                } catch (NoSuchFieldException e) {
                    // Every component has its field
                }
            }
            return parts;
        }
        for (Class<?> at = type; at != null && at != Object.class && !Walk.inLeafPackage(at); at = at.getSuperclass()) {
            for (Field field : at.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic() && field.trySetAccessible()) {
                    parts.add(new Part(field.getName(), field.getType(), field, null));
                }
            }
        }
        return parts;
    }
}
