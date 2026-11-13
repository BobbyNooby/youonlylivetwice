package bobbynooby.dev.gametest;

import bobbynooby.dev.config.HardcoreConfig;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.graves.GraveItems;
import bobbynooby.dev.hardcore.Status;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

// The switch is set on the live config and put back within the same tick, so no other test ever sees it. Setting an
// anvil slot works the result out at once, and a take goes through the same click a player makes.
public class HardcoreAnvilGameTests {
    private static final BlockPos AT = new BlockPos(1, 1, 1);
    private static final int LEVELS = 30;

    @GameTest
    public void anvil2FourDiamondRepairCostsFive(GameTestHelper helper) {
        ServerPlayer player = join(helper, "AnvilRepair", GameType.SURVIVAL);
        HardcoreConfig.AnvilSection anvil = Settings.get().hardcore.anvil;
        boolean before = anvil.enabled;
        try {
            anvil.enabled = true;
            AnvilMenu menu = open(player, ContainerLevelAccess.NULL);
            ItemStack pickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
            Integer priorWork = pickaxe.get(DataComponents.REPAIR_COST);
            for (int use = 1; use <= 2; use++) {
                pickaxe.setDamageValue(pickaxe.getMaxDamage());
                menu.getSlot(AnvilMenu.INPUT_SLOT).set(pickaxe);
                menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).set(new ItemStack(Items.DIAMOND, 4));
                helper.assertValueEqual(menu.getCost(), 5, "the cost of repair " + use);
                int levels = player.experienceLevel;
                pickaxe = take(menu, player);
                helper.assertTrue(pickaxe.is(Items.DIAMOND_PICKAXE), "repair " + use + " gave " + pickaxe);
                // Each diamond restores a quarter
                helper.assertValueEqual(pickaxe.getDamageValue(), pickaxe.getMaxDamage() - 4 * (pickaxe.getMaxDamage() / 4),
                        "the damage left after repair " + use);
                helper.assertTrue(menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).getItem().isEmpty(), "repair " + use + " left diamonds");
                helper.assertValueEqual(levels - player.experienceLevel, 5, "the levels repair " + use + " took");
                helper.assertTrue(Objects.equals(pickaxe.get(DataComponents.REPAIR_COST), priorWork),
                        "the prior work after repair " + use + ": " + pickaxe.get(DataComponents.REPAIR_COST) + ", was " + priorWork);
            }
            List<String> report = Status.report(player).stream().map(Component::getString).toList();
            helper.assertTrue(report.stream().anyMatch(line -> line.contains("anvil: rename 1, repair 5, enchant 6")),
                    "/status: " + report);
        } finally {
            anvil.enabled = before;
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // Vanilla would add the 63 to the price and refuse it as too expensive
    @GameTest
    public void anvil2OldPriorWorkIsIgnored(GameTestHelper helper) {
        ServerPlayer player = join(helper, "AnvilPriorWork", GameType.SURVIVAL);
        HardcoreConfig.AnvilSection anvil = Settings.get().hardcore.anvil;
        boolean before = anvil.enabled;
        try {
            anvil.enabled = true;
            AnvilMenu menu = open(player, ContainerLevelAccess.NULL);
            menu.getSlot(AnvilMenu.INPUT_SLOT).set(wornPickaxe(63));
            menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).set(new ItemStack(Items.DIAMOND));
            helper.assertFalse(menu.getSlot(AnvilMenu.RESULT_SLOT).getItem().isEmpty(), "no result for an old item");
            helper.assertValueEqual(menu.getCost(), 5, "the cost");
            ItemStack taken = take(menu, player);
            helper.assertValueEqual(taken.getDamageValue(), 0, "the damage left");
            helper.assertValueEqual(taken.get(DataComponents.REPAIR_COST), 63, "the prior work after the repair");
            helper.assertValueEqual(player.experienceLevel, LEVELS - 5, "the levels left");
        } finally {
            anvil.enabled = before;
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    @GameTest
    public void anvil2EnchantRepairAndRenameCostSix(GameTestHelper helper) {
        ServerPlayer player = join(helper, "AnvilJobs", GameType.SURVIVAL);
        HardcoreConfig.AnvilSection anvil = Settings.get().hardcore.anvil;
        boolean before = anvil.enabled;
        try {
            anvil.enabled = true;
            Holder<Enchantment> sharpness = enchantment(helper, Enchantments.SHARPNESS);
            Holder<Enchantment> unbreaking = enchantment(helper, Enchantments.UNBREAKING);

            // Enchanting and renaming in one use
            AnvilMenu menu = open(player, ContainerLevelAccess.NULL);
            menu.getSlot(AnvilMenu.INPUT_SLOT).set(sword(100, sharpness));
            menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).set(EnchantmentHelper.createBook(new EnchantmentInstance(unbreaking, 1)));
            menu.setItemName("Blade");
            ItemStack named = menu.getSlot(AnvilMenu.RESULT_SLOT).getItem();
            helper.assertTrue(EnchantmentHelper.getItemEnchantmentLevel(unbreaking, named) == 1
                    && named.getHoverName().getString().equals("Blade"), "the enchanted, renamed result: " + named);
            helper.assertValueEqual(menu.getCost(), 6, "the cost of enchanting and renaming");

            // Repairing and merging enchantments in one use
            menu = open(player, ContainerLevelAccess.NULL);
            menu.getSlot(AnvilMenu.INPUT_SLOT).set(sword(1000, sharpness));
            menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).set(sword(1000, unbreaking));
            ItemStack merged = menu.getSlot(AnvilMenu.RESULT_SLOT).getItem();
            helper.assertTrue(merged.getDamageValue() < 1000 && EnchantmentHelper.getItemEnchantmentLevel(unbreaking, merged) == 1,
                    "the merged result: " + merged);
            helper.assertValueEqual(menu.getCost(), 6, "the cost of merging two worn swords");

            // Only a rename
            menu = open(player, ContainerLevelAccess.NULL);
            menu.getSlot(AnvilMenu.INPUT_SLOT).set(new ItemStack(Items.DIAMOND_SWORD));
            menu.setItemName("Named");
            helper.assertFalse(menu.getSlot(AnvilMenu.RESULT_SLOT).getItem().isEmpty(), "no result for a rename");
            helper.assertValueEqual(menu.getCost(), 1, "the cost of a rename");
        } finally {
            anvil.enabled = before;
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // Vanilla would charge for raising Sharpness V, which is already the most it can be
    @GameTest
    public void anvil3NoChangeGivesNoResult(GameTestHelper helper) {
        ServerPlayer player = join(helper, "AnvilNoChange", GameType.SURVIVAL);
        HardcoreConfig.AnvilSection anvil = Settings.get().hardcore.anvil;
        boolean before = anvil.enabled;
        try {
            anvil.enabled = true;
            Holder<Enchantment> sharpness = enchantment(helper, Enchantments.SHARPNESS);
            AnvilMenu menu = open(player, ContainerLevelAccess.NULL);
            ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
            sword.enchant(sharpness, 5);
            menu.getSlot(AnvilMenu.INPUT_SLOT).set(sword);
            menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).set(EnchantmentHelper.createBook(new EnchantmentInstance(sharpness, 5)));
            helper.assertTrue(menu.getSlot(AnvilMenu.RESULT_SLOT).getItem().isEmpty(),
                    "the anvil made " + menu.getSlot(AnvilMenu.RESULT_SLOT).getItem());
            helper.assertValueEqual(menu.getCost(), 0, "the cost");
            ItemStack taken = take(menu, player);
            helper.assertTrue(taken.isEmpty(), "a click took " + taken);
            helper.assertValueEqual(player.experienceLevel, LEVELS, "the levels left");
            helper.assertTrue(menu.getSlot(AnvilMenu.INPUT_SLOT).getItem().is(Items.DIAMOND_SWORD)
                    && menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).getItem().is(Items.ENCHANTED_BOOK), "an input was used up");

            // A rename on top is only a rename, and the book that adds nothing isn't used up for it
            menu.setItemName("Same");
            helper.assertValueEqual(menu.getCost(), 1, "the cost of the rename");
            taken = take(menu, player);
            helper.assertTrue(taken.getHoverName().getString().equals("Same"), "the rename gave " + taken);
            helper.assertTrue(menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).getItem().is(Items.ENCHANTED_BOOK), "the rename used up the book");
            helper.assertValueEqual(player.experienceLevel, LEVELS - 1, "the levels left after the rename");
        } finally {
            anvil.enabled = before;
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    @GameTest
    public void anvil3AStackAndABookStaysBlocked(GameTestHelper helper) {
        ServerPlayer player = join(helper, "AnvilStack", GameType.SURVIVAL);
        HardcoreConfig.AnvilSection anvil = Settings.get().hardcore.anvil;
        boolean before = anvil.enabled;
        try {
            anvil.enabled = true;
            AnvilMenu menu = open(player, ContainerLevelAccess.NULL);
            menu.getSlot(AnvilMenu.INPUT_SLOT).set(new ItemStack(Items.BOOK, 2));
            menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).set(EnchantmentHelper.createBook(
                    new EnchantmentInstance(enchantment(helper, Enchantments.SHARPNESS), 1)));
            helper.assertTrue(menu.getSlot(AnvilMenu.RESULT_SLOT).getItem().isEmpty(),
                    "two books got " + menu.getSlot(AnvilMenu.RESULT_SLOT).getItem());

            // Compasses stack and do take Curse of Vanishing, so only the stack rule stops this one
            menu = open(player, ContainerLevelAccess.NULL);
            menu.getSlot(AnvilMenu.INPUT_SLOT).set(new ItemStack(Items.COMPASS, 2));
            menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).set(EnchantmentHelper.createBook(
                    new EnchantmentInstance(enchantment(helper, Enchantments.VANISHING_CURSE), 1)));
            helper.assertTrue(menu.getSlot(AnvilMenu.RESULT_SLOT).getItem().isEmpty(),
                    "two compasses got " + menu.getSlot(AnvilMenu.RESULT_SLOT).getItem());
            helper.assertValueEqual(menu.getCost(), 0, "the cost for two compasses");
        } finally {
            anvil.enabled = before;
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // The price is 5 before and after the rename, so vanilla would send it only once
    @GameTest
    public void anvil4PriceIsResentAfterARename(GameTestHelper helper) {
        ServerPlayer player = join(helper, "AnvilResend", GameType.SURVIVAL);
        HardcoreConfig.AnvilSection anvil = Settings.get().hardcore.anvil;
        boolean before = anvil.enabled;
        try {
            anvil.enabled = true;
            AnvilMenu menu = open(player, ContainerLevelAccess.NULL);
            menu.getSlot(AnvilMenu.INPUT_SLOT).set(wornPickaxe(0));
            menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).set(new ItemStack(Items.DIAMOND));
            helper.assertValueEqual(menu.getCost(), 5, "the cost before the rename");
            int sentBefore = TestPlayers.sentPackets(player).size();
            menu.setItemName("x");
            menu.broadcastChanges();
            List<Packet<?>> sent = TestPlayers.sentPackets(player);
            sent = sent.subList(sentBefore, sent.size());

            int slotAt = -1;
            int costAt = -1;
            for (int i = 0; i < sent.size(); i++) {
                Packet<?> packet = sent.get(i);
                if (packet instanceof ClientboundContainerSetSlotPacket slot && slot.getContainerId() == menu.containerId
                        && slot.getSlot() == AnvilMenu.RESULT_SLOT && slotAt < 0) {
                    slotAt = i;
                    helper.assertTrue(slot.getItem().getHoverName().getString().equals("x"), "the result sent: " + slot.getItem());
                }
                if (packet instanceof ClientboundContainerSetDataPacket data && data.getContainerId() == menu.containerId
                        && data.getValue() == 5 && slotAt >= 0 && costAt < 0) {
                    costAt = i;
                }
            }
            helper.assertTrue(slotAt >= 0, "no result slot was sent after the rename: " + sent);
            helper.assertTrue(costAt > slotAt, "no cost of 5 was sent after the result slot: " + sent);
        } finally {
            anvil.enabled = before;
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    @GameTest
    public void anvilCreativeAndOffAreVanilla(GameTestHelper helper) {
        ServerPlayer creative = join(helper, "AnvilCreative", GameType.CREATIVE);
        ServerPlayer survival = join(helper, "AnvilOff", GameType.SURVIVAL);
        HardcoreConfig.AnvilSection anvil = Settings.get().hardcore.anvil;
        boolean before = anvil.enabled;
        try {
            // Vanilla's 1 for one diamond, and the prior work grows from 0 to 1
            anvil.enabled = true;
            AnvilMenu menu = open(creative, ContainerLevelAccess.NULL);
            menu.getSlot(AnvilMenu.INPUT_SLOT).set(wornPickaxe(0));
            menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).set(new ItemStack(Items.DIAMOND));
            helper.assertValueEqual(menu.getCost(), 1, "the creative cost");
            helper.assertValueEqual(menu.getSlot(AnvilMenu.RESULT_SLOT).getItem().get(DataComponents.REPAIR_COST), 1,
                    "the creative result's prior work");

            // 63 + 1 is too expensive
            anvil.enabled = false;
            menu = open(survival, ContainerLevelAccess.NULL);
            menu.getSlot(AnvilMenu.INPUT_SLOT).set(wornPickaxe(63));
            menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).set(new ItemStack(Items.DIAMOND));
            helper.assertTrue(menu.getSlot(AnvilMenu.RESULT_SLOT).getItem().isEmpty(),
                    "the anvil made " + menu.getSlot(AnvilMenu.RESULT_SLOT).getItem());
            helper.assertValueEqual(menu.getCost(), 64, "the cost while off");
        } finally {
            anvil.enabled = before;
            TestPlayers.leave(creative);
            TestPlayers.leave(survival);
        }
        helper.succeed();
    }

    // Core's refusal still wins with the flat price on, and the client is told
    @GameTest
    public void anvilVanishingStaysRefusedOnGraveHeads(GameTestHelper helper) {
        ServerPlayer player = join(helper, "AnvilGraveHead", GameType.SURVIVAL);
        HardcoreConfig.AnvilSection anvil = Settings.get().hardcore.anvil;
        boolean before = anvil.enabled;
        try {
            anvil.enabled = true;
            AnvilMenu menu = open(player, ContainerLevelAccess.NULL);
            menu.getSlot(AnvilMenu.INPUT_SLOT).set(new ItemStack(Items.PLAYER_HEAD));
            menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).set(EnchantmentHelper.createBook(
                    new EnchantmentInstance(enchantment(helper, Enchantments.VANISHING_CURSE), 1)));
            helper.assertValueEqual(menu.getCost(), 6, "the cost for a plain head");

            menu.getSlot(AnvilMenu.INPUT_SLOT).set(GraveItems.graveHead(
                    ResolvableProfile.createResolved(new GameProfile(UUID.randomUUID(), "Ghost"))));
            helper.assertTrue(menu.getSlot(AnvilMenu.RESULT_SLOT).getItem().isEmpty(),
                    "the anvil made " + menu.getSlot(AnvilMenu.RESULT_SLOT).getItem());
            helper.assertValueEqual(menu.getCost(), 0, "the cost for a grave head");
            ItemStack lastSent = null;
            for (Packet<?> packet : TestPlayers.sentPackets(player)) {
                if (packet instanceof ClientboundContainerSetSlotPacket slot && slot.getContainerId() == menu.containerId
                        && slot.getSlot() == AnvilMenu.RESULT_SLOT) {
                    lastSent = slot.getItem();
                }
            }
            helper.assertTrue(lastSent != null && lastSent.isEmpty(), "the result slot the client was last sent: " + lastSent);
        } finally {
            anvil.enabled = before;
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    @GameTest
    public void anvilWearFollowsTheBreakChance(GameTestHelper helper) {
        BlockPos anvilAt = new BlockPos(2, 1, 1);
        helper.setBlock(anvilAt, Blocks.ANVIL);
        ServerPlayer player = join(helper, "AnvilWear", GameType.SURVIVAL);
        HardcoreConfig.AnvilSection anvil = Settings.get().hardcore.anvil;
        boolean before = anvil.enabled;
        double chanceBefore = anvil.breakChance;
        try {
            anvil.enabled = true;
            AnvilMenu menu = open(player, ContainerLevelAccess.create(helper.getLevel(), helper.absolutePos(anvilAt)));

            anvil.breakChance = 1.0;
            rename(helper, menu, player, "Worn");
            helper.assertTrue(helper.getBlockState(anvilAt).is(Blocks.CHIPPED_ANVIL), "after a sure break: " + helper.getBlockState(anvilAt));

            anvil.breakChance = 0.0;
            rename(helper, menu, player, "Kept");
            helper.assertTrue(helper.getBlockState(anvilAt).is(Blocks.CHIPPED_ANVIL), "after no chance: " + helper.getBlockState(anvilAt));
        } finally {
            anvil.enabled = before;
            anvil.breakChance = chanceBefore;
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // A survival player with levels to spend
    private static ServerPlayer join(GameTestHelper helper, String name, GameType mode) {
        ServerPlayer player = TestPlayers.join(helper, name, mode, AT);
        player.giveExperienceLevels(LEVELS);
        return player;
    }

    // Opening one closes the anvil before it, as it does for a player
    private static AnvilMenu open(ServerPlayer player, ContainerLevelAccess access) {
        player.openMenu(new SimpleMenuProvider((id, inventory, user) -> new AnvilMenu(id, inventory, access), Component.literal("Anvil")));
        return (AnvilMenu) player.containerMenu;
    }

    // A left click on the result with nothing held, and then the held item put away
    private static ItemStack take(AnvilMenu menu, ServerPlayer player) {
        menu.clicked(AnvilMenu.RESULT_SLOT, 0, ContainerInput.PICKUP, player);
        ItemStack taken = menu.getCarried();
        menu.setCarried(ItemStack.EMPTY);
        return taken;
    }

    private static void rename(GameTestHelper helper, AnvilMenu menu, ServerPlayer player, String name) {
        menu.getSlot(AnvilMenu.INPUT_SLOT).set(new ItemStack(Items.NAME_TAG));
        menu.setItemName(name);
        ItemStack taken = take(menu, player);
        helper.assertTrue(taken.getHoverName().getString().equals(name), "the rename to " + name + " gave " + taken);
    }

    private static Holder<Enchantment> enchantment(GameTestHelper helper, ResourceKey<Enchantment> key) {
        return helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key);
    }

    // A diamond pickaxe a hundred points worn, which one diamond repairs in full
    private static ItemStack wornPickaxe(int priorWork) {
        ItemStack pickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
        pickaxe.setDamageValue(100);
        pickaxe.set(DataComponents.REPAIR_COST, priorWork);
        return pickaxe;
    }

    private static ItemStack sword(int damage, Holder<Enchantment> enchantment) {
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.setDamageValue(damage);
        sword.enchant(enchantment, 1);
        return sword;
    }
}
