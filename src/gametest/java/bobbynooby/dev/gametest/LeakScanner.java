package bobbynooby.dev.gametest;

import net.minecraft.advancements.AdvancementRewards;
import net.minecraft.advancements.triggers.Criterion;
import net.minecraft.core.BlockPos;
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
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lists every real position in a packet a client with an offset was sent, and every constant sent shifted. The scene sits
 * at a real spot far out, so every real position in it falls in a narrow band around that spot, and every shifted one
 * lands near 0, 0, far from the band. A constant the client ignores, shifted, lands near the offset itself, which is
 * the band's mirror, so that is a band too. The walk goes through the packet as its fields hold it, nested items, NBT,
 * particles and texts included, and keeps note of what it couldn't read.
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
    // What to read of each class, and what of it can't be read, worked out once
    private static final Map<Class<?>, Parts> PARTS = new ConcurrentHashMap<>();

    /**
     * Where no place the client is sent may be. A place is flagged when either of its axes is in the band's span on that
     * axis, so a place with only one axis left real or shifted from a constant shows too.
     */
    record Band(String name, int minX, int maxX, int minZ, int maxZ) {
        static Band around(String name, int x, int z, int reach) {
            return new Band(name, x - reach, x + reach, z - reach, z + reach);
        }

        boolean inX(double x) {
            return x >= minX && x <= maxX;
        }

        boolean inZ(double z) {
            return z >= minZ && z <= maxZ;
        }
    }

    /** What the packets read so far hold: places in a band, the watched spots among their places, and what was unread. */
    static final class Scan {
        private final List<Band> bands;
        private final Set<BlockPos> watched;
        private final List<String> findings = new ArrayList<>();
        private final Set<BlockPos> seen = new HashSet<>();
        private final Set<String> unread = new TreeSet<>();

        Scan(List<Band> bands, Collection<BlockPos> watched) {
            this.bands = List.copyOf(bands);
            this.watched = Set.copyOf(watched);
        }

        void packet(Object packet) {
            new Walk(this).packet(packet);
        }

        /** One line per place in a band, naming the band, the packet class and the path to the field. */
        List<String> findings() {
            return findings;
        }

        /** The watched spots that no packet held as a place, by its block. */
        List<BlockPos> missing() {
            return watched.stream().filter(spot -> !seen.contains(spot)).sorted().toList();
        }

        /** Each class whose fields couldn't be listed, and each field that couldn't be read, by its class's name. */
        Set<String> unread() {
            return unread;
        }

        /** The same, only Minecraft's own classes, where a position the walk can't see would be. */
        List<String> unreadMinecraft() {
            return unread.stream().filter(name -> name.startsWith("net.minecraft.")).toList();
        }
    }

    private LeakScanner() {}

    /** One line per place in a band in the packet. */
    static List<String> findings(Object packet, Band... bands) {
        Scan scan = new Scan(List.of(bands), List.of());
        scan.packet(packet);
        return scan.findings();
    }

    // A field or a record component, read the same way, and the name of the class that declares it
    private record Part(String owner, String name, Class<?> type, Field field, @Nullable Method accessor) {
        @Nullable Object read(Object holder) throws ReflectiveOperationException {
            return accessor != null ? accessor.invoke(holder) : field.get(holder);
        }
    }

    // A class's parts, and the names of what of it can't be read at all
    private record Parts(List<Part> parts, List<String> unread) {}

    private static final class Walk {
        private final Scan scan;
        private final Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        private final List<String> path = new ArrayList<>();
        private String packetName = "";

        private Walk(Scan scan) {
            this.scan = scan;
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
            scan.findings.add(packetName + String.join("", path) + ": " + what + " " + value);
        }

        // A place on both axes: one finding for the first band either axis is in
        private void place(String what, Object value, double x, double z) {
            for (Band band : scan.bands) {
                if (band.inX(x) || band.inZ(z)) {
                    found(band.name() + " " + what, value);
                    return;
                }
            }
        }

        // A place with its height too, which may be one of the spots the scene expects to send shifted
        private void look(double x, double y, double z) {
            BlockPos block = BlockPos.containing(x, y, z);
            if (scan.watched.contains(block)) {
                scan.seen.add(block);
            }
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
            if (!visited.add(value) || position(value)) {
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
            Parts parts = partsOf(type);
            scan.unread.addAll(parts.unread());
            for (Part part : parts.parts()) {
                Object read;
                try {
                    read = part.read(value);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    scan.unread.add(part.owner() + "." + part.name());
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
                place("position", vector, vector.x, vector.z);
                look(vector.x, vector.y, vector.z);
                return true;
            }
            if (value instanceof SectionPos section) {
                place("section", section, section.x() * 16.0, section.z() * 16.0);
                return true;
            }
            if (value instanceof Vec3i block) {
                place("block", block, block.getX(), block.getZ());
                look(block.getX(), block.getY(), block.getZ());
                return true;
            }
            if (value instanceof ChunkPos chunk) {
                place("chunk", chunk, chunk.x() * 16.0, chunk.z() * 16.0);
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
            for (Band band : scan.bands) {
                if (x ? band.inX(blocks) : band.inZ(blocks)) {
                    found(band.name() + " " + name, value);
                    return;
                }
            }
        }

        private void tag(Tag tag) {
            if (tag instanceof IntArrayTag array) {
                int[] ints = array.getAsIntArray();
                if (ints.length == 3) {
                    place("int array", Arrays.toString(ints), ints[0], ints[2]);
                    look(ints[0], ints[1], ints[2]);
                }
            } else if (tag instanceof ListTag list) {
                if (list.size() == 3 && list.stream().allMatch(element -> element instanceof DoubleTag)) {
                    double x = ((DoubleTag) list.get(0)).value();
                    double y = ((DoubleTag) list.get(1)).value();
                    double z = ((DoubleTag) list.get(2)).value();
                    place("double list", list, x, z);
                    look(x, y, z);
                }
                for (int i = 0; i < list.size(); i++) {
                    path.add("[" + i + "]");
                    tag(list.get(i));
                    path.removeLast();
                }
            } else if (tag instanceof CompoundTag compound) {
                // A map marker's x and z
                if (compound.get("x") instanceof NumericTag x && compound.get("z") instanceof NumericTag z) {
                    place("x and z", compound, x.doubleValue(), z.doubleValue());
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
            for (Band band : scan.bands) {
                if (absoluteX && band.inX(to.x) || absoluteZ && band.inZ(to.z)) {
                    found(band.name() + " teleport", to);
                    break;
                }
            }
            if (absoluteX && absoluteZ && !relatives.contains(Relative.Y)) {
                look(to.x, to.y, to.z);
            }
        }

        private void walls(ClientboundInitializeBorderPacket border, double size) {
            int edge = border.getNewAbsoluteMaxSize();
            double half = size / 2.0;
            double west = Mth.clamp(border.getNewCenterX() - half, -edge, edge);
            double east = Mth.clamp(border.getNewCenterX() + half, -edge, edge);
            double north = Mth.clamp(border.getNewCenterZ() - half, -edge, edge);
            double south = Mth.clamp(border.getNewCenterZ() + half, -edge, edge);
            for (Band band : scan.bands) {
                if (band.inX(west) || band.inX(east)) {
                    found(band.name() + " border wall x", west + " to " + east);
                }
                if (band.inZ(north) || band.inZ(south)) {
                    found(band.name() + " border wall z", north + " to " + south);
                }
            }
        }
    }

    // Records by their components, anything else by its fields and its superclasses' fields, statics left out
    private static Parts partsOf(Class<?> type) {
        return PARTS.computeIfAbsent(type, LeakScanner::findPartsOrNone);
    }

    // A class whose fields can't even be listed is one this run has no part of, and says so
    private static Parts findPartsOrNone(Class<?> type) {
        try {
            return findParts(type);
        } catch (RuntimeException | LinkageError e) {
            return new Parts(List.of(), List.of(type.getName()));
        }
    }

    private static Parts findParts(Class<?> type) {
        List<Part> parts = new ArrayList<>();
        List<String> unread = new ArrayList<>();
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                Method accessor = component.getAccessor();
                try {
                    Field field = type.getDeclaredField(component.getName());
                    if (accessor.trySetAccessible()) {
                        parts.add(new Part(type.getName(), component.getName(), component.getType(), field, accessor));
                    } else {
                        unread.add(type.getName() + "." + component.getName());
                    }
                } catch (NoSuchFieldException e) {
                    unread.add(type.getName() + "." + component.getName());
                }
            }
            return new Parts(parts, unread);
        }
        for (Class<?> at = type; at != null && at != Object.class && !Walk.inLeafPackage(at); at = at.getSuperclass()) {
            for (Field field : at.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                if (field.trySetAccessible()) {
                    parts.add(new Part(at.getName(), field.getName(), field.getType(), field, null));
                } else {
                    unread.add(at.getName() + "." + field.getName());
                }
            }
        }
        return new Parts(parts, unread);
    }
}
