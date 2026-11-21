package bobbynooby.dev.mending;

import bobbynooby.dev.mending.shared.SettingsFile;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;

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
 * players left out by its own rule. YOLT's GameTests cover the same Mending hosted inside YOLT.
 * <p>
 * Each test swaps its settings in and puts them back within the same tick, and so does each test that writes the file,
 * so no other test ever sees them. A fake player never ticks, so it never picks an orb up by itself: each orb is handed
 * to it the way a touch does, which runs vanilla's whole pickup, the repair step included.
 */
public class MendingGameTests {
    private static final double CLOSE = 1e-9;

    @GameTest
    public void standaloneWritesAndReadsItsOwnFile(GameTestHelper helper) {
        helper.assertFalse(YoltMending.hosted(), "the mod is hosted, but nothing here hosts it");
        Path file = YoltMending.configFile();
        helper.assertTrue(Files.isRegularFile(file), "no " + file + " after the server started");
        SettingsFile.Result<MendingSettings> read = MendingConfig.load(file, MendingSettings.DEFAULTS);
        helper.assertFalse(read.failed(), "the file can't be read: " + read.notes());
        helper.assertValueEqual(read.settings(), YoltMending.settings(), "the settings in use");
        helper.assertTrue(helper.getLevel().getServer().getCommands().getDispatcher().getRoot().getChild("yolt-mending") != null,
                "no /yolt-mending on its own");
        helper.succeed();
    }

    // Five times 0.4 is 2 points, and at 1 each XP point is a point
    @GameTest
    public void aPickupRepairsAtTheSetRate(GameTestHelper helper) {
        MendingSettings before = YoltMending.swapForTesting(new MendingSettings(true, 0.4));
        try {
            ServerPlayer player = player(helper, "AloneFifth", GameType.SURVIVAL);
            ItemStack pickaxe = mendingPickaxe(helper, player, 100);
            touch(player, 5);
            helper.assertValueEqual(pickaxe.getDamageValue(), 98, "the damage after 5 XP at 0.4");

            YoltMending.swapForTesting(new MendingSettings(true, 1.0));
            ServerPlayer other = player(helper, "AloneWhole", GameType.SURVIVAL);
            ItemStack otherPickaxe = mendingPickaxe(helper, other, 100);
            touch(other, 5);
            helper.assertValueEqual(otherPickaxe.getDamageValue(), 95, "the damage after 5 XP at 1");
        } finally {
            YoltMending.swapForTesting(before);
        }
        helper.succeed();
    }

    @GameTest
    public void zeroSendsAllXpToTheBar(GameTestHelper helper) {
        MendingSettings before = YoltMending.swapForTesting(new MendingSettings(true, 0.0));
        try {
            ServerPlayer player = player(helper, "AloneZero", GameType.SURVIVAL);
            ItemStack pickaxe = mendingPickaxe(helper, player, 50);
            int xp = player.totalExperience;
            touch(player, 5);
            helper.assertValueEqual(pickaxe.getDamageValue(), 50, "the damage after 5 XP at 0");
            helper.assertValueEqual(player.totalExperience - xp, 5, "the XP that reached the bar");
        } finally {
            YoltMending.swapForTesting(before);
        }
        helper.succeed();
    }

    // Vanilla's 2 points per XP
    @GameTest
    public void offIsVanilla(GameTestHelper helper) {
        MendingSettings before = YoltMending.swapForTesting(new MendingSettings(false, 0.4));
        try {
            ServerPlayer player = player(helper, "AloneOff", GameType.SURVIVAL);
            ItemStack pickaxe = mendingPickaxe(helper, player, 100);
            touch(player, 1);
            helper.assertValueEqual(pickaxe.getDamageValue(), 98, "the damage after 1 XP while off");
        } finally {
            YoltMending.swapForTesting(before);
        }
        helper.succeed();
    }

    @GameTest
    public void creativeKeepsVanilla(GameTestHelper helper) {
        MendingSettings before = YoltMending.swapForTesting(new MendingSettings(true, 0.4));
        try {
            ServerPlayer player = player(helper, "AloneCreative", GameType.CREATIVE);
            ItemStack pickaxe = mendingPickaxe(helper, player, 100);
            touch(player, 1);
            helper.assertValueEqual(pickaxe.getDamageValue(), 98, "the damage after 1 XP in creative");
        } finally {
            YoltMending.swapForTesting(before);
        }
        helper.succeed();
    }

    // 1 XP at 0.4 repairs nothing and keeps 0.4 for the next pickup, until the player leaves
    @GameTest
    public void theCarriedFractionGoesWhenThePlayerLeaves(GameTestHelper helper) {
        MendingSettings before = YoltMending.swapForTesting(new MendingSettings(true, 0.4));
        try {
            ServerPlayer player = player(helper, "AloneLeaves", GameType.SURVIVAL);
            mendingPickaxe(helper, player, 100);
            touch(player, 1);
            double carried = MendingRepair.carried(player.getUUID());
            helper.assertTrue(Math.abs(carried - 0.4) < CLOSE, "carried after 1 XP: " + carried);
            ServerPlayerEvents.LEAVE.invoker().onLeave(player);
            helper.assertValueEqual(MendingRepair.carried(player.getUUID()), 0.0, "carried after the player left");
        } finally {
            YoltMending.swapForTesting(before);
        }
        helper.succeed();
    }

    // A refused value keeps the one in use
    @GameTest
    public void reloadReadsTheFile(GameTestHelper helper) {
        MendingSettings before = YoltMending.swapForTesting(MendingSettings.DEFAULTS);
        Path file = YoltMending.configFile();
        String original = read(file);
        try {
            write(file, "{\"enabled\": \"yes\", \"durabilityPerXp\": 1.5}");
            SettingsFile.Result<MendingSettings> result = YoltMending.reload();
            helper.assertFalse(result.failed(), "the reload failed: " + result.notes());
            helper.assertValueEqual(YoltMending.settings(), new MendingSettings(true, 1.5), "the settings after the reload");
            helper.assertTrue(result.notes().contains("enabled must be true or false, kept true"), "notes: " + result.notes());
        } finally {
            write(file, original);
            YoltMending.swapForTesting(before);
        }
        helper.succeed();
    }

    // Through the server's own dispatcher, as an op typing it would
    @GameTest
    public void theCommandShowsAndReloads(GameTestHelper helper) {
        MendingSettings before = YoltMending.swapForTesting(MendingSettings.DEFAULTS);
        Path file = YoltMending.configFile();
        String original = read(file);
        try {
            List<String> said = new ArrayList<>();
            CommandSourceStack source = source(helper.getLevel().getServer(), said);
            helper.assertValueEqual(run(helper, source, "yolt-mending"), 1, "/yolt-mending");
            helper.assertTrue(said.contains("Mending: 0.4 durability per XP point (vanilla 2)"), "it said " + said);

            said.clear();
            write(file, MendingConfig.json(new MendingSettings(false, 0.4)));
            helper.assertValueEqual(run(helper, source, "yolt-mending reload"), 1, "/yolt-mending reload");
            helper.assertTrue(said.contains("Reloaded yolt-mending.json. The Mending nerf is off"), "it said " + said);
        } finally {
            write(file, original);
            YoltMending.swapForTesting(before);
        }
        helper.succeed();
    }

    // A fake player for each name, in the game mode asked for
    private static ServerPlayer player(GameTestHelper helper, String name, GameType mode) {
        FakePlayer player = FakePlayer.get(helper.getLevel(),
                new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name));
        player.setGameMode(mode);
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
    private static void touch(ServerPlayer player, int value) {
        ExperienceOrb orb = new ExperienceOrb(player.level(), player.getX(), player.getY(), player.getZ(), value);
        player.takeXpDelay = 0;
        orb.playerTouch(player);
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
