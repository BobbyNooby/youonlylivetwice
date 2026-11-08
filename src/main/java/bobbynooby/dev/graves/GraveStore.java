package bobbynooby.dev.graves;

import bobbynooby.dev.YouOnlyLiveTwice;
import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Every grave in the world, saved with the world's data. Graves are few, so lookups just scan. */
public final class GraveStore extends SavedData {
    // A broken grave record is logged and skipped; failing here would make the storage start empty and overwrite the file
    private static final Codec<GraveStore> CODEC = Codec.of(
            Grave.CODEC.listOf().fieldOf("graves").codec().comap(store -> List.copyOf(store.graves.values())),
            Codec.PASSTHROUGH.listOf().fieldOf("graves").codec().map(GraveStore::readLeniently));

    // The storage runs this fixer on every load and fails on null, which would lose the graves after a restart.
    // The command-storage fixers don't touch our data and only run when the data version is older.
    public static final SavedDataType<GraveStore> TYPE = new SavedDataType<>(
            YouOnlyLiveTwice.id("graves"), GraveStore::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final Map<UUID, Grave> graves = new LinkedHashMap<>();

    private GraveStore() {
    }

    private GraveStore(List<Grave> loaded) {
        for (Grave grave : loaded) {
            graves.put(grave.id(), grave);
        }
    }

    private static GraveStore readLeniently(List<Dynamic<?>> entries) {
        List<Grave> loaded = new ArrayList<>();
        for (Dynamic<?> entry : entries) {
            Grave.CODEC.parse(entry)
                    .resultOrPartial(error -> YouOnlyLiveTwice.LOGGER.error("Skipping unreadable grave record: {}", error))
                    .ifPresent(loaded::add);
        }
        return new GraveStore(loaded);
    }

    public static GraveStore get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
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
