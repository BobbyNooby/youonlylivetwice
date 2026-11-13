package bobbynooby.dev.gametest;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.Yolt;
import bobbynooby.dev.hardcore.Hardcore;
import bobbynooby.dev.hardcore.HardcoreState;
import bobbynooby.dev.hardcore.HardcoreStates;
import bobbynooby.dev.hardcore.Legs;
import bobbynooby.dev.hardcore.Slowdowns;
import bobbynooby.dev.hardcore.Status;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

// The whole layer at once. Each test holds its switches through an environment of its own, so it runs alone.
public class HardcoreAllGameTests {
    // Every switch on; the blood moon's grace never ends, so the shared world clock can't start one
    private static final String ALL = "you-only-live-twice-gametest:hardcore_all";
    // Every switch off, said out loud
    private static final String ALL_OFF = "you-only-live-twice-gametest:hardcore_all_off";
    // A batch of its own: it changes the switch through the config file
    private static final String COLD_SWITCH = "you-only-live-twice-gametest:hardcore_cold_switch";
    private static final BlockPos AT = new BlockPos(1, 0, 1);
    private static final BlockPos MOB_AT = new BlockPos(5, 0, 5);
    // Weight weighs again every 10 ticks, so the full load counts from here on
    private static final int WEIGHED = 12;
    private static final double CLOSE = 1e-9;

    // A full load (18 stacks of cobblestone at 3 and 2 of dirt at 1), broken legs, a limp at 3 health, full chill and
    // low sugar while sprinting add up to well over the cap. The chill is set again each tick, since out of the cold
    // it would fall, and the sprint too, since nothing here keeps it.
    @GameTest(environment = ALL, maxTicks = 120)
    public void all1EverythingOnIsExactlyHalfSpeed(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "Overloaded", GameType.SURVIVAL, AT);
        TestPlayers.markLoaded(player);
        UUID uuid = player.getUUID();
        List<String> wrong = new CopyOnWriteArrayList<>();
        AtomicBoolean done = new AtomicBoolean();
        LogWatch errors = LogWatch.start();
        Runnable cleanUp = BodyTestKit.once(() -> {
            done.set(true);
            errors.stop();
            Legs.set(uuid, false);
            HardcoreStates.update(uuid, s -> {
                s.chill = 0.0;
                s.protein = HardcoreState.UNSET;
                s.vitamins = HardcoreState.UNSET;
                s.sugar = HardcoreState.UNSET;
            });
            player.getInventory().clearContent();
            TestPlayers.leave(player);
        });
        boolean waiting = false;
        try {
            for (int i = 0; i < 18; i++) {
                player.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));
            }
            for (int i = 0; i < 2; i++) {
                player.getInventory().add(new ItemStack(Items.DIRT, 64));
            }
            Legs.set(uuid, true);
            player.setHealth(3.0F);
            HardcoreStates.update(uuid, s -> {
                s.protein = 0.6;
                s.vitamins = 0.6;
                s.sugar = 0.2;
            });
            long start = helper.getTick();
            helper.onEachTick(() -> {
                if (done.get()) {
                    return;
                }
                if (helper.getTick() - start > WEIGHED) {
                    double applied = Slowdowns.applied(player);
                    double raw = Slowdowns.raw(player);
                    if (Math.abs(applied - 0.5) > CLOSE || !(raw > 0.5)) {
                        wrong.add("tick " + (helper.getTick() - start) + ": slowdown " + applied + ", raw " + raw);
                    }
                }
                HardcoreStates.update(uuid, s -> s.chill = 1.0);
                player.setSprinting(true);
            });
            helper.runAfterDelay(100, () -> {
                try {
                    helper.assertValueEqual(wrong, List.of(), "ticks off half speed");
                    helper.assertTrue(Math.abs(Slowdowns.applied(player) - 0.5) < CLOSE,
                            "slowdown " + Slowdowns.applied(player) + " after 100 ticks, not 0.5");
                    helper.assertTrue(Slowdowns.raw(player) > 1.0, "the parts add up to only " + Slowdowns.raw(player));
                    helper.assertValueEqual(errors.messages(), List.of(), "errors logged");
                } finally {
                    cleanUp.run();
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                cleanUp.run();
            }
        }
    }

    // Writes to the hardcore store happen on the server thread as they are made, and a player's cached state is
    // written when they leave, so with this batch running alone nothing from an earlier one is still to come: the
    // file as it stands now is what it must still be after 100 ticks. Whether it is made only on the first write is
    // the store's own test.
    @GameTest(environment = ALL_OFF, maxTicks = 120)
    public void off1WholeLayerOffIsClean(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Path store = server.getWorldPath(LevelResource.ROOT).resolve("database/" + Yolt.MOD_ID + "-hardcore.db");
        String before = fileState(store);
        helper.assertFalse(Hardcore.anyOn(), "a hardcore switch is on");
        ServerPlayer player = TestPlayers.join(helper, "Untouched", GameType.SURVIVAL, AT);
        TestPlayers.markLoaded(player);
        List<Entity> spawned = new ArrayList<>();
        Runnable cleanUp = BodyTestKit.once(() -> {
            spawned.forEach(Entity::discard);
            TestPlayers.leave(player);
        });
        boolean waiting = false;
        try {
            helper.runAfterDelay(100, () -> {
                try {
                    assertNothingOfOurs(helper, player);
                    Mob zombie = HardcoreMobsGameTests.create(helper, spawned, EntityTypes.ZOMBIE, EntitySpawnReason.NATURAL, MOB_AT);
                    assertNothingOfOurs(helper, zombie);
                    helper.assertValueEqual(Status.report(player).stream().map(Component::getString).toList(),
                            List.of("Nothing to report"), "the status report");
                    helper.assertValueEqual(fileState(store), before, "the hardcore store");
                } finally {
                    cleanUp.run();
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                cleanUp.run();
            }
        }
    }

    // /yolt config writes the file and swaps the config object; the test puts the file back and reloads it at its end.
    // The test runs vanilla's own tick, so frozen ticks fall once nothing holds them up. No once-a-second step comes
    // before the end, so the chill only moves if something resets it.
    @GameTest(environment = COLD_SWITCH, maxTicks = 40)
    public void off2ColdOffLiveKeepsItsState(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        byte[] fileBefore = Files.readAllBytes(Settings.path());
        ServerPlayer player = TestPlayers.join(helper, "ColdSwitcher", GameType.SURVIVAL, AT);
        TestPlayers.markLoaded(player);
        UUID uuid = player.getUUID();
        Runnable cleanUp = BodyTestKit.once(() -> {
            HardcoreStates.update(uuid, s -> s.chill = 0.0);
            TestPlayers.leave(player);
            try {
                ConfigCommandGameTests.restore(server, fileBefore);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        boolean waiting = false;
        try {
            HardcoreColdGameTests.setChill(player, 0.6);
            helper.onEachTick(() -> {
                if (!player.isRemoved()) {
                    HardcoreColdGameTests.tickVanilla(player, cleanUp);
                }
            });
            helper.runAfterDelay(2, () -> {
                try {
                    helper.assertValueEqual(player.getTicksFrozen(), 84, "frozen ticks at chill 0.6");
                    assertSlow(helper, player, 0.1, "at chill 0.6");
                    CommandGameTests.run(server, "yolt config hardcore.cold.enabled false");
                    helper.assertFalse(Settings.get().hardcore.cold.enabled, "the command left cold on");
                } catch (RuntimeException e) {
                    cleanUp.run();
                    throw e;
                }
            });
            helper.runAfterDelay(4, () -> {
                try {
                    helper.assertTrue(player.getTicksFrozen() < 84, "frozen ticks still " + player.getTicksFrozen()
                            + " with cold switched off");
                    assertSlow(helper, player, 0.0, "with cold switched off");
                    helper.assertTrue(Slowdowns.raw(player) == 0.0, "the cold part is still " + Slowdowns.raw(player));
                    helper.assertTrue(Math.abs(HardcoreColdGameTests.chill(player) - 0.6) < CLOSE,
                            "switching cold off changed the stored chill to " + HardcoreColdGameTests.chill(player));
                    CommandGameTests.run(server, "yolt config hardcore.cold.enabled true");
                    helper.assertTrue(Settings.get().hardcore.cold.enabled, "the command left cold off");
                } catch (RuntimeException e) {
                    cleanUp.run();
                    throw e;
                }
            });
            helper.runAfterDelay(6, () -> {
                try {
                    helper.assertValueEqual(player.getTicksFrozen(), 84, "frozen ticks with cold switched back on");
                    assertSlow(helper, player, 0.1, "with cold switched back on");
                    helper.assertTrue(Math.abs(HardcoreColdGameTests.chill(player) - 0.6) < CLOSE,
                            "the chill went on from " + HardcoreColdGameTests.chill(player) + ", not 0.6");
                } finally {
                    cleanUp.run();
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                cleanUp.run();
            }
        }
    }

    // Every modifier the layer puts on (hardcore_slow, undead_curse, nutrients, elytra_gravity, tougher, relentless,
    // blood_moon) is in YOLT's namespace, and every tag it adds (yolt.tough, yolt.vanilla, yolt.blood_moon,
    // yolt.door_rolled) starts with "yolt.", so this finds any of them and any added later
    private static void assertNothingOfOurs(GameTestHelper helper, LivingEntity entity) {
        for (Holder<Attribute> attribute : BuiltInRegistries.ATTRIBUTE.listElements().toList()) {
            AttributeInstance instance = entity.getAttribute(attribute);
            if (instance == null) {
                continue;
            }
            for (AttributeModifier modifier : instance.getModifiers()) {
                helper.assertFalse(modifier.id().getNamespace().equals(Yolt.MOD_ID),
                        "modifier " + modifier.id() + " on " + attribute.getRegisteredName() + " of " + entity.getType());
            }
        }
        helper.assertTrue(entity.entityTags().stream().noneMatch(tag -> tag.startsWith("yolt.")),
                entity.getType() + " has YOLT tags: " + entity.entityTags());
    }

    private static void assertSlow(GameTestHelper helper, ServerPlayer player, double expected, String when) {
        double applied = Slowdowns.applied(player);
        helper.assertTrue(Math.abs(applied - expected) < CLOSE, "slowdown " + applied + " " + when + ", not " + expected);
    }

    // Whether the file is there, how big it is and when it last changed
    private static String fileState(Path file) {
        if (!Files.exists(file)) {
            return "none";
        }
        try {
            return Files.size(file) + " bytes, changed " + Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // Every error YOLT logs from start to stop, across ticks. It joins the config YOLT's logger already uses, as
    // OfflineGameTests.yoltEvents does.
    private static final class LogWatch {
        private final LoggerConfig config;
        private final AbstractAppender appender;
        private final List<String> errors = new CopyOnWriteArrayList<>();

        private LogWatch() {
            config = ((Logger) LogManager.getLogger(Yolt.MOD_ID)).get();
            appender = new AbstractAppender("yolt-gametest-errors", null, null, false, Property.EMPTY_ARRAY) {
                @Override
                public void append(LogEvent event) {
                    if (Yolt.MOD_ID.equals(event.getLoggerName()) && event.getLevel().isMoreSpecificThan(Level.ERROR)) {
                        errors.add(event.getMessage().getFormattedMessage());
                    }
                }
            };
        }

        static LogWatch start() {
            LogWatch watch = new LogWatch();
            watch.appender.start();
            watch.config.addAppender(watch.appender, null, null);
            return watch;
        }

        void stop() {
            config.removeAppender(appender.getName());
            appender.stop();
        }

        List<String> messages() {
            return List.copyOf(errors);
        }
    }
}
