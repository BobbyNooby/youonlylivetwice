package bobbynooby.dev.gametest;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.hardcore.HardcoreConfig;
import bobbynooby.dev.hardcore.Status;
import com.mojang.authlib.GameProfile;
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

    // XP the first item doesn't need goes to the next damaged one, with the part carried over, and only then to the bar:
    // one point of damage each takes 3 XP and then 2, so 5 of a 10-XP orb reach the bar, whichever is mended first
    @GameTest
    public void mend2LeftoverXpMendsTheNextItem(GameTestHelper helper) {
        ServerPlayer player = join(helper, "MendTwo");
        HardcoreConfig.MendingSection mending = Settings.get().hardcore.mending;
        boolean before = mending.enabled;
        try {
            mending.enabled = true;
            ItemStack pickaxe = mendingPickaxe(helper, player, 1);
            ItemStack helmet = new ItemStack(Items.DIAMOND_HELMET);
            helmet.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.MENDING), 1);
            helmet.setDamageValue(1);
            player.setItemSlot(EquipmentSlot.HEAD, helmet);
            ItemStack worn = player.getItemBySlot(EquipmentSlot.HEAD);
            int xp = player.totalExperience;
            touch(helper, player, 10);
            helper.assertValueEqual(pickaxe.getDamageValue(), 0, "the pickaxe's damage");
            helper.assertValueEqual(worn.getDamageValue(), 0, "the helmet's damage");
            helper.assertValueEqual(player.totalExperience - xp, 5, "the XP that reached the bar");
        } finally {
            mending.enabled = before;
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // A creative player is outside the rules, so Mending is vanilla's for them: 2 points for 1 XP
    @GameTest
    public void mend2CreativeMendsAtVanillaRate(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "MendCreative", GameType.CREATIVE, AT);
        HardcoreConfig.MendingSection mending = Settings.get().hardcore.mending;
        boolean before = mending.enabled;
        try {
            mending.enabled = true;
            ItemStack pickaxe = mendingPickaxe(helper, player, 100);
            touch(helper, player, 1);
            helper.assertValueEqual(pickaxe.getDamageValue(), 98, "the pickaxe's damage after 1 XP in creative");
        } finally {
            mending.enabled = before;
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // The part of a point carried between pickups lasts only until the player leaves: 1 XP leaves 0.4, and 2 XP after
    // a relog are 0.8, which repairs nothing, where 1.2 would have repaired a point
    @GameTest
    public void mend2TheCarriedPartIsDroppedAtLogout(GameTestHelper helper) {
        ServerPlayer player = join(helper, "MendRelog");
        ServerPlayer stayer = join(helper, "MendStayed");
        GameProfile profile = player.getGameProfile();
        HardcoreConfig.MendingSection mending = Settings.get().hardcore.mending;
        boolean before = mending.enabled;
        ServerPlayer[] back = {null};
        try {
            mending.enabled = true;
            ItemStack stayersPickaxe = mendingPickaxe(helper, stayer, 100);
            touch(helper, stayer, 1);
            touch(helper, stayer, 2);
            helper.assertValueEqual(stayersPickaxe.getDamageValue(), 99, "the damage after 1 XP and then 2 with no relog");

            ItemStack pickaxe = mendingPickaxe(helper, player, 100);
            touch(helper, player, 1);
            helper.assertValueEqual(pickaxe.getDamageValue(), 100, "the pickaxe's damage after 1 XP");
            TestPlayers.leave(player);
            back[0] = TestPlayers.rejoin(helper, profile, GameType.SURVIVAL, AT);
            TestPlayers.markLoaded(back[0]);
            ItemStack kept = back[0].getItemBySlot(EquipmentSlot.MAINHAND);
            helper.assertTrue(kept.is(Items.DIAMOND_PICKAXE), "the pickaxe didn't come back with the player: " + kept);
            touch(helper, back[0], 2);
            helper.assertValueEqual(kept.getDamageValue(), 100, "the pickaxe's damage after 2 XP after a relog");
        } finally {
            mending.enabled = before;
            ServerPlayer online = helper.getLevel().getServer().getPlayerList().getPlayer(profile.id());
            if (online != null) {
                TestPlayers.leave(online);
            }
            TestPlayers.leave(stayer);
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
