package bobbynooby.dev.gametest;

import bobbynooby.dev.chat.CommandRestriction;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.coords.CoordinateOffsets;
import bobbynooby.dev.hardcore.Curse;
import bobbynooby.dev.hardcore.Hardcore;
import bobbynooby.dev.hardcore.HardcoreStates;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

import java.util.List;

// The shared test world keeps six switches off that ship on (TestServerSetup.pinOldDefaults). This batch's environment
// turns them back on, so these tests see a server as it ships.
public class ShippedDefaultsGameTests {
    private static final String SHIPPED = "you-only-live-twice-gametest:shipped_defaults";
    private static final BlockPos AT = new BlockPos(1, 1, 1);

    @GameTest(environment = SHIPPED)
    public void nonOpHasStatusAndItsRulesLine(GameTestHelper helper) {
        helper.assertTrue(Hardcore.anyOn(), "no hardcore feature is on as shipped");
        TestPlayers.Recording player = TestPlayers.joinRecording(helper, "Shipped", GameType.SURVIVAL, AT);
        try {
            helper.assertTrue(CommandRestriction.restricted(player), "the test player is not restricted");
            helper.getLevel().getServer().getCommands().sendCommands(player);
            helper.assertTrue(CommandRestrictionGameTests.lastTree(helper, player).getChild("status") != null,
                    "the tree sent to a non-op has no status");

            int from = player.messages.size();
            type(player, "status");
            List<String> lines = texts(player, from);
            helper.assertTrue(!lines.isEmpty() && lines.getFirst().equals("Your body"), "the report: " + lines);
            helper.assertTrue(lines.stream().anyMatch(line -> line.startsWith("Rules ")), "the report has no Rules line: " + lines);
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    @GameTest(environment = SHIPPED)
    public void nonOpCanWhisper(GameTestHelper helper) {
        TestPlayers.Recording sender = TestPlayers.joinRecording(helper, "ShipSend", GameType.SURVIVAL, AT);
        TestPlayers.Recording near = TestPlayers.joinRecording(helper, "ShipNear", GameType.SURVIVAL, AT.offset(2, 0, 0));
        try {
            helper.assertTrue(CommandRestriction.restricted(sender), "the sender is not restricted");
            helper.getLevel().getServer().getCommands().sendCommands(sender);
            helper.assertTrue(CommandRestrictionGameTests.lastTree(helper, sender).getChild("w") != null,
                    "the tree sent to a non-op has no w");

            String senderName = sender.nameAndId().name();
            near.messages.clear();
            // Straight through the dispatcher, as the whisper tests do: an unsigned packet can't carry a signed argument
            helper.getLevel().getServer().getCommands().performPrefixedCommand(sender.createCommandSourceStack(),
                    "w " + near.nameAndId().name() + " hi");
            helper.assertValueEqual(texts(near, 0), List.of(senderName + " whispers to you: hi"), "the near player's messages");
        } finally {
            TestPlayers.leave(sender);
            TestPlayers.leave(near);
        }
        helper.succeed();
    }

    // Creative, so the leave makes no body
    @GameTest(environment = SHIPPED)
    public void joiningPlayerGetsAnOffset(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "ShipOffset", GameType.CREATIVE, AT);
        try {
            helper.assertFalse(CoordinateOffsets.offset(player).isZero(), "the join got no offset");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // Vanilla would add the 63 to the price and refuse the job as too expensive
    @GameTest(environment = SHIPPED)
    public void anvilJobIsPricedFlat(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "ShipAnvil", GameType.SURVIVAL, AT);
        player.giveExperienceLevels(30);
        try {
            player.openMenu(new SimpleMenuProvider((id, inventory, user) -> new AnvilMenu(id, inventory, ContainerLevelAccess.NULL),
                    Component.literal("Anvil")));
            AnvilMenu menu = (AnvilMenu) player.containerMenu;
            ItemStack pickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
            pickaxe.setDamageValue(100);
            pickaxe.set(DataComponents.REPAIR_COST, 63);
            menu.getSlot(AnvilMenu.INPUT_SLOT).set(pickaxe);
            menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).set(new ItemStack(Items.DIAMOND));
            YoltConfig config = Settings.get();
            helper.assertFalse(menu.getSlot(AnvilMenu.RESULT_SLOT).getItem().isEmpty(), "no result for an old item");
            helper.assertValueEqual(menu.getCost(), config.hardcore.anvil.repairCost, "the cost");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // A totem is a return from death the curse counts, and it costs the heart at once, with no kick, grave or respawn in
    // the way, which the lockout and graves, on as shipped, would bring with a real death
    @GameTest(environment = SHIPPED)
    public void totemCostsAMaxHeart(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "ShipCurse", GameType.SURVIVAL, AT);
        // Loaded, so the player can be hurt, which vanilla holds off until a client says the world is in
        TestPlayers.markLoaded(player);
        try {
            player.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
            player.setHealth(2.0F);
            player.hurtServer(helper.getLevel(), helper.getLevel().damageSources().generic(), 10.0F);
            helper.assertFalse(player.isDeadOrDying(), "the totem did not save the player");
            helper.assertValueEqual(HardcoreStates.read(player.getUUID()).curseHeartsLost, 1, "hearts lost to the totem");
            helper.assertValueEqual(player.getMaxHealth(), 18.0F, "max health after the totem");
        } finally {
            Curse.set(player.getUUID(), 0);
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // What a client sends for a command typed in chat
    private static void type(ServerPlayer player, String command) {
        player.connection.handleChatCommand(new ServerboundChatCommandPacket(command));
    }

    private static List<String> texts(TestPlayers.Recording player, int from) {
        return player.messages.subList(from, player.messages.size()).stream().map(Component::getString).toList();
    }
}
