package bobbynooby.dev.gametest;

import bobbynooby.dev.bodies.BodyRecord;
import bobbynooby.dev.bodies.BodySnapshot;
import bobbynooby.dev.bodies.BodyStore;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.core.StoreVersions;
import bobbynooby.dev.core.UnreadableFiles;
import bobbynooby.dev.graves.Grave;
import bobbynooby.dev.graves.GraveStore;
import bobbynooby.dev.lockout.DeathLockout;
import bobbynooby.dev.lockout.DeathLog;
import bobbynooby.dev.lockout.DeathStore;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

public class MigrationGameTests {
    // The death table as every database before schema versions has it
    private static final String TODAY_TABLE = """
            CREATE TABLE death_logs (
                uuid TEXT,
                username TEXT,
                death_time_ms INTEGER,
                cooldown_days INTEGER,
                bypass INTEGER
            )
            """;

    private static String url(Path file) {
        return "jdbc:sqlite:" + file.toAbsolutePath();
    }

    private static int userVersion(Path file) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url(file));
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    private static void deleteFolder(Path dir) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @GameTest
    public void deathStoreFromBeforeVersioningIsV1(GameTestHelper helper) throws IOException, SQLException {
        Path dir = Files.createTempDirectory("yolt-migration");
        try {
            Path file = dir.resolve("before.db");
            UUID uuid = UUID.randomUUID();
            try (Connection connection = DriverManager.getConnection(url(file));
                 Statement statement = connection.createStatement()) {
                statement.execute(TODAY_TABLE);
                statement.execute("INSERT INTO death_logs VALUES ('" + uuid + "', 'Before', 1000, 7, 0)");
            }
            helper.assertValueEqual(userVersion(file), 0, "schema version before opening");

            DeathStore store = DeathStore.open(file);
            try {
                helper.assertValueEqual(store.latest(uuid).orElse(null), new DeathLog(uuid, "Before", 1000L, 7, false, null, null, null), "kept row");
            } finally {
                store.close();
            }
            helper.assertValueEqual(userVersion(file), DeathStore.SCHEMA_VERSION, "schema version after opening");
        } finally {
            deleteFolder(dir);
        }
        helper.succeed();
    }

    // The server opens its store this way at start; a refused open leaves it with none, which lets everyone in
    @GameTest
    public void newerDeathStoreIsNotWritten(GameTestHelper helper) throws IOException, SQLException {
        Path dir = Files.createTempDirectory("yolt-migration");
        try {
            Path file = dir.resolve("ahead.db");
            try (Connection connection = DriverManager.getConnection(url(file));
                 Statement statement = connection.createStatement()) {
                statement.execute(TODAY_TABLE);
                statement.execute("PRAGMA user_version = 99");
            }
            byte[] before = Files.readAllBytes(file);
            UUID uuid = UUID.randomUUID();

            DeathStore opened = null;
            RuntimeException refused = null;
            try {
                opened = DeathStore.open(file);
            } catch (IllegalStateException e) {
                refused = e;
            }
            DeathStore real = DeathLockout.swapStoreForTesting(opened);
            try {
                DeathLockout.recordDeath(uuid, "Newer");
                helper.assertTrue(DeathLockout.refusal(uuid, "Test") == null, "a player was refused with a newer database");
            } finally {
                DeathLockout.swapStoreForTesting(real);
                if (opened != null) {
                    opened.close();
                }
            }
            helper.assertTrue(opened == null, "a newer database was opened for writing");
            helper.assertTrue(refused != null && String.valueOf(refused.getCause()).contains("schema v99"),
                    "not refused for its version: " + refused);
            helper.assertTrue(Arrays.equals(Files.readAllBytes(file), before), "the newer database changed");
        } finally {
            deleteFolder(dir);
        }
        helper.succeed();
    }

    private static RegistryOps<Tag> ops(GameTestHelper helper) {
        return helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
    }

    private static Grave grave(List<ItemStack> items) {
        return new Grave(UUID.randomUUID(), UUID.randomUUID(), "Alex", ResolvableProfile.createUnresolved("Alex"),
                Level.OVERWORLD, new BlockPos(5, 64, 5), items, 1234L);
    }

    // A store's data as the storage hands it over, with one record saved at the given format and data version
    private static CompoundTag storeData(String listKey, Tag record, int format, int dataVersion) {
        ListTag records = new ListTag();
        records.add(record.copy());
        CompoundTag data = new CompoundTag();
        data.put(listKey, records);
        data.putInt("format", format);
        data.putInt("data_version", dataVersion);
        return data;
    }

    private static void assertSavedHeader(GameTestHelper helper, MinecraftServer server, SavedDataType<?> type, int format)
            throws IOException {
        int current = SharedConstants.getCurrentVersion().dataVersion().version();
        Path file = type.id().withSuffix(".dat").resolveAgainst(server.getWorldPath(LevelResource.DATA));
        CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        CompoundTag data = root.getCompoundOrEmpty("data");
        helper.assertValueEqual(data.getIntOr("format", -1), format, type.id() + " format");
        helper.assertValueEqual(data.getIntOr("data_version", -1), current, type.id() + " data version");
        helper.assertValueEqual(root.getIntOr("DataVersion", -1), current, type.id() + " vanilla's data version");
    }

    @GameTest
    public void storesWriteFormatAndDataVersion(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        GraveStore.get(server).saveNow(server);
        BodyStore.get(server).setDirty();
        server.getDataStorage().saveAndJoin();
        assertSavedHeader(helper, server, GraveStore.TYPE, GraveStore.FORMAT);
        assertSavedHeader(helper, server, BodyStore.TYPE, BodyStore.FORMAT);
        helper.succeed();
    }

    // The format and data version pairs both fixer tests load: older Minecraft, current, newer Minecraft, newer
    // format, and the two damaged values that read as missing
    private static int[][] versionCases(int format, int current) {
        return new int[][]{{format, current - 1}, {format, current}, {format, current + 1}, {format + 1, current},
                {0, current}, {format, 0}};
    }

    // How a store reads a saved value: zero or below is missing, and a missing data version is 26.3's (5023)
    private static int asRead(int saved, int missing) {
        return saved > 0 ? saved : missing;
    }

    // An older Minecraft's real item format can't be made here. This shows the fixer runs for an older file, is
    // skipped for a current or newer one, and that the item comes through whole either way.
    @GameTest
    public void graveItemsRunThroughTheItemFixer(GameTestHelper helper) {
        RegistryOps<Tag> ops = ops(helper);
        int current = StoreVersions.currentDataVersion();
        Grave grave = grave(List.of(new ItemStack(Items.DIAMOND, 5)));
        Tag graveTag = Grave.CODEC.encodeStart(ops, grave).getOrThrow();
        for (int[] saved : versionCases(GraveStore.FORMAT, current)) {
            String label = "format " + saved[0] + ", data version " + saved[1];
            long before = StoreVersions.itemsFixed();
            GraveStore loaded = GraveStore.TYPE.codec()
                    .parse(ops, storeData("graves", graveTag, saved[0], saved[1])).getOrThrow();
            int format = asRead(saved[0], 1);
            int dataVersion = asRead(saved[1], 5023);
            helper.assertValueEqual(StoreVersions.itemsFixed() - before, dataVersion < current ? 1L : 0L, "items fixed at " + label);
            helper.assertValueEqual(loaded.fromNewer(), format > GraveStore.FORMAT || dataVersion > current, "newer at " + label);
            List<ItemStack> items = loaded.byId(grave.id()).orElseThrow().items();
            helper.assertTrue(items.size() == 1 && items.get(0).is(Items.DIAMOND) && items.get(0).getCount() == 5,
                    "the diamonds changed at " + label + ": " + items);
        }
        helper.succeed();
    }

    @GameTest
    public void bodyGearRunsThroughTheItemFixer(GameTestHelper helper) {
        RegistryOps<Tag> ops = ops(helper);
        int current = StoreVersions.currentDataVersion();
        ItemStack chest = new ItemStack(Items.IRON_CHESTPLATE);
        chest.set(DataComponents.DAMAGE, 10);
        BodySnapshot snapshot = new BodySnapshot(Level.OVERWORLD, new Vec3(1.5, 64, -2.5), 90f, 14f, 2f, 0.0, 0, 300,
                List.of(new MobEffectInstance(MobEffects.SPEED, 200, 1)),
                Map.of(EquipmentSlot.CHEST, chest, EquipmentSlot.OFFHAND, new ItemStack(Items.ARROW, 3)));
        BodyRecord record = new BodyRecord(UUID.randomUUID(), "Alex", UUID.randomUUID(), YoltConfig.BodyType.MANNEQUIN,
                1234L, false, snapshot);
        Tag recordTag = BodyRecord.CODEC.encodeStart(ops, record).getOrThrow();
        for (int[] saved : versionCases(BodyStore.FORMAT, current)) {
            String label = "format " + saved[0] + ", data version " + saved[1];
            long before = StoreVersions.itemsFixed();
            BodyStore loaded = BodyStore.TYPE.codec()
                    .parse(ops, storeData("bodies", recordTag, saved[0], saved[1])).getOrThrow();
            int format = asRead(saved[0], 1);
            int dataVersion = asRead(saved[1], 5023);
            helper.assertValueEqual(StoreVersions.itemsFixed() - before, dataVersion < current ? 2L : 0L, "gear fixed at " + label);
            helper.assertValueEqual(loaded.fromNewer(), format > BodyStore.FORMAT || dataVersion > current, "newer at " + label);
            BodySnapshot kept = loaded.byOwner(record.owner()).orElseThrow().snapshot();
            ItemStack keptChest = kept.gear().get(EquipmentSlot.CHEST);
            ItemStack keptArrows = kept.gear().get(EquipmentSlot.OFFHAND);
            helper.assertTrue(keptChest != null && keptChest.is(Items.IRON_CHESTPLATE) && keptChest.getCount() == 1
                    && keptChest.getOrDefault(DataComponents.DAMAGE, 0) == 10, "the chestplate changed at " + label);
            helper.assertTrue(keptArrows != null && keptArrows.is(Items.ARROW) && keptArrows.getCount() == 3,
                    "the arrows changed at " + label);
            helper.assertTrue(kept.effects().size() == 1 && kept.effects().get(0).is(MobEffects.SPEED),
                    "the effect changed at " + label);
        }
        helper.succeed();
    }

    // A real vanilla item fix: Minecraft renamed the explorer maps at data version 5012, and 26.3 has no item under
    // the old name, so a stored one only loads after the fixer ran
    @GameTest
    public void renamedItemInGraveIsFixedNotDropped(GameTestHelper helper) {
        RegistryOps<Tag> ops = ops(helper);
        CompoundTag oldMap = new CompoundTag();
        oldMap.putString("id", "minecraft:ocean_explorer_map");
        oldMap.putInt("count", 2);
        helper.assertTrue(ItemStack.CODEC.parse(ops, oldMap).result().isEmpty(), "the old name loads without the fixer");

        Grave grave = grave(List.of());
        CompoundTag graveTag = (CompoundTag) Grave.CODEC.encodeStart(ops, grave).getOrThrow();
        ListTag items = new ListTag();
        items.add(oldMap);
        graveTag.put("items", items);

        GraveStore loaded = GraveStore.TYPE.codec().parse(ops, storeData("graves", graveTag, GraveStore.FORMAT, 5011)).getOrThrow();
        List<ItemStack> kept = loaded.byId(grave.id()).orElseThrow().items();
        helper.assertValueEqual(kept.size(), 1, "items in the grave");
        helper.assertTrue(kept.get(0).is(Items.OCEAN_MONUMENT_MAP), "the map was not renamed: " + kept.get(0));
        helper.assertValueEqual(kept.get(0).getCount(), 2, "map count");
        helper.succeed();
    }

    // Only the copy helper, here for its one copy per file content. A newer file handed to get(storage, folder) is
    // BodyStoreGameTests' newerBodyFileCopiedThroughGet. Junk in a temp file stands in for a store file saved by a newer
    // version.
    @GameTest
    public void newerCopyIsKeptOncePerFileContent(GameTestHelper helper) throws IOException {
        Path dir = helper.getLevel().getServer().getWorldPath(LevelResource.DATA);
        Files.createDirectories(dir);
        Path file = Files.createTempFile(dir, "graves-test-", ".dat");
        byte[] first = {1, 2, 3, 4, 5};
        try {
            Files.write(file, first);
            Optional<Path> copy = UnreadableFiles.keepNewerCopy(file, "grave");
            helper.assertTrue(copy.isPresent() && Arrays.equals(Files.readAllBytes(copy.get()), first),
                    "no exact copy of the newer file");
            helper.assertTrue(copy.get().getFileName().toString().startsWith(file.getFileName() + ".newer-"),
                    "the copy is not named after the file: " + copy.get());
            helper.assertTrue(Arrays.equals(Files.readAllBytes(file), first), "the newer file changed");

            // A restart before the store saved again: the same file, so no second copy
            helper.assertTrue(UnreadableFiles.keepNewerCopy(file, "grave").isEmpty(), "a second copy of the same file");
            helper.assertValueEqual(newerCopiesOf(file).size(), 1, "copies after a restart");

            // A different newer file later gets its own copy. Waits for the clock so the two names differ.
            Files.write(file, new byte[]{6, 7, 8});
            long now = System.currentTimeMillis();
            while (System.currentTimeMillis() == now) {
                Thread.onSpinWait();
            }
            helper.assertTrue(UnreadableFiles.keepNewerCopy(file, "grave").isPresent(), "no copy of a different newer file");
            helper.assertValueEqual(newerCopiesOf(file).size(), 2, "copies of two different newer files");
        } finally {
            Files.deleteIfExists(file);
            for (Path left : newerCopiesOf(file)) {
                Files.deleteIfExists(left);
            }
        }
        helper.succeed();
    }

    private static List<Path> newerCopiesOf(Path file) throws IOException {
        String prefix = file.getFileName() + ".newer-";
        try (Stream<Path> files = Files.list(file.getParent())) {
            return files.filter(path -> path.getFileName().toString().startsWith(prefix)).toList();
        }
    }
}
