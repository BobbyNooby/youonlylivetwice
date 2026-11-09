package bobbynooby.dev.offline;

import bobbynooby.dev.YouOnlyLiveTwice;
import bobbynooby.dev.bodies.BodySnapshot;
import bobbynooby.dev.bodies.GearSync;
import bobbynooby.dev.core.Stacks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.Util;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec2;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Reads and edits the save file of a player who is not online. Key names follow vanilla's Player save code. */
public final class OfflinePlayerData {
    private static final String INVENTORY = "Inventory";
    private static final String EQUIPMENT = "equipment";
    private static final String SELECTED_SLOT = "SelectedItemSlot";
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
        return tag.getFloatOr("Health", 1.0F) <= 0.0F;
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
        RegistryOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
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
     * other slots sit under equipment. Empty slots are left out.
     */
    public static Map<EquipmentSlot, ItemStack> readGear(CompoundTag tag, HolderLookup.Provider registries) {
        RegistryOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
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
     * inventory entry, stay exactly as saved, so an entry that doesn't read back is never rewritten or lost.
     */
    public static void writeGear(CompoundTag tag, Map<EquipmentSlot, ItemStack> gear, HolderLookup.Provider registries) {
        RegistryOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
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

    /**
     * Carries what happened to a body's gear over to the gear in the save, by GearSync's rules. Only for a body
     * that is being removed, or its copies would exist twice.
     */
    public static void applyBodyGear(CompoundTag tag, Map<EquipmentSlot, ItemStack> bodyGear, HolderLookup.Provider registries) {
        Map<EquipmentSlot, ItemStack> saved = readGear(tag, registries);
        Map<EquipmentSlot, ItemStack> changed = new EnumMap<>(EquipmentSlot.class);
        GearSync.applyBodyGear(bodyGear, slot -> saved.getOrDefault(slot, ItemStack.EMPTY), changed::put);
        writeGear(tag, changed, registries);
    }

    /**
     * Writes a body's state into its owner's save, as a login would have taken it over: where it stands, its
     * health, fall, fire and air, its effects when copyEffects is on, and its gear. Only for a body being removed.
     */
    public static void applySnapshot(CompoundTag tag, BodySnapshot snapshot, boolean copyEffects, HolderLookup.Provider registries) {
        RegistryOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
        ServerPlayer.SavedPosition saved = tag.read(ServerPlayer.SavedPosition.MAP_CODEC).orElse(ServerPlayer.SavedPosition.EMPTY);
        // The body only keeps which way it faces, so the saved pitch stays
        float xRot = saved.rotation().map(rotation -> rotation.y).orElse(0.0F);
        tag.store(ServerPlayer.SavedPosition.MAP_CODEC, new ServerPlayer.SavedPosition(Optional.of(snapshot.dimension()),
                Optional.of(snapshot.pos()), Optional.of(new Vec2(snapshot.yRot(), xRot))));
        tag.putFloat("Health", snapshot.health());
        tag.putFloat("AbsorptionAmount", snapshot.absorption());
        tag.putDouble("fall_distance", snapshot.fallDistance());
        tag.putShort("Fire", (short) snapshot.fireTicks());
        tag.putShort("Air", (short) snapshot.air());
        if (copyEffects) {
            List<MobEffectInstance> before = tag.read(EFFECTS, MobEffectInstance.CODEC.listOf(), ops).orElse(List.of());
            List<AttributeInstance.Packed> attributes = tag.read(ATTRIBUTES, AttributeInstance.Packed.LIST_CODEC, ops)
                    .orElse(List.of());
            tag.store(ATTRIBUTES, AttributeInstance.Packed.LIST_CODEC, ops,
                    swapEffectModifiers(attributes, before, snapshot.effects()));
            // Vanilla leaves the key out when there are no effects
            if (snapshot.effects().isEmpty()) {
                tag.remove(EFFECTS);
            } else {
                tag.store(EFFECTS, MobEffectInstance.CODEC.listOf(), ops, snapshot.effects());
            }
        }
        applyBodyGear(tag, snapshot.gear(), registries);
    }

    public static List<ItemStack> takeEnderChest(CompoundTag tag, HolderLookup.Provider registries) {
        return takeSlots(tag, ENDER_ITEMS, registries.createSerializationContext(NbtOps.INSTANCE));
    }

    /** Falls back to the overworld origin only for a save with no position, which vanilla never writes. */
    public static GlobalPos lastPosition(CompoundTag tag) {
        ServerPlayer.SavedPosition saved = tag.read(ServerPlayer.SavedPosition.MAP_CODEC).orElse(ServerPlayer.SavedPosition.EMPTY);
        return GlobalPos.of(
                saved.dimension().orElse(Level.OVERWORLD),
                saved.position().map(BlockPos::containing).orElse(BlockPos.ZERO));
    }

    // An effect's attribute modifiers are saved with the attributes and loading doesn't add them again, so the old
    // effects' come off and the new ones' go on, as removing and adding the effects on a live player would
    private static List<AttributeInstance.Packed> swapEffectModifiers(List<AttributeInstance.Packed> attributes,
                                                                      List<MobEffectInstance> before,
                                                                      List<MobEffectInstance> after) {
        Map<Holder<Attribute>, Double> bases = new LinkedHashMap<>();
        Map<Holder<Attribute>, List<AttributeModifier>> modifiers = new HashMap<>();
        for (AttributeInstance.Packed packed : attributes) {
            bases.put(packed.attribute(), packed.baseValue());
            modifiers.put(packed.attribute(), new ArrayList<>(packed.modifiers()));
        }
        for (MobEffectInstance effect : before) {
            effect.getEffect().value().createModifiers(effect.getAmplifier(), (attribute, modifier) -> {
                List<AttributeModifier> list = modifiers.get(attribute);
                if (list != null) {
                    list.removeIf(existing -> existing.id().equals(modifier.id()));
                }
            });
        }
        // The save only lists the attributes the player has used. One it lacks starts from a player's default, which is
        // what loading would have given it; one a player doesn't have at all would be dropped by the load anyway.
        AttributeSupplier defaults = DefaultAttributes.getSupplier(EntityTypes.PLAYER);
        for (MobEffectInstance effect : after) {
            effect.getEffect().value().createModifiers(effect.getAmplifier(), (attribute, modifier) -> {
                if (!bases.containsKey(attribute)) {
                    if (!defaults.hasAttribute(attribute)) {
                        return;
                    }
                    bases.put(attribute, defaults.getBaseValue(attribute));
                    modifiers.put(attribute, new ArrayList<>());
                }
                List<AttributeModifier> list = modifiers.get(attribute);
                list.removeIf(existing -> existing.id().equals(modifier.id()));
                list.add(modifier);
            });
        }
        List<AttributeInstance.Packed> swapped = new ArrayList<>();
        bases.forEach((attribute, base) -> swapped.add(new AttributeInstance.Packed(attribute, base, modifiers.get(attribute))));
        return swapped;
    }

    private static int selectedSlot(CompoundTag tag) {
        return tag.getIntOr(SELECTED_SLOT, 0);
    }

    // A missing Slot reads as 0, the same as vanilla's codec
    private static boolean isSlot(Tag entry, int slot) {
        return entry instanceof CompoundTag item && Byte.toUnsignedInt(item.getByteOr("Slot", (byte) 0)) == slot;
    }

    private static List<ItemStack> takeSlots(CompoundTag tag, String key, RegistryOps<Tag> ops) {
        List<ItemStack> items = new ArrayList<>();
        tag.read(key, ItemStackWithSlot.CODEC.listOf(), ops).ifPresent(slots ->
                items.addAll(Stacks.nonEmpty(slots.stream().map(ItemStackWithSlot::stack).toList())));
        tag.remove(key);
        return items;
    }
}
