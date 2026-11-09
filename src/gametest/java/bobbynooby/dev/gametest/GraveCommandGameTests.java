package bobbynooby.dev.gametest;

import bobbynooby.dev.graves.Grave;
import bobbynooby.dev.graves.GraveItems;
import bobbynooby.dev.graves.GraveMenu;
import bobbynooby.dev.graves.GraveStore;
import bobbynooby.dev.graves.Graves;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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

    @GameTest
    public void removeWhileOpenDoesNotDuplicate(GameTestHelper helper) {
        Grave grave = makeGrave(helper);
        ServerPlayer player = TestPlayers.join(helper, "Looter", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        try {
            Graves.open(player, grave);
            helper.assertTrue(player.containerMenu instanceof GraveMenu, "menu did not open");
            BlockPos pos = grave.pos();
            run(helper, "yolt grave remove " + pos.getX() + " " + pos.getY() + " " + pos.getZ());

            helper.assertFalse(player.containerMenu instanceof GraveMenu, "menu still open");
            helper.assertTrue(GraveStore.get(helper.getLevel().getServer()).byId(grave.id()).isEmpty(), "record came back");
            helper.assertTrue(Graves.containerFor(grave) == null, "shared container still registered");
            int diamonds = dropped(helper, grave, Items.DIAMOND) + player.getInventory().countItem(Items.DIAMOND);
            helper.assertValueEqual(diamonds, 1, "diamonds in the world");
        } finally {
            cleanUp(helper, grave, player);
            discardDrops(helper, grave);
        }
        helper.succeed();
    }

    private static Grave makeGrave(GameTestHelper helper) {
        ResolvableProfile profile = ResolvableProfile.createUnresolved("Ghost");
        List<ItemStack> stacks = List.of(GraveItems.graveHead(profile), new ItemStack(Items.DIAMOND), new ItemStack(Items.IRON_SWORD));
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
