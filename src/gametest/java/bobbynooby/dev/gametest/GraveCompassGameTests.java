package bobbynooby.dev.gametest;

import bobbynooby.dev.bodies.Kills;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.graves.Grave;
import bobbynooby.dev.graves.GraveBlocks;
import bobbynooby.dev.graves.GraveItems;
import bobbynooby.dev.graves.GraveStore;
import bobbynooby.dev.graves.Graves;
import bobbynooby.dev.lockout.DeathLockout;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Each test flips the compass switch, the lockout, keepInventory or the ender chest mode, so each runs in an environment
// of its own, and puts them all back in its finally
public class GraveCompassGameTests {
    private static final String ONCE = "you-only-live-twice-gametest:grave_compass_once";
    private static final String TARGET = "you-only-live-twice-gametest:grave_compass_target";
    private static final String LATEST = "you-only-live-twice-gametest:grave_compass_latest";
    private static final String OFF = "you-only-live-twice-gametest:grave_compass_off";
    private static final String LOCKOUT = "you-only-live-twice-gametest:grave_compass_lockout";
    private static final String DEAD_JOINER = "you-only-live-twice-gametest:grave_compass_dead_joiner";
    private static final String FULL = "you-only-live-twice-gametest:grave_compass_full";
    private static final String SPIN = "you-only-live-twice-gametest:grave_compass_spin";
    private static final String TWO_GRAVES = "you-only-live-twice-gametest:grave_compass_two_graves";
    private static final String SWITCHED_OFF = "you-only-live-twice-gametest:grave_compass_switched_off";
    private static final String MOVED = "you-only-live-twice-gametest:grave_compass_moved";

    // One compass at the respawn, and the rejoin after it owes none
    @GameTest(environment = ONCE)
    public void compassGivenOnceAfterDeath(GameTestHelper helper) {
        Runnable restore = keepSwitches(helper);
        ServerPlayer player = join(helper, "CompassOnce");
        try {
            config().graves.compass = true;
            config().deathLockout.enabled = false;
            ServerPlayer back = dieAndRespawn(helper, player);
            helper.assertValueEqual(compasses(back).size(), 1, "compasses after the respawn");

            TestPlayers.leave(back);
            ServerPlayer rejoined = TestPlayers.rejoin(helper, back.getGameProfile(), GameType.SURVIVAL, new BlockPos(1, 1, 1));
            helper.assertValueEqual(compasses(rejoined).size(), 1, "compasses after the rejoin");
        } finally {
            restore.run();
            cleanUp(helper, player.getUUID());
        }
        helper.succeed();
    }

    // The grave owes the compass on disk too, from the death's own save, so a restart before the respawn keeps it owed
    @GameTest(environment = TARGET)
    public void compassPointsAtTheGrave(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Runnable restore = keepSwitches(helper);
        ServerPlayer player = join(helper, "CompassAim");
        UUID owner = player.getUUID();
        String name = player.nameAndId().name();
        try {
            config().graves.compass = true;
            config().deathLockout.enabled = false;
            GraveDeathGameTests.respawnBesideTest(helper, player);
            kill(helper, player);
            Grave grave = onlyGrave(helper, owner);
            helper.assertTrue(grave.compassPending(), "the grave owes no compass");
            helper.assertTrue(GraveStoreGameTests.onDisk(server).stream().anyMatch(saved -> saved.id().equals(grave.id())
                    && saved.compassPending()), "the grave on disk owes no compass");

            ItemStack compass = onlyCompass(helper, respawn(helper, player));
            helper.assertValueEqual(trackerOf(compass), pointingAt(grave), "the compass's tracker");
            helper.assertValueEqual(compass.get(DataComponents.CUSTOM_NAME),
                    Component.literal(name + "'s grave").withStyle(style -> style.withItalic(false)), "the compass's name");
            helper.assertValueEqual(compass.getHoverName().getString(), name + "'s grave", "the name the compass shows");
            // Every item carries an empty lore by default
            helper.assertValueEqual(compass.get(DataComponents.LORE), ItemLore.EMPTY, "the compass's lore");
            CompoundTag marker = new CompoundTag();
            marker.putString("grave_compass", grave.id().toString());
            CompoundTag expected = new CompoundTag();
            expected.put("yolt", marker);
            helper.assertValueEqual(compass.get(DataComponents.CUSTOM_DATA), CustomData.of(expected),
                    "the compass's custom data");
            helper.assertFalse(onlyGrave(helper, owner).compassPending(), "the grave still owes its compass");
        } finally {
            restore.run();
            cleanUp(helper, owner);
        }
        helper.succeed();
    }

    // Both deaths are while locked out, so the first grave still owes its compass when the second death comes. The
    // second death takes that over, and the respawn after the revive gives one compass, to the second grave.
    @GameTest(environment = LATEST)
    public void compassPointsAtLatestGrave(GameTestHelper helper) {
        Runnable restore = keepSwitches(helper);
        ServerPlayer player = join(helper, "CompassNew");
        UUID owner = player.getUUID();
        String name = player.nameAndId().name();
        try {
            config().graves.compass = true;
            assertLockoutOn(helper);
            GraveDeathGameTests.respawnBesideTest(helper, player);
            kill(helper, player);
            ServerPlayer back = respawnByButton(player);
            helper.assertValueEqual(compasses(back).size(), 0, "compasses at the respawn while locked out");
            Grave first = onlyGrave(helper, owner);
            helper.assertTrue(first.compassPending(), "the first grave owes no compass");

            kill(helper, back);
            Grave second = BodyTestKit.gravesOf(helper, owner).stream().filter(grave -> !grave.id().equals(first.id()))
                    .findFirst().orElseThrow();
            helper.assertTrue(second.compassPending(), "the second grave owes no compass");
            helper.assertFalse(current(helper, first).compassPending(), "the first grave still owes a compass");

            helper.assertValueEqual(DeathLockout.revive(owner, name, "test"), DeathLockout.ReviveResult.REVIVED, "the revive");
            ItemStack compass = onlyCompass(helper, respawn(helper, back));
            helper.assertValueEqual(trackerOf(compass), pointingAt(second), "the compass's tracker");
            helper.assertFalse(current(helper, second).compassPending(), "the second grave still owes its compass");
        } finally {
            restore.run();
            cleanUp(helper, owner);
        }
        helper.succeed();
    }

    @GameTest(environment = OFF)
    public void compassNotGivenWithSwitchOff(GameTestHelper helper) {
        Runnable restore = keepSwitches(helper);
        ServerPlayer player = join(helper, "CompassOff");
        try {
            config().graves.compass = false;
            config().deathLockout.enabled = false;
            ServerPlayer back = dieAndRespawn(helper, player);
            helper.assertValueEqual(compasses(back).size(), 0, "compasses after the respawn");
            helper.assertFalse(onlyGrave(helper, player.getUUID()).compassPending(), "the grave owes a compass");
        } finally {
            restore.run();
            cleanUp(helper, player.getUUID());
        }
        helper.succeed();
    }

    // The revive comes before the kick, which then finds nothing to kick for
    @GameTest(environment = LOCKOUT)
    public void compassWaitsForLockoutToEnd(GameTestHelper helper) {
        Runnable restore = keepSwitches(helper);
        ServerPlayer player = join(helper, "CompassWait");
        UUID owner = player.getUUID();
        try {
            config().graves.compass = true;
            assertLockoutOn(helper);
            ServerPlayer back = dieAndRespawn(helper, player);
            helper.assertValueEqual(compasses(back).size(), 0, "compasses at the respawn while locked out");
            helper.assertTrue(onlyGrave(helper, owner).compassPending(), "the grave stopped owing its compass");

            helper.assertValueEqual(DeathLockout.revive(owner, player.nameAndId().name(), "test"),
                    DeathLockout.ReviveResult.REVIVED, "the revive");
            TestPlayers.leave(back);
            ServerPlayer rejoined = TestPlayers.rejoin(helper, back.getGameProfile(), GameType.SURVIVAL, new BlockPos(1, 1, 1));
            helper.assertValueEqual(compasses(rejoined).size(), 1, "compasses after the rejoin");
        } finally {
            restore.run();
            cleanUp(helper, owner);
        }
        helper.succeed();
    }

    // Killed while away, then revived: the join finds them dead, so the compass waits for the respawn
    @GameTest(environment = DEAD_JOINER)
    public void deadJoinerGetsCompassAfterRespawn(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Runnable restore = keepSwitches(helper);
        ServerPlayer player = join(helper, "CompassAway");
        GameProfile profile = player.getGameProfile();
        NameAndId who = player.nameAndId();
        try {
            config().graves.compass = true;
            assertLockoutOn(helper);
            GraveDeathGameTests.respawnBesideTest(helper, player);
            TestPlayers.leave(player);
            helper.assertValueEqual(Kills.kill(server, who), Kills.Result.KILLED_OFFLINE, "the kill");
            helper.assertTrue(onlyGrave(helper, who.id()).compassPending(), "the grave of the death away owes no compass");
            helper.assertTrue(DeathLockout.refusal(who.id(), who.name()) != null, "the death away did not lock the player out");

            helper.assertValueEqual(DeathLockout.revive(who.id(), who.name(), "test"), DeathLockout.ReviveResult.REVIVED,
                    "the revive");
            ServerPlayer back = TestPlayers.rejoin(helper, profile, GameType.SURVIVAL, new BlockPos(1, 1, 1));
            helper.assertTrue(back.isDeadOrDying(), "the player did not come back dead");
            helper.assertValueEqual(compasses(back).size(), 0, "compasses while dead");
            helper.assertValueEqual(compasses(respawn(helper, back)).size(), 1, "compasses after the respawn");
        } finally {
            restore.run();
            cleanUp(helper, who.id());
        }
        helper.succeed();
    }

    @GameTest(environment = FULL)
    public void compassDropsWhenInventoryFull(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Runnable restore = keepSwitches(helper);
        ServerPlayer player = join(helper, "CompassFull");
        try {
            config().graves.compass = true;
            config().deathLockout.enabled = false;
            server.getGameRules().set(GameRules.KEEP_INVENTORY, true, server);
            Inventory inventory = player.getInventory();
            for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
                inventory.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
            }
            ServerPlayer back = dieAndRespawn(helper, player);
            helper.assertValueEqual(back.getInventory().getFreeSlot(), -1, "a free slot after the respawn");
            helper.assertValueEqual(compasses(back).size(), 0, "compasses in the inventory");

            List<ItemEntity> dropped = droppedCompasses(helper);
            helper.assertValueEqual(dropped.size(), 1, "compasses dropped");
            helper.assertTrue(dropped.get(0).distanceTo(back) < 2.0, "the compass dropped "
                    + dropped.get(0).distanceTo(back) + " blocks from the player");
            helper.assertValueEqual(trackerOf(dropped.get(0).getItem()),
                    pointingAt(onlyGrave(helper, player.getUUID())), "the dropped compass's tracker");
        } finally {
            restore.run();
            droppedCompasses(helper).forEach(Entity::discard);
            cleanUp(helper, player.getUUID());
        }
        helper.succeed();
    }

    // A tick while the grave is there changes nothing; once it is gone the compass loses its target and its marker
    @GameTest(environment = SPIN)
    public void compassSpinsOnceGraveIsGone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Runnable restore = keepSwitches(helper);
        ServerPlayer player = join(helper, "CompassSpin");
        try {
            config().graves.compass = true;
            config().deathLockout.enabled = false;
            ServerPlayer back = dieAndRespawn(helper, player);
            Grave grave = onlyGrave(helper, player.getUUID());
            ItemStack compass = onlyCompass(helper, back);
            compass.inventoryTick(level, back, null);
            helper.assertValueEqual(trackerOf(compass), pointingAt(grave), "the tracker while the grave is there");
            helper.assertTrue(compass.get(DataComponents.CUSTOM_DATA) != null, "the compass lost its marker");

            helper.assertTrue(Graves.removeAndDrop(level.getServer(), grave) instanceof Graves.Removal.Dropped,
                    "the grave was not removed");
            compass.inventoryTick(level, back, null);
            helper.assertValueEqual(trackerOf(compass), new LodestoneTracker(Optional.empty(), false),
                    "the tracker once the grave is gone");
            helper.assertTrue(compass.get(DataComponents.CUSTOM_DATA) == null, "the compass kept its marker");
        } finally {
            restore.run();
            // The removal dropped the grave head
            itemsInTest(helper).forEach(Entity::discard);
            cleanUp(helper, player.getUUID());
        }
        helper.succeed();
    }

    // 36 inventory stacks, 27 from the ender chest and the head make 64 stacks, so two graves, and the head is in one
    @GameTest(environment = TWO_GRAVES)
    public void compassPointsAtHeadGraveWhenTwoGraves(GameTestHelper helper) {
        Runnable restore = keepSwitches(helper);
        ServerPlayer player = join(helper, "CompassTwo");
        UUID owner = player.getUUID();
        try {
            config().graves.compass = true;
            config().deathLockout.enabled = false;
            config().graves.enderChestOnDeath = YoltConfig.EnderChestMode.GRAVE;
            Inventory inventory = player.getInventory();
            for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
                inventory.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
            }
            PlayerEnderChestContainer enderChest = player.getEnderChestInventory();
            for (int slot = 0; slot < enderChest.getContainerSize(); slot++) {
                enderChest.setItem(slot, new ItemStack(Items.EMERALD, 64));
            }
            ServerPlayer back = dieAndRespawn(helper, player);
            List<Grave> graves = BodyTestKit.gravesOf(helper, owner);
            helper.assertValueEqual(graves.size(), 2, "graves for 64 stacks");
            List<Grave> withHead = graves.stream()
                    .filter(grave -> grave.items().stream().anyMatch(GraveItems::isGraveHead)).toList();
            helper.assertValueEqual(withHead.size(), 1, "graves holding the head");

            ItemStack compass = onlyCompass(helper, back);
            helper.assertValueEqual(trackerOf(compass), pointingAt(withHead.get(0)), "the compass's tracker");
            helper.assertTrue(BodyTestKit.gravesOf(helper, owner).stream().noneMatch(Grave::compassPending),
                    "a grave still owes a compass");
        } finally {
            restore.run();
            cleanUp(helper, owner);
        }
        helper.succeed();
    }

    // The switch at the death decides, so a death while it was on still gets its compass after it goes off
    @GameTest(environment = SWITCHED_OFF)
    public void compassGivenAfterSwitchTurnedOff(GameTestHelper helper) {
        Runnable restore = keepSwitches(helper);
        ServerPlayer player = join(helper, "CompassLate");
        try {
            config().graves.compass = true;
            config().deathLockout.enabled = false;
            GraveDeathGameTests.respawnBesideTest(helper, player);
            kill(helper, player);
            config().graves.compass = false;
            helper.assertValueEqual(compasses(respawn(helper, player)).size(), 1, "compasses after the respawn");
        } finally {
            restore.run();
            cleanUp(helper, player.getUUID());
        }
        helper.succeed();
    }

    // Stone where the head was makes the grave's restore move it, as on a chunk load or the re-check, and the compass's
    // next tick points at the new spot
    @GameTest(environment = MOVED)
    public void compassFollowsMovedGrave(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Runnable restore = keepSwitches(helper);
        ServerPlayer player = join(helper, "CompassMove");
        List<BlockPos> stone = new ArrayList<>();
        try {
            config().graves.compass = true;
            config().deathLockout.enabled = false;
            ServerPlayer back = dieAndRespawn(helper, player);
            Grave grave = onlyGrave(helper, player.getUUID());
            ItemStack compass = onlyCompass(helper, back);

            stone.add(grave.pos());
            level.setBlock(grave.pos(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            GraveBlocks.restoreChunk(level, level.getChunkAt(grave.pos()));
            Grave moved = current(helper, grave);
            helper.assertFalse(moved.pos().equals(grave.pos()), "the grave did not move off " + grave.pos());

            compass.inventoryTick(level, back, null);
            helper.assertValueEqual(trackerOf(compass), pointingAt(moved), "the tracker after the move");
        } finally {
            restore.run();
            stone.forEach(pos -> level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL));
            cleanUp(helper, player.getUUID());
        }
        helper.succeed();
    }

    private static YoltConfig config() {
        return Settings.get();
    }

    // The switches these tests flip, as they are now, and how to put them back
    private static Runnable keepSwitches(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig config = config();
        boolean compass = config.graves.compass;
        boolean lockout = config.deathLockout.enabled;
        YoltConfig.EnderChestMode enderChest = config.graves.enderChestOnDeath;
        boolean keepInventory = server.getGameRules().get(GameRules.KEEP_INVENTORY);
        return () -> {
            config.graves.compass = compass;
            config.deathLockout.enabled = lockout;
            config.graves.enderChestOnDeath = enderChest;
            server.getGameRules().set(GameRules.KEEP_INVENTORY, keepInventory, server);
        };
    }

    private static void assertLockoutOn(GameTestHelper helper) {
        helper.assertTrue(config().deathLockout.enabled && config().deathLockout.cooldownDays > 0,
                "the lockout is not on with days to wait");
    }

    private static ServerPlayer join(GameTestHelper helper, String name) {
        return TestPlayers.join(helper, name, GameType.SURVIVAL, new BlockPos(1, 1, 1));
    }

    private static void kill(GameTestHelper helper, ServerPlayer player) {
        TestPlayers.markLoaded(player);
        player.kill(player.level());
        helper.assertTrue(player.isDeadOrDying(), "the player did not die");
    }

    // Back through the death screen's respawn
    private static ServerPlayer respawn(GameTestHelper helper, ServerPlayer player) {
        return helper.getLevel().getServer().getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
    }

    // As the death screen's button does it. The connection then takes the new player, and stops waiting for a respawn,
    // which a respawn straight through the player list leaves it doing, so that player can't be hurt.
    private static ServerPlayer respawnByButton(ServerPlayer player) {
        player.connection.handleClientCommand(
                new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
        return player.connection.player;
    }

    // Killed, then back at a respawn point beside the test, so no random spot is searched
    private static ServerPlayer dieAndRespawn(GameTestHelper helper, ServerPlayer player) {
        GraveDeathGameTests.respawnBesideTest(helper, player);
        kill(helper, player);
        return respawn(helper, player);
    }

    // Every compass anywhere in the inventory, armour and off hand included
    private static List<ItemStack> compasses(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        List<ItemStack> found = new ArrayList<>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(Items.COMPASS)) {
                found.add(stack);
            }
        }
        return found;
    }

    private static ItemStack onlyCompass(GameTestHelper helper, ServerPlayer player) {
        List<ItemStack> found = compasses(player);
        helper.assertValueEqual(found.size(), 1, "compasses on the player");
        return found.get(0);
    }

    private static @Nullable LodestoneTracker trackerOf(ItemStack compass) {
        return compass.get(DataComponents.LODESTONE_TRACKER);
    }

    private static LodestoneTracker pointingAt(Grave grave) {
        return new LodestoneTracker(Optional.of(GlobalPos.of(grave.dimension(), grave.pos())), false);
    }

    private static Grave onlyGrave(GameTestHelper helper, UUID owner) {
        List<Grave> graves = BodyTestKit.gravesOf(helper, owner);
        helper.assertValueEqual(graves.size(), 1, "graves of the player");
        return graves.get(0);
    }

    // The record as the store has it now
    private static Grave current(GameTestHelper helper, Grave grave) {
        return GraveStore.get(helper.getLevel().getServer()).byId(grave.id()).orElseThrow();
    }

    // Each test runs alone in its batch, so every item in its area is its own
    private static List<ItemEntity> itemsInTest(GameTestHelper helper) {
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, helper.getBounds().inflate(8.0), Entity::isAlive);
    }

    private static List<ItemEntity> droppedCompasses(GameTestHelper helper) {
        return itemsInTest(helper).stream().filter(item -> item.getItem().is(Items.COMPASS)).toList();
    }

    // Whichever player object is online for the owner leaves, so a queued kick finds nobody, and their graves go
    private static void cleanUp(GameTestHelper helper, UUID owner) {
        ServerPlayer online = helper.getLevel().getServer().getPlayerList().getPlayer(owner);
        if (online != null) {
            TestPlayers.leave(online);
        }
        BodyTestKit.removeGraves(helper, owner);
    }
}
