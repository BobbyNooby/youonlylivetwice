package bobbynooby.dev.gametest;

import bobbynooby.dev.graves.Grave;
import bobbynooby.dev.graves.GraveStore;
import com.mojang.serialization.DataResult;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.UUID;

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
}
