package bobbynooby.dev.elytra;

import bobbynooby.dev.elytra.mixin.FireworkRocketInvoker;
import bobbynooby.dev.elytra.shared.SettingsFile;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The mod on its own, with You Only Live Twice nowhere on the server: its own config file, its own command, and creative
 * players left out by its own rule. YOLT's GameTests cover the same elytra nerf hosted inside YOLT.
 * <p>
 * Each test swaps its settings in and puts them back within the same tick, and so does each test that writes the file,
 * so no other test ever sees them. A fake player isn't in the server's player list, so the mod's own tick never reaches
 * it: each test ticks its players itself. A glide is the flag the server sets when one starts, and the speed is the
 * movement the server keeps from the client's move packets, set the way the packet handler sets it.
 */
public class ElytraGameTests {
    private static final double CLOSE = 1e-9;
    // Blocks per tick: 30 m/s and 10 m/s, either side of the 20 m/s cut-off
    private static final double FAST = 1.5;
    private static final double SLOW = 0.5;
    private static final double ROCKET_REACH = 8.0;

    @GameTest
    public void standaloneWritesAndReadsItsOwnFile(GameTestHelper helper) {
        helper.assertFalse(YoltElytra.hosted(), "the mod is hosted, but nothing here hosts it");
        Path file = YoltElytra.configFile();
        helper.assertTrue(Files.isRegularFile(file), "no " + file + " after the server started");
        SettingsFile.Result<ElytraSettings> read = ElytraConfig.load(file, ElytraSettings.DEFAULTS);
        helper.assertFalse(read.failed(), "the file can't be read: " + read.notes());
        helper.assertValueEqual(read.settings(), YoltElytra.settings(), "the settings in use");
        helper.assertTrue(helper.getLevel().getServer().getCommands().getDispatcher().getRoot().getChild("yolt-elytra") != null,
                "no /yolt-elytra on its own");
        helper.succeed();
    }

    // 0.6 times vanilla's gravity is vanilla's less 0.4 of it, and the modifier is transient, so no save holds it
    @GameTest
    public void gravityChangesOnlyWhileGliding(GameTestHelper helper) {
        ElytraSettings before = YoltElytra.swapForTesting(ElytraSettings.DEFAULTS);
        ServerPlayer glider = glider(helper, "AloneGlider", GameType.SURVIVAL, SLOW);
        ServerPlayer walker = player(helper, "AloneWalker", GameType.SURVIVAL);
        try {
            Glide.tick(glider);
            Glide.tick(walker);
            assertGravity(helper, glider, -0.4, "while gliding");
            helper.assertTrue(gravity(glider).getPermanentModifiers().stream()
                    .noneMatch(modifier -> modifier.is(YoltElytra.GRAVITY_MODIFIER)), "the gravity modifier is saved with the player");
            assertGravity(helper, walker, null, "while walking");

            glider.stopFallFlying();
            Glide.tick(glider);
            assertGravity(helper, glider, null, "a tick after the glide ended");
        } finally {
            land(glider, walker);
            YoltElytra.swapForTesting(before);
        }
        helper.succeed();
    }

    // The switch is read at every tick, so the gravity comes off at the next one while the glide goes on
    @GameTest
    public void aReloadThatTurnsItOffClearsIt(GameTestHelper helper) {
        ElytraSettings before = YoltElytra.swapForTesting(ElytraSettings.DEFAULTS);
        Path file = YoltElytra.configFile();
        String original = read(file);
        ServerPlayer glider = glider(helper, "AloneReloaded", GameType.SURVIVAL, SLOW);
        try {
            Glide.tick(glider);
            assertGravity(helper, glider, -0.4, "before the reload");

            write(file, ElytraConfig.json(new ElytraSettings(false, 0.6, 20.0)));
            SettingsFile.Result<ElytraSettings> result = YoltElytra.reload();
            helper.assertFalse(result.failed(), "the reload failed: " + result.notes());
            helper.assertFalse(YoltElytra.settings().enabled(), "the mod is still on after the reload");
            Glide.tick(glider);
            helper.assertTrue(glider.isFallFlying(), "the glide ended");
            assertGravity(helper, glider, null, "a tick after a reload turned it off");
        } finally {
            write(file, original);
            land(glider);
            YoltElytra.swapForTesting(before);
        }
        helper.succeed();
    }

    // Creative keeps vanilla's gravity, whether the player goes creative mid-glide or glides in creative from the start
    @GameTest
    public void aGliderWhoGoesCreativeLosesIt(GameTestHelper helper) {
        ElytraSettings before = YoltElytra.swapForTesting(ElytraSettings.DEFAULTS);
        ServerPlayer glider = glider(helper, "AloneGoesCreative", GameType.SURVIVAL, SLOW);
        ServerPlayer creative = glider(helper, "AloneCreative", GameType.CREATIVE, SLOW);
        try {
            Glide.tick(glider);
            assertGravity(helper, glider, -0.4, "on a survival glider");
            glider.setGameMode(GameType.CREATIVE);
            helper.assertTrue(glider.isFallFlying(), "the switch to creative ended the glide");
            Glide.tick(glider);
            assertGravity(helper, glider, null, "a tick after the switch to creative");

            Glide.tick(creative);
            assertGravity(helper, creative, null, "on a glider in creative from the start");
        } finally {
            land(glider, creative);
            YoltElytra.swapForTesting(before);
        }
        helper.succeed();
    }

    // Each rocket is also used the way the use-item packet uses it, so the refusal is the one the rocket item makes: a
    // refused rocket isn't launched or used up, and one below the cut-off is vanilla's
    @GameTest
    public void aRocketIsRefusedPastTheCutoff(GameTestHelper helper) {
        ElytraSettings before = YoltElytra.swapForTesting(ElytraSettings.DEFAULTS);
        ServerPlayer fast = glider(helper, "AloneRocketFast", GameType.SURVIVAL, FAST);
        ServerPlayer slow = glider(helper, "AloneRocketSlow", GameType.SURVIVAL, SLOW);
        try {
            helper.assertTrue(YoltElytra.refusesRocket(fast), "a rocket isn't refused at 30 m/s");
            helper.assertValueEqual(useRocket(helper, fast), InteractionResult.FAIL, "the use of a rocket at 30 m/s");
            helper.assertValueEqual(fast.getMainHandItem().getCount(), 5, "rockets left after one was refused");
            helper.assertTrue(rockets(helper, fast).isEmpty(), "a refused rocket was launched");

            helper.assertFalse(YoltElytra.refusesRocket(slow), "a rocket is refused at 10 m/s");
            useRocket(helper, slow);
            helper.assertValueEqual(slow.getMainHandItem().getCount(), 4, "rockets left after one was fired");
            helper.assertTrue(rockets(helper, slow).stream().anyMatch(ElytraGameTests::attached), "no rocket attached at 10 m/s");

            fast.stopFallFlying();
            helper.assertFalse(YoltElytra.refusesRocket(fast), "a rocket is refused at 30 m/s without a glide");
        } finally {
            rockets(helper, fast).forEach(FireworkRocketEntity::discard);
            rockets(helper, slow).forEach(FireworkRocketEntity::discard);
            land(fast, slow);
            YoltElytra.swapForTesting(before);
        }
        helper.succeed();
    }

    // The same 30 m/s that is past the default cut-off
    @GameTest
    public void aCutoffOfZeroNeverCuts(GameTestHelper helper) {
        ElytraSettings before = YoltElytra.swapForTesting(ElytraSettings.DEFAULTS);
        ServerPlayer fast = glider(helper, "AloneNoCutoff", GameType.SURVIVAL, FAST);
        try {
            helper.assertTrue(YoltElytra.pastCutoff(fast), "not past the default cut-off at 30 m/s");
            YoltElytra.swapForTesting(new ElytraSettings(true, 0.6, 0.0));
            helper.assertFalse(YoltElytra.pastCutoff(fast), "past a cut-off of 0 at 30 m/s");
        } finally {
            land(fast);
            YoltElytra.swapForTesting(before);
        }
        helper.succeed();
    }

    // Through the server's own dispatcher, as an op typing it would
    @GameTest
    public void theCommandShowsAndReloads(GameTestHelper helper) {
        ElytraSettings before = YoltElytra.swapForTesting(ElytraSettings.DEFAULTS);
        Path file = YoltElytra.configFile();
        String original = read(file);
        try {
            List<String> said = new ArrayList<>();
            CommandSourceStack source = source(helper.getLevel().getServer(), said);
            helper.assertValueEqual(run(helper, source, "yolt-elytra"), 1, "/yolt-elytra");
            helper.assertTrue(said.contains("Elytra: glide gravity 0.6, rocket cut-off 20 m/s"), "it said " + said);

            said.clear();
            write(file, ElytraConfig.json(new ElytraSettings(false, 0.6, 20.0)));
            helper.assertValueEqual(run(helper, source, "yolt-elytra reload"), 1, "/yolt-elytra reload");
            helper.assertTrue(said.contains("Reloaded yolt-elytra.json. The elytra nerf is off"), "it said " + said);
        } finally {
            write(file, original);
            YoltElytra.swapForTesting(before);
        }
        helper.succeed();
    }

    // A fake player for each name, in the game mode asked for. It stands in the test's own space, so a rocket it
    // launches is in a loaded chunk, where the test finds it and takes it away.
    private static ServerPlayer player(GameTestHelper helper, String name, GameType mode) {
        FakePlayer player = FakePlayer.get(helper.getLevel(),
                new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name));
        player.setGameMode(mode);
        player.setPos(helper.absoluteVec(new Vec3(1.5, 1.0, 1.5)));
        return player;
    }

    // Wearing an elytra and gliding east at this many blocks per tick
    private static ServerPlayer glider(GameTestHelper helper, String name, GameType mode, double blocksPerTick) {
        ServerPlayer player = player(helper, name, mode);
        player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
        player.startFallFlying();
        player.setKnownMovement(new Vec3(blocksPerTick, 0.0, 0.0));
        return player;
    }

    // Fabric may hand the same fake player to a later ask for its name, so what a test put on its players comes off
    private static void land(ServerPlayer... players) {
        for (ServerPlayer player : players) {
            player.stopFallFlying();
            player.setKnownMovement(Vec3.ZERO);
            player.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
            player.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            gravity(player).removeModifier(YoltElytra.GRAVITY_MODIFIER);
        }
    }

    // A stack of 5 in the main hand, used the way the use-item packet uses it
    private static InteractionResult useRocket(GameTestHelper helper, ServerPlayer player) {
        player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.FIREWORK_ROCKET, 5));
        return player.gameMode.useItem(player, helper.getLevel(), player.getItemInHand(InteractionHand.MAIN_HAND),
                InteractionHand.MAIN_HAND);
    }

    // Every rocket this player launched
    private static List<FireworkRocketEntity> rockets(GameTestHelper helper, ServerPlayer player) {
        return helper.getLevel().getEntitiesOfClass(FireworkRocketEntity.class, player.getBoundingBox().inflate(ROCKET_REACH),
                rocket -> rocket.getOwner() == player && !rocket.isRemoved());
    }

    private static boolean attached(FireworkRocketEntity rocket) {
        return ((FireworkRocketInvoker) rocket).yoltElytraIsAttachedToEntity();
    }

    private static AttributeInstance gravity(ServerPlayer player) {
        return Objects.requireNonNull(player.getAttribute(Attributes.GRAVITY), "a player without a gravity attribute");
    }

    private static void assertGravity(GameTestHelper helper, ServerPlayer player, Double expected, String when) {
        AttributeModifier modifier = gravity(player).getModifier(YoltElytra.GRAVITY_MODIFIER);
        if (expected == null) {
            helper.assertTrue(modifier == null, "the gravity modifier is on " + when);
            return;
        }
        helper.assertTrue(modifier != null, "no gravity modifier " + when);
        helper.assertTrue(Math.abs(modifier.amount() - expected) < CLOSE, "gravity modifier " + modifier.amount() + " " + when
                + ", not " + expected);
        helper.assertValueEqual(modifier.operation(), AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL,
                "the gravity modifier's operation " + when);
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
