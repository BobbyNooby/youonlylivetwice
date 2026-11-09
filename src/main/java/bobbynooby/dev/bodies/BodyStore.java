package bobbynooby.dev.bodies;

import bobbynooby.dev.core.LenientCodecs;
import bobbynooby.dev.core.Migrations;
import bobbynooby.dev.core.StoreVersions;
import bobbynooby.dev.core.UnreadableFiles;
import bobbynooby.dev.core.Yolt;
import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Every logged-out player's body record, saved with the world's data. Bodies are few, so lookups just scan. */
public final class BodyStore extends SavedData {
    /** The layout of the body file's own fields. */
    public static final int FORMAT = 1;
    // Each step upgrades the body file from its format to the next
    private static final List<Migrations.Step<Dynamic<?>>> STEPS = List.of();

    // A broken record is logged and skipped; failing here would make the storage start empty and overwrite the file.
    // Effects have no fixer of their own in 26.3 (vanilla fixes them only inside whole entities), so only gear is fixed.
    private static final Codec<BodyStore> CODEC = StoreVersions.codec("body", FORMAT, STEPS,
                    (data, fix) -> StoreVersions.eachInList(data, "bodies",
                            body -> body.update("snapshot", snapshot -> StoreVersions.eachInMap(snapshot, "gear", fix))),
                    LenientCodecs.list(BodyRecord.CODEC, "body").fieldOf("bodies"))
            .xmap(loaded -> new BodyStore(loaded.records(), loaded.fromNewer()),
                    store -> new StoreVersions.Loaded<>(List.copyOf(store.bodies.values()), false));

    // The storage runs this fixer on every load and fails on null, which would lose the bodies after a restart.
    // The command-storage fixers don't touch our data and only run when the data version is older.
    public static final SavedDataType<BodyStore> TYPE = new SavedDataType<>(
            Yolt.id("bodies"), BodyStore::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final Map<UUID, BodyRecord> bodies = new LinkedHashMap<>();
    // False when the storage started this store empty, because there was no file or it couldn't be read
    private final boolean fromDisk;
    // True when the file came from a newer YOLT or Minecraft, so the next save would drop what this one can't read
    private final boolean fromNewer;
    private boolean fileChecked;

    private BodyStore() {
        fromDisk = false;
        fromNewer = false;
    }

    private BodyStore(List<BodyRecord> loaded, boolean fromNewer) {
        fromDisk = true;
        this.fromNewer = fromNewer;
        for (BodyRecord record : loaded) {
            bodies.put(record.owner(), record);
        }
    }

    public static BodyStore get(MinecraftServer server) {
        BodyStore store = server.getDataStorage().computeIfAbsent(TYPE);
        // Checked on first use, before anything can save, so a file that couldn't be read is kept
        if (!store.fileChecked) {
            store.fileChecked = true;
            UnreadableFiles.keepCopy(file(server), store.fromDisk, "body");
            if (store.fromNewer) {
                UnreadableFiles.keepNewerCopy(file(server), "body");
            }
        }
        return store;
    }

    /** Where the storage keeps the bodies. */
    private static Path file(MinecraftServer server) {
        return TYPE.id().withSuffix(".dat").resolveAgainst(server.getWorldPath(LevelResource.DATA));
    }

    /** True when the file came from a newer YOLT or Minecraft than this one. */
    public boolean fromNewer() {
        return fromNewer;
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
