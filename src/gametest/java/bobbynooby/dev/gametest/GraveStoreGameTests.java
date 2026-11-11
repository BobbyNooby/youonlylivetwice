package bobbynooby.dev.gametest;

import bobbynooby.dev.core.StoreVersions;
import bobbynooby.dev.core.UnreadableFiles;
import bobbynooby.dev.core.Yolt;
import bobbynooby.dev.graves.Grave;
import bobbynooby.dev.graves.GraveItems;
import bobbynooby.dev.graves.GravePlacement;
import bobbynooby.dev.graves.GraveStore;
import bobbynooby.dev.skins.SkinStore;
import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.serialization.DataResult;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
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

    // Not a check of behaviour: it logs, for the CI log, what 1,000 more graves cost the lookups, a placement and the
    // save every death makes, first with graves of 10 stacks, then of 41, a full inventory and the head. Each grave
    // carries a signed skin twice, in its record and on its head, as a real one does. The graves are records only, far
    // from everything, so each lookup scans them all and finds nothing. All in this tick, and the grave file is written
    // again without them.
    @GameTest(skyAccess = true)
    public void costOfAThousandGravesIsLogged(GameTestHelper helper) throws IOException {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        GraveStore store = GraveStore.get(server);
        RegistryOps<Tag> ops = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        Path file = GraveStore.TYPE.id().withSuffix(".dat").resolveAgainst(server.getWorldPath(LevelResource.DATA));
        BlockPos open = helper.absolutePos(new BlockPos(1, 0, 1));
        BlockPos inLava = helper.absolutePos(new BlockPos(3, 0, 3));
        ChunkPos chunk = ChunkPos.containing(open);
        Holder<Enchantment> sharpness = server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS);
        Random random = new Random(1234);
        List<Grave> light = new ArrayList<>();
        List<Grave> full = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            ResolvableProfile profile = signedProfile(random, i);
            BlockPos pos = new BlockPos(2_000_000 + (i % 32) * 16, 64, 2_000_000 + (i / 32) * 16);
            light.add(costGrave(profile, pos, costItems(profile, 9, null)));
            full.add(costGrave(profile, pos, costItems(profile, 35, sharpness)));
        }
        List<BlockPos> pool = new ArrayList<>();
        try {
            double placementBefore = meanMicros(200, () -> GravePlacement.find(level, open, store));
            List<Long> saveBefore = eachMillis(3, () -> store.saveNow(server));

            light.forEach(store::put);
            List<Long> saveLight = eachMillis(3, () -> store.saveNow(server));
            long lightBytes = Files.size(file);
            light.forEach(grave -> store.remove(grave.id()));

            full.forEach(store::put);
            Grave some = full.get(500);
            helper.assertValueEqual(store.at(some.dimension(), some.pos()).map(Grave::id), Optional.of(some.id()), "a grave found by spot");
            double at = meanMicros(1000, () -> store.at(Level.OVERWORLD, open));
            double inChunk = meanMicros(1000, () -> store.inChunk(Level.OVERWORLD, chunk));
            double placement = meanMicros(200, () -> GravePlacement.find(level, open, store));
            helper.assertValueEqual(GravePlacement.find(level, open, store), open, "placement on open floor");
            List<Long> saveFull = eachMillis(3, () -> store.saveNow(server));
            List<Long> storageSave = eachMillis(3, () -> {
                store.setDirty();
                server.getDataStorage().saveAndJoin();
            });
            List<Long> encode = eachMillis(3, () -> GraveStore.TYPE.codec().encodeStart(ops, store).getOrThrow());
            List<Long> readBack = eachMillis(3, () -> {
                try {
                    NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            long fullBytes = Files.size(file);

            // The middle of a lava pool, as in the placement tests: the search reads far more blocks before the rim
            for (int x = 0; x <= 7; x++) {
                for (int z = 0; z <= 7; z++) {
                    BlockPos pos = new BlockPos(x, 0, z);
                    pool.add(pos);
                    helper.setBlock(pos, x == 7 || z == 7 ? Blocks.STONE : Blocks.LAVA);
                }
            }
            double poolPlacement = meanMicros(50, () -> GravePlacement.find(level, inLava, store));
            full.forEach(grave -> store.remove(grave.id()));
            double poolPlacementBefore = meanMicros(50, () -> GravePlacement.find(level, inLava, store));

            Yolt.LOGGER.info("Grave cost, in microseconds each, with 1000 more graves: a lookup by spot {}, by chunk {}; a "
                            + "placement on open floor {} ({} without them), in a lava pool {} ({} without them)",
                    micros(at), micros(inChunk), micros(placement), micros(placementBefore), micros(poolPlacement),
                    micros(poolPlacementBefore));
            Yolt.LOGGER.info("Grave cost, in milliseconds for each of 3 runs, of the save every death makes: {} with the "
                            + "store as it was; {} with 1000 more graves of 10 stacks, a {} KB file; {} with 1000 of 41 "
                            + "stacks, a {} KB file. Of the last, the data storage's save took {}, one encode {}, the "
                            + "read back {}", saveBefore, saveLight, lightBytes / 1024, saveFull, fullBytes / 1024,
                    storageSave, encode, readBack);
        } finally {
            for (BlockPos pos : pool) {
                helper.setBlock(pos, Blocks.AIR);
            }
            light.forEach(grave -> store.remove(grave.id()));
            full.forEach(grave -> store.remove(grave.id()));
            store.saveNow(server);
        }
        helper.succeed();
    }

    // Mean microseconds a run, after as many runs again to warm up
    private static double meanMicros(int runs, Runnable action) {
        for (int i = 0; i < runs; i++) {
            action.run();
        }
        long start = System.nanoTime();
        for (int i = 0; i < runs; i++) {
            action.run();
        }
        return (System.nanoTime() - start) / 1_000.0 / runs;
    }

    // Each run's milliseconds, after one run to warm up
    private static List<Long> eachMillis(int runs, Runnable action) {
        action.run();
        List<Long> taken = new ArrayList<>();
        for (int i = 0; i < runs; i++) {
            long start = System.nanoTime();
            action.run();
            taken.add((System.nanoTime() - start) / 1_000_000);
        }
        return taken;
    }

    private static String micros(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    // An owner with signed skin textures the size the session server hands out: a value of about 300 characters and a
    // 512-byte signature
    private static ResolvableProfile signedProfile(Random random, int i) {
        UUID id = new UUID(random.nextLong(), random.nextLong());
        String name = "Owner" + i;
        byte[] skin = new byte[32];
        random.nextBytes(skin);
        String textures = "{\"timestamp\":" + (1_700_000_000_000L + i) + ",\"profileId\":\"" + id + "\",\"profileName\":\"" + name
                + "\",\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/" + HexFormat.of().formatHex(skin) + "\"}}}";
        byte[] signature = new byte[512];
        random.nextBytes(signature);
        PropertyMap properties = new PropertyMap(ImmutableMultimap.of("textures", new Property("textures",
                Base64.getEncoder().encodeToString(textures.getBytes(StandardCharsets.UTF_8)),
                Base64.getEncoder().encodeToString(signature))));
        return ResolvableProfile.createResolved(new GameProfile(id, name, properties));
    }

    private static Grave costGrave(ResolvableProfile profile, BlockPos pos, List<ItemStack> items) {
        UUID owner = profile.partialProfile().id();
        String name = profile.name().orElseThrow();
        return new Grave(UUID.randomUUID(), owner, name, profile, Level.OVERWORLD, pos, items, 1L,
                GlobalPos.of(Level.OVERWORLD, pos.below(3)));
    }

    // The grave head and plain stacks; with a sword's enchantment also worn iron armour and that sword
    private static List<ItemStack> costItems(ResolvableProfile profile, int plain, @Nullable Holder<Enchantment> sharpness) {
        List<ItemStack> items = new ArrayList<>();
        items.add(GraveItems.graveHead(profile));
        if (sharpness != null) {
            for (Item armour : List.of(Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS)) {
                ItemStack worn = new ItemStack(armour);
                worn.setDamageValue(37);
                items.add(worn);
            }
            ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
            sword.enchant(sharpness, 5);
            sword.setDamageValue(120);
            items.add(sword);
        }
        List<Item> kinds = List.of(Items.COBBLESTONE, Items.DIRT, Items.OAK_LOG, Items.TORCH, Items.BREAD, Items.ARROW,
                Items.IRON_INGOT, Items.COAL, Items.OAK_PLANKS, Items.REDSTONE, Items.GOLD_INGOT, Items.DIAMOND, Items.STRING);
        for (int i = 0; i < plain; i++) {
            Item kind = kinds.get(i % kinds.size());
            items.add(new ItemStack(kind, Math.min(64, kind.getDefaultMaxStackSize()) - i % 5));
        }
        return items;
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
