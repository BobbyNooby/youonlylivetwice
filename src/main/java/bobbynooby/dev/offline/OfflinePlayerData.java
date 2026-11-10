package bobbynooby.dev.offline;

import bobbynooby.dev.core.Stacks;
import bobbynooby.dev.core.Yolt;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
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
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
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
    private static final String GAME_TYPE = "playerGameType";
    private static final String XP_LEVEL = "XpLevel";
    private static final String XP_TOTAL = "XpTotal";
    private static final String XP_PROGRESS = "XpP";
    private static final String LAST_DEATH_LOCATION = "LastDeathLocation";
    private static final String TAGS = "Tags";

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

    /** The saved game mode; survival when the save has none. */
    public static GameType gameType(CompoundTag tag) {
        return GameType.byId(tag.getIntOr(GAME_TYPE, 0));
    }

    /** Clears the save's experience and returns the level it had. */
    public static int takeExperience(CompoundTag tag) {
        int level = tag.getIntOr(XP_LEVEL, 0);
        tag.putInt(XP_LEVEL, 0);
        tag.putInt(XP_TOTAL, 0);
        tag.putFloat(XP_PROGRESS, 0.0F);
        return level;
    }

    /** Adds an entity tag to the save, as /tag would, so the player has it once they are loaded. */
    public static void addEntityTag(CompoundTag tag, String entityTag) {
        List<String> tags = new ArrayList<>(tag.read(TAGS, Codec.STRING.listOf()).orElse(List.of()));
        if (!tags.contains(entityTag)) {
            tags.add(entityTag);
            tag.store(TAGS, Codec.STRING.listOf(), tags);
        }
    }

    public static void removeEntityTag(CompoundTag tag, String entityTag) {
        List<String> tags = new ArrayList<>(tag.read(TAGS, Codec.STRING.listOf()).orElse(List.of()));
        if (tags.remove(entityTag)) {
            tag.store(TAGS, Codec.STRING.listOf(), tags);
        }
    }

    /** Makes the save a dead player's, who died at where: no effects and no health. */
    public static void markDead(CompoundTag tag, GlobalPos where) {
        tag.remove(EFFECTS);
        tag.putFloat(HEALTH, 0.0F);
        tag.store(LAST_DEATH_LOCATION, GlobalPos.CODEC, where);
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
            Yolt.LOGGER.error("Could not delete unused player data file {}", prepared, e);
        }
    }

    /**
     * Takes the main inventory plus every equipment slot (armour, offhand), which 26.x saves under its own key. Like
     * vanilla's own load, an item that only partly reads back is taken without the parts that don't. An entry with no
     * readable item at all stays in the save as it was. Both are logged.
     */
    public static List<ItemStack> takeInventory(CompoundTag tag, HolderLookup.Provider registries, String owner) {
        RegistryOps<Tag> ops = ops(registries);
        List<ItemStack> items = takeSlots(tag, INVENTORY, ops, owner, "inventory");
        items.addAll(takeEquipment(tag, ops, owner));
        return items;
    }

    // Read slot by slot, so one entry that doesn't read back keeps only itself in the save
    private static List<ItemStack> takeEquipment(CompoundTag tag, RegistryOps<Tag> ops, String owner) {
        Tag saved = tag.get(EQUIPMENT);
        if (!(saved instanceof CompoundTag equipment)) {
            if (saved != null) {
                Yolt.LOGGER.warn("Keeping {}'s unreadable equipment in their save file: {}", owner, saved);
            }
            return List.of();
        }
        Map<EquipmentSlot, ItemStack> worn = new EnumMap<>(EquipmentSlot.class);
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            String key = slot.getSerializedName();
            Tag entry = equipment.get(key);
            if (entry != null) {
                readItem(ItemStack.CODEC.parse(ops, entry), owner, "equipment slot " + key, entry)
                        .ifPresent(stack -> worn.put(slot, stack));
            }
        }
        worn.keySet().forEach(slot -> equipment.remove(slot.getSerializedName()));
        // What is left didn't read back, or names no slot
        for (String key : equipment.keySet()) {
            Yolt.LOGGER.warn("Keeping {}'s unreadable item in equipment slot {} in their save file: {}",
                    owner, key, equipment.get(key));
        }
        if (equipment.isEmpty()) {
            tag.remove(EQUIPMENT);
        }
        return Stacks.nonEmpty(worn.values());
    }

    /**
     * The worn and held items of a save, by slot. A slot with no entry is in none of the maps. An item that reads back
     * only in part is in items, as vanilla's own load would take it, and its raw entry in partlyRead. An entry that
     * doesn't read back at all is only in unreadable, as saved, so a caller can leave it alone.
     */
    public record Gear(Map<EquipmentSlot, ItemStack> items, Map<EquipmentSlot, Tag> partlyRead,
                       Map<EquipmentSlot, Tag> unreadable) {
        private void read(EquipmentSlot slot, Tag entry, DataResult<ItemStack> result) {
            Optional<ItemStack> stack = result.resultOrPartial();
            if (stack.isEmpty()) {
                unreadable.put(slot, entry);
                return;
            }
            if (result.isError()) {
                partlyRead.put(slot, entry);
            }
            if (!stack.get().isEmpty()) {
                items.put(slot, stack.get());
            }
        }
    }

    /**
     * Every worn or held item in the save. The main hand is the selected hotbar slot of the inventory list; the other
     * slots sit under equipment. An inventory or equipment key that isn't the list or compound vanilla writes counts
     * as an unreadable entry for each slot it would hold.
     */
    public static Gear readGear(CompoundTag tag, HolderLookup.Provider registries) {
        RegistryOps<Tag> ops = ops(registries);
        Gear gear = new Gear(new EnumMap<>(EquipmentSlot.class), new EnumMap<>(EquipmentSlot.class),
                new EnumMap<>(EquipmentSlot.class));
        Tag inventory = tag.get(INVENTORY);
        if (inventory instanceof ListTag entries) {
            int selected = selectedSlot(tag);
            Optional<Tag> held = entries.stream().filter(entry -> isSlot(entry, selected)).findFirst();
            held.ifPresent(entry -> gear.read(EquipmentSlot.MAINHAND, entry,
                    ItemStackWithSlot.CODEC.parse(ops, entry).map(ItemStackWithSlot::stack)));
        } else if (inventory != null) {
            gear.unreadable().put(EquipmentSlot.MAINHAND, inventory);
        }
        Tag equipment = tag.get(EQUIPMENT);
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot == EquipmentSlot.MAINHAND || equipment == null) {
                continue;
            }
            if (!(equipment instanceof CompoundTag worn)) {
                gear.unreadable().put(slot, equipment);
                continue;
            }
            Tag entry = worn.get(slot.getSerializedName());
            if (entry != null) {
                gear.read(slot, entry, ItemStack.CODEC.parse(ops, entry));
            }
        }
        return gear;
    }

    /**
     * Writes the given slots into the save; an empty stack clears its slot. Slots not in the map, and every other
     * inventory entry, stay exactly as saved. A slot in the map is written over whatever its entry held, so a caller
     * leaves out the slots readGear found unreadable.
     */
    public static void writeGear(CompoundTag tag, Map<EquipmentSlot, ItemStack> gear, HolderLookup.Provider registries) {
        RegistryOps<Tag> ops = ops(registries);
        CompoundTag equipment = tag.getCompoundOrEmpty(EQUIPMENT);
        gear.forEach((slot, stack) -> {
            if (slot == EquipmentSlot.MAINHAND) {
                ListTag inventory = tag.getListOrEmpty(INVENTORY);
                int selected = selectedSlot(tag);
                inventory.removeIf(entry -> isSlot(entry, selected));
                if (!stack.isEmpty()) {
                    inventory.add(ItemStackWithSlot.CODEC.encodeStart(ops, new ItemStackWithSlot(selected, stack)).getOrThrow());
                }
                tag.put(INVENTORY, inventory);
            } else if (stack.isEmpty()) {
                equipment.remove(slot.getSerializedName());
            } else {
                equipment.store(slot.getSerializedName(), ItemStack.CODEC, ops, stack);
            }
        });
        // Only when a worn slot was written, so an equipment key that couldn't be read stays as saved
        if (gear.keySet().stream().allMatch(slot -> slot == EquipmentSlot.MAINHAND)) {
            return;
        }
        // Vanilla leaves the key out when nothing is worn
        if (equipment.isEmpty()) {
            tag.remove(EQUIPMENT);
        } else {
            tag.put(EQUIPMENT, equipment);
        }
    }

    /**
     * Puts the stack into the lowest main-inventory slot that no entry of the save uses, and returns that slot. Empty
     * when every one is taken, or when the inventory key isn't a list, which would be written over.
     */
    public static OptionalInt addToInventory(CompoundTag tag, ItemStack stack, HolderLookup.Provider registries) {
        Tag saved = tag.get(INVENTORY);
        if (saved != null && !(saved instanceof ListTag)) {
            return OptionalInt.empty();
        }
        ListTag entries = saved instanceof ListTag list ? list : new ListTag();
        Set<Integer> used = new HashSet<>();
        for (Tag entry : entries) {
            if (entry instanceof CompoundTag item) {
                used.add(slotOf(item));
            }
        }
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            if (!used.contains(slot)) {
                entries.add(ItemStackWithSlot.CODEC.encodeStart(ops(registries), new ItemStackWithSlot(slot, stack)).getOrThrow());
                tag.put(INVENTORY, entries);
                return OptionalInt.of(slot);
            }
        }
        return OptionalInt.empty();
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

    /** Takes the ender chest. Like takeInventory, an entry that doesn't read back stays in the save. */
    public static List<ItemStack> takeEnderChest(CompoundTag tag, HolderLookup.Provider registries, String owner) {
        return takeSlots(tag, ENDER_ITEMS, ops(registries), owner, "ender chest");
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

    private static boolean isSlot(Tag entry, int slot) {
        return entry instanceof CompoundTag item && slotOf(item) == slot;
    }

    // A missing Slot reads as 0, the same as vanilla's codec
    private static int slotOf(CompoundTag item) {
        return Byte.toUnsignedInt(item.getByteOr(SLOT, (byte) 0));
    }

    // Read entry by entry, so one that doesn't read back keeps only itself in the save
    private static List<ItemStack> takeSlots(CompoundTag tag, String key, RegistryOps<Tag> ops, String owner, String what) {
        List<ItemStack> items = new ArrayList<>();
        Tag saved = tag.get(key);
        if (!(saved instanceof ListTag entries)) {
            if (saved != null) {
                Yolt.LOGGER.warn("Keeping {}'s unreadable {} in their save file: {}", owner, what, saved);
            }
            return items;
        }
        Iterator<Tag> each = entries.iterator();
        while (each.hasNext()) {
            Tag entry = each.next();
            Object slot = entry instanceof CompoundTag item ? slotOf(item) : "?";
            Optional<ItemStack> read = readItem(ItemStackWithSlot.CODEC.parse(ops, entry).map(ItemStackWithSlot::stack),
                    owner, what + " slot " + slot, entry);
            if (read.isPresent()) {
                items.add(read.get());
                each.remove();
            } else {
                Yolt.LOGGER.warn("Keeping {}'s unreadable item in {} slot {} in their save file: {}", owner, what, slot, entry);
            }
        }
        if (entries.isEmpty()) {
            tag.remove(key);
        }
        return items;
    }

    // An item from a mod or datapack removed since reads back without the component it brought, as in vanilla's
    // own load, and the raw entry goes in the log. Empty means no usable item at all, so the entry stays saved.
    private static Optional<ItemStack> readItem(DataResult<ItemStack> read, String owner, String where, Tag entry) {
        Optional<ItemStack> stack = read.resultOrPartial().filter(item -> !item.isEmpty());
        if (stack.isPresent() && read.isError()) {
            Yolt.LOGGER.warn("Taking {}'s partly unreadable item in {} without the parts that can't be read: {}",
                    owner, where, entry);
        }
        return stack;
    }
}
