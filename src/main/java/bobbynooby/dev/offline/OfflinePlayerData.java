package bobbynooby.dev.offline;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
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
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Reads and edits the save file of a player who is not online. Key names follow vanilla's Player save code. */
public final class OfflinePlayerData {
    private static final String INVENTORY = "Inventory";
    private static final String EQUIPMENT = "equipment";
    private static final String ENDER_ITEMS = "EnderItems";

    private OfflinePlayerData() {}

    /** The same load vanilla uses at login, so the tag is already upgraded to the current data version. */
    public static Optional<CompoundTag> load(MinecraftServer server, NameAndId who) {
        return server.getPlayerList().loadPlayerData(who);
    }

    /** Writes a temp file and swaps it in, keeping the previous save as .dat_old, like vanilla's own save. */
    public static void save(MinecraftServer server, UUID uuid, CompoundTag tag) {
        Path dir = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
        try {
            Path tmp = Files.createTempFile(dir, uuid + "-", ".dat");
            // load() upgraded the data, so the old version number would make vanilla upgrade it twice
            NbtIo.writeCompressed(NbtUtils.addCurrentDataVersion(tag), tmp);
            Util.safeReplaceFile(dir.resolve(uuid + ".dat"), tmp, dir.resolve(uuid + ".dat_old"));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save player data for " + uuid, e);
        }
    }

    /** Main inventory plus every equipment slot (armour, offhand), which 26.x saves under its own key. */
    public static List<ItemStack> takeInventory(CompoundTag tag, HolderLookup.Provider registries) {
        RegistryOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
        List<ItemStack> items = takeSlots(tag, INVENTORY, ops);
        tag.read(EQUIPMENT, EntityEquipment.CODEC, ops).ifPresent(equipment -> {
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack item = equipment.get(slot);
                if (!item.isEmpty()) {
                    items.add(item);
                }
            }
        });
        tag.remove(EQUIPMENT);
        return items;
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

    private static List<ItemStack> takeSlots(CompoundTag tag, String key, RegistryOps<Tag> ops) {
        List<ItemStack> items = new ArrayList<>();
        tag.read(key, ItemStackWithSlot.CODEC.listOf(), ops).ifPresent(slots -> {
            for (ItemStackWithSlot slot : slots) {
                if (!slot.stack().isEmpty()) {
                    items.add(slot.stack());
                }
            }
        });
        tag.remove(key);
        return items;
    }
}
