package bobbynooby.dev.elytra;

import bobbynooby.dev.elytra.mixin.FireworkRocketInvoker;
import bobbynooby.dev.elytra.shared.SettingsFile;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
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
import java.util.concurrent.atomic.AtomicInteger;

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

    // A tick that throws, ahead of a glider in the loop the server's tick runs: the throw stays in the loop, and the glider
    // after it still gets its gravity. The error this logs is expected.
    @GameTest
    public void oneFailingTickLeavesTheNextPlayerGliding(GameTestHelper helper) {
        ElytraSettings before = YoltElytra.swapForTesting(ElytraSettings.DEFAULTS);
        AtomicInteger thrown = new AtomicInteger();
        String name = "AloneBroken";
        ServerPlayer broken = new FakePlayer(helper.getLevel(),
                new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name)) {
            @Override
            public boolean isFallFlying() {
                thrown.incrementAndGet();
                throw new IllegalStateException("a glide tick that fails, for the test");
            }
        };
        broken.setGameMode(GameType.SURVIVAL);
        ServerPlayer glider = glider(helper, "AloneAfterBroken", GameType.SURVIVAL, SLOW);
        try {
            Glide.tickAll(List.of(broken, glider));
            helper.assertValueEqual(thrown.get(), 1, "times the broken player's tick threw");
            assertGravity(helper, glider, -0.4, "on the player after one whose tick threw");
        } finally {
            land(glider);
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

    // A rocket boosting a glider past the cut-off is ended at the glider's tick, with no blast and no damage, and one
    // boosting a glider under it goes on. The rocket carries a star and the fast glider can be hurt, so a rocket that
    // exploded rather than ended would show in their health.
    @GameTest
    public void aBoostingRocketEndsPastTheCutoff(GameTestHelper helper) {
        ElytraSettings before = YoltElytra.swapForTesting(ElytraSettings.DEFAULTS);
        ServerPlayer fast = glide(hurtable(helper, "AloneBoostFast"), FAST);
        ServerPlayer slow = glider(helper, "AloneBoostSlow", GameType.SURVIVAL, SLOW);
        FireworkRocketEntity fastRocket = attachRocket(helper, fast);
        FireworkRocketEntity slowRocket = attachRocket(helper, slow);
        try {
            float health = fast.getHealth();
            Glide.tick(fast);
            Glide.tick(slow);
            helper.assertTrue(fastRocket.isRemoved(), "the rocket still boosts at 30 m/s");
            helper.assertValueEqual(fast.getHealth(), health, "the glider's health after the rocket ended");
            helper.assertFalse(slowRocket.isRemoved(), "the rocket ended at 10 m/s");
        } finally {
            fastRocket.discard();
            slowRocket.discard();
            land(fast, slow);
            YoltElytra.swapForTesting(before);
        }
        helper.succeed();
    }

    // Only rockets boosting the fast glider end: not another glider's rocket beside them, nor a rocket the fast glider
    // shot that flies free
    @GameTest
    public void someoneElsesRocketIsLeftAlone(GameTestHelper helper) {
        ElytraSettings before = YoltElytra.swapForTesting(ElytraSettings.DEFAULTS);
        ServerPlayer fast = glider(helper, "AloneOwnFast", GameType.SURVIVAL, FAST);
        ServerPlayer other = glider(helper, "AloneOther", GameType.SURVIVAL, SLOW);
        FireworkRocketEntity boosting = attachRocket(helper, fast);
        FireworkRocketEntity othersRocket = attachRocket(helper, other);
        FireworkRocketEntity shot = new FireworkRocketEntity(helper.getLevel(), new ItemStack(Items.FIREWORK_ROCKET), fast,
                fast.getX() + 2.0, fast.getY(), fast.getZ(), true);
        helper.getLevel().addFreshEntity(shot);
        try {
            helper.assertTrue(othersRocket.distanceTo(fast) < ROCKET_REACH, "the other rocket is out of the fast glider's reach");
            Glide.tick(fast);
            helper.assertTrue(boosting.isRemoved(), "the fast glider's own rocket still boosts at 30 m/s");
            helper.assertFalse(othersRocket.isRemoved(), "the other glider's rocket ended");
            helper.assertFalse(shot.isRemoved(), "the rocket the fast glider shot ended");
        } finally {
            boosting.discard();
            othersRocket.discard();
            shot.discard();
            land(fast, other);
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

    // The same source with the permissions the server gives a player who isn't an op. The file that would turn the mod
    // off proves the refused reload read nothing, and the op's reload of it proves it would have.
    @GameTest
    public void theCommandIsForOpsOnly(GameTestHelper helper) {
        ElytraSettings before = YoltElytra.swapForTesting(ElytraSettings.DEFAULTS);
        Path file = YoltElytra.configFile();
        String original = read(file);
        try {
            List<String> said = new ArrayList<>();
            CommandSourceStack op = source(helper.getLevel().getServer(), said);
            CommandSourceStack player = op.withPermission(LevelBasedPermissionSet.forLevel(PermissionLevel.ALL));
            refused(helper, player, "yolt-elytra");
            String off = ElytraConfig.json(new ElytraSettings(false, 0.6, 20.0));
            write(file, off);
            refused(helper, player, "yolt-elytra reload");
            helper.assertTrue(said.isEmpty(), "it said " + said);
            helper.assertValueEqual(YoltElytra.settings(), ElytraSettings.DEFAULTS, "the settings after a refused reload");
            helper.assertValueEqual(read(file), off, "the file after a refused reload");

            helper.assertValueEqual(run(helper, op, "yolt-elytra"), 1, "/yolt-elytra as an op");
            helper.assertValueEqual(run(helper, op, "yolt-elytra reload"), 1, "/yolt-elytra reload as an op");
            helper.assertFalse(YoltElytra.settings().enabled(), "still on after an op reloaded a file that turns it off");
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

    // A survival fake player of its own that damage reaches, which Fabric's fake players never take. Where player()
    // stands one.
    private static ServerPlayer hurtable(GameTestHelper helper, String name) {
        ServerPlayer player = new FakePlayer(helper.getLevel(),
                new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name)) {
            @Override
            public boolean isInvulnerableTo(ServerLevel level, DamageSource source) {
                return false;
            }
        };
        player.setGameMode(GameType.SURVIVAL);
        player.setPos(helper.absoluteVec(new Vec3(1.5, 1.0, 1.5)));
        return player;
    }

    // Wearing an elytra and gliding east at this many blocks per tick
    private static ServerPlayer glider(GameTestHelper helper, String name, GameType mode, double blocksPerTick) {
        return glide(player(helper, name, mode), blocksPerTick);
    }

    private static ServerPlayer glide(ServerPlayer player, double blocksPerTick) {
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

    // A rocket boosting the player, as one fired while gliding is. It carries a firework star, so it would hurt the
    // player if it exploded.
    private static FireworkRocketEntity attachRocket(GameTestHelper helper, ServerPlayer player) {
        ItemStack stack = new ItemStack(Items.FIREWORK_ROCKET);
        stack.set(DataComponents.FIREWORKS, new Fireworks(1, List.of(FireworkExplosion.DEFAULT)));
        FireworkRocketEntity rocket = new FireworkRocketEntity(helper.getLevel(), stack, player);
        helper.getLevel().addFreshEntity(rocket);
        return rocket;
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

    // The dispatcher skips a node the source may not use, so the command is unknown to it, as vanilla answers any
    // command a player may not use
    private static void refused(GameTestHelper helper, CommandSourceStack source, String command) {
        try {
            int result = helper.getLevel().getServer().getCommands().getDispatcher().execute(command, source);
            throw helper.assertionException("/" + command + " ran without op permission and returned " + result);
        } catch (CommandSyntaxException e) {
            helper.assertTrue(e.getType() == CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand(),
                    "/" + command + " was refused as " + e.getMessage() + ", not as an unknown command");
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
