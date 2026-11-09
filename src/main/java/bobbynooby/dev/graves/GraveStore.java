package bobbynooby.dev.graves;

import bobbynooby.dev.core.LenientCodecs;
import bobbynooby.dev.core.Migrations;
import bobbynooby.dev.core.StoreVersions;
import bobbynooby.dev.core.UnreadableFiles;
import bobbynooby.dev.core.Yolt;
import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Every grave in the world, saved with the world's data. Graves are few, so lookups just scan. */
public final class GraveStore extends SavedData {
    /** The layout of the grave file's own fields. */
    public static final int FORMAT = 1;
    // Each step upgrades the grave file from its format to the next
    private static final List<Migrations.Step<Dynamic<?>>> STEPS = List.of();

    // A broken grave record is logged and skipped; failing here would make the storage start empty and overwrite the file
    private static final Codec<GraveStore> CODEC = StoreVersions.codec("grave", FORMAT, STEPS,
                    (data, fix) -> StoreVersions.eachInList(data, "graves", grave -> StoreVersions.eachInList(grave, "items", fix)),
                    LenientCodecs.list(Grave.CODEC, "grave").fieldOf("graves"))
            .xmap(loaded -> new GraveStore(loaded.records(), loaded.fromNewer()),
                    store -> new StoreVersions.Loaded<>(List.copyOf(store.graves.values()), false));

    // The storage runs this fixer on every load and fails on null, which would lose the graves after a restart.
    // The command-storage fixers don't touch our data and only run when the data version is older.
    public static final SavedDataType<GraveStore> TYPE = new SavedDataType<>(
            Yolt.id("graves"), GraveStore::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final Map<UUID, Grave> graves = new LinkedHashMap<>();
    // False when the storage started this store empty, because there was no file or it couldn't be read
    private final boolean fromDisk;
    // True when the file came from a newer YOLT or Minecraft, so the next save would drop what this one can't read
    private final boolean fromNewer;
    private boolean fileChecked;

    private GraveStore() {
        fromDisk = false;
        fromNewer = false;
    }

    private GraveStore(List<Grave> loaded, boolean fromNewer) {
        fromDisk = true;
        this.fromNewer = fromNewer;
        for (Grave grave : loaded) {
            graves.put(grave.id(), grave);
        }
    }

    public static GraveStore get(MinecraftServer server) {
        GraveStore store = server.getDataStorage().computeIfAbsent(TYPE);
        // Checked on first use, before anything can save: a dedicated server saves once before its start event,
        // and chunk loads can fetch the graves before that
        if (!store.fileChecked) {
            store.fileChecked = true;
            UnreadableFiles.keepCopy(file(server), store.fromDisk, "grave");
            if (store.fromNewer) {
                UnreadableFiles.keepNewerCopy(file(server), "grave");
            }
        }
        return store;
    }

    /** Where the storage keeps the graves. */
    private static Path file(MinecraftServer server) {
        return TYPE.id().withSuffix(".dat").resolveAgainst(server.getWorldPath(LevelResource.DATA));
    }

    /**
     * Writes the store to disk before returning, and throws unless the file then matches it.
     * 26.3 can't save one entry on its own, so this saves every changed entry of the data storage. That save only
     * logs a failed write, which is why the file is read back.
     */
    public void saveNow(MinecraftServer server) {
        setDirty();
        server.getDataStorage().saveAndJoin();
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
        graves.put(grave.id(), grave);
        setDirty();
    }

    public Optional<Grave> at(ResourceKey<Level> dim, BlockPos pos) {
        return graves.values().stream().filter(g -> g.dimension().equals(dim) && g.pos().equals(pos)).findFirst();
    }

    public Optional<Grave> byId(UUID id) {
        return Optional.ofNullable(graves.get(id));
    }

    public void remove(UUID id) {
        if (graves.remove(id) != null) {
            setDirty();
        }
    }

    public Collection<Grave> all() {
        return List.copyOf(graves.values());
    }

    public List<Grave> inChunk(ResourceKey<Level> dim, ChunkPos chunk) {
        List<Grave> found = new ArrayList<>();
        for (Grave grave : graves.values()) {
            if (grave.dimension().equals(dim) && ChunkPos.containing(grave.pos()).equals(chunk)) {
                found.add(grave);
            }
        }
        return found;
    }
}
