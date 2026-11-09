package bobbynooby.dev.gametest;

import bobbynooby.dev.graves.Grave;
import bobbynooby.dev.graves.GraveStore;
import com.mojang.serialization.DataResult;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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

        Grave after = Grave.CODEC.parse(ops, tag).getOrThrow();
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
            helper.assertTrue(GraveStore.keepUnreadableCopy(file, true).isEmpty(), "copied a file the store was read from");
            helper.assertTrue(copiesOf(file).isEmpty(), "a copy appeared for a file the store was read from");
            helper.assertTrue(GraveStore.keepUnreadableCopy(dir.resolve(file.getFileName() + ".missing"), false).isEmpty(),
                    "copied a file that isn't there");

            Optional<Path> copy = GraveStore.keepUnreadableCopy(file, false);
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
            Grave.CODEC.parse(ops, entry).result().ifPresent(graves::add);
        }
        return graves;
    }
}
