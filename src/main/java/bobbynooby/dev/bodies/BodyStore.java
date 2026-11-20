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
import net.minecraft.world.level.storage.SavedDataStorage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Every logged-out player's body record, saved with the world's data. Records are found by owner or by their body's
 * entity, which a mob's target check and every hit on a body look up.
 */
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
                    LenientCodecs.read(BodyRecord.CODEC, "body").fieldOf("bodies"))
            .xmap(loaded -> new BodyStore(loaded.records().values(), loaded.fromNewer(), !loaded.records().complete()),
                    store -> new StoreVersions.Loaded<>(
                            new LenientCodecs.Read<>(List.copyOf(store.bodies.values()), true), false));

    // The storage runs this fixer on every load and fails on null, which would lose the bodies after a restart.
    // The command-storage fixers don't touch our data and only run when the data version is older.
    public static final SavedDataType<BodyStore> TYPE = new SavedDataType<>(
            Yolt.id("bodies"), BodyStore::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final Map<UUID, BodyRecord> bodies = new LinkedHashMap<>();
    // The same records by their body's entity. Every body is a new entity, so only a file edited by hand can have two
    // records share one.
    private final Map<UUID, List<BodyRecord>> byBody = new HashMap<>();
    // False when the storage started this store empty, because there was no file or it couldn't be read
    private final boolean fromDisk;
    // True when the file came from a newer YOLT or Minecraft, so the next save would drop what this one can't read
    private final boolean fromNewer;
    // True when a record in the file, or part of one, couldn't be read, so the next save would drop it
    private final boolean partlyRead;
    private boolean fileChecked;

    private BodyStore() {
        fromDisk = false;
        fromNewer = false;
        partlyRead = false;
    }

    private BodyStore(List<BodyRecord> loaded, boolean fromNewer, boolean partlyRead) {
        fromDisk = true;
        this.fromNewer = fromNewer;
        this.partlyRead = partlyRead;
        for (BodyRecord record : loaded) {
            index(record);
        }
    }

    public static BodyStore get(MinecraftServer server) {
        BodyStore store = server.getDataStorage().computeIfAbsent(TYPE);
        // Most calls find it checked already, and skip working out where the file is
        return store.fileChecked ? store : get(server.getDataStorage(), server.getWorldPath(LevelResource.DATA));
    }

    /** The store in this data storage, which reads its files from dataFolder. Tests pass a storage of their own. */
    public static BodyStore get(SavedDataStorage storage, Path dataFolder) {
        BodyStore store = storage.computeIfAbsent(TYPE);
        // Checked on first use, before anything can save, so a file that couldn't be read is kept
        if (!store.fileChecked) {
            store.fileChecked = true;
            store.checkFile(TYPE.id().withSuffix(".dat").resolveAgainst(dataFolder));
        }
        return store;
    }

    /**
     * Keeps a copy of the file this store was loaded from whenever the store doesn't hold all of it, so the next save
     * can't lose the rest: the file couldn't be read at all, it came from a newer version, or part of it was skipped.
     */
    public void checkFile(Path file) {
        UnreadableFiles.keepCopy(file, fromDisk, "body", "bodies");
        if (fromNewer) {
            UnreadableFiles.keepNewerCopy(file, "body");
        } else if (partlyRead) {
            // A newer file's copy already holds whatever this version skipped
            UnreadableFiles.keepPartlyReadCopy(file, "body");
        }
    }

    /** True when the file came from a newer YOLT or Minecraft than this one. */
    public boolean fromNewer() {
        return fromNewer;
    }

    /**
     * Writes the body file now, with every other changed entry of the data storage, which 26.3 can't save one by one.
     * A logout calls it: the player's own save is written at every logout, and a body record left for the next world
     * save would be older than that save after a crash, so the next login would take over a body from before it. A
     * failed save is logged, never thrown: the logout goes on, and the next world save tries again.
     */
    public void saveNow(MinecraftServer server) {
        setDirty();
        try {
            server.getDataStorage().saveAndJoin();
        } catch (RuntimeException e) {
            // The storage counts the store as saved before it writes
            setDirty();
            Yolt.LOGGER.error("Could not save the body file at a logout; the next world save tries again", e);
        }
    }

    public void put(BodyRecord record) {
        index(record);
        setDirty();
    }

    private void index(BodyRecord record) {
        BodyRecord old = bodies.put(record.owner(), record);
        if (old != null) {
            unindex(old);
        }
        byBody.computeIfAbsent(record.bodyEntity(), body -> new ArrayList<>(1)).add(record);
    }

    private void unindex(BodyRecord record) {
        List<BodyRecord> sharing = byBody.get(record.bodyEntity());
        sharing.removeIf(indexed -> indexed.owner().equals(record.owner()));
        if (sharing.isEmpty()) {
            byBody.remove(record.bodyEntity());
        }
    }

    public Optional<BodyRecord> byOwner(UUID owner) {
        return Optional.ofNullable(bodies.get(owner));
    }

    public Optional<BodyRecord> byEntity(UUID bodyEntity) {
        List<BodyRecord> found = byBody.get(bodyEntity);
        if (found == null) {
            return Optional.empty();
        }
        if (found.size() == 1) {
            return Optional.of(found.getFirst());
        }
        // Records sharing a body: the first in the store counts, as it always has
        return bodies.values().stream().filter(r -> r.bodyEntity().equals(bodyEntity)).findFirst();
    }

    /** Every record whose body is this entity: at most one, unless the file was edited by hand. */
    public List<BodyRecord> allByEntity(UUID bodyEntity) {
        List<BodyRecord> found = byBody.get(bodyEntity);
        return found == null ? List.of() : List.copyOf(found);
    }

    public void remove(UUID owner) {
        BodyRecord removed = bodies.remove(owner);
        if (removed != null) {
            unindex(removed);
            setDirty();
        }
    }

    public Collection<BodyRecord> all() {
        return List.copyOf(bodies.values());
    }
}
