package bobbynooby.dev.gametest;

import bobbynooby.dev.bodies.BodyStore;
import bobbynooby.dev.graves.Grave;
import bobbynooby.dev.graves.GraveStore;
import bobbynooby.dev.lockout.DeathLockout;
import bobbynooby.dev.lockout.DeathLog;
import bobbynooby.dev.lockout.DeathStore;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

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

    @GameTest
    public void deathStoreFromBeforeVersioningIsV1(GameTestHelper helper) throws IOException, SQLException {
        Path file = Files.createTempDirectory("yolt-migration").resolve("before.db");
        UUID uuid = UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection(url(file));
             Statement statement = connection.createStatement()) {
            statement.execute(TODAY_TABLE);
            statement.execute("INSERT INTO death_logs VALUES ('" + uuid + "', 'Before', 1000, 7, 0)");
        }
        helper.assertValueEqual(userVersion(file), 0, "schema version before opening");

        DeathStore store = DeathStore.open(file);
        try {
            helper.assertValueEqual(store.latest(uuid).orElse(null), new DeathLog(uuid, "Before", 1000L, 7, false), "kept row");
        } finally {
            store.close();
        }
        helper.assertValueEqual(userVersion(file), DeathStore.SCHEMA_VERSION, "schema version after opening");
        helper.succeed();
    }

    // The server opens its store this way at start; a refused open leaves it with none, which lets everyone in
    @GameTest
    public void newerDeathStoreIsNotWritten(GameTestHelper helper) throws IOException, SQLException {
        Path file = Files.createTempDirectory("yolt-migration").resolve("newer.db");
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
            helper.assertTrue(DeathLockout.refusal(uuid) == null, "a player was refused with a newer database");
        } finally {
            DeathLockout.swapStoreForTesting(real);
            if (opened != null) {
                opened.close();
            }
        }
        helper.assertTrue(opened == null, "a newer database was opened for writing");
        helper.assertTrue(refused != null && String.valueOf(refused.getCause()).contains("newer"), "no reason given: " + refused);
        helper.assertTrue(Arrays.equals(Files.readAllBytes(file), before), "the newer database changed");
        helper.succeed();
    }

    @GameTest
    public void storesWriteFormatAndDataVersion(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        int current = SharedConstants.getCurrentVersion().dataVersion().version();
        GraveStore.get(server).saveNow(server);
        BodyStore.get(server).setDirty();
        server.getDataStorage().saveAndJoin();

        for (SavedDataType<?> type : List.of(GraveStore.TYPE, BodyStore.TYPE)) {
            Path file = type.id().withSuffix(".dat").resolveAgainst(server.getWorldPath(LevelResource.DATA));
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            CompoundTag data = root.getCompoundOrEmpty("data");
            helper.assertValueEqual(data.getIntOr("format", -1), 1, type.id() + " format");
            helper.assertValueEqual(data.getIntOr("data_version", -1), current, type.id() + " data version");
            helper.assertValueEqual(root.getIntOr("DataVersion", -1), current, type.id() + " vanilla's data version");
        }
        helper.succeed();
    }

    // A real vanilla item fix: Minecraft renamed the explorer maps at data version 5012, and 26.3 has no item under
    // the old name, so a stored one only loads after the fixer ran
    @GameTest
    public void renamedItemInAGraveIsFixedNotDropped(GameTestHelper helper) {
        RegistryOps<Tag> ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
        CompoundTag oldMap = new CompoundTag();
        oldMap.putString("id", "minecraft:ocean_explorer_map");
        oldMap.putInt("count", 2);
        helper.assertTrue(ItemStack.CODEC.parse(ops, oldMap).result().isEmpty(), "the old name loads without the fixer");

        Grave grave = new Grave(UUID.randomUUID(), UUID.randomUUID(), "Alex", ResolvableProfile.createUnresolved("Alex"),
                Level.OVERWORLD, new BlockPos(5, 64, 5), List.of(), 1234L);
        CompoundTag graveTag = (CompoundTag) Grave.CODEC.encodeStart(ops, grave).getOrThrow();
        ListTag items = new ListTag();
        items.add(oldMap);
        graveTag.put("items", items);
        ListTag graves = new ListTag();
        graves.add(graveTag);
        CompoundTag file = new CompoundTag();
        file.put("graves", graves);
        file.putInt("format", 1);
        file.putInt("data_version", 5011);

        GraveStore loaded = GraveStore.TYPE.codec().parse(ops, file).getOrThrow();
        List<ItemStack> kept = loaded.byId(grave.id()).orElseThrow().items();
        helper.assertValueEqual(kept.size(), 1, "items in the grave");
        helper.assertTrue(kept.get(0).is(Items.OCEAN_MONUMENT_MAP), "the map was not renamed: " + kept.get(0));
        helper.assertValueEqual(kept.get(0).getCount(), 2, "map count");
        helper.succeed();
    }
}
