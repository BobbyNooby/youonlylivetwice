package bobbynooby.dev.gametest;

import bobbynooby.dev.bodies.BodyStore;
import bobbynooby.dev.core.StoreVersions;
import bobbynooby.dev.core.UnreadableFiles;
import bobbynooby.dev.graves.Grave;
import bobbynooby.dev.graves.GraveStore;
import bobbynooby.dev.skins.SkinStore;
import com.mojang.serialization.DataResult;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class GraveStoreGameTests {
    private static Grave grave(BlockPos pos, List<ItemStack> items) {
        return new Grave(UUID.randomUUID(), UUID.randomUUID(), "Alex", ResolvableProfile.createUnresolved("Alex"),
                Level.OVERWORLD, pos, items, 1234L);
    }

    private static Grave roundTrip(GameTestHelper helper, Grave grave) {
        RegistryOps<Tag> ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
        Tag encoded = Grave.CODEC.encodeStart(ops, grave).getOrThrow();
        DataResult<Grave> decoded = Grave.CODEC.parse(ops, encoded);
        return decoded.getOrThrow();
    }

    @GameTest
    public void graveRoundTripsThroughCodec(GameTestHelper helper) {
        Grave before = grave(new BlockPos(100, 64, -200), List.of(new ItemStack(Items.DIAMOND, 5), new ItemStack(Items.APPLE, 2)));
        Grave after = roundTrip(helper, before);

        helper.assertValueEqual(after.id(), before.id(), "id");
        helper.assertValueEqual(after.owner(), before.owner(), "owner");
        helper.assertValueEqual(after.dimension(), before.dimension(), "dimension");
        helper.assertValueEqual(after.pos(), before.pos(), "pos");
        helper.assertValueEqual(after.createdAt(), before.createdAt(), "createdAt");
        helper.assertValueEqual(after.items().size(), 2, "item count");
        helper.assertTrue(after.items().get(0).is(Items.DIAMOND) && after.items().get(0).getCount() == 5, "diamonds changed");
        helper.assertTrue(after.items().get(1).is(Items.APPLE) && after.items().get(1).getCount() == 2, "apples changed");
        helper.assertTrue(after.deathPos() == null, "a grave with no death spot came back with one: " + after.deathPos());

        // The death spot goes into the file too, so a login after a restart can still tell how far the grave went
        GlobalPos died = GlobalPos.of(Level.NETHER, new BlockPos(7, -70, -9));
        Grave withDeath = new Grave(before.id(), before.owner(), before.ownerName(), before.profile(), before.dimension(), before.pos(),
                before.items(), before.createdAt(), died);
        helper.assertValueEqual(roundTrip(helper, withDeath).deathPos(), died, "death spot");
        helper.succeed();
    }

    @GameTest
    public void storeFindsGraveByPositionAndChunk(GameTestHelper helper) {
        GraveStore store = GraveStore.get(helper.getLevel().getServer());
        BlockPos pos = new BlockPos(30000, 70, 30000);
        Grave grave = grave(pos, List.of(new ItemStack(Items.DIAMOND)));
        try {
            store.put(grave);
            helper.assertTrue(store.at(Level.OVERWORLD, pos).isPresent(), "grave not found by position");
            helper.assertTrue(store.byId(grave.id()).isPresent(), "grave not found by id");
            helper.assertTrue(store.inChunk(Level.OVERWORLD, ChunkPos.containing(pos)).stream().anyMatch(g -> g.id().equals(grave.id())),
                    "grave not found by chunk");
            helper.assertTrue(store.inChunk(Level.NETHER, ChunkPos.containing(pos)).stream().noneMatch(g -> g.id().equals(grave.id())),
                    "grave found in the wrong dimension");
        } finally {
            store.remove(grave.id());
        }
        helper.assertTrue(store.at(Level.OVERWORLD, pos).isEmpty(), "grave still there after remove");
        helper.assertTrue(store.all().stream().noneMatch(g -> g.id().equals(grave.id())), "grave still listed after remove");
        helper.succeed();
    }

    // The lookups by spot go through an index by chunk. Hundreds of graves crowd a few chunks of three dimensions, some
    // share a spot, some move within or out of their chunk and some go and come back, and every lookup must still give
    // what a scan of every grave, in the store's order, gives. So must the same store read back from its file.
    @GameTest
    public void chunkIndexMatchesScan(GameTestHelper helper) {
        RegistryOps<Tag> ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
        GraveStore store = GraveStore.TYPE.codec().parse(ops, storeData()).getOrThrow();
        Random random = new Random(26);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            Grave grave = new Grave(UUID.randomUUID(), UUID.randomUUID(), "Alex", ResolvableProfile.createUnresolved("Alex"),
                    INDEX_DIMENSIONS.get(random.nextInt(INDEX_DIMENSIONS.size())), indexSpot(random), List.of(), i);
            store.put(grave);
            ids.add(grave.id());
        }
        for (int i = 0; i < 200; i++) {
            UUID id = ids.get(random.nextInt(ids.size()));
            switch (random.nextInt(4)) {
                case 0 -> store.byId(id).ifPresent(grave -> store.put(grave.withPos(indexSpot(random))));
                // Within its own chunk, so the grave's place among the chunk's others must hold
                case 1 -> store.byId(id).ifPresent(grave -> store.put(grave.withPos(grave.pos().above())));
                // Changed where it stands, as handing out its compass does
                case 2 -> store.byId(id).ifPresent(grave -> store.put(grave.withCompassPending(!grave.compassPending())));
                default -> {
                    Optional<Grave> gone = store.byId(id);
                    store.remove(id);
                    // Half come back, last in the store's order like a new grave
                    if (random.nextBoolean()) {
                        gone.ifPresent(store::put);
                    }
                }
            }
        }
        helper.assertTrue(store.all().size() > 200, "too few graves left to test: " + store.all().size());
        assertIndexMatchesScan(helper, store, "store");
        GraveStore loaded = GraveStore.TYPE.codec().parse(ops, GraveStore.TYPE.codec().encodeStart(ops, store).getOrThrow()).getOrThrow();
        assertIndexMatchesScan(helper, loaded, "store read back");
        helper.succeed();
    }

    private static final List<ResourceKey<Level>> INDEX_DIMENSIONS = List.of(Level.OVERWORLD, Level.NETHER, Level.END);

    // Four chunks a side with few heights, so spots are shared now and then
    private static BlockPos indexSpot(Random random) {
        return new BlockPos(random.nextInt(64) - 32, 60 + random.nextInt(3), random.nextInt(64) - 32);
    }

    private static void assertIndexMatchesScan(GameTestHelper helper, GraveStore store, String which) {
        List<Grave> all = List.copyOf(store.all());
        for (ResourceKey<Level> dim : INDEX_DIMENSIONS) {
            helper.assertValueEqual(store.inDimension(dim), all.stream().filter(g -> g.dimension().equals(dim)).toList(),
                    which + ": graves in " + dim.identifier());
            helper.assertValueEqual(store.inChunks(dim, chunk -> ChunkPos.getX(chunk) % 2 == 0),
                    all.stream().filter(g -> g.dimension().equals(dim) && ChunkPos.containing(g.pos()).x() % 2 == 0).toList(),
                    which + ": graves in even chunks of " + dim.identifier());
            for (int x = -3; x <= 2; x++) {
                for (int z = -3; z <= 2; z++) {
                    ChunkPos chunk = new ChunkPos(x, z);
                    helper.assertValueEqual(store.inChunk(dim, chunk),
                            all.stream().filter(g -> g.dimension().equals(dim) && ChunkPos.containing(g.pos()).equals(chunk)).toList(),
                            which + ": graves in chunk " + chunk + " of " + dim.identifier());
                }
            }
            for (int x = -33; x <= 32; x++) {
                for (int z = -33; z <= 32; z++) {
                    for (int y = 59; y <= 63; y++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        helper.assertValueEqual(store.at(dim, pos),
                                all.stream().filter(g -> g.dimension().equals(dim) && g.pos().equals(pos)).findFirst(),
                                which + ": grave at " + pos + " in " + dim.identifier());
                    }
                }
            }
        }
    }

    @GameTest
    public void shulkerContentsSurviveRoundTrip(GameTestHelper helper) {
        ItemStack shulker = new ItemStack(Items.SHULKER_BOX);
        shulker.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND, 3))));
        Grave after = roundTrip(helper, grave(new BlockPos(0, 64, 0), List.of(shulker)));

        ItemContainerContents contents = after.items().get(0).get(DataComponents.CONTAINER);
        helper.assertTrue(contents != null, "container component was lost");
        int diamonds = contents.nonEmptyItemCopyStream().filter(s -> s.is(Items.DIAMOND)).mapToInt(ItemStack::getCount).sum();
        helper.assertValueEqual(diamonds, 3, "diamonds inside the shulker box");
        helper.succeed();
    }

    @GameTest
    public void storeCodecRoundTrips(GameTestHelper helper) {
        RegistryOps<Tag> ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
        GraveStore store = GraveStore.get(helper.getLevel().getServer());
        Grave a = grave(new BlockPos(1, 64, 1), List.of(new ItemStack(Items.DIAMOND, 4)));
        Grave b = grave(new BlockPos(2, 64, 2), List.of(new ItemStack(Items.APPLE, 7)));
        try {
            store.put(a);
            store.put(b);
            Tag encoded = GraveStore.TYPE.codec().encodeStart(ops, store).getOrThrow();
            GraveStore decoded = GraveStore.TYPE.codec().parse(ops, encoded).getOrThrow();
            helper.assertTrue(decoded.byId(a.id()).map(g -> g.items().size() == 1 && g.items().get(0).is(Items.DIAMOND)
                    && g.items().get(0).getCount() == 4).orElse(false), "first grave or its items missing");
            helper.assertTrue(decoded.byId(b.id()).map(g -> g.items().size() == 1 && g.items().get(0).is(Items.APPLE)
                    && g.items().get(0).getCount() == 7).orElse(false), "second grave or its items missing");
        } finally {
            store.remove(a.id());
            store.remove(b.id());
        }
        helper.succeed();
    }

    @GameTest
    public void badItemOnlyDropsThatItem(GameTestHelper helper) {
        RegistryOps<Tag> ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
        Grave grave = grave(new BlockPos(3, 64, 3), List.of(new ItemStack(Items.DIAMOND, 2), new ItemStack(Items.APPLE, 3)));
        CompoundTag tag = (CompoundTag) Grave.CODEC.encodeStart(ops, grave).getOrThrow();
        ListTag items = tag.getListOrEmpty("items");
        items.getCompound(0).orElseThrow().putString("id", "minecraft:does_not_exist");

        DataResult<Grave> parsed = Grave.CODEC.parse(ops, tag);
        // Read in part: an error that still carries the grave, so its store knows to keep a copy of the file
        helper.assertTrue(parsed.isError(), "a grave missing an item read as whole");
        Grave after = parsed.resultOrPartial().orElseThrow();
        helper.assertValueEqual(after.id(), grave.id(), "id");
        helper.assertValueEqual(after.items().size(), 1, "surviving item count");
        helper.assertTrue(after.items().get(0).is(Items.APPLE) && after.items().get(0).getCount() == 3, "other item changed");
        helper.succeed();
    }

    @GameTest
    public void emptyStacksAreFiltered(GameTestHelper helper) {
        Grave grave = grave(new BlockPos(4, 64, 4), List.of(ItemStack.EMPTY, new ItemStack(Items.DIAMOND)));
        helper.assertValueEqual(grave.items().size(), 1, "items after filtering");
        helper.assertValueEqual(grave.withItems(List.of(ItemStack.EMPTY)).items().size(), 0, "items after withItems");
        roundTrip(helper, grave);
        helper.succeed();
    }

    // Junk in a temp file stands in for a grave file the storage couldn't read
    @GameTest
    public void unreadableGraveFileIsCopied(GameTestHelper helper) throws IOException {
        Path dir = helper.getLevel().getServer().getWorldPath(LevelResource.DATA);
        Files.createDirectories(dir);
        Path file = Files.createTempFile(dir, "graves-test-", ".dat");
        byte[] junk = {1, 2, 3, 4, 5};
        try {
            Files.write(file, junk);
            helper.assertTrue(UnreadableFiles.keepCopy(file, true, "grave", "graves").isEmpty(), "copied a file the store was read from");
            helper.assertTrue(copiesOf(file).isEmpty(), "a copy appeared for a file the store was read from");
            helper.assertTrue(UnreadableFiles.keepCopy(dir.resolve(file.getFileName() + ".missing"), false, "grave", "graves").isEmpty(),
                    "copied a file that isn't there");

            Optional<Path> copy = UnreadableFiles.keepCopy(file, false, "grave", "graves");
            helper.assertTrue(copy.isPresent() && Files.exists(copy.get()), "no copy of the unreadable file");
            helper.assertValueEqual(copiesOf(file).size(), 1, "copies next to the file");
            helper.assertTrue(Arrays.equals(Files.readAllBytes(copy.get()), junk), "the copy differs from the file");
        } finally {
            Files.deleteIfExists(file);
            for (Path left : copiesOf(file)) {
                Files.deleteIfExists(left);
            }
        }
        helper.succeed();
    }

    // Junk as the grave file of a data storage of the test's own, so get reads it the way the server reads its own at
    // start: the store starts empty, and a copy of the file is kept once
    @GameTest
    public void unreadableGraveFileCopiedThroughGet(GameTestHelper helper) throws IOException {
        Path folder = Files.createTempDirectory("yolt-store-test-");
        Path file = GraveStore.TYPE.id().withSuffix(".dat").resolveAgainst(folder);
        byte[] junk = {1, 2, 3, 4, 5};
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, junk);
            SavedDataStorage storage = freshStorage(helper, folder);
            List<GraveStore> got = new ArrayList<>();
            List<String> log = OfflineGameTests.yoltLog(() -> got.add(GraveStore.get(storage, folder)));
            GraveStore store = got.get(0);
            helper.assertTrue(store.all().isEmpty(), "graves read from junk: " + store.all());
            List<Path> copies = copiesOf(file);
            helper.assertValueEqual(copies.size(), 1, "copies next to the broken file");
            helper.assertTrue(Arrays.equals(Files.readAllBytes(copies.get(0)), junk), "the copy differs from the file");
            String line = "Could not read the grave file " + file + ", so graves start empty. A copy of it is kept at " + copies.get(0);
            helper.assertValueEqual(log.stream().filter(entry -> entry.startsWith("Could not read the grave file ")).toList(),
                    List.of(line), "the copy's log lines");
            // Checked on first use only, so a second get keeps no second copy
            helper.assertTrue(GraveStore.get(storage, folder) == store, "a second get made a new store");
            helper.assertValueEqual(copiesOf(file).size(), 1, "copies after a second get");
        } finally {
            deleteFolder(folder);
        }
        helper.succeed();
    }

    // The skin store's save at once, with a folder where its file goes, so the write fails. The storage only logs that
    // and counts the store as saved, so the store must stay marked as changed: the world's next save then writes the
    // pick. The file is written again in this tick.
    @GameTest
    public void skinPickSurvivesAFailedSaveNow(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        SkinStore store = SkinStore.get(server);
        UUID player = UUID.randomUUID();
        Path file = SkinStore.TYPE.id().withSuffix(".dat").resolveAgainst(server.getWorldPath(LevelResource.DATA));
        try {
            store.choose(player, "Picked");
            Files.deleteIfExists(file);
            Files.createDirectories(file);
            store.saveNow(server);
            helper.assertTrue(store.isDirty(), "the failed write left the skin store marked as saved");

            Files.delete(file);
            server.getDataStorage().saveAndJoin();
            helper.assertTrue(Files.isRegularFile(file), "the next save did not write the skin file");
            RegistryOps<Tag> ops = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
            SkinStore onDisk = SkinStore.TYPE.codec()
                    .parse(ops, NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).get("data")).getOrThrow();
            helper.assertValueEqual(onDisk.choice(player), "Picked", "the pick in the skin file");
        } finally {
            if (Files.isDirectory(file)) {
                Files.delete(file);
            }
            store.clearChoice(player);
            server.getDataStorage().saveAndJoin();
        }
        helper.succeed();
    }

    // The save goes through a temp file and a move, so the file reads whole and nothing is left next to it. Junk left in
    // the temp file's place first shows the save went that way: vanilla's own write would leave it there.
    @GameTest
    public void graveSaveLeavesNoTempFile(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        GraveStore store = GraveStore.get(server);
        Path file = GraveStore.TYPE.id().withSuffix(".dat").resolveAgainst(server.getWorldPath(LevelResource.DATA));
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Grave grave = grave(new BlockPos(29_000_000, 70, 29_000_000), List.of(new ItemStack(Items.DIAMOND)));
        try {
            Files.createDirectories(file.getParent());
            Files.write(tmp, new byte[] {1, 2, 3, 4, 5});
            store.put(grave);
            store.saveNow(server);
            helper.assertTrue(onDisk(server).stream().anyMatch(g -> g.id().equals(grave.id())), "the grave is not in the grave file");
            helper.assertFalse(Files.exists(tmp), "a temp file sits beside the grave file");
        } finally {
            Files.deleteIfExists(tmp);
            store.remove(grave.id());
            store.saveNow(server);
        }
        helper.succeed();
    }

    // A grave file from a newer YOLT, its format one past this one's, as the grave file of a data storage of the test's
    // own. get reads what it can and keeps a copy of the file before anything saves over it, as a newer copy, not as an
    // unreadable one.
    @GameTest
    public void newerGraveFileCopiedThroughGet(GameTestHelper helper) throws IOException {
        RegistryOps<Tag> ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
        Grave grave = grave(new BlockPos(5, 64, 5), List.of(new ItemStack(Items.DIAMOND)));
        CompoundTag data = storeData(Grave.CODEC.encodeStart(ops, grave).getOrThrow());
        data.putInt("format", GraveStore.FORMAT + 1);
        CompoundTag root = new CompoundTag();
        root.put("data", data);
        NbtUtils.addCurrentDataVersion(root);
        Path folder = Files.createTempDirectory("yolt-store-test-");
        Path file = GraveStore.TYPE.id().withSuffix(".dat").resolveAgainst(folder);
        try {
            Files.createDirectories(file.getParent());
            NbtIo.writeCompressed(root, file);
            byte[] written = Files.readAllBytes(file);
            SavedDataStorage storage = freshStorage(helper, folder);
            List<GraveStore> got = new ArrayList<>();
            List<String> log = OfflineGameTests.yoltLog(() -> got.add(GraveStore.get(storage, folder)));
            GraveStore store = got.get(0);
            helper.assertTrue(store.fromNewer(), "the newer file did not read as newer");
            helper.assertTrue(store.byId(grave.id()).filter(read -> GraveAsserts.count(read.items(), Items.DIAMOND) == 1).isPresent(),
                    "the newer file's grave was not read");
            List<Path> copies = copiesOf(file, ".newer-");
            helper.assertValueEqual(copies.size(), 1, "copies of the newer file");
            helper.assertTrue(Arrays.equals(Files.readAllBytes(copies.get(0)), written), "the copy differs from the file");
            helper.assertTrue(copiesOf(file).isEmpty(), "the newer file was also kept as unreadable");
            String line = "The grave file " + file + " is from a newer version, so a copy of it is kept at " + copies.get(0)
                    + " before it is saved over";
            helper.assertValueEqual(log.stream().filter(entry -> entry.startsWith("The grave file ")).toList(), List.of(line),
                    "the copy's log lines");
        } finally {
            deleteFolder(folder);
        }
        helper.succeed();
    }

    // YOLT's three stores are written to a temp file that then takes the old file's place, so a crash mid-save can't cut
    // one short. The file's inode changing at each save shows the swap; vanilla's scoreboard file, written in place,
    // keeps its inode, so the check can tell the two apart. Nothing is left beside the files.
    @GameTest
    public void yoltStoresAreSavedThroughATempFile(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        Path data = server.getWorldPath(LevelResource.DATA);
        GraveStore graves = GraveStore.get(server);
        BodyStore bodies = BodyStore.get(server);
        SkinStore skins = SkinStore.get(server);
        Map<String, Path> files = new LinkedHashMap<>();
        files.put("grave", GraveStore.TYPE.id().withSuffix(".dat").resolveAgainst(data));
        files.put("body", BodyStore.TYPE.id().withSuffix(".dat").resolveAgainst(data));
        files.put("skin", SkinStore.TYPE.id().withSuffix(".dat").resolveAgainst(data));
        Path scoreboardFile = data.resolve("minecraft").resolve("scoreboard.dat");
        Scoreboard scoreboard = server.getScoreboard();
        String teamName = "yolt_inode_" + UUID.randomUUID().toString().substring(0, 6);
        Grave grave = grave(new BlockPos(29_000_000, 70, 29_000_000), List.of(new ItemStack(Items.DIAMOND)));
        try {
            graves.put(grave);
            bodies.setDirty();
            skins.setDirty();
            PlayerTeam team = scoreboard.addPlayerTeam(teamName);
            server.getDataStorage().saveAndJoin();
            Map<String, Object> first = new LinkedHashMap<>();
            for (Map.Entry<String, Path> each : files.entrySet()) {
                helper.assertTrue(Files.exists(each.getValue()), "no " + each.getKey() + " file after the first save");
                first.put(each.getKey(), Files.getAttribute(each.getValue(), "unix:ino"));
            }
            helper.assertTrue(Files.exists(scoreboardFile), "no scoreboard file after the first save");
            Object scoreboardFirst = Files.getAttribute(scoreboardFile, "unix:ino");

            graves.setDirty();
            bodies.setDirty();
            skins.setDirty();
            scoreboard.removePlayerTeam(team);
            server.getDataStorage().saveAndJoin();
            for (Map.Entry<String, Path> each : files.entrySet()) {
                helper.assertFalse(Files.getAttribute(each.getValue(), "unix:ino").equals(first.get(each.getKey())),
                        "the " + each.getKey() + " file was written in place");
                helper.assertFalse(Files.exists(each.getValue().resolveSibling(each.getValue().getFileName() + ".tmp")),
                        "a temp file sits beside the " + each.getKey() + " file");
            }
            helper.assertValueEqual(Files.getAttribute(scoreboardFile, "unix:ino"), scoreboardFirst,
                    "the scoreboard file's inode, which vanilla writes in place");
        } finally {
            PlayerTeam left = scoreboard.getPlayerTeam(teamName);
            if (left != null) {
                scoreboard.removePlayerTeam(left);
            }
            graves.remove(grave.id());
            graves.saveNow(server);
        }
        helper.succeed();
    }

    private static List<Path> copiesOf(Path file, String kind) throws IOException {
        String prefix = file.getFileName() + kind;
        try (Stream<Path> files = Files.list(file.getParent())) {
            return files.filter(path -> path.getFileName().toString().startsWith(prefix)).toList();
        }
    }

    // The second grave has no position, so it can't be read at all
    @GameTest
    public void skippedGraveRecordKeepsFileCopy(GameTestHelper helper) throws IOException {
        RegistryOps<Tag> ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
        Grave kept = grave(new BlockPos(5, 64, 5), List.of(new ItemStack(Items.DIAMOND)));
        Grave broken = grave(new BlockPos(6, 64, 6), List.of(new ItemStack(Items.APPLE)));
        Tag keptTag = Grave.CODEC.encodeStart(ops, kept).getOrThrow();
        CompoundTag brokenTag = (CompoundTag) Grave.CODEC.encodeStart(ops, broken).getOrThrow();
        brokenTag.remove("pos");

        GraveStore loaded = GraveStore.TYPE.codec().parse(ops, storeData(keptTag, brokenTag)).getOrThrow();
        helper.assertTrue(loaded.byId(kept.id()).isPresent(), "the readable grave was not loaded");
        helper.assertTrue(loaded.byId(broken.id()).isEmpty(), "the grave with no position was loaded");
        assertFileCopiedOnce(helper, loaded::checkFile, storeData(keptTag, brokenTag));
        assertFileNotCopied(helper, GraveStore.TYPE.codec().parse(ops, storeData(keptTag)).getOrThrow()::checkFile, storeData(keptTag));
        helper.succeed();
    }

    // The grave's second item has an id no item has, so the grave loads without it
    @GameTest
    public void skippedGraveItemKeepsFileCopy(GameTestHelper helper) throws IOException {
        RegistryOps<Tag> ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
        Grave grave = grave(new BlockPos(7, 64, 7), List.of(new ItemStack(Items.DIAMOND, 2), new ItemStack(Items.APPLE, 3)));
        Tag wholeTag = Grave.CODEC.encodeStart(ops, grave).getOrThrow();
        CompoundTag brokenTag = (CompoundTag) wholeTag.copy();
        brokenTag.getListOrEmpty("items").getCompound(1).orElseThrow().putString("id", "minecraft:does_not_exist");

        GraveStore loaded = GraveStore.TYPE.codec().parse(ops, storeData(brokenTag)).getOrThrow();
        List<ItemStack> items = loaded.byId(grave.id()).map(Grave::items).orElse(List.of());
        helper.assertTrue(items.size() == 1 && items.get(0).is(Items.DIAMOND) && items.get(0).getCount() == 2,
                "the grave did not keep just its readable item: " + items);
        assertFileCopiedOnce(helper, loaded::checkFile, storeData(brokenTag));
        assertFileNotCopied(helper, GraveStore.TYPE.codec().parse(ops, storeData(wholeTag)).getOrThrow()::checkFile, storeData(wholeTag));
        helper.succeed();
    }

    // A death spot that can't be read is only a hint lost: the grave loads whole with its items, without the hint and
    // with a warning, and the file needs no copy kept. So is a compass flag, which then owes no compass.
    @GameTest
    public void unreadableDeathSpotLoadsGraveWithoutIt(GameTestHelper helper) throws IOException {
        RegistryOps<Tag> ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
        Grave grave = new Grave(UUID.randomUUID(), UUID.randomUUID(), "Alex", ResolvableProfile.createUnresolved("Alex"),
                Level.OVERWORLD, new BlockPos(8, 64, 8), List.of(new ItemStack(Items.DIAMOND, 2)), 1234L,
                GlobalPos.of(Level.NETHER, new BlockPos(1, 2, 3)), true);
        CompoundTag tag = (CompoundTag) Grave.CODEC.encodeStart(ops, grave).getOrThrow();
        helper.assertTrue(tag.contains("death_pos"), "the death spot was not saved");
        helper.assertTrue(tag.contains("compass_pending"), "the compass flag was not saved");
        tag.putString("death_pos", "nowhere");
        tag.putString("compass_pending", "maybe");

        List<GraveStore> loaded = new ArrayList<>();
        List<String> log = OfflineGameTests.yoltLog(() -> loaded.add(GraveStore.TYPE.codec().parse(ops, storeData(tag)).getOrThrow()));
        Grave back = loaded.get(0).byId(grave.id()).orElse(null);
        helper.assertTrue(back != null, "the grave with an unreadable death spot was not loaded");
        helper.assertTrue(back.items().size() == 1 && back.items().get(0).is(Items.DIAMOND) && back.items().get(0).getCount() == 2,
                "the grave's items changed: " + back.items());
        helper.assertTrue(back.deathPos() == null, "an unreadable death spot read as " + back.deathPos());
        helper.assertTrue(log.stream().anyMatch(line -> line.contains("unreadable death spot")), "no warning in " + log);
        helper.assertFalse(back.compassPending(), "an unreadable compass flag still owes a compass");
        helper.assertTrue(log.stream().anyMatch(line -> line.contains("unreadable compass flag")), "no compass warning in " + log);
        assertFileNotCopied(helper, loaded.get(0)::checkFile, storeData(tag));
        helper.succeed();
    }

    // A grave file's data part, with the version fields at today's values so no fixer runs
    private static CompoundTag storeData(Tag... graves) {
        ListTag list = new ListTag();
        list.addAll(Arrays.asList(graves));
        CompoundTag data = new CompoundTag();
        data.put("graves", list);
        data.putInt("format", GraveStore.FORMAT);
        data.putInt("data_version", StoreVersions.currentDataVersion());
        return data;
    }

    // A temp file stands in for the store file the store was loaded from. The live store loads at server start, so
    // this checks the store's own file check, which get runs on first use. Body tests use these too.
    static void assertFileCopiedOnce(GameTestHelper helper, Consumer<Path> checkFile, CompoundTag data) throws IOException {
        Path file = writeStoreFile(helper, data);
        try {
            checkFile.accept(file);
            List<Path> copies = copiesOf(file);
            helper.assertValueEqual(copies.size(), 1, "copies next to the file");
            helper.assertTrue(Arrays.equals(Files.readAllBytes(copies.get(0)), Files.readAllBytes(file)), "the copy differs from the file");
            // A restart before the next save finds the same file, and one copy of it is enough
            checkFile.accept(file);
            helper.assertValueEqual(copiesOf(file).size(), 1, "copies after a second check");
        } finally {
            deleteWithCopies(file);
        }
    }

    static void assertFileNotCopied(GameTestHelper helper, Consumer<Path> checkFile, CompoundTag data) throws IOException {
        Path file = writeStoreFile(helper, data);
        try {
            checkFile.accept(file);
            helper.assertTrue(copiesOf(file).isEmpty(), "a copy was kept of a file read whole");
        } finally {
            deleteWithCopies(file);
        }
    }

    // A data storage like the server's own, over the folder, which it reads its files from on first use
    static SavedDataStorage freshStorage(GameTestHelper helper, Path folder) {
        MinecraftServer server = helper.getLevel().getServer();
        return new SavedDataStorage(folder, server.getFixerUpper(), server.registryAccess());
    }

    static void deleteFolder(Path folder) throws IOException {
        try (Stream<Path> paths = Files.walk(folder)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static Path writeStoreFile(GameTestHelper helper, CompoundTag data) throws IOException {
        Path dir = helper.getLevel().getServer().getWorldPath(LevelResource.DATA);
        Files.createDirectories(dir);
        Path file = Files.createTempFile(dir, "store-test-", ".dat");
        CompoundTag root = new CompoundTag();
        root.put("data", data);
        NbtIo.writeCompressed(root, file);
        return file;
    }

    private static void deleteWithCopies(Path file) throws IOException {
        for (Path copy : copiesOf(file)) {
            Files.deleteIfExists(copy);
        }
        Files.deleteIfExists(file);
    }

    private static List<Path> copiesOf(Path file) throws IOException {
        String prefix = file.getFileName() + ".unreadable-";
        try (Stream<Path> files = Files.list(file.getParent())) {
            return files.filter(path -> path.getFileName().toString().startsWith(prefix)).toList();
        }
    }

    // The graves in the grave file right now, whatever the store holds in memory
    static List<Grave> onDisk(MinecraftServer server) {
        Path file = GraveStore.TYPE.id().withSuffix(".dat").resolveAgainst(server.getWorldPath(LevelResource.DATA));
        if (!Files.exists(file)) {
            return List.of();
        }
        CompoundTag root;
        try {
            root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        RegistryOps<Tag> ops = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        List<Grave> graves = new ArrayList<>();
        for (Tag entry : root.getCompoundOrEmpty("data").getListOrEmpty("graves")) {
            Grave.CODEC.parse(ops, entry).resultOrPartial().ifPresent(graves::add);
        }
        return graves;
    }
}
