package bobbynooby.dev.bodies;

import bobbynooby.dev.YouOnlyLiveTwice;
import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Every logged-out player's body record, saved with the world's data. Bodies are few, so lookups just scan. */
public final class BodyStore extends SavedData {
    // A broken record is logged and skipped; failing here would make the storage start empty and overwrite the file
    private static final Codec<BodyStore> CODEC = Codec.of(
            BodyRecord.CODEC.listOf().fieldOf("bodies").codec().comap(store -> List.copyOf(store.bodies.values())),
            Codec.PASSTHROUGH.listOf().fieldOf("bodies").codec().map(BodyStore::readLeniently));

    // The storage runs this fixer on every load and fails on null, which would lose the bodies after a restart.
    // The command-storage fixers don't touch our data and only run when the data version is older.
    public static final SavedDataType<BodyStore> TYPE = new SavedDataType<>(
            YouOnlyLiveTwice.id("bodies"), BodyStore::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final Map<UUID, BodyRecord> bodies = new LinkedHashMap<>();
    // False when the storage started this store empty, because there was no file or it couldn't be read
    private final boolean fromDisk;
    private boolean fileChecked;

    private BodyStore() {
        fromDisk = false;
    }

    private BodyStore(List<BodyRecord> loaded) {
        fromDisk = true;
        for (BodyRecord record : loaded) {
            bodies.put(record.owner(), record);
        }
    }

    private static BodyStore readLeniently(List<Dynamic<?>> entries) {
        List<BodyRecord> loaded = new ArrayList<>();
        for (Dynamic<?> entry : entries) {
            BodyRecord.CODEC.parse(entry)
                    .resultOrPartial(error -> YouOnlyLiveTwice.LOGGER.error("Skipping unreadable body record: {}", error))
                    .ifPresent(loaded::add);
        }
        return new BodyStore(loaded);
    }

    public static BodyStore get(MinecraftServer server) {
        BodyStore store = server.getDataStorage().computeIfAbsent(TYPE);
        // Checked on first use, before anything can save, so a file that couldn't be read is kept
        if (!store.fileChecked) {
            store.fileChecked = true;
            keepUnreadableCopy(file(server), store.fromDisk);
        }
        return store;
    }

    /** Where the storage keeps the bodies. */
    public static Path file(MinecraftServer server) {
        return TYPE.id().withSuffix(".dat").resolveAgainst(server.getWorldPath(LevelResource.DATA));
    }

    /** A store not read from a file that exists means the file was unreadable; keep a copy before a save overwrites it. */
    private static void keepUnreadableCopy(Path file, boolean fromDisk) {
        if (fromDisk || !Files.exists(file)) {
            return;
        }
        Path copy = file.resolveSibling(file.getFileName() + ".unreadable-" + System.currentTimeMillis());
        try {
            Files.copy(file, copy);
            YouOnlyLiveTwice.LOGGER.error("Could not read the body file {}, so bodies start empty. A copy of it is kept at {}",
                    file, copy);
        } catch (IOException e) {
            YouOnlyLiveTwice.LOGGER.error("Could not read the body file {}, and could not copy it to {}; bodies start empty "
                    + "and the next save overwrites it", file, copy, e);
        }
    }

    public void put(BodyRecord record) {
        bodies.put(record.owner(), record);
        setDirty();
    }

    public Optional<BodyRecord> byOwner(UUID owner) {
        return Optional.ofNullable(bodies.get(owner));
    }

    public Optional<BodyRecord> byEntity(UUID bodyEntity) {
        return bodies.values().stream().filter(r -> r.bodyEntity().equals(bodyEntity)).findFirst();
    }

    public void remove(UUID owner) {
        if (bodies.remove(owner) != null) {
            setDirty();
        }
    }

    public Collection<BodyRecord> all() {
        return List.copyOf(bodies.values());
    }
}
