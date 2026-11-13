package bobbynooby.dev.gametest;

import bobbynooby.dev.config.HardcoreConfig;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.hardcore.Status;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;

import java.util.List;

// Mending is set on the live config and put back within the same tick, so no other test ever sees it. Each orb is
// handed to the player the way a touch does, rather than waiting for a fake player to pick it up.
public class HardcoreMendingGameTests {
    private static final BlockPos AT = new BlockPos(1, 1, 1);

    @GameTest
    public void mend2UnusedXpReachesTheBar(GameTestHelper helper) {
        ServerPlayer player = join(helper, "MendLeftover");
        HardcoreConfig.MendingSection mending = Settings.get().hardcore.mending;
        boolean before = mending.enabled;
        try {
            mending.enabled = true;
            ItemStack pickaxe = mendingPickaxe(helper, player, 1);
            int xp = player.totalExperience;
            touch(helper, player, 10);
            helper.assertValueEqual(pickaxe.getDamageValue(), 0, "the pickaxe's damage");
            helper.assertValueEqual(player.totalExperience - xp, 7, "the XP that reached the bar");
            helper.assertTrue(report(player).stream().anyMatch(line -> line.contains("Mending 0.4/XP (vanilla 2)")),
                    "/status: " + report(player));
        } finally {
            mending.enabled = before;
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    @GameTest
    public void mend2OneXpOrbsAreNeverEaten(GameTestHelper helper) {
        ServerPlayer player = join(helper, "MendSmallOrbs");
        HardcoreConfig.MendingSection mending = Settings.get().hardcore.mending;
        boolean before = mending.enabled;
        try {
            mending.enabled = true;
            ItemStack pickaxe = mendingPickaxe(helper, player, 100);
            int xp = player.totalExperience;
            touch(helper, player, 1);
            touch(helper, player, 1);
            helper.assertValueEqual(pickaxe.getDamageValue(), 100, "the pickaxe's damage after two 1-XP orbs");
            touch(helper, player, 1);
            helper.assertValueEqual(pickaxe.getDamageValue(), 99, "the pickaxe's damage after three 1-XP orbs");
            helper.assertValueEqual(player.totalExperience, xp, "the XP bar");
        } finally {
            mending.enabled = before;
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    @GameTest
    public void mend2ZeroSendsAllXpToTheBar(GameTestHelper helper) {
        ServerPlayer player = join(helper, "MendZero");
        HardcoreConfig.MendingSection mending = Settings.get().hardcore.mending;
        boolean before = mending.enabled;
        double rateBefore = mending.durabilityPerXp;
        try {
            mending.enabled = true;
            mending.durabilityPerXp = 0.0;
            ItemStack pickaxe = mendingPickaxe(helper, player, 50);
            int xp = player.totalExperience;
            touch(helper, player, 5);
            helper.assertValueEqual(pickaxe.getDamageValue(), 50, "the pickaxe's damage");
            helper.assertValueEqual(player.totalExperience - xp, 5, "the XP that reached the bar");
        } finally {
            mending.enabled = before;
            mending.durabilityPerXp = rateBefore;
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // Vanilla's 2 points per XP
    @GameTest
    public void mendOffIsVanilla(GameTestHelper helper) {
        ServerPlayer player = join(helper, "MendVanilla");
        HardcoreConfig.MendingSection mending = Settings.get().hardcore.mending;
        boolean before = mending.enabled;
        try {
            mending.enabled = false;
            ItemStack pickaxe = mendingPickaxe(helper, player, 100);
            int xp = player.totalExperience;
            touch(helper, player, 5);
            helper.assertValueEqual(pickaxe.getDamageValue(), 90, "the pickaxe's damage");
            helper.assertValueEqual(player.totalExperience, xp, "the XP bar");
        } finally {
            mending.enabled = before;
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    private static ServerPlayer join(GameTestHelper helper, String name) {
        ServerPlayer player = TestPlayers.join(helper, name, GameType.SURVIVAL, AT);
        TestPlayers.markLoaded(player);
        return player;
    }

    // A diamond pickaxe with Mending in the main hand, the only item the player has
    private static ItemStack mendingPickaxe(GameTestHelper helper, ServerPlayer player, int damage) {
        ItemStack pickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
        pickaxe.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.MENDING), 1);
        pickaxe.setDamageValue(damage);
        player.setItemSlot(EquipmentSlot.MAINHAND, pickaxe);
        return player.getItemBySlot(EquipmentSlot.MAINHAND);
    }

    // What an orb touching the player does, without the wait between pickups
    private static void touch(GameTestHelper helper, ServerPlayer player, int value) {
        ExperienceOrb orb = new ExperienceOrb(helper.getLevel(), player.getX(), player.getY(), player.getZ(), value);
        player.takeXpDelay = 0;
        orb.playerTouch(player);
    }

    // What /status shows the player
    private static List<String> report(ServerPlayer player) {
        return Status.report(player).stream().map(Component::getString).toList();
    }
}
