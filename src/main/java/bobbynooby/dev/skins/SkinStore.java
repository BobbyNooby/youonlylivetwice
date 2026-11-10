package bobbynooby.dev.skins;

import bobbynooby.dev.core.LenientCodecs;
import bobbynooby.dev.core.Migrations;
import bobbynooby.dev.core.StoreVersions;
import bobbynooby.dev.core.UnreadableFiles;
import bobbynooby.dev.core.Yolt;
import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The skins YOLT knows, saved with the world's data: what Mojang said about each account name, the skin each player
 * last joined with, the account each player picked with /yolt skin, the name each player last joined with, and who
 * is still to hear that their pick was reset. Only the server thread reads or writes it.
 */
public final class SkinStore extends SavedData {
    /** The layout of the skin file's own fields. */
    public static final int FORMAT = 1;
    // Each step upgrades the skin file from its format to the next
    private static final List<Migrations.Step<Dynamic<?>>> STEPS = List.of();

    private static final Codec<SkinTextures> TEXTURES_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("value").forGetter(SkinTextures::value),
            Codec.STRING.optionalFieldOf("signature").forGetter(textures -> Optional.ofNullable(textures.signature()))
    ).apply(i, (value, signature) -> new SkinTextures(value, signature.orElse(null))));

    private static final Codec<CachedSkin> SKIN_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("name").forGetter(CachedSkin::name),
            // None for a name no account has
            TEXTURES_CODEC.optionalFieldOf("textures").forGetter(skin -> Optional.ofNullable(skin.textures())),
            Codec.LONG.fieldOf("fetched_at").forGetter(CachedSkin::fetchedAt)
    ).apply(i, (name, textures, fetchedAt) -> new CachedSkin(name, textures.orElse(null), fetchedAt)));

    private record Applied(UUID player, SkinTextures textures) {
        static final Codec<Applied> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("player").forGetter(Applied::player),
                TEXTURES_CODEC.fieldOf("textures").forGetter(Applied::textures)
        ).apply(i, Applied::new));
    }

    private record Choice(UUID player, String name) {
        static final Codec<Choice> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("player").forGetter(Choice::player),
                Codec.STRING.fieldOf("name").forGetter(Choice::name)
        ).apply(i, Choice::new));
    }

    private record Joined(UUID player, String name) {
        static final Codec<Joined> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("player").forGetter(Joined::player),
                Codec.STRING.fieldOf("name").forGetter(Joined::name)
        ).apply(i, Joined::new));
    }

    private record Contents(LenientCodecs.Read<CachedSkin> skins, LenientCodecs.Read<Applied> applied,
                            LenientCodecs.Read<Choice> choices, LenientCodecs.Read<Joined> joined,
                            LenientCodecs.Read<UUID> resets) {
        boolean complete() {
            return skins.complete() && applied.complete() && choices.complete() && joined.complete() && resets.complete();
        }
    }

    // A broken entry is logged and skipped, so one can't make the whole store start empty and be saved over
    private static final MapCodec<Contents> CONTENTS = RecordCodecBuilder.mapCodec(i -> i.group(
            LenientCodecs.read(SKIN_CODEC, "skin").optionalFieldOf("skins", new LenientCodecs.Read<>(List.of(), true))
                    .forGetter(Contents::skins),
            LenientCodecs.read(Applied.CODEC, "applied skin").optionalFieldOf("applied", new LenientCodecs.Read<>(List.of(), true))
                    .forGetter(Contents::applied),
            LenientCodecs.read(Choice.CODEC, "skin choice").optionalFieldOf("choices", new LenientCodecs.Read<>(List.of(), true))
                    .forGetter(Contents::choices),
            // Both added after the first format; a file without them reads them as empty
            LenientCodecs.read(Joined.CODEC, "joined player").optionalFieldOf("joined", new LenientCodecs.Read<>(List.of(), true))
                    .forGetter(Contents::joined),
            LenientCodecs.read(UUIDUtil.CODEC, "skin reset").optionalFieldOf("resets", new LenientCodecs.Read<>(List.of(), true))
                    .forGetter(Contents::resets)
    ).apply(i, Contents::new));

    private static final Codec<SkinStore> CODEC = StoreVersions.codec("skin", FORMAT, STEPS, CONTENTS)
            .xmap(loaded -> new SkinStore(loaded.records(), loaded.fromNewer()), SkinStore::contents);

    // The storage runs this fixer on every load and fails on null. The command-storage fixers don't touch our data.
    public static final SavedDataType<SkinStore> TYPE = new SavedDataType<>(
            Yolt.id("skins"), SkinStore::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    // Keyed by the name in lower case, since Mojang's names ignore case
    private final Map<String, CachedSkin> skins = new LinkedHashMap<>();
    private final Map<UUID, SkinTextures> applied = new LinkedHashMap<>();
    // The Mojang account name each player picked
    private final Map<UUID, String> choices = new LinkedHashMap<>();
    // The name each player last joined with
    private final Map<UUID, String> joined = new LinkedHashMap<>();
    // Players whose pick was reset because its owner joined, until they are told at their next join
    private final Set<UUID> resets = new LinkedHashSet<>();
    // False when the storage started this store empty, because there was no file or it couldn't be read
    private final boolean fromDisk;
    private final boolean fromNewer;
    private final boolean partlyRead;
    private boolean fileChecked;

    private SkinStore() {
        fromDisk = false;
        fromNewer = false;
        partlyRead = false;
    }

    private SkinStore(Contents loaded, boolean fromNewer) {
        fromDisk = true;
        this.fromNewer = fromNewer;
        partlyRead = !loaded.complete();
        for (CachedSkin skin : loaded.skins().values()) {
            skins.put(key(skin.name()), skin);
        }
        for (Applied entry : loaded.applied().values()) {
            applied.put(entry.player(), entry.textures());
        }
        for (Choice choice : loaded.choices().values()) {
            choices.put(choice.player(), choice.name());
        }
        for (Joined entry : loaded.joined().values()) {
            joined.put(entry.player(), entry.name());
        }
        resets.addAll(loaded.resets().values());
    }

    private StoreVersions.Loaded<Contents> contents() {
        List<Applied> appliedList = new ArrayList<>();
        applied.forEach((player, textures) -> appliedList.add(new Applied(player, textures)));
        List<Choice> choiceList = new ArrayList<>();
        choices.forEach((player, name) -> choiceList.add(new Choice(player, name)));
        List<Joined> joinedList = new ArrayList<>();
        joined.forEach((player, name) -> joinedList.add(new Joined(player, name)));
        return new StoreVersions.Loaded<>(new Contents(new LenientCodecs.Read<>(List.copyOf(skins.values()), true),
                new LenientCodecs.Read<>(appliedList, true), new LenientCodecs.Read<>(choiceList, true),
                new LenientCodecs.Read<>(joinedList, true), new LenientCodecs.Read<>(List.copyOf(resets), true)), false);
    }

    public static SkinStore get(MinecraftServer server) {
        SkinStore store = server.getDataStorage().computeIfAbsent(TYPE);
        // Checked on first use, before anything can save, so a file that couldn't be read is kept
        if (!store.fileChecked) {
            store.fileChecked = true;
            store.checkFile(TYPE.id().withSuffix(".dat").resolveAgainst(server.getWorldPath(LevelResource.DATA)));
        }
        return store;
    }

    // The same copies the body and grave files get, so the next save can't lose what this version didn't read
    private void checkFile(Path file) {
        UnreadableFiles.keepCopy(file, fromDisk, "skin");
        if (fromNewer) {
            UnreadableFiles.keepNewerCopy(file, "skin");
        } else if (partlyRead) {
            UnreadableFiles.keepPartlyReadCopy(file, "skin");
        }
    }

    /**
     * Writes the store to disk before returning, so a crash can't lose a pick the player was told about. 26.3 can't
     * save one entry on its own, so this saves every changed entry of the data storage.
     */
    public void saveNow(MinecraftServer server) {
        setDirty();
        server.getDataStorage().saveAndJoin();
    }

    /** True when the file came from a newer YOLT than this one. */
    public boolean fromNewer() {
        return fromNewer;
    }

    /** What Mojang said last about this account name, in any case, or null if it was never asked. */
    public @Nullable CachedSkin skin(String name) {
        return skins.get(key(name));
    }

    public void putSkin(CachedSkin skin) {
        skins.put(key(skin.name()), skin);
        setDirty();
    }

    /** The skin this player last joined with, or null if they had none. */
    public @Nullable SkinTextures applied(UUID player) {
        return applied.get(player);
    }

    public void setApplied(UUID player, @Nullable SkinTextures textures) {
        boolean changed = textures == null ? applied.remove(player) != null : !textures.equals(applied.put(player, textures));
        if (changed) {
            setDirty();
        }
    }

    /** The Mojang account name this player picked, or null if they haven't picked one. */
    public @Nullable String choice(UUID player) {
        return choices.get(player);
    }

    public void choose(UUID player, String name) {
        if (!name.equals(choices.put(player, name))) {
            setDirty();
        }
    }

    /** False when the player had picked nothing. */
    public boolean clearChoice(UUID player) {
        boolean had = choices.remove(player) != null;
        if (had) {
            setDirty();
        }
        return had;
    }

    /** The players other than this one who picked the account with this name, in any case. */
    public List<UUID> pickedBy(String name, UUID except) {
        List<UUID> found = new ArrayList<>();
        choices.forEach((player, picked) -> {
            if (!player.equals(except) && picked.equalsIgnoreCase(name)) {
                found.add(player);
            }
        });
        return found;
    }

    public void noteJoin(UUID player, String name) {
        if (!name.equals(joined.put(player, name))) {
            setDirty();
        }
    }

    /** True when a player other than this one last joined under this name, in any case. */
    public boolean joinedAs(String name, UUID except) {
        for (Map.Entry<UUID, String> entry : joined.entrySet()) {
            if (!entry.getKey().equals(except) && entry.getValue().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** The player is told at their next join that their pick was reset. */
    public void noteReset(UUID player) {
        if (resets.add(player)) {
            setDirty();
        }
    }

    /** True once for a player whose pick was reset, at the join that tells them. */
    public boolean takeReset(UUID player) {
        boolean had = resets.remove(player);
        if (had) {
            setDirty();
        }
        return had;
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
