package bobbynooby.dev.gametest;

import bobbynooby.dev.bodies.PlayerBody;
import bobbynooby.dev.config.HardcoreConfig;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.hardcore.BloodMoon;
import bobbynooby.dev.hardcore.HardcoreStates;
import bobbynooby.dev.hardcore.MobBoosts;
import bobbynooby.dev.hardcore.Status;
import bobbynooby.dev.world.Sleep;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LocalMobCapCalculator;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

// One blood moon runs per server, so the tests that start one and stop it again within their first tick share an
// environment, and each test that waits across ticks has a batch of its own. Those drive the night through a stand-in
// clock, since every test of a batch shares the world's real one, and pick days past any saved blood moon so the roll
// and the minimum break never stand in their way. Every test leaves the blood moon stopped.
public class HardcoreBloodMoonGameTests {
    // Every environment has a billion grace days, so no night the world's real clock reaches ever rolls
    private static final String MOON = "you-only-live-twice-gametest:hardcore_blood_moon";
    // Every night past the grace days rolls, with no break between
    private static final String CLOCK = "you-only-live-twice-gametest:hardcore_blood_moon_clock";
    private static final String WARNING = "you-only-live-twice-gametest:hardcore_blood_moon_warning";
    // The same, with a night of 100 ticks
    private static final String FROZEN = "you-only-live-twice-gametest:hardcore_blood_moon_frozen";
    private static final long DAY = 24000L;
    private static final BlockPos PLAYER_AT = new BlockPos(1, 0, 1);
    private static final BlockPos SECOND_PLAYER_AT = new BlockPos(2, 0, 1);
    private static final BlockPos MOB_AT = new BlockPos(4, 0, 4);
    private static final BlockPos PLAYER_KILL_AT = new BlockPos(1, 0, 6);
    private static final BlockPos FALL_KILL_AT = new BlockPos(5, 0, 6);
    private static final BlockPos UNMARKED_AT = new BlockPos(6, 0, 3);

    // A zombie's 20 health times 1.25, and times 1.5 as well with tougher mobs on
    @GameTest(environment = MOON)
    public void blood2MobsSpawnedDuringItAreMarked(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        HardcoreConfig.TougherMobsSection tougher = Settings.get().hardcore.tougherMobs;
        boolean tougherBefore = tougher.enabled;
        List<Entity> spawned = new ArrayList<>();
        try {
            helper.assertValueEqual(level.dimension(), Level.OVERWORLD, "the test level");
            // Set and put back within this tick
            tougher.enabled = false;
            BloodMoon.start(server);
            helper.assertTrue(BloodMoon.active(), "no blood moon after the start");
            Mob zombie = HardcoreMobsGameTests.create(helper, spawned, EntityTypes.ZOMBIE, EntitySpawnReason.NATURAL, MOB_AT);
            assertMarked(helper, zombie, 25.0F, "with tougher mobs off");
            tougher.enabled = true;
            Mob tough = HardcoreMobsGameTests.create(helper, spawned, EntityTypes.ZOMBIE, EntitySpawnReason.NATURAL, MOB_AT);
            assertMarked(helper, tough, 37.5F, "with tougher mobs on");
            helper.assertTrue(tough.entityTags().contains(MobBoosts.TOUGH), "the zombie with tougher mobs on is not tough");
            tougher.enabled = false;

            // Loaded again, as each time its chunk starts ticking: still one boost
            MobBoosts.onLoad(zombie, level);
            assertMarked(helper, zombie, 25.0F, "after another load");
            helper.assertValueEqual(HardcoreMobsGameTests.attribute(zombie, Attributes.MAX_HEALTH).getModifiers().size(), 1,
                    "max health modifiers after another load");

            BloodMoon.stop(server);
            Mob after = HardcoreMobsGameTests.create(helper, spawned, EntityTypes.ZOMBIE, EntitySpawnReason.NATURAL, MOB_AT);
            helper.assertFalse(after.entityTags().contains(MobBoosts.BLOOD_MOON_MARK), "a zombie from after the end is marked");
            helper.assertValueEqual(after.getMaxHealth(), 20.0F, "max health of a zombie from after the end");
            // Marked for life
            helper.assertTrue(zombie.entityTags().contains(MobBoosts.BLOOD_MOON_MARK), "the mark went with the blood moon");
        } finally {
            BloodMoon.stop(server);
            tougher.enabled = tougherBefore;
            spawned.forEach(Entity::discard);
        }
        helper.succeed();
    }

    // An evoker, whose own loot table always drops exactly one totem a roll, so the totems count the rolls
    @GameTest(environment = MOON)
    public void blood3ExtraLootOnlyOnPlayerKills(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        List<Entity> spawned = new ArrayList<>();
        ServerPlayer killer = null;
        try {
            killer = TestPlayers.join(helper, "MoonHunter", GameType.SURVIVAL, PLAYER_AT);
            BloodMoon.start(server);
            Mob byPlayer = HardcoreMobsGameTests.create(helper, spawned, EntityTypes.EVOKER, EntitySpawnReason.NATURAL,
                    PLAYER_KILL_AT);
            Mob byFall = HardcoreMobsGameTests.create(helper, spawned, EntityTypes.EVOKER, EntitySpawnReason.NATURAL,
                    FALL_KILL_AT);
            helper.assertTrue(byPlayer.entityTags().contains(MobBoosts.BLOOD_MOON_MARK), "the evoker is not marked");
            helper.assertTrue(byFall.entityTags().contains(MobBoosts.BLOOD_MOON_MARK), "the second evoker is not marked");
            BloodMoon.stop(server);
            Mob unmarked = HardcoreMobsGameTests.create(helper, spawned, EntityTypes.EVOKER, EntitySpawnReason.NATURAL,
                    UNMARKED_AT);

            // Before the helmet goes on: worn gear that can drop adds XP of its own, at random
            int vanillaXp = unmarked.getExperienceReward(level, killer);
            helper.assertTrue(vanillaXp > 0, "an evoker gives no XP");
            helper.assertValueEqual(byPlayer.getExperienceReward(level, killer), vanillaXp * 2, "XP of a marked evoker");

            // Stands for an item it picked up, which vanilla always drops, and only from the equipment path
            byPlayer.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            byPlayer.setGuaranteedDrop(EquipmentSlot.HEAD);
            helper.assertTrue(byPlayer.hurtServer(level, level.damageSources().playerAttack(killer), 1000.0F),
                    "the player's hit did not land");
            helper.assertTrue(byPlayer.isDeadOrDying(), "the evoker survived the player");
            helper.assertValueEqual(dropped(helper, Items.TOTEM_OF_UNDYING, PLAYER_KILL_AT), 2, "totems from a player kill");
            helper.assertValueEqual(dropped(helper, Items.IRON_HELMET, PLAYER_KILL_AT), 1, "picked-up helmets dropped");

            helper.assertTrue(byFall.hurtServer(level, level.damageSources().fall(), 1000.0F), "the fall did not land");
            helper.assertTrue(byFall.isDeadOrDying(), "the evoker survived the fall");
            helper.assertValueEqual(dropped(helper, Items.TOTEM_OF_UNDYING, FALL_KILL_AT), 1, "totems from a fall");
        } finally {
            BloodMoon.stop(server);
            if (killer != null) {
                TestPlayers.leave(killer);
            }
            spawned.forEach(Entity::discard);
            for (BlockPos at : List.of(PLAYER_KILL_AT, FALL_KILL_AT)) {
                helper.getEntities(EntityTypes.ITEM, at, 2.0).forEach(Entity::discard);
                helper.getEntities(EntityTypes.EXPERIENCE_ORB, at, 2.0).forEach(Entity::discard);
            }
        }
        helper.succeed();
    }

    // Drives the night with a stand-in clock: the start, halfway, a restart, then dawn
    @GameTest(environment = CLOCK, maxTicks = 200)
    public void blood4BarDrainsAndARestartCarriesOn(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        HardcoreConfig.BloodMoonSection config = Settings.get().hardcore.bloodMoon;
        long day = freshDay();
        long[] clock = {day * DAY + config.startTick};
        Runnable realClock = BloodMoon.useClockForTesting(() -> clock[0]);
        List<ServerPlayer> players = new ArrayList<>();
        Runnable cleanUp = BodyTestKit.once(() -> {
            BloodMoon.stop(server);
            realClock.run();
            players.forEach(TestPlayers::leave);
        });
        helper.runBeforeTestEnd(cleanUp);
        int[] mark = {0};
        try {
            ServerPlayer player = TestPlayers.join(helper, "MoonWatcher", GameType.SURVIVAL, PLAYER_AT);
            players.add(player);
            helper.startSequence()
                    .thenWaitUntil(() -> helper.assertTrue(BloodMoon.active(), "no blood moon at startTick"))
                    .thenExecute(() -> {
                        helper.assertValueEqual(BloodMoon.barProgress().orElse(-1.0), 1.0, "the bar at the start");
                        helper.assertValueEqual(HardcoreStates.world(BloodMoon.LAST_DAY), OptionalLong.of(day), "the saved day");
                        clock[0] = day * DAY + (config.startTick + config.endTick) / 2;
                    })
                    .thenWaitUntil(() -> helper.assertTrue(BloodMoon.barProgress().orElse(1.0) < 0.6,
                            "the bar did not drain by halfway: " + BloodMoon.barProgress()))
                    .thenExecute(() -> {
                        double halfway = BloodMoon.barProgress().orElse(-1.0);
                        helper.assertTrue(halfway > 0.4, "the bar drained too far by halfway: " + halfway);
                        mark[0] = TestPlayers.sentPackets(player).size();
                        BloodMoon.restartForTesting();
                        helper.assertFalse(BloodMoon.active(), "still up straight after the restart");
                    })
                    .thenWaitUntil(() -> helper.assertTrue(BloodMoon.active(), "not picked up again after the restart"))
                    .thenIdle(5)
                    .thenExecute(() -> {
                        helper.assertValueEqual(chatLines(player, mark[0]), List.of(BloodMoon.UP_LINE),
                                "lines after the restart");
                        double after = BloodMoon.barProgress().orElse(-1.0);
                        helper.assertTrue(after > 0.4 && after < 0.6, "the bar after the restart: " + after);
                        mark[0] = TestPlayers.sentPackets(player).size();
                        clock[0] = day * DAY + config.endTick;
                    })
                    .thenWaitUntil(() -> helper.assertFalse(BloodMoon.active(), "still up at endTick"))
                    .thenExecute(() -> {
                        helper.assertValueEqual(chatLines(player, mark[0]), List.of(BloodMoon.SET_LINE), "lines at dawn");
                        helper.assertTrue(BloodMoon.barProgress().isEmpty(), "the bar is still there at dawn");
                        cleanUp.run();
                    })
                    .thenSucceed();
        } catch (RuntimeException | Error e) {
            cleanUp.run();
            throw e;
        }
    }

    // With the clock stopped inside the night, it still ends after one night's worth of server ticks, and only once
    @GameTest(environment = FROZEN, maxTicks = 300)
    public void blood4FrozenTimeStillEnds(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        HardcoreConfig.BloodMoonSection config = Settings.get().hardcore.bloodMoon;
        long day = freshDay();
        long window = config.endTick - config.startTick;
        long[] clock = {day * DAY + config.startTick};
        Runnable realClock = BloodMoon.useClockForTesting(() -> clock[0]);
        Runnable cleanUp = BodyTestKit.once(() -> {
            BloodMoon.stop(server);
            realClock.run();
        });
        helper.runBeforeTestEnd(cleanUp);
        int[] seenAt = {0};
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(BloodMoon.active(), "no blood moon at startTick"))
                .thenExecute(() -> {
                    seenAt[0] = server.getTickCount();
                    clock[0] = day * DAY + config.startTick + 10;
                })
                .thenWaitUntil(() -> helper.assertFalse(BloodMoon.active(), "still up with the clock stopped"))
                .thenExecute(() -> helper.assertValueEqual((long) (server.getTickCount() - seenAt[0]), window,
                        "server ticks it lasted"))
                .thenIdle(5)
                .thenExecute(() -> {
                    helper.assertFalse(BloodMoon.active(), "it started again in the same night");
                    cleanUp.run();
                })
                .thenSucceed();
    }

    // The level's cap and a real player's are doubled; a player body's stays vanilla's
    @GameTest(environment = MOON)
    public void blood5NoBiggerCapAroundPlayerBodies(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer player = TestPlayers.join(helper, "CapNear", GameType.SURVIVAL, PLAYER_AT);
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = BodyTestKit.usePlayerBodies();
        try {
            BloodMoon.start(server);
            // Asked outside a spawn, with no level spawning, so vanilla's
            int vanilla = MobCategory.MONSTER.getMaxInstancesPerChunk();
            helper.assertValueEqual(BloodMoon.monsterCap(level, vanilla), vanilla * 2, "the level's monster cap");
            helper.assertValueEqual(BloodMoon.monsterCapNear(player, vanilla), vanilla * 2, "the cap near a real player");
            ChunkPos near = player.chunkPosition();
            helper.assertTrue(roomAfter(level, near, vanilla), "no room past vanilla's cap near a real player");
            helper.assertFalse(roomAfter(level, near, vanilla * 2), "room past twice the cap near a real player");

            PlayerBody body = BodyTestKit.leavePlayerBody(helper, player);
            helper.assertValueEqual(BloodMoon.monsterCapNear(body, vanilla), vanilla, "the cap near a player body");
            helper.assertTrue(roomAfter(level, body.chunkPosition(), vanilla - 1), "no room below vanilla's cap near a body");
            helper.assertFalse(roomAfter(level, body.chunkPosition(), vanilla), "room past vanilla's cap near a body");
        } finally {
            BloodMoon.stop(server);
            BodyTestKit.cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    @GameTest(environment = MOON)
    public void blood6SleepCantSkipItAndBossesAreNotMarked(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        YoltConfig.SleepSection sleep = Settings.get().sleep;
        HardcoreConfig.BloodMoonSection config = Settings.get().hardcore.bloodMoon;
        boolean noSkipBefore = sleep.noNightSkip;
        boolean sleepThroughBefore = config.canSleepThrough;
        List<Entity> spawned = new ArrayList<>();
        try {
            // Set and put back within this tick
            sleep.noNightSkip = false;
            config.canSleepThrough = false;
            helper.assertFalse(Sleep.blocksNightSkip(), "the night skip is blocked before the blood moon");
            BloodMoon.start(server);
            helper.assertTrue(Sleep.blocksNightSkip(), "the night skip is not blocked during the blood moon");
            config.canSleepThrough = true;
            helper.assertFalse(Sleep.blocksNightSkip(), "the night skip is blocked with canSleepThrough on");
            config.canSleepThrough = false;

            Mob wither = HardcoreMobsGameTests.create(helper, spawned, EntityTypes.WITHER, EntitySpawnReason.NATURAL, MOB_AT);
            helper.assertFalse(wither.entityTags().contains(MobBoosts.BLOOD_MOON_MARK), "the wither is marked");
            helper.assertTrue(HardcoreMobsGameTests.attribute(wither, Attributes.MAX_HEALTH).getModifier(MobBoosts.BLOOD_MOON) == null,
                    "the wither has a blood moon modifier");

            BloodMoon.stop(server);
            helper.assertFalse(Sleep.blocksNightSkip(), "the night skip is still blocked after the blood moon");
        } finally {
            BloodMoon.stop(server);
            sleep.noNightSkip = noSkipBefore;
            config.canSleepThrough = sleepThroughBefore;
            spawned.forEach(Entity::discard);
        }
        helper.succeed();
    }

    // The same packets for one player alone and for each of two, and the sound only on each one's own connection: a
    // sound played in the world at either player would reach the other too, who stands next to them
    @GameTest(environment = WARNING, maxTicks = 200)
    public void info1WarningAndBarAreTheSameForEveryone(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        HardcoreConfig.BloodMoonSection config = Settings.get().hardcore.bloodMoon;
        long firstDay = freshDay();
        long secondDay = firstDay + 1;
        long[] clock = {firstDay * DAY + config.warnTick};
        Runnable realClock = BloodMoon.useClockForTesting(() -> clock[0]);
        List<ServerPlayer> players = new ArrayList<>();
        Runnable cleanUp = BodyTestKit.once(() -> {
            BloodMoon.stop(server);
            realClock.run();
            players.forEach(TestPlayers::leave);
        });
        helper.runBeforeTestEnd(cleanUp);
        int[] marks = new int[2];
        List<List<String>> alone = new ArrayList<>();
        try {
            ServerPlayer first = TestPlayers.join(helper, "MoonAlone", GameType.SURVIVAL, PLAYER_AT);
            players.add(first);
            marks[0] = TestPlayers.sentPackets(first).size();
            helper.startSequence()
                    .thenWaitUntil(() -> helper.assertTrue(BloodMoon.tonight(), "no warning at warnTick"))
                    .thenExecute(() -> {
                        alone.add(moonPackets(first, marks[0]));
                        marks[0] = TestPlayers.sentPackets(first).size();
                        clock[0] = firstDay * DAY + config.startTick;
                    })
                    .thenWaitUntil(() -> helper.assertTrue(BloodMoon.active(), "no blood moon at startTick"))
                    .thenExecute(() -> {
                        alone.add(moonPackets(first, marks[0]));
                        assertWarning(helper, alone.get(0), "the player alone");
                        BloodMoon.stop(server);
                        ServerPlayer second = TestPlayers.join(helper, "MoonPair", GameType.SURVIVAL, SECOND_PLAYER_AT);
                        players.add(second);
                        marks[0] = TestPlayers.sentPackets(first).size();
                        marks[1] = TestPlayers.sentPackets(second).size();
                        clock[0] = secondDay * DAY + config.warnTick;
                    })
                    .thenWaitUntil(() -> helper.assertTrue(BloodMoon.tonight(), "no warning on the second night"))
                    .thenExecute(() -> {
                        for (int i = 0; i < 2; i++) {
                            List<String> warning = moonPackets(players.get(i), marks[i]);
                            helper.assertValueEqual(warning, alone.get(0), "warning packets of player " + (i + 1) + " of two");
                            marks[i] = TestPlayers.sentPackets(players.get(i)).size();
                        }
                        clock[0] = secondDay * DAY + config.startTick;
                    })
                    .thenWaitUntil(() -> helper.assertTrue(BloodMoon.active(), "no blood moon on the second night"))
                    .thenExecute(() -> {
                        for (int i = 0; i < 2; i++) {
                            helper.assertValueEqual(moonPackets(players.get(i), marks[i]), alone.get(1),
                                    "start packets of player " + (i + 1) + " of two");
                        }
                        cleanUp.run();
                    })
                    .thenSucceed();
        } catch (RuntimeException | Error e) {
            cleanUp.run();
            throw e;
        }
    }

    @GameTest(environment = MOON)
    public void bloodCommandsStartStopAndReport(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TestPlayers.join(helper, "MoonCommands", GameType.SURVIVAL, PLAYER_AT);
        try {
            helper.assertValueEqual(CommandGameTests.run(server, "yolt bloodmoon status"), List.of("No blood moon is up."),
                    "status before");
            int mark = TestPlayers.sentPackets(player).size();
            helper.assertValueEqual(CommandGameTests.run(server, "yolt bloodmoon start"), List.of("Started a blood moon."),
                    "start");
            helper.assertTrue(BloodMoon.active(), "no blood moon after the start");
            helper.assertValueEqual(CommandGameTests.run(server, "yolt bloodmoon start"), List.of("A blood moon is up already."),
                    "a second start");
            helper.assertValueEqual(CommandGameTests.run(server, "yolt bloodmoon status"),
                    List.of("A blood moon is up, with 100% of it left."), "status during it");
            helper.assertTrue(Status.lines(player).stream().anyMatch(line -> line.getString().equals("Blood moon tonight")),
                    "no status line during it");

            helper.assertValueEqual(CommandGameTests.run(server, "yolt bloodmoon stop"), List.of("Stopped the blood moon."),
                    "stop");
            helper.assertFalse(BloodMoon.active(), "still up after the stop");
            helper.assertTrue(BloodMoon.barProgress().isEmpty(), "the bar is still there after the stop");
            List<String> sent = moonPackets(player, mark);
            helper.assertTrue(sent.stream().anyMatch(line -> line.startsWith("bar add Blood Moon")), "no bar was sent: " + sent);
            helper.assertValueEqual(sent.get(sent.size() - 2), "bar remove", "the bar was not taken off: " + sent);
            helper.assertValueEqual(sent.get(sent.size() - 1), "chat " + BloodMoon.SET_LINE, "the last line: " + sent);
            helper.assertValueEqual(CommandGameTests.run(server, "yolt bloodmoon stop"), List.of("No blood moon is up."),
                    "a second stop");
            helper.assertTrue(Status.lines(player).stream().noneMatch(line -> line.getString().equals("Blood moon tonight")),
                    "the status line stayed after the stop");
        } finally {
            BloodMoon.stop(server);
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // Past the grace days, which keep the world's real clock from ever rolling a night, and past any blood moon saved by
    // an earlier test, begun or ended, so the minimum break never stops the roll and no night counts as over already
    private static long freshDay() {
        long last = HardcoreStates.world(BloodMoon.LAST_DAY).orElse(0L);
        long ended = HardcoreStates.world(BloodMoon.ENDED_DAY).orElse(0L);
        long grace = Settings.get().hardcore.bloodMoon.graceDays;
        return Math.max(Math.max(last, ended), grace) + 100L;
    }

    private static void assertMarked(GameTestHelper helper, Mob mob, float maxHealth, String when) {
        helper.assertTrue(mob.entityTags().contains(MobBoosts.BLOOD_MOON_MARK), "not marked " + when);
        helper.assertValueEqual(mob.getMaxHealth(), maxHealth, "max health " + when);
        helper.assertValueEqual(mob.getHealth(), maxHealth, "health " + when);
    }

    private static void assertWarning(GameTestHelper helper, List<String> warning, String who) {
        helper.assertTrue(warning.contains("title " + BloodMoon.WARNING_TITLE), "no warning title for " + who + ": " + warning);
        helper.assertTrue(warning.contains("chat " + BloodMoon.WARNING_LINE), "no warning line for " + who + ": " + warning);
        helper.assertValueEqual(warning.stream().filter(line -> line.startsWith("sound ")).count(), 1L,
                "warning sounds for " + who + ": " + warning);
    }

    // Room for one more monster after this many near the chunk, on a fresh count as each spawn tick makes
    private static boolean roomAfter(ServerLevel level, ChunkPos chunk, int monsters) {
        LocalMobCapCalculator caps = new LocalMobCapCalculator(level.getChunkSource().chunkMap);
        for (int i = 0; i < monsters; i++) {
            caps.addMob(chunk, MobCategory.MONSTER);
        }
        return caps.canSpawn(MobCategory.MONSTER, chunk);
    }

    private static int dropped(GameTestHelper helper, Item item, BlockPos at) {
        int count = 0;
        for (ItemEntity entity : helper.getEntities(EntityTypes.ITEM, at, 2.0)) {
            if (entity.getItem().is(item)) {
                count += entity.getItem().getCount();
            }
        }
        return count;
    }

    private static List<String> chatLines(ServerPlayer player, int from) {
        return moonPackets(player, from).stream()
                .filter(line -> line.startsWith("chat "))
                .map(line -> line.substring("chat ".length()))
                .toList();
    }

    // The packets a blood moon sends, as text, leaving out the bar's progress, which depends on the tick it lands in
    private static List<String> moonPackets(ServerPlayer player, int from) {
        List<Packet<?>> sent = TestPlayers.sentPackets(player);
        List<String> described = new ArrayList<>();
        for (Packet<?> packet : sent.subList(from, sent.size())) {
            if (packet instanceof ClientboundSetTitleTextPacket title) {
                described.add("title " + title.text().getString());
            } else if (packet instanceof ClientboundSetTitlesAnimationPacket) {
                described.add("title times");
            } else if (packet instanceof ClientboundSystemChatPacket chat && !chat.overlay()) {
                described.add("chat " + chat.content().getString());
            } else if (packet instanceof ClientboundSoundPacket sound) {
                described.add("sound " + sound.getSound().getRegisteredName() + " " + sound.getSource());
            } else if (packet instanceof ClientboundBossEventPacket bar) {
                bar.dispatch(new ClientboundBossEventPacket.Handler() {
                    @Override
                    public void add(UUID id, Component name, float progress, BossEvent.BossBarColor color,
                                    BossEvent.BossBarOverlay overlay, boolean darkenScreen, boolean playMusic,
                                    boolean createWorldFog) {
                        described.add("bar add " + name.getString() + " " + progress + " " + color + " " + overlay
                                + " darken " + darkenScreen + " music " + playMusic + " fog " + createWorldFog);
                    }

                    @Override
                    public void remove(UUID id) {
                        described.add("bar remove");
                    }
                });
            }
        }
        return described;
    }
}
