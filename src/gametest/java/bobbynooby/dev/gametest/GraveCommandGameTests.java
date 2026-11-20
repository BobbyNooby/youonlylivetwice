package bobbynooby.dev.gametest;

import bobbynooby.dev.coords.CoordinateOffsets;
import bobbynooby.dev.graves.Grave;
import bobbynooby.dev.graves.GraveItems;
import bobbynooby.dev.graves.GraveMenu;
import bobbynooby.dev.graves.GraveMenus;
import bobbynooby.dev.graves.GraveStore;
import bobbynooby.dev.graves.Graves;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
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
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Collection;
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

    // The grave commands are for ops: a player without the level, typing them as a client does, can neither list the
    // graves nor remove one, and the grave stays as it was. The same typed list from an op does list it, so the typing
    // reaches the command.
    @GameTest
    public void nonOpCannotRunGraveCommands(GameTestHelper helper) {
        Grave grave = makeGrave(helper);
        TestPlayers.Recording player = TestPlayers.joinRecording(helper, "Nosy", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        BlockPos pos = grave.pos();
        String line = "Ghost " + grave.dimension().identifier() + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
        try {
            for (String command : List.of("yolt grave list", "yolt grave remove " + pos.getX() + " " + pos.getY() + " " + pos.getZ())) {
                helper.assertTrue(Commands.getParseException(helper.getLevel().getServer().getCommands().getDispatcher()
                        .parse(command, player.createCommandSourceStack())) != null, "'" + command + "' parses for a non-op");
                int from = player.messages.size();
                SkinGameTests.type(player, command);
                List<String> said = player.messages.subList(from, player.messages.size()).stream().map(Component::getString).toList();
                helper.assertFalse(said.contains(line), "'" + command + "' listed the grave to a non-op: " + said);
                helper.assertTrue(said.stream().noneMatch(text -> text.startsWith("Removed ")), "'" + command + "' removed: " + said);
            }
            helper.assertTrue(GraveStore.get(helper.getLevel().getServer()).byId(grave.id()).isPresent(), "the grave record went");
            helper.assertTrue(helper.getLevel().getBlockState(pos).is(Blocks.PLAYER_HEAD), "the grave head went");

            TestPlayers.op(player);
            int from = player.messages.size();
            SkinGameTests.type(player, "yolt grave list");
            helper.assertTrue(player.messages.subList(from, player.messages.size()).stream().map(Component::getString).toList()
                    .contains(line), "the op's typed list doesn't show the grave");
        } finally {
            TestPlayers.deop(player);
            cleanUp(helper, grave, player);
        }
        helper.succeed();
    }

    // The removal looks in the command's own dimension: from the console, which is in the Overworld, the Nether grave's
    // position finds nothing, and the same command run in the Nether removes it
    @GameTest
    public void removeUsesTheCommandsDimension(GameTestHelper helper) {
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "no nether");
        ResolvableProfile profile = ResolvableProfile.createUnresolved("Ghost");
        List<Grave> made = Graves.create(nether, helper.absolutePos(new BlockPos(2, 1, 2)), profile, UUID.randomUUID(), "Ghost",
                List.of(GraveItems.graveHead(profile), new ItemStack(Items.DIAMOND)));
        helper.assertValueEqual(made.size(), 1, "graves made");
        Grave grave = made.get(0);
        BlockPos pos = grave.pos();
        String at = pos.getX() + " " + pos.getY() + " " + pos.getZ();
        // The console's own level must have the spot loaded, or the position is refused before any grave is looked for
        helper.getLevel().getChunk(pos);
        try {
            helper.assertValueEqual(run(helper, "yolt grave remove " + at), List.of("No grave at " + at + "."),
                    "output from the Overworld");
            helper.assertTrue(GraveStore.get(nether.getServer()).byId(grave.id()).isPresent(), "the Nether grave went");
            helper.assertValueEqual(run(helper, "execute in minecraft:the_nether run yolt grave remove " + at),
                    List.of("Removed Ghost's grave and dropped 2 stacks."), "output from the Nether");
            helper.assertTrue(GraveStore.get(nether.getServer()).byId(grave.id()).isEmpty(), "the Nether grave is still there");
            helper.assertTrue(nether.getBlockState(pos).isAir(), "the Nether grave's head is still there");
        } finally {
            GraveStore.get(nether.getServer()).remove(grave.id());
            nether.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            nether.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(3.0)).forEach(ItemEntity::discard);
        }
        helper.succeed();
    }

    // The list gives real positions, also to an op whose client sees shifted ones, as docs/commands.md says. Typed back
    // as that op, they are read in the op's own frame, which is somewhere else, so the grave stays.
    @GameTest
    public void listShowsRealPositionsToAnOpWithAnOffset(GameTestHelper helper) {
        Grave grave = makeGrave(helper);
        ServerPlayer op = OffsetTestKit.join(helper, "Shifted", GameType.SURVIVAL);
        BlockPos pos = grave.pos();
        String real = pos.getX() + " " + pos.getY() + " " + pos.getZ();
        try {
            helper.assertFalse(CoordinateOffsets.offset(op).isZero(), "the op has no offset");
            TestPlayers.op(op);
            int mark = OffsetTestKit.mark(op);
            OffsetTestKit.type(op, "yolt grave list");
            List<String> listed = chat(op, mark);
            helper.assertTrue(listed.contains("Ghost " + grave.dimension().identifier() + " " + real),
                    "the op's client wasn't sent the real position: " + listed);

            mark = OffsetTestKit.mark(op);
            OffsetTestKit.type(op, "yolt grave remove " + real);
            List<String> removed = chat(op, mark);
            helper.assertTrue(removed.stream().noneMatch(text -> text.startsWith("Removed ")), "the typed real position removed: " + removed);
            helper.assertTrue(GraveStore.get(helper.getLevel().getServer()).byId(grave.id()).isPresent(), "the grave record went");
            helper.assertTrue(helper.getLevel().getBlockState(pos).is(Blocks.PLAYER_HEAD), "the grave head went");
        } finally {
            TestPlayers.deop(op);
            cleanUp(helper, grave, op);
        }
        helper.succeed();
    }

    // Relative coordinates are read from where the op stands
    @GameTest
    public void removeWithRelativeCoordinatesFromAPlayer(GameTestHelper helper) {
        Grave grave = makeGrave(helper);
        ServerPlayer op = TestPlayers.join(helper, "Standing", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        try {
            TestPlayers.op(op);
            BlockPos pos = grave.pos();
            op.teleportTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            helper.assertValueEqual(SkinGameTests.console(op.createCommandSourceStack(), "yolt grave remove ~ ~ ~"),
                    List.of("Removed Ghost's grave and dropped 3 stacks."), "command output");
            helper.assertTrue(GraveStore.get(helper.getLevel().getServer()).byId(grave.id()).isEmpty(), "the grave is still there");
        } finally {
            TestPlayers.deop(op);
            cleanUp(helper, grave, op);
            discardDrops(helper, grave);
        }
        helper.succeed();
    }

    // With no grave anywhere the list still answers, and counts as done. Every record is taken out and put back in this
    // tick, so no other test sees the store empty.
    @GameTest
    public void emptyListSaysNoGraves(GameTestHelper helper) {
        GraveStore store = GraveStore.get(helper.getLevel().getServer());
        List<Grave> all = List.copyOf(store.all());
        List<Integer> results = new ArrayList<>();
        List<String> output;
        try {
            all.forEach(grave -> store.remove(grave.id()));
            output = CommandGameTests.run(helper.getLevel().getServer(), "yolt grave list", results::add);
        } finally {
            all.forEach(store::put);
        }
        helper.assertValueEqual(output, List.of("No graves."), "command output");
        helper.assertValueEqual(results, List.of(1), "command result");
        helper.succeed();
    }

    // The list's result is how many graves there are, which execute store and a command block read
    @GameTest
    public void listResultIsTheGraveCount(GameTestHelper helper) {
        Grave grave = makeGrave(helper);
        try {
            List<Integer> results = new ArrayList<>();
            CommandGameTests.run(helper.getLevel().getServer(), "yolt grave list", results::add);
            helper.assertValueEqual(results, List.of(GraveStore.get(helper.getLevel().getServer()).all().size()), "command result");
        } finally {
            cleanUp(helper, grave);
        }
        helper.succeed();
    }

    // A command block is an admin's too: it runs the list, its success count is the number of graves, and its last
    // output line is one of them
    @GameTest
    public void commandBlockCanListGraves(GameTestHelper helper) {
        Grave grave = makeGrave(helper);
        BlockPos blockAt = new BlockPos(4, 1, 4);
        try {
            helper.setBlock(blockAt, Blocks.COMMAND_BLOCK);
            CommandBlockEntity block = (CommandBlockEntity) helper.getBlockEntity(blockAt, CommandBlockEntity.class);
            block.getCommandBlock().setTrackOutput(true);
            block.getCommandBlock().setCommand("yolt grave list");
            helper.assertTrue(block.getCommandBlock().performCommand(helper.getLevel()), "the command block did not run");
            Collection<Grave> all = GraveStore.get(helper.getLevel().getServer()).all();
            helper.assertValueEqual(block.getCommandBlock().getSuccessCount(), all.size(), "the command block's success count");
            String last = block.getCommandBlock().getLastOutput().getString();
            helper.assertTrue(all.stream().anyMatch(each -> last.endsWith(each.ownerName() + " " + each.dimension().identifier() + " "
                    + each.pos().getX() + " " + each.pos().getY() + " " + each.pos().getZ())), "the last output names no grave: " + last);
        } finally {
            helper.setBlock(blockAt, Blocks.AIR);
            cleanUp(helper, grave);
        }
        helper.succeed();
    }

    // The log says who removed which grave, where, and how much it dropped
    @GameTest
    public void removeIsLoggedWithWhoAndHowMany(GameTestHelper helper) {
        Grave grave = makeGrave(helper);
        try {
            BlockPos pos = grave.pos();
            List<String> log = OfflineGameTests.yoltLog(() ->
                    run(helper, "yolt grave remove " + pos.getX() + " " + pos.getY() + " " + pos.getZ()));
            helper.assertValueEqual(log.stream().filter(line -> line.contains(" removed ")).toList(),
                    List.of("Server removed Ghost's grave at " + grave.dimension().identifier() + " " + pos + " and dropped 3 stacks"),
                    "the removal's log lines");
        } finally {
            cleanUp(helper, grave);
            discardDrops(helper, grave);
        }
        helper.succeed();
    }

    // What the player's client was shown in chat since packet "from"
    private static List<String> chat(ServerPlayer player, int from) {
        return OffsetTestKit.sent(player, from, ClientboundSystemChatPacket.class).stream()
                .map(packet -> packet.content().getString())
                .toList();
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
