package bobbynooby.dev.anvils;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The mod on its own, with You Only Live Twice nowhere on the server: its own config file, its own command, and creative
 * players left out by its own rule. YOLT's GameTests cover the same anvil hosted inside YOLT.
 * <p>
 * Each test swaps its settings in and puts them back within the same tick, and so does each test that writes the file,
 * so no other test ever sees them. Setting an anvil slot works the result out at once, and a take goes through the same
 * click a player makes.
 */
public class AnvilGameTests {
    private static final int LEVELS = 30;

    @GameTest
    public void standaloneWritesAndReadsItsOwnFile(GameTestHelper helper) {
        helper.assertFalse(YoltAnvils.hosted(), "the mod is hosted, but nothing here hosts it");
        Path file = YoltAnvils.configFile();
        helper.assertTrue(Files.isRegularFile(file), "no " + file + " after the server started");
        AnvilConfig.Result read = AnvilConfig.load(file, AnvilSettings.DEFAULTS);
        helper.assertFalse(read.failed(), "the file can't be read: " + read.notes());
        helper.assertValueEqual(read.settings(), YoltAnvils.settings(), "the settings in use");
        helper.assertTrue(helper.getLevel().getServer().getCommands().getDispatcher().getRoot().getChild("yolt-anvils") != null,
                "no /yolt-anvils on its own");
        helper.succeed();
    }

    @GameTest
    public void fourDiamondRepairCostsTheFlatFive(GameTestHelper helper) {
        AnvilSettings before = YoltAnvils.swapForTesting(AnvilSettings.DEFAULTS);
        try {
            ServerPlayer player = player(helper, "AloneRepair", GameType.SURVIVAL);
            AnvilMenu menu = open(player, ContainerLevelAccess.NULL);
            ItemStack pickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
            for (int use = 1; use <= 2; use++) {
                pickaxe.setDamageValue(pickaxe.getMaxDamage());
                menu.getSlot(AnvilMenu.INPUT_SLOT).set(pickaxe);
                menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).set(new ItemStack(Items.DIAMOND, 4));
                helper.assertValueEqual(menu.getCost(), 5, "the cost of repair " + use);
                int levels = player.experienceLevel;
                pickaxe = take(menu, player);
                helper.assertTrue(pickaxe.is(Items.DIAMOND_PICKAXE), "repair " + use + " gave " + pickaxe);
                helper.assertTrue(menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).getItem().isEmpty(), "repair " + use + " left diamonds");
                helper.assertValueEqual(levels - player.experienceLevel, 5, "the levels repair " + use + " took");
                helper.assertValueEqual(pickaxe.get(DataComponents.REPAIR_COST), 0, "the prior work after repair " + use);
            }
        } finally {
            YoltAnvils.swapForTesting(before);
        }
        helper.succeed();
    }

    // Vanilla would add the 63 to the price and refuse it as too expensive
    @GameTest
    public void oldPriorWorkIsIgnoredAndNeverTooExpensive(GameTestHelper helper) {
        AnvilSettings before = YoltAnvils.swapForTesting(AnvilSettings.DEFAULTS);
        try {
            ServerPlayer player = player(helper, "AlonePriorWork", GameType.SURVIVAL);
            AnvilMenu menu = open(player, ContainerLevelAccess.NULL);
            menu.getSlot(AnvilMenu.INPUT_SLOT).set(wornPickaxe(63));
            menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).set(new ItemStack(Items.DIAMOND));
            helper.assertValueEqual(menu.getCost(), 5, "the cost");
            ItemStack taken = take(menu, player);
            helper.assertValueEqual(taken.getDamageValue(), 0, "the damage left");
            helper.assertValueEqual(taken.get(DataComponents.REPAIR_COST), 0, "the prior work after the repair");
            helper.assertValueEqual(player.experienceLevel, LEVELS - 5, "the levels left");
        } finally {
            YoltAnvils.swapForTesting(before);
        }
        helper.succeed();
    }

    // One use that enchants, repairs and renames pays for the enchanting only; a rename alone pays the rename
    @GameTest
    public void theBiggestJobSetsThePrice(GameTestHelper helper) {
        AnvilSettings before = YoltAnvils.swapForTesting(AnvilSettings.DEFAULTS);
        try {
            ServerPlayer player = player(helper, "AloneBiggest", GameType.SURVIVAL);
            AnvilMenu menu = open(player, ContainerLevelAccess.NULL);
            ItemStack worn = new ItemStack(Items.DIAMOND_SWORD);
            worn.setDamageValue(500);
            ItemStack other = new ItemStack(Items.DIAMOND_SWORD);
            other.enchant(enchantment(helper, Enchantments.SHARPNESS), 1);
            menu.getSlot(AnvilMenu.INPUT_SLOT).set(worn);
            menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).set(other);
            menu.setItemName("Old Faithful");
            helper.assertValueEqual(menu.getCost(), 6, "enchant, repair and rename");

            menu = open(player, ContainerLevelAccess.NULL);
            menu.getSlot(AnvilMenu.INPUT_SLOT).set(new ItemStack(Items.NAME_TAG));
            menu.setItemName("Rex");
            helper.assertValueEqual(menu.getCost(), 1, "a rename");
            ItemStack tag = take(menu, player);
            helper.assertValueEqual(tag.getHoverName().getString(), "Rex", "the renamed tag");
            helper.assertValueEqual(player.experienceLevel, LEVELS - 1, "the levels a rename took");
        } finally {
            YoltAnvils.swapForTesting(before);
        }
        helper.succeed();
    }

    @GameTest
    public void pricesFollowTheSettings(GameTestHelper helper) {
        AnvilSettings before = YoltAnvils.swapForTesting(new AnvilSettings(true, 9, 4, 2, 0.12));
        try {
            ServerPlayer player = player(helper, "AlonePrices", GameType.SURVIVAL);
            AnvilMenu menu = open(player, ContainerLevelAccess.NULL);
            menu.getSlot(AnvilMenu.INPUT_SLOT).set(wornPickaxe(0));
            menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).set(new ItemStack(Items.DIAMOND));
            helper.assertValueEqual(menu.getCost(), 4, "the repair cost");

            menu = open(player, ContainerLevelAccess.NULL);
            menu.getSlot(AnvilMenu.INPUT_SLOT).set(new ItemStack(Items.NAME_TAG));
            menu.setItemName("x");
            helper.assertValueEqual(menu.getCost(), 2, "the rename cost");

            menu = open(player, ContainerLevelAccess.NULL);
            menu.getSlot(AnvilMenu.INPUT_SLOT).set(new ItemStack(Items.DIAMOND_SWORD));
            menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).set(EnchantmentHelper.createBook(
                    new EnchantmentInstance(enchantment(helper, Enchantments.SHARPNESS), 1)));
            helper.assertValueEqual(menu.getCost(), 9, "the enchant cost");
        } finally {
            YoltAnvils.swapForTesting(before);
        }
        helper.succeed();
    }

    // Off, the same pickaxe is too expensive, as vanilla prices it
    @GameTest
    public void offIsVanilla(GameTestHelper helper) {
        AnvilSettings off = new AnvilSettings(false, 6, 5, 1, 0.12);
        AnvilSettings before = YoltAnvils.swapForTesting(off);
        try {
            ServerPlayer player = player(helper, "AloneOff", GameType.SURVIVAL);
            AnvilMenu menu = open(player, ContainerLevelAccess.NULL);
            menu.getSlot(AnvilMenu.INPUT_SLOT).set(wornPickaxe(63));
            menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).set(new ItemStack(Items.DIAMOND));
            helper.assertTrue(menu.getSlot(AnvilMenu.RESULT_SLOT).getItem().isEmpty(),
                    "the anvil made " + menu.getSlot(AnvilMenu.RESULT_SLOT).getItem());
            helper.assertValueEqual(menu.getCost(), 64, "the cost while off");
        } finally {
            YoltAnvils.swapForTesting(before);
        }
        helper.succeed();
    }

    // Vanilla's 1 for one diamond, and the prior work grows from 0 to 1. Adventure counts as survival.
    @GameTest
    public void creativeAndSpectatorKeepVanilla(GameTestHelper helper) {
        AnvilSettings before = YoltAnvils.swapForTesting(AnvilSettings.DEFAULTS);
        try {
            for (GameType mode : List.of(GameType.CREATIVE, GameType.SPECTATOR)) {
                ServerPlayer player = player(helper, "Alone" + mode.getName(), mode);
                AnvilMenu menu = open(player, ContainerLevelAccess.NULL);
                menu.getSlot(AnvilMenu.INPUT_SLOT).set(wornPickaxe(0));
                menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).set(new ItemStack(Items.DIAMOND));
                helper.assertValueEqual(menu.getCost(), 1, "the " + mode.getName() + " cost");
                helper.assertValueEqual(menu.getSlot(AnvilMenu.RESULT_SLOT).getItem().get(DataComponents.REPAIR_COST), 1,
                        "the " + mode.getName() + " result's prior work");
            }
            ServerPlayer adventurer = player(helper, "AloneAdventure", GameType.ADVENTURE);
            AnvilMenu menu = open(adventurer, ContainerLevelAccess.NULL);
            menu.getSlot(AnvilMenu.INPUT_SLOT).set(wornPickaxe(0));
            menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).set(new ItemStack(Items.DIAMOND));
            helper.assertValueEqual(menu.getCost(), 5, "the adventure cost");
        } finally {
            YoltAnvils.swapForTesting(before);
        }
        helper.succeed();
    }

    @GameTest
    public void wearFollowsTheBreakChance(GameTestHelper helper) {
        BlockPos anvilAt = new BlockPos(1, 1, 1);
        helper.setBlock(anvilAt, Blocks.ANVIL);
        AnvilSettings before = YoltAnvils.swapForTesting(new AnvilSettings(true, 6, 5, 1, 1.0));
        try {
            ServerPlayer player = player(helper, "AloneWear", GameType.SURVIVAL);
            ContainerLevelAccess access = ContainerLevelAccess.create(helper.getLevel(), helper.absolutePos(anvilAt));
            rename(helper, open(player, access), player, "a");
            helper.assertTrue(helper.getBlockState(anvilAt).is(Blocks.CHIPPED_ANVIL), "after a sure break: " + helper.getBlockState(anvilAt));
            YoltAnvils.swapForTesting(new AnvilSettings(true, 6, 5, 1, 0.0));
            for (int use = 0; use < 20; use++) {
                rename(helper, open(player, access), player, "b" + use);
            }
            helper.assertTrue(helper.getBlockState(anvilAt).is(Blocks.CHIPPED_ANVIL), "after no chance: " + helper.getBlockState(anvilAt));
            YoltAnvils.swapForTesting(new AnvilSettings(true, 6, 5, 1, 1.0));
            rename(helper, open(player, access), player, "c");
            rename(helper, open(player, access), player, "d");
            helper.assertTrue(helper.getBlockState(anvilAt).isAir(), "after two more sure breaks: " + helper.getBlockState(anvilAt));
        } finally {
            YoltAnvils.swapForTesting(before);
        }
        helper.succeed();
    }

    // A refused value keeps the one in use, and the file stays as typed
    @GameTest
    public void reloadReadsTheFileKeyByKey(GameTestHelper helper) {
        AnvilSettings before = YoltAnvils.swapForTesting(AnvilSettings.DEFAULTS);
        Path file = YoltAnvils.configFile();
        String original = read(file);
        try {
            String typed = """
                    {"enabled": true, "enchantCost": 7, "repairCost": 40, "renameCost": 2, "breakChance": 0.3}""";
            write(file, typed);
            AnvilConfig.Result result = YoltAnvils.reload();
            helper.assertFalse(result.failed(), "the reload failed: " + result.notes());
            helper.assertValueEqual(YoltAnvils.settings(), new AnvilSettings(true, 7, 5, 2, 0.3), "the settings after the reload");
            helper.assertTrue(result.notes().stream().anyMatch(note -> note.startsWith("repairCost")), "notes: " + result.notes());
            helper.assertValueEqual(read(file), typed, "the file after the reload");

            ServerPlayer player = player(helper, "AloneReload", GameType.SURVIVAL);
            AnvilMenu menu = open(player, ContainerLevelAccess.NULL);
            menu.getSlot(AnvilMenu.INPUT_SLOT).set(new ItemStack(Items.NAME_TAG));
            menu.setItemName("y");
            helper.assertValueEqual(menu.getCost(), 2, "the rename cost read from the file");
        } finally {
            write(file, original);
            YoltAnvils.swapForTesting(before);
        }
        helper.succeed();
    }

    @GameTest
    public void reloadOfABrokenFileChangesNothing(GameTestHelper helper) {
        AnvilSettings custom = new AnvilSettings(true, 8, 3, 2, 0.2);
        AnvilSettings before = YoltAnvils.swapForTesting(custom);
        Path file = YoltAnvils.configFile();
        String original = read(file);
        try {
            write(file, "{ \"enchantCost\": ");
            AnvilConfig.Result result = YoltAnvils.reload();
            helper.assertTrue(result.failed(), "a broken file was read: " + result.notes());
            helper.assertValueEqual(YoltAnvils.settings(), custom, "the settings after a failed reload");
            helper.assertValueEqual(read(file), "{ \"enchantCost\": ", "the broken file after the reload");
        } finally {
            write(file, original);
            YoltAnvils.swapForTesting(before);
        }
        helper.succeed();
    }

    // Through the server's own dispatcher, as an op typing it would
    @GameTest
    public void theCommandShowsAndReloads(GameTestHelper helper) {
        AnvilSettings before = YoltAnvils.swapForTesting(AnvilSettings.DEFAULTS);
        Path file = YoltAnvils.configFile();
        String original = read(file);
        try {
            List<String> said = new ArrayList<>();
            CommandSourceStack source = source(helper.getLevel().getServer(), said);
            helper.assertValueEqual(run(helper, source, "yolt-anvils"), 1, "/yolt-anvils");
            helper.assertTrue(said.contains("Anvil prices: rename 1, repair 5, enchant 6; break chance 0.12"), "it said " + said);

            said.clear();
            write(file, AnvilConfig.json(new AnvilSettings(false, 6, 5, 1, 0.12)));
            helper.assertValueEqual(run(helper, source, "yolt-anvils reload"), 1, "/yolt-anvils reload");
            helper.assertTrue(said.stream().anyMatch(line -> line.startsWith("Reloaded yolt-anvils.json")), "it said " + said);
            helper.assertFalse(YoltAnvils.settings().enabled(), "still on after reloading a file that turns it off");

            said.clear();
            helper.assertValueEqual(run(helper, source, "yolt-anvils"), 1, "/yolt-anvils while off");
            helper.assertTrue(said.contains("Flat anvil prices are off"), "it said " + said);

            said.clear();
            write(file, "[]");
            helper.assertValueEqual(run(helper, source, "yolt-anvils reload"), 0, "a reload of a broken file");
            helper.assertTrue(said.stream().anyMatch(line -> line.startsWith("Nothing changed")), "it said " + said);
        } finally {
            write(file, original);
            YoltAnvils.swapForTesting(before);
        }
        helper.succeed();
    }

    // A fake player for each name, with levels to spend and the game mode asked for
    private static ServerPlayer player(GameTestHelper helper, String name, GameType mode) {
        FakePlayer player = FakePlayer.get(helper.getLevel(),
                new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name));
        player.setGameMode(mode);
        player.setExperienceLevels(LEVELS);
        return player;
    }

    // A fake player can't open a menu, so the anvil is made for it as opening one would
    private static AnvilMenu open(ServerPlayer player, ContainerLevelAccess access) {
        return new AnvilMenu(1, player.getInventory(), access);
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

    // The server's own op source, with what it says caught in a list
    private static CommandSourceStack source(MinecraftServer server, List<String> said) {
        return server.createCommandSourceStack().withSource(new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                said.add(message.getString());
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
        });
    }

    private static int run(GameTestHelper helper, CommandSourceStack source, String command) {
        try {
            return helper.getLevel().getServer().getCommands().getDispatcher().execute(command, source);
        } catch (CommandSyntaxException e) {
            throw helper.assertionException("/" + command + " didn't parse: " + e.getMessage());
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path file, String text) {
        try {
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
