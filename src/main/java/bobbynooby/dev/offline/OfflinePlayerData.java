package bobbynooby.dev.offline;

import bobbynooby.dev.YouOnlyLiveTwice;
import bobbynooby.dev.core.Stacks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.Util;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Reads and edits the save file of a player who is not online. Key names follow vanilla's Player save code. */
public final class OfflinePlayerData {
    public static final String HEALTH = "Health";
    public static final String ABSORPTION = "AbsorptionAmount";
    public static final String FALL_DISTANCE = "fall_distance";
    public static final String FIRE = "Fire";
    public static final String AIR = "Air";
    private static final String INVENTORY = "Inventory";
    private static final String EQUIPMENT = "equipment";
    private static final String SELECTED_SLOT = "SelectedItemSlot";
    private static final String SLOT = "Slot";
    private static final String ENDER_ITEMS = "EnderItems";
    private static final String EFFECTS = "active_effects";
    private static final String ATTRIBUTES = "attributes";

    private OfflinePlayerData() {}

    /** The same load vanilla uses at login, so the tag is already upgraded to the current data version. */
    public static Optional<CompoundTag> load(MinecraftServer server, NameAndId who) {
        return server.getPlayerList().loadPlayerData(who);
    }

    /** Like load, but a player with no save file is an error. */
    public static CompoundTag loadOrThrow(MinecraftServer server, NameAndId who) {
        return load(server, who).orElseThrow(() -> new IllegalStateException("No save file for " + who.name()));
    }

    /** True for a save that holds a dead player. A save with no health loads at full health, so it isn't dead. */
    public static boolean isDead(CompoundTag tag) {
        return tag.getFloatOr(HEALTH, 1.0F) <= 0.0F;
    }

    /** Writes the save the way vanilla does: a temp file, then a swap that keeps the previous save as .dat_old. */
    public static void save(MinecraftServer server, UUID uuid, CompoundTag tag) {
        Path prepared = prepareSave(server, uuid, tag);
        try {
            commitSave(server, uuid, prepared);
        } catch (RuntimeException e) {
            discardSave(prepared);
            throw e;
        }
    }

    /** Writes the tag to a temp file next to the save. Nothing else changes until commitSave. */
    public static Path prepareSave(MinecraftServer server, UUID uuid, CompoundTag tag) {
        Path dir = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
        Path tmp = null;
        try {
            tmp = Files.createTempFile(dir, uuid + "-", ".dat");
            // load() upgraded the data, so the old version number would make vanilla upgrade it twice
            NbtIo.writeCompressed(NbtUtils.addCurrentDataVersion(tag), tmp);
            return tmp;
        } catch (IOException e) {
            if (tmp != null) {
                discardSave(tmp);
            }
            throw new UncheckedIOException("Could not write player data for " + uuid, e);
        }
    }

    /** Swaps the prepared file in. Throws if the swap failed, which leaves the old save in charge. */
    public static void commitSave(MinecraftServer server, UUID uuid, Path prepared) {
        Path dir = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
        // safeReplaceFile drops this result, so a failed swap would look like success
        if (!Util.safeReplaceOrMoveFile(dir.resolve(uuid + ".dat"), prepared, dir.resolve(uuid + ".dat_old"), false)) {
            throw new UncheckedIOException(new IOException("Could not replace the save file of " + uuid));
        }
    }

    /** Deletes a prepared file that won't be committed. Only logs on failure, since callers are already failing. */
    public static void discardSave(Path prepared) {
        try {
            Files.deleteIfExists(prepared);
        } catch (IOException e) {
            YouOnlyLiveTwice.LOGGER.error("Could not delete unused player data file {}", prepared, e);
        }
    }

    /** Main inventory plus every equipment slot (armour, offhand), which 26.x saves under its own key. */
    public static List<ItemStack> takeInventory(CompoundTag tag, HolderLookup.Provider registries) {
        RegistryOps<Tag> ops = ops(registries);
        List<ItemStack> items = takeSlots(tag, INVENTORY, ops);
        tag.read(EQUIPMENT, EntityEquipment.CODEC, ops).ifPresent(equipment -> {
            List<ItemStack> worn = new ArrayList<>();
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                worn.add(equipment.get(slot));
            }
            items.addAll(Stacks.nonEmpty(worn));
        });
        tag.remove(EQUIPMENT);
        return items;
    }

    /**
     * Every worn or held item in the save. The main hand is the selected hotbar slot of the inventory list; the
     * other slots sit under equipment. Empty slots, and items that don't read back, are left out.
     */
    public static Map<EquipmentSlot, ItemStack> readGear(CompoundTag tag, HolderLookup.Provider registries) {
        RegistryOps<Tag> ops = ops(registries);
        Map<EquipmentSlot, ItemStack> gear = new EnumMap<>(EquipmentSlot.class);
        CompoundTag equipment = tag.getCompoundOrEmpty(EQUIPMENT);
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            Optional<ItemStack> stack = slot == EquipmentSlot.MAINHAND
                    ? tag.getListOrEmpty(INVENTORY).stream()
                            .filter(entry -> isSlot(entry, selectedSlot(tag)))
                            .findFirst()
                            .flatMap(entry -> ItemStackWithSlot.CODEC.parse(ops, entry).result())
                            .map(ItemStackWithSlot::stack)
                    : equipment.read(slot.getSerializedName(), ItemStack.CODEC, ops);
            stack.filter(item -> !item.isEmpty()).ifPresent(item -> gear.put(slot, item));
        }
        return gear;
    }

    /**
     * Writes the given slots into the save; an empty stack clears its slot. Slots not in the map, and every other
     * inventory entry, stay exactly as saved. A slot in the map is overwritten even if its saved item didn't read
     * back (readGear reports that slot as empty), so such an item is lost.
     */
    public static void writeGear(CompoundTag tag, Map<EquipmentSlot, ItemStack> gear, HolderLookup.Provider registries) {
        RegistryOps<Tag> ops = ops(registries);
        CompoundTag equipment = tag.getCompoundOrEmpty(EQUIPMENT);
        ListTag inventory = tag.getListOrEmpty(INVENTORY);
        int selected = selectedSlot(tag);
        gear.forEach((slot, stack) -> {
            if (slot == EquipmentSlot.MAINHAND) {
                inventory.removeIf(entry -> isSlot(entry, selected));
                if (!stack.isEmpty()) {
                    inventory.add(ItemStackWithSlot.CODEC.encodeStart(ops, new ItemStackWithSlot(selected, stack)).getOrThrow());
                }
            } else if (stack.isEmpty()) {
                equipment.remove(slot.getSerializedName());
            } else {
                equipment.store(slot.getSerializedName(), ItemStack.CODEC, ops, stack);
            }
        });
        tag.put(INVENTORY, inventory);
        // Vanilla leaves the key out when nothing is worn
        if (equipment.isEmpty()) {
            tag.remove(EQUIPMENT);
        } else {
            tag.put(EQUIPMENT, equipment);
        }
    }

    /** The saved effects; none when the key is missing. */
    public static List<MobEffectInstance> readEffects(CompoundTag tag, HolderLookup.Provider registries) {
        return tag.read(EFFECTS, MobEffectInstance.CODEC.listOf(), ops(registries)).orElse(List.of());
    }

    public static void writeEffects(CompoundTag tag, List<MobEffectInstance> effects, HolderLookup.Provider registries) {
        // Vanilla leaves the key out when there are no effects
        if (effects.isEmpty()) {
            tag.remove(EFFECTS);
        } else {
            tag.store(EFFECTS, MobEffectInstance.CODEC.listOf(), ops(registries), effects);
        }
    }

    /** The saved attributes; none when the key is missing. */
    public static List<AttributeInstance.Packed> readAttributes(CompoundTag tag, HolderLookup.Provider registries) {
        return tag.read(ATTRIBUTES, AttributeInstance.Packed.LIST_CODEC, ops(registries)).orElse(List.of());
    }

    public static void writeAttributes(CompoundTag tag, List<AttributeInstance.Packed> attributes,
                                       HolderLookup.Provider registries) {
        tag.store(ATTRIBUTES, AttributeInstance.Packed.LIST_CODEC, ops(registries), attributes);
    }

    /** Moves the saved player to pos in dimension, facing yRot. The saved pitch stays. */
    public static void writePosition(CompoundTag tag, ResourceKey<Level> dimension, Vec3 pos, float yRot) {
        float xRot = savedPosition(tag).rotation().map(rotation -> rotation.y).orElse(0.0F);
        tag.store(ServerPlayer.SavedPosition.MAP_CODEC, new ServerPlayer.SavedPosition(Optional.of(dimension),
                Optional.of(pos), Optional.of(new Vec2(yRot, xRot))));
    }

    public static List<ItemStack> takeEnderChest(CompoundTag tag, HolderLookup.Provider registries) {
        return takeSlots(tag, ENDER_ITEMS, ops(registries));
    }

    /** Falls back to the overworld origin only for a save with no position, which vanilla never writes. */
    public static GlobalPos lastPosition(CompoundTag tag) {
        ServerPlayer.SavedPosition saved = savedPosition(tag);
        return GlobalPos.of(
                saved.dimension().orElse(Level.OVERWORLD),
                saved.position().map(BlockPos::containing).orElse(BlockPos.ZERO));
    }

    private static RegistryOps<Tag> ops(HolderLookup.Provider registries) {
        return registries.createSerializationContext(NbtOps.INSTANCE);
    }

    private static ServerPlayer.SavedPosition savedPosition(CompoundTag tag) {
        return tag.read(ServerPlayer.SavedPosition.MAP_CODEC).orElse(ServerPlayer.SavedPosition.EMPTY);
    }

    private static int selectedSlot(CompoundTag tag) {
        return tag.getIntOr(SELECTED_SLOT, 0);
    }

    // A missing Slot reads as 0, the same as vanilla's codec
    private static boolean isSlot(Tag entry, int slot) {
        return entry instanceof CompoundTag item && Byte.toUnsignedInt(item.getByteOr(SLOT, (byte) 0)) == slot;
    }

    private static List<ItemStack> takeSlots(CompoundTag tag, String key, RegistryOps<Tag> ops) {
        List<ItemStack> items = new ArrayList<>();
        tag.read(key, ItemStackWithSlot.CODEC.listOf(), ops).ifPresent(slots ->
                items.addAll(Stacks.nonEmpty(slots.stream().map(ItemStackWithSlot::stack).toList())));
        tag.remove(key);
        return items;
    }
}
