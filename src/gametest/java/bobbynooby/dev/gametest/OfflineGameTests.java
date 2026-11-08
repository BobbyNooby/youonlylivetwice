package bobbynooby.dev.gametest;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.lockout.DeathLockout;
import bobbynooby.dev.offline.OfflineKill;
import bobbynooby.dev.offline.OfflinePlayerData;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

public class OfflineGameTests {
    @GameTest(maxTicks = 40)
    public void offlineKillDropsSavedItemsAndLocksOut(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TestPlayers.join(helper, "Off", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        NameAndId who = player.nameAndId();
        BlockPos last = helper.relativePos(player.blockPosition());
        try {
            player.getInventory().add(new ItemStack(Items.DIAMOND));
            player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            player.giveExperienceLevels(30);
        } finally {
            // Leaving saves the player, like a real logout
            TestPlayers.leave(player);
        }

        CompoundTag before = OfflinePlayerData.load(server, who).orElseThrow();
        helper.assertFalse(before.getListOrEmpty("Inventory").isEmpty(), "inventory was not saved");
        helper.assertFalse(before.getCompoundOrEmpty("equipment").isEmpty(), "equipment was not saved");

        helper.assertValueEqual(OfflineKill.kill(server, who), OfflineKill.KillResult.KILLED_OFFLINE, "kill result");
        helper.assertValueEqual(itemEntities(helper, Items.DIAMOND, last), 1, "diamond item entities");
        helper.assertItemEntityCountIs(Items.DIAMOND, last, 2.0, 1);
        helper.assertValueEqual(itemEntities(helper, Items.IRON_HELMET, last), 1, "iron helmet item entities");
        helper.assertEntityPresent(EntityTypes.EXPERIENCE_ORB, last, 2.0);

        CompoundTag saved = OfflinePlayerData.load(server, who).orElseThrow();
        helper.assertTrue(saved.getListOrEmpty("Inventory").isEmpty(), "saved inventory still has items");
        helper.assertTrue(saved.getCompoundOrEmpty("equipment").isEmpty(), "saved equipment still has items");
        helper.assertValueEqual(saved.getFloatOr("Health", -1.0F), 0.0F, "saved health");
        helper.assertValueEqual(saved.getIntOr("XpLevel", -1), 0, "saved XP level");
        helper.assertValueEqual(saved.getIntOr("XpTotal", -1), 0, "saved total XP");
        helper.assertTrue(DeathLockout.refusal(who.id()) != null, "offline kill did not start the lockout");
        helper.succeed();
    }

    // A non-empty folder where the old save gets moved can't be deleted, so the swap of the new save fails
    @GameTest
    public void offlineKillFailureLeavesNoDuplicate(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TestPlayers.join(helper, "Fail", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        NameAndId who = player.nameAndId();
        BlockPos last = helper.relativePos(player.blockPosition());
        try {
            player.getInventory().add(new ItemStack(Items.DIAMOND));
            player.giveExperienceLevels(30);
        } finally {
            TestPlayers.leave(player);
        }

        Path dir = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
        Path blocker = dir.resolve(who.id() + ".dat_old");
        Path blockerContent = blocker.resolve("blocker");
        Files.createDirectories(blocker);
        Files.writeString(blockerContent, "");
        boolean failed = false;
        try {
            OfflineKill.kill(server, who);
        } catch (RuntimeException expected) {
            failed = true;
        } finally {
            Files.delete(blockerContent);
            Files.delete(blocker);
        }

        helper.assertTrue(failed, "kill did not report the failed save");
        helper.assertValueEqual(itemEntities(helper, Items.DIAMOND, last), 0, "diamond item entities");
        helper.assertTrue(helper.getEntities(EntityTypes.EXPERIENCE_ORB, last, 2.0).isEmpty(), "XP orbs left behind");
        CompoundTag saved = OfflinePlayerData.load(server, who).orElseThrow();
        helper.assertTrue(OfflinePlayerData.takeInventory(saved, server.registryAccess()).stream().anyMatch(item -> item.is(Items.DIAMOND)),
                "save lost the diamond");
        helper.assertValueEqual(saved.getIntOr("XpLevel", -1), 30, "saved XP level");
        helper.assertTrue(DeathLockout.refusal(who.id()) == null, "failed kill started the lockout");
        try (Stream<Path> files = Files.list(dir)) {
            helper.assertTrue(files.noneMatch(file -> file.getFileName().toString().startsWith(who.id() + "-")), "temp save file left behind");
        }
        helper.succeed();
    }

    // A second kill must not drop anything, write the save, or restart the lockout
    @GameTest
    public void offlineKillOfDeadPlayerChangesNothing(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TestPlayers.join(helper, "Twice", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        NameAndId who = player.nameAndId();
        BlockPos last = helper.relativePos(player.blockPosition());
        try {
            player.getInventory().add(new ItemStack(Items.DIAMOND));
            player.giveExperienceLevels(30);
        } finally {
            TestPlayers.leave(player);
        }
        helper.assertValueEqual(OfflineKill.kill(server, who), OfflineKill.KillResult.KILLED_OFFLINE, "first kill result");

        // Clear the first death's drops and lockout, so anything found afterwards came from the second kill
        helper.getEntities(EntityTypes.ITEM, last, 2.0).forEach(Entity::discard);
        helper.getEntities(EntityTypes.EXPERIENCE_ORB, last, 2.0).forEach(Entity::discard);
        helper.assertTrue(DeathLockout.revive(who.id()), "first kill recorded no death");
        byte[][] filesBefore = saveFiles(server, who);

        helper.assertValueEqual(OfflineKill.kill(server, who), OfflineKill.KillResult.ALREADY_DEAD, "second kill result");
        helper.assertTrue(helper.getEntities(EntityTypes.ITEM, last, 2.0).isEmpty(), "second kill dropped items");
        helper.assertTrue(helper.getEntities(EntityTypes.EXPERIENCE_ORB, last, 2.0).isEmpty(), "second kill dropped XP");
        helper.assertTrue(Arrays.deepEquals(saveFiles(server, who), filesBefore), "second kill wrote the save");
        helper.assertTrue(DeathLockout.refusal(who.id()) == null, "second kill restarted the lockout");
        helper.succeed();
    }

    @GameTest
    public void offlineKillAppliesEnderChestMode(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TestPlayers.join(helper, "Ender", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        NameAndId who = player.nameAndId();
        BlockPos last = helper.relativePos(player.blockPosition());
        try {
            player.getEnderChestInventory().setItem(0, new ItemStack(Items.EMERALD));
        } finally {
            TestPlayers.leave(player);
        }
        helper.assertFalse(enderChest(server, who).isEmpty(), "ender chest was not saved");

        YoltConfig.GravesSection graves = Settings.get().graves;
        YoltConfig.EnderChestMode before = graves.enderChestOnDeath;
        graves.enderChestOnDeath = YoltConfig.EnderChestMode.WIPE;
        try {
            helper.assertValueEqual(OfflineKill.kill(server, who), OfflineKill.KillResult.KILLED_OFFLINE, "kill result");
        } finally {
            graves.enderChestOnDeath = before;
        }
        helper.assertTrue(enderChest(server, who).isEmpty(), "ender chest not wiped");
        // Wiped means gone, not dropped
        helper.assertItemEntityNotPresent(Items.EMERALD, last, 2.0);
        helper.succeed();
    }

    @GameTest(maxTicks = 20)
    public void onlineKillRunsNormalDeath(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TestPlayers.join(helper, "Online", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        UUID uuid = player.getUUID();
        boolean waitingForKick = false;
        try {
            TestPlayers.markLoaded(player);
            helper.assertValueEqual(OfflineKill.kill(server, player.nameAndId()), OfflineKill.KillResult.KILLED_ONLINE, "kill result");
            helper.assertTrue(player.isDeadOrDying(), "player is still alive");
            // The death kick waits for the end of the tick, so the death code still sees a connected player
            helper.assertTrue(server.getPlayerList().getPlayer(uuid) != null, "player was kicked inside the death call");
            helper.assertTrue(DeathLockout.refusal(uuid) != null, "death did not start the lockout");
            helper.assertValueEqual(OfflineKill.kill(server, player.nameAndId()), OfflineKill.KillResult.ALREADY_DEAD, "second kill result");

            // A server running behind still runs queued tasks within 4 ticks
            helper.runAfterDelay(5, () -> {
                boolean kicked = server.getPlayerList().getPlayer(uuid) == null;
                if (!kicked) {
                    TestPlayers.leave(player);
                }
                helper.assertTrue(kicked, "player was not kicked after dying");
                helper.succeed();
            });
            waitingForKick = true;
        } finally {
            if (!waitingForKick) {
                TestPlayers.leave(player);
            }
        }
    }

    // Vanilla ignores damage to a player whose client hasn't finished loading the world
    @GameTest
    public void onlineKillOfLoadingPlayerIsReported(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TestPlayers.join(helper, "Loading", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        try {
            helper.assertValueEqual(OfflineKill.kill(server, player.nameAndId()), OfflineKill.KillResult.NOT_KILLED, "kill result");
            helper.assertFalse(player.isDeadOrDying(), "loading player died");
            helper.assertTrue(DeathLockout.refusal(player.getUUID()) == null, "a death was recorded");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // Entities, not items: checked in the same tick as the drop, before anything could merge
    private static int itemEntities(GameTestHelper helper, Item item, BlockPos relativePos) {
        AABB area = new AABB(helper.absolutePos(relativePos)).inflate(2.0);
        return helper.getLevel().getEntities(EntityTypes.ITEM, area, entity -> entity.isAlive() && entity.getItem().is(item)).size();
    }

    // A write moves the current save to .dat_old, so both files staying the same means nothing was written
    private static byte[][] saveFiles(MinecraftServer server, NameAndId who) throws IOException {
        Path dir = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
        return new byte[][] {Files.readAllBytes(dir.resolve(who.id() + ".dat")), Files.readAllBytes(dir.resolve(who.id() + ".dat_old"))};
    }

    private static List<ItemStack> enderChest(MinecraftServer server, NameAndId who) {
        return OfflinePlayerData.takeEnderChest(OfflinePlayerData.load(server, who).orElseThrow(), server.registryAccess());
    }
}
