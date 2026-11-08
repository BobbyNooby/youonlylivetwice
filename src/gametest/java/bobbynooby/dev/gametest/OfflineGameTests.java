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
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

import java.util.List;

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

        helper.assertValueEqual(OfflineKill.kill(server, who), OfflineKill.KillResult.KILLED_OFFLINE, "kill result");
        helper.assertItemEntityPresent(Items.DIAMOND, last, 2.0);
        helper.assertItemEntityPresent(Items.IRON_HELMET, last, 2.0);
        helper.assertEntityPresent(EntityTypes.EXPERIENCE_ORB, last, 2.0);

        CompoundTag saved = OfflinePlayerData.load(server, who).orElseThrow();
        helper.assertTrue(OfflinePlayerData.takeInventory(saved, server.registryAccess()).isEmpty(), "saved inventory still has items");
        helper.assertValueEqual(saved.getFloatOr("Health", -1.0F), 0.0F, "saved health");
        helper.assertValueEqual(saved.getIntOr("XpLevel", -1), 0, "saved XP level");
        helper.assertValueEqual(saved.getIntOr("XpTotal", -1), 0, "saved total XP");
        helper.assertTrue(DeathLockout.refusal(who.id()) != null, "offline kill did not start the lockout");
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

    @GameTest
    public void onlineKillRunsNormalDeath(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TestPlayers.join(helper, "Online", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        try {
            // A real client sends this once the world has loaded; until then vanilla makes the player invulnerable
            player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            helper.assertValueEqual(OfflineKill.kill(server, player.nameAndId()), OfflineKill.KillResult.KILLED_ONLINE, "kill result");
            helper.assertTrue(player.isDeadOrDying(), "player is still alive");
            helper.assertTrue(DeathLockout.refusal(player.getUUID()) != null, "death did not start the lockout");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    private static List<ItemStack> enderChest(MinecraftServer server, NameAndId who) {
        return OfflinePlayerData.takeEnderChest(OfflinePlayerData.load(server, who).orElseThrow(), server.registryAccess());
    }
}
