package bobbynooby.dev.gametest;

import bobbynooby.dev.bodies.PlayerBody;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.coords.YoltCoordinates;
import bobbynooby.dev.core.Yolt;
import bobbynooby.dev.gametest.mixin.FoodDataAccessor;
import bobbynooby.dev.hardcore.Cold;
import bobbynooby.dev.hardcore.Curse;
import bobbynooby.dev.hardcore.Hardcore;
import bobbynooby.dev.hardcore.HardcoreState;
import bobbynooby.dev.hardcore.HardcoreStates;
import bobbynooby.dev.hardcore.Legs;
import bobbynooby.dev.hardcore.Slowdowns;
import bobbynooby.dev.hardcore.Status;
import bobbynooby.dev.hardcore.YoltHardcore;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueOutput;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
    // A batch of its own, so only its own player's hooks fail
    private static final String HOOK_FAILS = "you-only-live-twice-gametest:hardcore_hook_fails";
    // Every switch on, as ALL, each in a batch of its own
    private static final String ALL_SAVED = "you-only-live-twice-gametest:hardcore_all_saved";
    private static final String ALL_CREATIVE = "you-only-live-twice-gametest:hardcore_all_creative";
    // Weight, legs, nutrients and cold on, hunger ×2, in clear weather, for a biome of its own
    private static final String BODY_RULES = "you-only-live-twice-gametest:hardcore_body_rules";
    // The players the two test hooks below act on; every other player passes through them untouched
    private static final Set<UUID> FAILING = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> RAN_AFTER = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean HOOKED = new AtomicBoolean();
    private static final BlockPos AT = new BlockPos(1, 0, 1);
    private static final BlockPos MOB_AT = new BlockPos(5, 0, 5);
    // Weight weighs again every 10 ticks, so the full load counts from here on
    private static final int WEIGHED = 12;
    private static final double CLOSE = 1e-9;

    // A full load (6 stacks of iron blocks at 27 and 2 of dirt at 1), broken legs, a limp at 3 health, full chill and
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
            for (int i = 0; i < 6; i++) {
                player.getInventory().add(new ItemStack(Items.IRON_BLOCK, 64));
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

    // Every modifier the layer puts on (hardcore_slow, undead_curse, nutrients, tougher, relentless, blood_moon) is in
    // YOLT's namespace, the glide's (glide_gravity) in yolt-elytra's, and every tag it adds (yolt.tough, yolt.vanilla,
    // yolt.blood_moon, yolt.door_rolled) starts with "yolt.", so this finds any of them and any added later
    // A tick hook that throws is logged with the player's name and its exception, the way Guard.run logs one, and the
    // hooks after it still run. The name is only read once a hook has thrown, so it must still come out right then.
    @GameTest(environment = HOOK_FAILS, maxTicks = 40)
    public void hook1AFailingTickHookIsLoggedAndTheRestRun(GameTestHelper helper) {
        // Registered once for the whole run: the hub has no way to take a hook off again
        if (HOOKED.compareAndSet(false, true)) {
            Hardcore.onTick(player -> {
                if (FAILING.contains(player.getUUID())) {
                    throw new IllegalStateException("a tick hook that fails");
                }
            });
            Hardcore.onTick(player -> {
                if (FAILING.contains(player.getUUID())) {
                    RAN_AFTER.add(player.getUUID());
                }
            });
        }
        ServerPlayer player = TestPlayers.join(helper, "HookFails", GameType.SURVIVAL, AT);
        UUID uuid = player.getUUID();
        String name = player.nameAndId().name();
        LogWatch errors = LogWatch.start();
        Runnable cleanUp = BodyTestKit.once(() -> {
            FAILING.remove(uuid);
            RAN_AFTER.remove(uuid);
            errors.stop();
            TestPlayers.leave(player);
        });
        helper.runBeforeTestEnd(cleanUp);
        FAILING.add(uuid);
        helper.runAfterDelay(3, () -> {
            try {
                List<LogEvent> ours = errors.events().stream()
                        .filter(event -> event.getMessage().getFormattedMessage().contains(name))
                        .toList();
                helper.assertFalse(ours.isEmpty(), "the failing hook was not logged");
                for (LogEvent event : ours) {
                    helper.assertValueEqual(event.getMessage().getFormattedMessage(), "A hardcore tick failed for " + name,
                            "the failing hook's line");
                    helper.assertTrue(event.getThrown() instanceof IllegalStateException thrown
                            && thrown.getMessage().equals("a tick hook that fails"), "the line's exception: " + event.getThrown());
                }
                helper.assertTrue(RAN_AFTER.contains(uuid), "the hook after the failing one did not run");
            } finally {
                cleanUp.run();
            }
            helper.succeed();
        });
    }

    // Every modifier the layer puts on a player is transient, so the save holds none of them: the curse, broken legs, a
    // full load, low vitamins, a limp, the cold and a glide all on at once, each checked live first. Removing YOLT then
    // leaves nothing behind on a player (docs/known-limits.md).
    @GameTest(environment = ALL_SAVED, maxTicks = 60)
    public void all2NothingOfOursIsSavedWithThePlayer(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "Unsaved", GameType.SURVIVAL, AT);
        TestPlayers.markLoaded(player);
        UUID uuid = player.getUUID();
        Runnable cleanUp = BodyTestKit.once(() -> {
            Curse.set(uuid, 0);
            Legs.set(uuid, false);
            resetState(uuid);
            player.getInventory().clearContent();
            TestPlayers.leave(player);
        });
        boolean waiting = false;
        try {
            for (int i = 0; i < 6; i++) {
                player.getInventory().add(new ItemStack(Items.IRON_BLOCK, 64));
            }
            Curse.set(uuid, 2);
            Legs.set(uuid, true);
            player.setHealth(3.0F);
            HardcoreStates.update(uuid, s -> {
                s.protein = 0.6;
                s.vitamins = 0.1;
                s.sugar = 0.6;
                s.chill = 0.5;
            });
            player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
            player.startFallFlying();
            helper.runAfterDelay(WEIGHED, () -> {
                try {
                    for (Holder<Attribute> attribute : List.of(Attributes.MAX_HEALTH, Attributes.MOVEMENT_SPEED,
                            Attributes.BLOCK_BREAK_SPEED, Attributes.GRAVITY)) {
                        helper.assertTrue(HardcoreMobsGameTests.attribute(player, attribute).getModifiers().stream()
                                        .anyMatch(modifier -> Ours.is(modifier.id())),
                                "no modifier of ours on " + attribute.getRegisteredName() + " to leave out of the save");
                    }
                    TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING,
                            helper.getLevel().registryAccess());
                    player.saveWithoutId(output);
                    CompoundTag saved = output.buildResult();
                    helper.assertTrue(saved.contains("attributes"), "the save has no attributes: " + saved.keySet());
                    helper.assertFalse(Ours.named(String.valueOf(saved.get("attributes"))),
                            "the saved attributes hold a modifier of ours: " + saved.get("attributes"));
                    helper.assertFalse(Ours.named(saved.toString()), "the save holds something of ours: " + saved);
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

    // Creative is outside the rules: the switch takes every modifier and lost heart off at the next tick and lets the
    // frost thaw under vanilla's own tick, and the switch back puts it all on again at the next. No once-a-second cold
    // step comes before the end, so the chill stays at 0.5: frozen ticks of 70, and a slowdown of 0.1 with the legs' 0.2.
    @GameTest(environment = ALL_CREATIVE, maxTicks = 60)
    public void all2CreativeTakesEverythingOffSurvivalPutsItBack(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "Switcher", GameType.SURVIVAL, AT);
        TestPlayers.markLoaded(player);
        UUID uuid = player.getUUID();
        AtomicBoolean vanillaTicks = new AtomicBoolean();
        Runnable cleanUp = BodyTestKit.once(() -> {
            vanillaTicks.set(false);
            Curse.set(uuid, 0);
            Legs.set(uuid, false);
            resetState(uuid);
            TestPlayers.leave(player);
        });
        boolean waiting = false;
        try {
            Curse.set(uuid, 2);
            Legs.set(uuid, true);
            HardcoreStates.update(uuid, s -> {
                s.protein = 0.6;
                s.vitamins = 0.1;
                s.sugar = 0.6;
                s.chill = 0.5;
            });
            helper.onEachTick(() -> {
                if (vanillaTicks.get() && !player.isRemoved()) {
                    HardcoreColdGameTests.tickVanilla(player, cleanUp);
                }
            });
            helper.runAfterDelay(WEIGHED, () -> step(cleanUp, () -> {
                assertInTheRules(helper, player, "in survival");
                player.setGameMode(GameType.CREATIVE);
                vanillaTicks.set(true);
            }));
            helper.runAfterDelay(WEIGHED + 10, () -> step(cleanUp, () -> {
                assertNothingOfOurs(helper, player);
                helper.assertValueEqual(player.getMaxHealth(), 20.0F, "max health in creative");
                helper.assertTrue(player.getTicksFrozen() < 70, "frozen ticks " + player.getTicksFrozen() + " after 10 ticks in creative");
                vanillaTicks.set(false);
                player.setGameMode(GameType.SURVIVAL);
            }));
            helper.runAfterDelay(WEIGHED + 12, () -> {
                try {
                    assertInTheRules(helper, player, "back in survival");
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

    // A player body is its owner standing in for them, but carries none of their body's state: on snow for 100 ticks
    // with a full load, broken legs, low vitamins and a chill stored, it gets no modifier and no frost, the owner's
    // stored state doesn't move, and its own hunger isn't taxed
    @GameTest(environment = BODY_RULES, maxTicks = 140, skyAccess = true)
    public void all2APlayerBodyCarriesNoHardcoreState(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "BodyRules", GameType.SURVIVAL, AT);
        TestPlayers.markLoaded(player);
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = BodyTestKit.usePlayerBodies();
        Runnable[] biome = {() -> {}};
        Runnable cleanUp = BodyTestKit.once(() -> {
            try {
                Legs.set(owner, false);
                resetState(owner);
                BodyTestKit.cleanUp(helper, owner, before);
            } finally {
                biome[0].run();
            }
        });
        helper.runBeforeTestEnd(cleanUp);
        try {
            biome[0] = HardcoreColdGameTests.setBiome(helper, new BlockPos(-2, -2, -2), new BlockPos(9, 8, 9), Biomes.SNOWY_PLAINS);
            helper.assertTrue(Cold.coldSpot(helper.getLevel(), player.blockPosition()), "the body's spot isn't cold");
            for (int i = 0; i < 6; i++) {
                player.getInventory().add(new ItemStack(Items.IRON_BLOCK, 64));
            }
            Legs.set(owner, true);
            HardcoreStates.update(owner, s -> {
                s.protein = 0.6;
                s.vitamins = 0.1;
                s.sugar = 0.6;
                s.chill = 0.5;
            });
            PlayerBody body = BodyTestKit.leavePlayerBody(helper, player);
            String stored = HardcoreStates.read(owner).encode();
            helper.runAfterDelay(100, () -> {
                try {
                    helper.assertFalse(body.isRemoved(), "the body is gone");
                    for (Holder<Attribute> attribute : BuiltInRegistries.ATTRIBUTE.listElements().toList()) {
                        AttributeInstance instance = body.getAttribute(attribute);
                        if (instance != null) {
                            for (AttributeModifier modifier : instance.getModifiers()) {
                                helper.assertFalse(Ours.is(modifier.id()),
                                        "modifier " + modifier.id() + " on the body's " + attribute.getRegisteredName());
                            }
                        }
                    }
                    helper.assertValueEqual(body.getTicksFrozen(), 0, "the body's frozen ticks");
                    helper.assertValueEqual(HardcoreStates.read(owner).encode(), stored, "the owner's stored state");
                    FoodDataAccessor food = (FoodDataAccessor) body.getFoodData();
                    food.yoltSetExhaustion(0.0F);
                    body.causeFoodExhaustion(0.1F);
                    helper.assertTrue(Math.abs(food.yoltExhaustion() - 0.1F) < 1e-6F,
                            "the body's exhaustion after 0.1 at ×2: " + food.yoltExhaustion());
                } finally {
                    cleanUp.run();
                }
                helper.succeed();
            });
        } catch (RuntimeException | Error e) {
            cleanUp.run();
            throw e;
        }
    }

    // In the rules: two hearts lost, the legs' and the chill's slowdown, low vitamins' mining slowdown and the chill's frost
    private static void assertInTheRules(GameTestHelper helper, ServerPlayer player, String when) {
        helper.assertValueEqual(player.getMaxHealth(), 16.0F, "max health " + when);
        assertSlow(helper, player, 0.3, when);
        helper.assertTrue(HardcoreMobsGameTests.attribute(player, Attributes.BLOCK_BREAK_SPEED).getModifier(Yolt.id("nutrients")) != null,
                "no mining slowdown " + when);
        helper.assertValueEqual(player.getTicksFrozen(), 70, "frozen ticks " + when);
    }

    // Nothing stored for the player but what a fresh state holds
    private static void resetState(UUID uuid) {
        HardcoreStates.update(uuid, s -> {
            s.chill = 0.0;
            s.protein = HardcoreState.UNSET;
            s.vitamins = HardcoreState.UNSET;
            s.sugar = HardcoreState.UNSET;
        });
    }

    // A failed check ends the test at once, so the player isn't left online for the batch
    private static void step(Runnable cleanUp, Runnable check) {
        try {
            check.run();
        } catch (RuntimeException e) {
            cleanUp.run();
            throw e;
        }
    }

    private static void assertNothingOfOurs(GameTestHelper helper, LivingEntity entity) {
        for (Holder<Attribute> attribute : BuiltInRegistries.ATTRIBUTE.listElements().toList()) {
            AttributeInstance instance = entity.getAttribute(attribute);
            if (instance == null) {
                continue;
            }
            for (AttributeModifier modifier : instance.getModifiers()) {
                helper.assertFalse(Ours.is(modifier.id()),
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

    // Every error YOLT, the hardcore layer and the coordinate offset log from start to stop, across ticks. The last two
    // are the YOLT Hardcore and YOLT Coordinates mods YOLT ships, each logging under its own name. It joins the configs
    // their loggers already use, as OfflineGameTests.yoltEvents does.
    private static final class LogWatch {
        private static final List<String> LOGGERS = List.of(Yolt.MOD_ID, YoltHardcore.LOGGER.getName(),
                YoltCoordinates.LOGGER.getName());
        // The loggers may share a config, which takes the appender once
        private final List<LoggerConfig> configs;
        private final AbstractAppender appender;
        private final List<LogEvent> errors = new CopyOnWriteArrayList<>();

        private LogWatch() {
            configs = LOGGERS.stream().map(name -> ((Logger) LogManager.getLogger(name)).get()).distinct().toList();
            appender = new AbstractAppender("yolt-gametest-errors", null, null, false, Property.EMPTY_ARRAY) {
                @Override
                public void append(LogEvent event) {
                    if (LOGGERS.contains(event.getLoggerName()) && event.getLevel().isMoreSpecificThan(Level.ERROR)) {
                        // Log4j can reuse the event it hands over, so a copy is kept
                        errors.add(event.toImmutable());
                    }
                }
            };
        }

        static LogWatch start() {
            LogWatch watch = new LogWatch();
            watch.appender.start();
            for (LoggerConfig config : watch.configs) {
                config.addAppender(watch.appender, null, null);
            }
            return watch;
        }

        void stop() {
            for (LoggerConfig config : configs) {
                config.removeAppender(appender.getName());
            }
            appender.stop();
        }

        List<String> messages() {
            return errors.stream().map(event -> event.getMessage().getFormattedMessage()).toList();
        }

        List<LogEvent> events() {
            return List.copyOf(errors);
        }
    }
}
