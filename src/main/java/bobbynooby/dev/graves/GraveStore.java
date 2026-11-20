package bobbynooby.dev.graves;

import bobbynooby.dev.core.LenientCodecs;
import bobbynooby.dev.core.Migrations;
import bobbynooby.dev.core.StoreVersions;
import bobbynooby.dev.core.UnreadableFiles;
import bobbynooby.dev.core.Yolt;
import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.SavedDataStorage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongPredicate;

/**
 * Every grave in the world, saved with the world's data. Lookups by spot go through an index by chunk, since they run
 * on every chunk load and for every spot a placement tries.
 */
public final class GraveStore extends SavedData {
    /** The layout of the grave file's own fields. */
    public static final int FORMAT = 1;
    // Each step upgrades the grave file from its format to the next
    private static final List<Migrations.Step<Dynamic<?>>> STEPS = List.of();

    // A broken grave record is logged and skipped; failing here would make the storage start empty and overwrite the file
    private static final Codec<GraveStore> CODEC = StoreVersions.codec("grave", FORMAT, STEPS,
                    (data, fix) -> StoreVersions.eachInList(data, "graves", grave -> StoreVersions.eachInList(grave, "items", fix)),
                    LenientCodecs.read(Grave.CODEC, "grave").fieldOf("graves"))
            .xmap(loaded -> new GraveStore(loaded.records().values(), loaded.fromNewer(), !loaded.records().complete()),
                    store -> new StoreVersions.Loaded<>(new LenientCodecs.Read<>(List.copyOf(store.graves.values()), true), false));

    // The storage runs this fixer on every load and fails on null, which would lose the graves after a restart.
    // The command-storage fixers don't touch our data and only run when the data version is older.
    public static final SavedDataType<GraveStore> TYPE = new SavedDataType<>(
            Yolt.id("graves"), GraveStore::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final Map<UUID, Grave> graves = new LinkedHashMap<>();
    // The same graves by dimension and chunk (ChunkPos.pack). Each list keeps the order of graves above, so a lookup
    // finds the same grave first that a scan of every grave would.
    private final Map<ResourceKey<Level>, Long2ObjectMap<List<Grave>>> byChunk = new HashMap<>();
    // Each grave's place in that order. Putting a grave again keeps its place, as the LinkedHashMap does.
    private final Map<UUID, Long> order = new HashMap<>();
    private long added;
    // False when the storage started this store empty, because there was no file or it couldn't be read
    private final boolean fromDisk;
    // True when the file came from a newer YOLT or Minecraft, so the next save would drop what this one can't read
    private final boolean fromNewer;
    // True when a grave, or an item or part of one, in the file couldn't be read, so the next save would drop it
    private final boolean partlyRead;
    private boolean fileChecked;

    private GraveStore() {
        fromDisk = false;
        fromNewer = false;
        partlyRead = false;
    }

    private GraveStore(List<Grave> loaded, boolean fromNewer, boolean partlyRead) {
        fromDisk = true;
        this.fromNewer = fromNewer;
        this.partlyRead = partlyRead;
        for (Grave grave : loaded) {
            index(grave);
        }
    }

    public static GraveStore get(MinecraftServer server) {
        GraveStore store = server.getDataStorage().computeIfAbsent(TYPE);
        // Most calls find it checked already, and skip working out where the file is
        return store.fileChecked ? store : get(server.getDataStorage(), server.getWorldPath(LevelResource.DATA));
    }

    /** The store in this data storage, which reads its files from dataFolder. Tests pass a storage of their own. */
    public static GraveStore get(SavedDataStorage storage, Path dataFolder) {
        GraveStore store = storage.computeIfAbsent(TYPE);
        // Checked on first use, before anything can save: a dedicated server saves once before its start event,
        // and chunk loads can fetch the graves before that
        if (!store.fileChecked) {
            store.fileChecked = true;
            store.checkFile(file(dataFolder));
        }
        return store;
    }

    /**
     * Keeps a copy of the file this store was loaded from whenever the store doesn't hold all of it, so the next save
     * can't lose the rest: the file couldn't be read at all, it came from a newer version, or part of it was skipped.
     */
    public void checkFile(Path file) {
        UnreadableFiles.keepCopy(file, fromDisk, "grave", "graves");
        if (fromNewer) {
            UnreadableFiles.keepNewerCopy(file, "grave");
        } else if (partlyRead) {
            // A newer file's copy already holds whatever this version skipped
            UnreadableFiles.keepPartlyReadCopy(file, "grave");
        }
    }

    /** Where the storage keeps the graves. */
    private static Path file(MinecraftServer server) {
        return file(server.getWorldPath(LevelResource.DATA));
    }

    private static Path file(Path dataFolder) {
        return TYPE.id().withSuffix(".dat").resolveAgainst(dataFolder);
    }

    /**
     * Writes the store to disk before returning, and throws unless the file then matches it.
     * 26.3 can't save one entry on its own, so this saves every changed entry of the data storage. That save only
     * logs a failed write, which is why the file is read back.
     */
    public void saveNow(MinecraftServer server) {
        setDirty();
        try {
            server.getDataStorage().saveAndJoin();
            checkOnDisk(server);
        } catch (RuntimeException e) {
            // The storage counts the store as saved before it writes, so it is marked again for the next save to
            // write. Only on a failure: a good save leaves it clean, since encoding every grave again takes time.
            setDirty();
            throw e;
        }
    }

    // Throws unless the grave file holds exactly this store
    private void checkOnDisk(MinecraftServer server) {
        Path file = file(server);
        Tag expected = CODEC.encodeStart(server.registryAccess().createSerializationContext(NbtOps.INSTANCE), this).getOrThrow();
        try {
            if (!expected.equals(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).get("data"))) {
                throw new IllegalStateException("The grave file " + file + " does not hold the graves just saved");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read back the grave file " + file, e);
        }
    }

    /** True when the file came from a newer YOLT or Minecraft than this one. */
    public boolean fromNewer() {
        return fromNewer;
    }

    public void put(Grave grave) {
        index(grave);
        setDirty();
    }

    // A grave put again can have moved (withPos), so its old spot's entry goes first
    private void index(Grave grave) {
        Grave old = graves.put(grave.id(), grave);
        if (old != null) {
            unindex(old);
        } else {
            order.put(grave.id(), added++);
        }
        List<Grave> chunk = byChunk.computeIfAbsent(grave.dimension(), dim -> new Long2ObjectOpenHashMap<>())
                .computeIfAbsent(ChunkPos.pack(grave.pos()), key -> new ArrayList<>());
        long place = order.get(grave.id());
        int at = chunk.size();
        while (at > 0 && order.get(chunk.get(at - 1).id()) > place) {
            at--;
        }
        chunk.add(at, grave);
    }

    private void unindex(Grave grave) {
        Long2ObjectMap<List<Grave>> chunks = byChunk.get(grave.dimension());
        long key = ChunkPos.pack(grave.pos());
        List<Grave> chunk = chunks.get(key);
        chunk.removeIf(indexed -> indexed.id().equals(grave.id()));
        // An emptied chunk goes, so the index only ever holds chunks with a grave in them
        if (chunk.isEmpty()) {
            chunks.remove(key);
            if (chunks.isEmpty()) {
                byChunk.remove(grave.dimension());
            }
        }
    }

    private List<Grave> chunk(ResourceKey<Level> dim, long chunk) {
        Long2ObjectMap<List<Grave>> chunks = byChunk.get(dim);
        List<Grave> found = chunks == null ? null : chunks.get(chunk);
        return found == null ? List.of() : found;
    }

    public Optional<Grave> at(ResourceKey<Level> dim, BlockPos pos) {
        for (Grave grave : chunk(dim, ChunkPos.pack(pos))) {
            if (grave.pos().equals(pos)) {
                return Optional.of(grave);
            }
        }
        return Optional.empty();
    }

    public Optional<Grave> byId(UUID id) {
        return Optional.ofNullable(graves.get(id));
    }

    public void remove(UUID id) {
        Grave removed = graves.remove(id);
        if (removed != null) {
            unindex(removed);
            order.remove(id);
            setDirty();
        }
    }

    public Collection<Grave> all() {
        return List.copyOf(graves.values());
    }

    public List<Grave> inDimension(ResourceKey<Level> dim) {
        return inChunks(dim, chunk -> true);
    }

    /**
     * The graves in this dimension whose chunk (ChunkPos.pack) passes the test, in the order of all(). Only chunks that
     * hold a grave are tested.
     */
    public List<Grave> inChunks(ResourceKey<Level> dim, LongPredicate test) {
        List<Grave> found = new ArrayList<>();
        Long2ObjectMap<List<Grave>> chunks = byChunk.get(dim);
        if (chunks != null) {
            for (Long2ObjectMap.Entry<List<Grave>> chunk : chunks.long2ObjectEntrySet()) {
                if (test.test(chunk.getLongKey())) {
                    found.addAll(chunk.getValue());
                }
            }
        }
        found.sort(Comparator.comparingLong(grave -> order.get(grave.id())));
        return found;
    }

    // A copy, since restoring a chunk's graves can move them
    public List<Grave> inChunk(ResourceKey<Level> dim, ChunkPos chunk) {
        return new ArrayList<>(chunk(dim, chunk.pack()));
    }
}
