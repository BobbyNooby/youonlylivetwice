package bobbynooby.dev.gametest;

import bobbynooby.dev.graves.Grave;
import bobbynooby.dev.graves.GraveItems;
import bobbynooby.dev.graves.GraveMenu;
import bobbynooby.dev.graves.GraveMenus;
import bobbynooby.dev.graves.GraveStore;
import bobbynooby.dev.graves.Graves;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

// Commands run as the console; each test removes the grave it made
public class GraveCommandGameTests {
    @GameTest
    public void listShowsGrave(GameTestHelper helper) {
        Grave grave = makeGrave(helper);
        try {
            List<String> out = run(helper, "yolt grave list");
            BlockPos pos = grave.pos();
            String line = "Ghost " + grave.dimension().identifier() + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
            helper.assertTrue(out.contains(line), "list lacks '" + line + "': " + out);
        } finally {
            cleanUp(helper, grave);
        }
        helper.succeed();
    }

    @GameTest
    public void removeDropsItemsAndDeletes(GameTestHelper helper) {
        Grave grave = makeGrave(helper);
        try {
            BlockPos pos = grave.pos();
            List<String> out = run(helper, "yolt grave remove " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
            helper.assertValueEqual(out, List.of("Removed Ghost's grave and dropped 3 stacks."), "command output");
            helper.assertTrue(GraveStore.get(helper.getLevel().getServer()).byId(grave.id()).isEmpty(), "record still there");
            helper.assertTrue(helper.getLevel().getBlockState(pos).isAir(), "head still there");
            helper.assertTrue(dropped(helper, grave, Items.DIAMOND) == 1, "diamond not dropped once");
            helper.assertTrue(dropped(helper, grave, Items.IRON_SWORD) == 1, "sword not dropped once");

            List<String> again = run(helper, "yolt grave remove " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
            helper.assertValueEqual(again, List.of("No grave at " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + "."), "second output");
        } finally {
            cleanUp(helper, grave);
            discardDrops(helper, grave);
        }
        helper.succeed();
    }

    // Removing a grave must never delete its items, so the stacks it drops never despawn
    @GameTest
    public void removedGraveItemsNeverDespawn(GameTestHelper helper) {
        Grave grave = makeGrave(helper);
        try {
            BlockPos pos = grave.pos();
            run(helper, "yolt grave remove " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
            List<ItemEntity> drops = dropsAt(helper, grave);
            helper.assertValueEqual(drops.size(), 3, "dropped stacks");
            for (ItemEntity drop : drops) {
                // ItemEntity.setUnlimitedLifetime sets this age, which the item's tick never counts up from
                helper.assertValueEqual(drop.getAge(), (int) Short.MIN_VALUE, drop.getItem() + "'s age");
            }
        } finally {
            cleanUp(helper, grave);
            discardDrops(helper, grave);
        }
        helper.succeed();
    }

    @GameTest
    public void removeOneStackSaysStack(GameTestHelper helper) {
        // Only the grave head, so the reply has one stack to count
        Grave grave = makeGrave(helper, GraveItems.graveHead(ResolvableProfile.createUnresolved("Ghost")));
        try {
            BlockPos pos = grave.pos();
            List<String> out = run(helper, "yolt grave remove " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
            helper.assertValueEqual(out, List.of("Removed Ghost's grave and dropped 1 stack."), "command output");
        } finally {
            cleanUp(helper, grave);
            discardDrops(helper, grave);
        }
        helper.succeed();
    }

    @GameTest
    public void removeWhileOpenDoesNotDuplicate(GameTestHelper helper) {
        Grave grave = makeGrave(helper);
        ServerPlayer player = TestPlayers.join(helper, "Looter", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        try {
            GraveMenus.open(player, grave);
            helper.assertTrue(player.containerMenu instanceof GraveMenu, "menu did not open");
            BlockPos pos = grave.pos();
            run(helper, "yolt grave remove " + pos.getX() + " " + pos.getY() + " " + pos.getZ());

            helper.assertFalse(player.containerMenu instanceof GraveMenu, "menu still open");
            helper.assertTrue(GraveStore.get(helper.getLevel().getServer()).byId(grave.id()).isEmpty(), "record came back");
            helper.assertTrue(GraveMenus.containerFor(grave) == null, "shared container still registered");
            int diamonds = dropped(helper, grave, Items.DIAMOND) + player.getInventory().countItem(Items.DIAMOND);
            helper.assertValueEqual(diamonds, 1, "diamonds in the world");
        } finally {
            cleanUp(helper, grave, player);
            discardDrops(helper, grave);
        }
        helper.succeed();
    }

    // Lava has reached the floor under and around the grave since it was made, and a stone rim keeps it there.
    // Burning takes a few ticks, so the count waits two seconds. No ceiling, which the search would take as a floor.
    @GameTest(maxTicks = 60, skyAccess = true)
    public void graveRemoveNeverDropsIntoLava(GameTestHelper helper) {
        Grave grave = makeGraveOverPool(helper);
        List<BlockPos> pool = new ArrayList<>();
        boolean waiting = false;
        try {
            for (int x = 0; x <= 4; x++) {
                for (int z = 0; z <= 4; z++) {
                    BlockPos pos = new BlockPos(x, 0, z);
                    boolean rim = x == 0 || x == 4 || z == 0 || z == 4;
                    helper.setBlock(pos, rim ? Blocks.STONE : Blocks.LAVA);
                    pool.add(pos);
                }
            }
            BlockPos pos = grave.pos();
            helper.assertTrue(helper.getLevel().getFluidState(pos.below()).is(FluidTags.LAVA), "no lava under the grave");
            List<String> out = run(helper, "yolt grave remove " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
            helper.assertValueEqual(out, List.of("Removed Ghost's grave and dropped 3 stacks."), "command output");

            helper.runAfterDelay(40, () -> {
                try {
                    List<ItemEntity> drops = helper.getEntities(EntityTypes.ITEM);
                    for (ItemEntity drop : drops) {
                        helper.assertFalse(drop.isInLava(), drop.getItem() + " is in lava at " + drop.blockPosition());
                    }
                    helper.assertValueEqual(drops.size(), 3, "dropped stacks left after two seconds");
                    helper.assertValueEqual(count(drops, stack -> stack.is(Items.DIAMOND)), 1, "diamonds left");
                    helper.assertValueEqual(count(drops, stack -> stack.is(Items.IRON_SWORD)), 1, "swords left");
                    helper.assertValueEqual(count(drops, GraveItems::isGraveHead), 1, "grave heads left");
                } finally {
                    removePool(helper, grave, pool);
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                removePool(helper, grave, pool);
            }
        }
    }

    // Lava covers the floor as far as the search looks, with the barrier walls and a stone rim around it
    @GameTest(skyAccess = true)
    public void graveRemoveRefusesWhenNoSpotIsSafe(GameTestHelper helper) {
        Grave grave = makeGraveOverPool(helper);
        List<BlockPos> pool = new ArrayList<>();
        try {
            for (int x = 0; x <= 6; x++) {
                for (int z = 0; z <= 6; z++) {
                    BlockPos pos = new BlockPos(x, 0, z);
                    helper.setBlock(pos, x == 6 || z == 6 ? Blocks.STONE : Blocks.LAVA);
                    pool.add(pos);
                }
            }
            BlockPos pos = grave.pos();
            List<String> out = run(helper, "yolt grave remove " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
            helper.assertValueEqual(out, List.of("Could not remove Ghost's grave: there is nowhere near it to drop its items safely."),
                    "command output");
            helper.assertTrue(GraveStore.get(helper.getLevel().getServer()).byId(grave.id()).isPresent(), "record removed");
            helper.assertTrue(helper.getLevel().getBlockState(pos).is(Blocks.PLAYER_HEAD), "head removed");
            helper.assertTrue(helper.getEntities(EntityTypes.ITEM).isEmpty(), "items dropped");
        } finally {
            removePool(helper, grave, pool);
        }
        helper.succeed();
    }

    // The command only takes a loaded spot, so a grave in a chunk nobody has loaded keeps its record, and the command
    // doesn't load the chunk to look. Only the record is there, as for a grave whose chunk unloaded.
    @GameTest
    public void removeRefusesUnloadedChunk(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        GraveStore store = GraveStore.get(level.getServer());
        ChunkPos chunk = ChunkPos.containing(helper.absolutePos(BlockPos.ZERO).offset(-4000, 0, 4000));
        helper.assertTrue(level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) == null, "the far chunk is loaded already");
        Grave grave = record(level.dimension(), chunk.getMiddleBlockPosition(64));
        store.put(grave);
        try {
            BlockPos pos = grave.pos();
            List<String> out = run(helper, "yolt grave remove " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
            helper.assertValueEqual(out, List.of(Component.translatable("argument.pos.unloaded").getString()), "command output");
            helper.assertValueEqual(store.byId(grave.id()).map(stored -> stored.items().size()), Optional.of(2),
                    "stacks in the grave record");
            helper.assertTrue(level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) == null, "the command loaded the far chunk");
        } finally {
            store.remove(grave.id());
        }
        helper.succeed();
    }

    // A grave in a dimension the server doesn't have, as after the datapack that added it went. The command can't
    // name one, since it removes in its own dimension, so the removal is called as the command calls it. The record
    // stays for when the dimension is back.
    @GameTest
    public void removeLeavesGraveInMissingDimension(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        GraveStore store = GraveStore.get(server);
        ResourceKey<Level> missing = ResourceKey.create(Registries.DIMENSION,
                Identifier.fromNamespaceAndPath("you-only-live-twice-gametest", "nowhere"));
        helper.assertTrue(server.getLevel(missing) == null, "the made-up dimension is loaded");
        Grave grave = record(missing, new BlockPos(1, 64, 1));
        store.put(grave);
        try {
            List<Graves.Removal> result = new ArrayList<>(1);
            List<String> log = OfflineGameTests.yoltLog(() -> result.add(Graves.removeAndDrop(server, grave)));
            helper.assertTrue(result.get(0) instanceof Graves.Removal.DimensionNotLoaded, "removal result: " + result.get(0));
            helper.assertValueEqual(store.byId(grave.id()).map(stored -> stored.items().size()), Optional.of(2),
                    "stacks in the grave record");
            String line = "Could not remove Ghost's grave at " + missing.identifier() + " " + grave.pos()
                    + ": that dimension is not loaded";
            helper.assertValueEqual(log.stream().filter(entry -> entry.startsWith("Could not remove Ghost's grave")).toList(),
                    List.of(line), "the removal's log lines");
        } finally {
            store.remove(grave.id());
        }
        helper.succeed();
    }

    // Only a record, with no head block, holding the grave head and a diamond
    private static Grave record(ResourceKey<Level> dimension, BlockPos pos) {
        ResolvableProfile profile = ResolvableProfile.createUnresolved("Ghost");
        return new Grave(UUID.randomUUID(), UUID.randomUUID(), "Ghost", profile, dimension, pos,
                List.of(GraveItems.graveHead(profile), new ItemStack(Items.DIAMOND)), 1L);
    }

    private static int count(List<ItemEntity> drops, Predicate<ItemStack> which) {
        return drops.stream().map(ItemEntity::getItem).filter(which).mapToInt(ItemStack::getCount).sum();
    }

    private static void removePool(GameTestHelper helper, Grave grave, List<BlockPos> pool) {
        cleanUp(helper, grave);
        helper.getEntities(EntityTypes.ITEM).forEach(ItemEntity::discard);
        for (BlockPos pos : pool) {
            helper.setBlock(pos, Blocks.AIR);
        }
    }

    private static Grave makeGrave(GameTestHelper helper) {
        ResolvableProfile profile = ResolvableProfile.createUnresolved("Ghost");
        return makeGrave(helper, GraveItems.graveHead(profile), new ItemStack(Items.DIAMOND), new ItemStack(Items.IRON_SWORD));
    }

    // Made on a block that the pool then replaces, since a grave made in open air would drop to the floor
    private static Grave makeGraveOverPool(GameTestHelper helper) {
        helper.setBlock(new BlockPos(2, 0, 2), Blocks.STONE);
        return makeGrave(helper);
    }

    private static Grave makeGrave(GameTestHelper helper, ItemStack... items) {
        ResolvableProfile profile = ResolvableProfile.createUnresolved("Ghost");
        List<ItemStack> stacks = List.of(items);
        List<Grave> made = Graves.create(helper.getLevel(), helper.absolutePos(new BlockPos(2, 1, 2)), profile,
                UUID.randomUUID(), "Ghost", stacks);
        helper.assertValueEqual(made.size(), 1, "graves made");
        return made.get(0);
    }

    private static List<ItemEntity> dropsAt(GameTestHelper helper, Grave grave) {
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, new net.minecraft.world.phys.AABB(grave.pos()).inflate(2));
    }

    private static int dropped(GameTestHelper helper, Grave grave, net.minecraft.world.item.Item item) {
        return dropsAt(helper, grave).stream().filter(e -> e.getItem().is(item)).mapToInt(e -> e.getItem().getCount()).sum();
    }

    private static void discardDrops(GameTestHelper helper, Grave grave) {
        dropsAt(helper, grave).forEach(ItemEntity::discard);
    }

    private static void cleanUp(GameTestHelper helper, Grave grave, ServerPlayer... players) {
        for (ServerPlayer player : players) {
            TestPlayers.leave(player);
        }
        GraveStore.get(helper.getLevel().getServer()).remove(grave.id());
        helper.getLevel().setBlock(grave.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    private static List<String> run(GameTestHelper helper, String command) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel level = helper.getLevel();
        List<String> output = new ArrayList<>();
        CommandSource capture = new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                output.add(message.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSource(capture).withLevel(level), command);
        return output;
    }
}
