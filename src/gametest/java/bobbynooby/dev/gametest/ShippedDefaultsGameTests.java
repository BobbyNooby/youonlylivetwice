package bobbynooby.dev.gametest;

import bobbynooby.dev.chat.CommandRestriction;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.coords.CoordinateOffsets;
import bobbynooby.dev.coords.Offset;
import bobbynooby.dev.graves.Grave;
import bobbynooby.dev.graves.GraveItems;
import bobbynooby.dev.graves.GraveMenu;
import bobbynooby.dev.hardcore.Curse;
import bobbynooby.dev.hardcore.Hardcore;
import bobbynooby.dev.hardcore.HardcoreStates;
import bobbynooby.dev.lockout.DeathLockout;
import bobbynooby.dev.world.RandomSpawn;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.function.Predicate;

// The shared test world keeps six switches off that ship on (TestServerSetup.pinOldDefaults). This batch's environment
// turns them back on, so these tests see a server as it ships.
public class ShippedDefaultsGameTests {
    private static final String SHIPPED = "you-only-live-twice-gametest:shipped_defaults";
    // The same, with bodies on as they ship, which the shared world keeps off, and the grave compass, which ships off
    private static final String SHIPPED_COMPASS = "you-only-live-twice-gametest:shipped_defaults_compass";
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
            helper.assertTrue(lines.contains("Server rules"), "the report has no Server rules line: " + lines);
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

    // The story the README tells, on a server as it ships, with the off-by-default compass on: a player with an offset
    // falls to their death and is kicked, leaving a grave with their things, a max heart lost and no body. Another
    // player takes the head from the grave, crafts the paper at a crafting table and uses it. The dead player rejoins,
    // respawns at a new spot with the heart still gone, and the compass they are given points at the grave in the frame
    // their client has now, which is a new one. Bodies are on for the batch, as shipped, so every leave here makes one
    // and the clean-up takes it away. The spawn search is seeded, so a failure repeats.
    @GameTest(environment = SHIPPED_COMPASS, maxTicks = 200)
    public void shippedDefaultsDeathToReviveStory(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel level = helper.getLevel();
        ServerPlayer victim = TestPlayers.join(helper, "Faller", GameType.SURVIVAL, AT);
        TestPlayers.Recording reviver = TestPlayers.joinRecording(helper, "Mourner", GameType.SURVIVAL, AT.offset(2, 0, 2));
        GameProfile profile = victim.getGameProfile();
        UUID id = victim.getUUID();
        String name = profile.name();
        BlockPos table = new BlockPos(5, 1, 5);
        Runnable cleanUp = BodyTestKit.once(() -> {
            RandomSpawn.useRandomForTesting(null);
            Curse.set(id, 0);
            DeathLockout.revive(id, name, "console");
            BodyTestKit.cleanUp(helper, id);
            BodyTestKit.cleanUp(helper, reviver.getUUID());
            helper.setBlock(table, Blocks.AIR);
        });
        boolean waiting = false;
        try {
            helper.assertTrue(Settings.get().bodies.enabled && Settings.get().graves.compass, "bodies or the compass are off");
            RandomSpawn.useRandomForTesting(new Random(7));
            Offset before = CoordinateOffsets.offset(victim);
            helper.assertFalse(before.isZero(), "the victim's join got no offset");
            victim.getInventory().add(new ItemStack(Items.DIAMOND, 5));
            TestPlayers.markLoaded(victim);
            victim.fallDistance = 30.0;
            victim.hurtServer(level, level.damageSources().fall(), Float.MAX_VALUE);
            helper.assertTrue(victim.isDeadOrDying(), "the fall did not kill the victim");
            helper.assertValueEqual(HardcoreStates.read(id).curseHeartsLost, 1, "max hearts the death took");
            List<Grave> graves = BodyTestKit.gravesOf(helper, id);
            helper.assertValueEqual(graves.size(), 1, "graves");
            Grave grave = graves.get(0);
            helper.assertValueEqual(GraveAsserts.count(grave.items(), Items.DIAMOND), 5, "diamonds in the grave");

            // The kick waits for the end of the tick
            helper.runAfterDelay(5, () -> {
                try {
                    afterTheKick(helper, server, victim, reviver, profile, grave, table, before);
                } finally {
                    cleanUp.run();
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                cleanUp.run();
            }
        }
    }

    private static void afterTheKick(GameTestHelper helper, MinecraftServer server, ServerPlayer victim,
                                     TestPlayers.Recording reviver, GameProfile profile, Grave grave, BlockPos table, Offset before) {
        UUID id = profile.id();
        String name = profile.name();
        helper.assertTrue(server.getPlayerList().getPlayer(id) == null, "the victim was not kicked");
        String reason = LockoutGameTests.disconnectReason(victim);
        helper.assertTrue(reason.startsWith("You died: " + name), "kick reason: " + reason);
        helper.assertTrue(BodyTestKit.store(helper).byOwner(id).isEmpty(), "the kick left a body record");
        helper.assertTrue(BodyTestKit.bodiesOf(helper, id).isEmpty(), "the kick left a body");

        // The head is the grave's first item, taken with a shift-click
        BlockHitResult onGrave = new BlockHitResult(Vec3.atCenterOf(grave.pos()), Direction.UP, grave.pos(), false);
        reviver.gameMode.useItemOn(reviver, reviver.level(), reviver.getMainHandItem(), InteractionHand.MAIN_HAND, onGrave);
        helper.assertTrue(reviver.containerMenu instanceof GraveMenu, "the grave did not open");
        reviver.containerMenu.clicked(0, 0, ContainerInput.QUICK_MOVE, reviver);
        reviver.closeContainer();
        ItemStack head = findIn(reviver, GraveItems::isGraveHead);
        helper.assertTrue(head != null, "the reviver did not get the grave head");

        helper.setBlock(table, Blocks.CRAFTING_TABLE);
        BlockPos tablePos = helper.absolutePos(table);
        reviver.openMenu(new SimpleMenuProvider((containerId, inventory, user) ->
                new CraftingMenu(containerId, inventory, ContainerLevelAccess.create(reviver.level(), tablePos)),
                Component.literal("Crafting")));
        helper.assertTrue(reviver.containerMenu instanceof CraftingMenu, "the crafting table did not open");
        CraftingMenu crafting = (CraftingMenu) reviver.containerMenu;
        List<ItemStack> grid = List.of(head.copyWithCount(1), new ItemStack(Items.NETHER_STAR), new ItemStack(Items.NETHERITE_INGOT),
                new ItemStack(Items.DIAMOND), new ItemStack(Items.GOLD_INGOT), new ItemStack(Items.IRON_INGOT));
        head.shrink(1);
        for (int i = 0; i < grid.size(); i++) {
            crafting.getSlot(1 + i).set(grid.get(i));
        }
        crafting.clicked(0, 0, ContainerInput.QUICK_MOVE, reviver);
        reviver.closeContainer();
        ItemStack paper = findIn(reviver, GraveItems::isReviveHead);
        helper.assertTrue(paper != null, "the crafting table made no revive paper");
        helper.assertValueEqual(paper.getHoverName().getString(), "Revive " + name, "the paper's name");

        int mark = reviver.messages.size();
        reviver.setItemInHand(InteractionHand.MAIN_HAND, paper.copy());
        paper.setCount(0);
        reviver.gameMode.useItem(reviver, reviver.level(), reviver.getMainHandItem(), InteractionHand.MAIN_HAND);
        helper.assertValueEqual(reviver.messages.subList(mark, reviver.messages.size()).stream().map(Component::getString).toList(),
                List.of(name + " can come back now."), "what the reviver was told");
        helper.assertTrue(DeathLockout.refusal(id, name) == null, "the victim is still locked out");

        ServerPlayer back = TestPlayers.rejoin(helper, profile, GameType.SURVIVAL, AT);
        helper.assertTrue(back.isDeadOrDying(), "the victim did not come back dead");
        ServerPlayer respawned = TestPlayers.respawn(back, false, Entity.RemovalReason.KILLED);
        helper.assertValueEqual(respawned.getMaxHealth(), 18.0F, "max health after the return");
        helper.assertValueEqual(respawned.getInventory().countItem(Items.DIAMOND), 0, "diamonds the victim came back with");
        Offset now = CoordinateOffsets.offset(respawned);
        helper.assertFalse(now.equals(before), "the respawn kept the old offset " + before);

        ItemStack compass = findIn(respawned, stack -> stack.is(Items.COMPASS));
        helper.assertTrue(compass != null, "the victim got no compass");
        GlobalPos target = GlobalPos.of(grave.dimension(), grave.pos());
        helper.assertValueEqual(compass.get(DataComponents.LODESTONE_TRACKER).target(), Optional.of(target), "the compass's real target");
        // What the player's tick sends for the slot the compass went into, which the client then holds
        int sentMark = TestPlayers.sentPackets(respawned).size();
        respawned.inventoryMenu.broadcastChanges();
        List<ClientboundContainerSetSlotPacket> slots = OffsetTestKit.sent(respawned, sentMark, ClientboundContainerSetSlotPacket.class)
                .stream().filter(slot -> slot.getItem().is(Items.COMPASS)).toList();
        helper.assertFalse(slots.isEmpty(), "the client was not sent the compass");
        LodestoneTracker seen = slots.getLast().getItem().get(DataComponents.LODESTONE_TRACKER);
        helper.assertValueEqual(seen.target(), Optional.of(now.shift(target)), "the compass's target in the client's frame");
    }

    // The first stack in the player's inventory that matches, or null
    private static @Nullable ItemStack findIn(ServerPlayer player, Predicate<ItemStack> match) {
        for (ItemStack stack : player.getInventory()) {
            if (!stack.isEmpty() && match.test(stack)) {
                return stack;
            }
        }
        return null;
    }

    // What a client sends for a command typed in chat
    private static void type(ServerPlayer player, String command) {
        player.connection.handleChatCommand(new ServerboundChatCommandPacket(command));
    }

    private static List<String> texts(TestPlayers.Recording player, int from) {
        return player.messages.subList(from, player.messages.size()).stream().map(Component::getString).toList();
    }
}
