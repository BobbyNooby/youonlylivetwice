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
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LocalMobCapCalculator;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
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
    // The same, for the warning in the config's words, since each batch drives its own stand-in clock
    private static final String WORDS = "you-only-live-twice-gametest:hardcore_blood_moon_words";
    // The same, with a night of 100 ticks
    private static final String FROZEN = "you-only-live-twice-gametest:hardcore_blood_moon_frozen";
    // Every night past the grace days rolls, each in a batch of its own for its own stand-in clock
    private static final String STATUS = "you-only-live-twice-gametest:hardcore_blood_moon_status";
    private static final String STOPPED = "you-only-live-twice-gametest:hardcore_blood_moon_stopped";
    private static final String REWARN = "you-only-live-twice-gametest:hardcore_blood_moon_rewarn";
    private static final String SETBACK = "you-only-live-twice-gametest:hardcore_blood_moon_setback";
    // The same, with the warning's title and sound off
    private static final String QUIET_WARNING = "you-only-live-twice-gametest:hardcore_blood_moon_quiet_warning";
    // Never a night that rolls, in a batch of its own, since its test turns the blood moon off across ticks
    private static final String OFF_LIVE = "you-only-live-twice-gametest:hardcore_blood_moon_off_live";
    // Never a night that rolls, with the night skip allowed, for a sleeper on the real clock
    private static final String SLEEP = "you-only-live-twice-gametest:hardcore_blood_moon_sleep";
    // Every switch off, apart from the batch that checks the whole layer off leaves the store's file alone
    private static final String ALL_OFF = "you-only-live-twice-gametest:hardcore_all_off_commands";
    private static final long DAY = 24000L;
    // Somewhere in the Nether for a player to be, the same spot the coordinate offset's portal test uses
    private static final Vec3 NETHER_SPOT = new Vec3(37.5, 64.0, -50.5);
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

            // A portal makes a copy of a mob that lived before, which is no new spawn
            Mob travelled = HardcoreMobsGameTests.create(helper, spawned, EntityTypes.ZOMBIE,
                    EntitySpawnReason.DIMENSION_TRAVEL, MOB_AT);
            helper.assertFalse(travelled.entityTags().contains(MobBoosts.BLOOD_MOON_MARK), "a zombie through a portal is marked");
            helper.assertValueEqual(travelled.getMaxHealth(), 20.0F, "max health of a zombie through a portal");

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

    // The level's cap and a real player's are doubled; a player body's stays vanilla's. Relies on no other player or
    // player body standing within 128 blocks of this structure in this tick, since any of them would add room of its own.
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
    // sound played in the world at either player would reach the other too, who stands next to them. Relies on nothing
    // else sending these players a chat line, title or sound in the ticks it watches.
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
                        // Warned and not yet up: a storm lets players into bed now, and their sleep must not skip it
                        helper.assertFalse(BloodMoon.active(), "the blood moon is up at its warning");
                        assertSkipBlockedUnlessSleptThrough(helper, "between the warning and the start");
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

    // The warning's title and line in the config's words
    @GameTest(environment = WORDS, maxTicks = 100)
    public void info1WarningTakesTheConfigsWords(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        HardcoreConfig.BloodMoonSection config = Settings.get().hardcore.bloodMoon;
        YoltConfig.MessagesSection texts = Settings.get().messages;
        String titleBefore = texts.bloodMoonWarningTitle;
        String lineBefore = texts.bloodMoonWarningLine;
        long[] clock = {freshDay() * DAY + config.warnTick};
        Runnable realClock = BloodMoon.useClockForTesting(() -> clock[0]);
        List<ServerPlayer> players = new ArrayList<>();
        Runnable cleanUp = BodyTestKit.once(() -> {
            texts.bloodMoonWarningTitle = titleBefore;
            texts.bloodMoonWarningLine = lineBefore;
            BloodMoon.stop(server);
            realClock.run();
            // A warning saves no day, so the next batch would pick this same day and find it warned already
            BloodMoon.restartForTesting();
            players.forEach(TestPlayers::leave);
        });
        helper.runBeforeTestEnd(cleanUp);
        try {
            texts.bloodMoonWarningTitle = "\u00a74Look up.";
            texts.bloodMoonWarningLine = "Tonight it bleeds.";
            ServerPlayer player = TestPlayers.join(helper, "MoonWarned", GameType.SURVIVAL, PLAYER_AT);
            players.add(player);
            int mark = TestPlayers.sentPackets(player).size();
            helper.startSequence()
                    .thenWaitUntil(() -> helper.assertTrue(BloodMoon.tonight(), "no warning at warnTick"))
                    .thenExecute(() -> {
                        List<String> warning = moonPackets(player, mark);
                        helper.assertTrue(warning.contains("title \u00a74Look up."), "no warning title in the config's words: " + warning);
                        helper.assertTrue(warning.contains("chat Tonight it bleeds."), "no warning line in the config's words: " + warning);
                        cleanUp.run();
                    })
                    .thenSucceed();
        } catch (RuntimeException | Error e) {
            cleanUp.run();
            throw e;
        }
    }

    // The lines a blood moon sends and its bar's title in the config's words: risen at the start, up for a player who
    // arrives during it, set at the end. Relies on nothing else sending these players a chat line or a boss bar.
    @GameTest(environment = MOON)
    public void bloodLinesAndBarTakeTheConfigsWords(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.MessagesSection texts = Settings.get().messages;
        String risenBefore = texts.bloodMoonRisenLine;
        String upBefore = texts.bloodMoonUpLine;
        String setBefore = texts.bloodMoonSetLine;
        String barBefore = texts.bloodMoonBarTitle;
        List<ServerPlayer> players = new ArrayList<>();
        try {
            texts.bloodMoonRisenLine = "\u00a74Red.";
            texts.bloodMoonUpLine = "Still red.";
            texts.bloodMoonSetLine = "Pale again.";
            texts.bloodMoonBarTitle = "\u00a7cThe Red";
            ServerPlayer first = TestPlayers.join(helper, "MoonWords", GameType.SURVIVAL, PLAYER_AT);
            players.add(first);
            int mark = TestPlayers.sentPackets(first).size();
            BloodMoon.start(server);
            ServerPlayer late = TestPlayers.join(helper, "MoonWordsLate", GameType.SURVIVAL, SECOND_PLAYER_AT);
            players.add(late);
            BloodMoon.stop(server);
            List<String> sent = moonPackets(first, mark);
            helper.assertTrue(sent.contains("chat \u00a74Red."), "no risen line in the config's words: " + sent);
            helper.assertTrue(sent.stream().anyMatch(line -> line.startsWith("bar add \u00a7cThe Red ")), "no bar with the config's title: " + sent);
            helper.assertValueEqual(sent.getLast(), "chat Pale again.", "the last line: " + sent);
            helper.assertTrue(chatLines(late, 0).contains("Still red."), "no up line for the late player: " + chatLines(late, 0));
        } finally {
            texts.bloodMoonRisenLine = risenBefore;
            texts.bloodMoonUpLine = upBefore;
            texts.bloodMoonSetLine = setBefore;
            texts.bloodMoonBarTitle = barBefore;
            BloodMoon.stop(server);
            players.forEach(TestPlayers::leave);
        }
        helper.succeed();
    }

    // barColour and darkenSky are read as a blood moon starts: changed while one is up, its bar stays as it began, even
    // for a player who arrives then, and the next blood moon takes them. Red is the colour it always had.
    @GameTest(environment = MOON)
    public void blood4BarColourAndSkyWaitForTheNextBloodMoon(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        HardcoreConfig.BloodMoonSection config = Settings.get().hardcore.bloodMoon;
        HardcoreConfig.BarColour colourBefore = config.barColour;
        boolean darkenBefore = config.darkenSky;
        List<ServerPlayer> players = new ArrayList<>();
        try {
            ServerPlayer first = TestPlayers.join(helper, "MoonColour", GameType.SURVIVAL, PLAYER_AT);
            players.add(first);
            int mark = TestPlayers.sentPackets(first).size();
            BloodMoon.start(server);
            assertBar(helper, moonPackets(first, mark), "RED", true, "the first blood moon");
            config.barColour = HardcoreConfig.BarColour.BLUE;
            config.darkenSky = false;
            ServerPlayer late = TestPlayers.join(helper, "MoonColourLate", GameType.SURVIVAL, SECOND_PLAYER_AT);
            players.add(late);
            assertBar(helper, moonPackets(late, 0), "RED", true, "a player arriving after the change");
            BloodMoon.stop(server);

            mark = TestPlayers.sentPackets(first).size();
            BloodMoon.start(server);
            assertBar(helper, moonPackets(first, mark), "BLUE", false, "the next blood moon");
            BloodMoon.stop(server);
        } finally {
            config.barColour = colourBefore;
            config.darkenSky = darkenBefore;
            BloodMoon.stop(server);
            players.forEach(TestPlayers::leave);
        }
        helper.succeed();
    }

    private static void assertBar(GameTestHelper helper, List<String> sent, String colour, boolean darken, String when) {
        List<String> added = sent.stream().filter(line -> line.startsWith("bar add ")).toList();
        helper.assertValueEqual(added.size(), 1, "bars added for " + when + ": " + sent);
        helper.assertTrue(added.getFirst().contains(" " + colour + " PROGRESS darken " + darken + " "),
                "the bar for " + when + ": " + added.getFirst());
    }

    // Relies on nothing else sending this player a chat line or a boss bar between its join and the stop
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

    // On a server that lets players sleep the night away, a lone sleeper still can't skip a blood moon: vanilla's own
    // sleep, driven tick by tick on the real clock at midnight, leaves the clock running. With canSleepThrough on, the
    // same sleep skips to morning.
    @GameTest(environment = SLEEP, maxTicks = 200)
    public void bloodMoonNightCannotBeSleptThrough(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        HardcoreConfig.BloodMoonSection config = Settings.get().hardcore.bloodMoon;
        boolean sleepThroughBefore = config.canSleepThrough;
        long savedTicks = SleepGameTests.clockTicks(helper);
        BlockPos head = SleepGameTests.placeBed(helper);
        ServerPlayer sleeper = TestPlayers.join(helper, "MoonSleeper", GameType.SURVIVAL, SleepGameTests.NEXT_TO_BED);
        Runnable cleanUp = BodyTestKit.once(() -> {
            config.canSleepThrough = sleepThroughBefore;
            BloodMoon.stop(server);
            SleepGameTests.wakeAndLeave(sleeper);
            server.clockManager().setTotalTicks(helper.getLevel().dimensionType().defaultClock().orElseThrow(), savedTicks);
            helper.getLevel().updateSkyBrightness();
        });
        helper.runBeforeTestEnd(cleanUp);
        long[] asleepAt = new long[1];
        try {
            helper.assertFalse(Settings.get().sleep.noNightSkip, "the environment left the night skip blocked");
            config.canSleepThrough = false;
            SleepGameTests.toMidnight(helper);
            BloodMoon.start(server);
            helper.assertTrue(BloodMoon.active(), "no blood moon after the start");
            SleepGameTests.useBed(helper, sleeper, head);
            helper.assertTrue(sleeper.isSleeping(), "the player could not get into bed at midnight");
            asleepAt[0] = SleepGameTests.clockTicks(helper);
            helper.onEachTick(() -> {
                if (!sleeper.isRemoved()) {
                    sleeper.doTick();
                }
            });
            helper.startSequence()
                    .thenIdle(120)
                    .thenExecute(guarded(cleanUp, () -> {
                        long moved = SleepGameTests.clockTicks(helper) - asleepAt[0];
                        helper.assertTrue(Math.abs(moved - 120) <= 5, "the clock moved " + moved + " ticks in 120 of sleep");
                        helper.assertTrue(sleeper.isSleepingLongEnough(), "the player hasn't slept long enough to skip");
                        helper.assertTrue(BloodMoon.active(), "the blood moon ended under the sleeper");
                        config.canSleepThrough = true;
                    }))
                    .thenWaitUntil(() -> helper.assertTrue(SleepGameTests.clockTicks(helper) - asleepAt[0] > 1000,
                            "the night wasn't skipped with canSleepThrough on"))
                    .thenExecute(cleanUp)
                    .thenSucceed();
        } catch (RuntimeException | Error e) {
            cleanUp.run();
            throw e;
        }
    }

    @GameTest(environment = ALL_OFF)
    public void bloodCommandsRefuseWhileOff(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        for (String command : List.of("start", "stop", "status")) {
            int[] result = {-1};
            helper.assertValueEqual(CommandGameTests.run(server, "yolt bloodmoon " + command, value -> result[0] = value),
                    List.of("The blood moon is turned off."), "the reply to " + command);
            helper.assertValueEqual(result[0], 0, "the result of " + command);
            helper.assertFalse(BloodMoon.active(), "a blood moon is up after " + command);
        }
        helper.succeed();
    }

    // From the warning the status says one rises tonight; once up it counts down what is left, here from halfway
    @GameTest(environment = STATUS, maxTicks = 200)
    public void bloodStatusRisesTonightThenCountsDown(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        HardcoreConfig.BloodMoonSection config = Settings.get().hardcore.bloodMoon;
        long day = freshDay();
        long[] clock = {day * DAY + config.warnTick + 1};
        Runnable realClock = BloodMoon.useClockForTesting(() -> clock[0]);
        Runnable cleanUp = BodyTestKit.once(() -> {
            BloodMoon.stop(server);
            realClock.run();
            BloodMoon.restartForTesting();
        });
        helper.runBeforeTestEnd(cleanUp);
        try {
            helper.startSequence()
                    .thenWaitUntil(() -> helper.assertTrue(BloodMoon.tonight(), "no warning past warnTick"))
                    .thenExecute(guarded(cleanUp, () -> {
                        helper.assertValueEqual(CommandGameTests.run(server, "yolt bloodmoon status"),
                                List.of("A blood moon rises tonight."), "the status after the warning");
                        clock[0] = day * DAY + config.startTick;
                    }))
                    .thenWaitUntil(() -> helper.assertTrue(BloodMoon.active(), "no blood moon at startTick"))
                    .thenExecute(() -> clock[0] = day * DAY + (config.startTick + config.endTick) / 2)
                    .thenWaitUntil(() -> helper.assertValueEqual(CommandGameTests.run(server, "yolt bloodmoon status"),
                            List.of("A blood moon is up, with 50% of it left."), "the status halfway"))
                    .thenExecute(cleanUp)
                    .thenSucceed();
        } catch (RuntimeException | Error e) {
            cleanUp.run();
            throw e;
        }
    }

    // armourChance and enchantChance of 1 are floors under vanilla's rolls: a skeleton spawned during one always wears
    // armour, all of it enchanted, whatever the local difficulty. Outside one, or at a chance of 0, vanilla's multiplier
    // stands. A skeleton, since a zombie's own spawn can bring a chicken jockey along.
    @GameTest(environment = MOON)
    public void blood2GearFloorsArmourAndEnchants(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        HardcoreConfig.BloodMoonSection config = Settings.get().hardcore.bloodMoon;
        double armourBefore = config.armourChance;
        double enchantBefore = config.enchantChance;
        List<Entity> spawned = new ArrayList<>();
        try {
            // Set and put back within this tick
            config.armourChance = 1.0;
            config.enchantChance = 1.0;
            BloodMoon.start(server);
            Mob skeleton = spawnNaturally(helper, spawned, EntityTypes.SKELETON, MOB_AT);
            List<ItemStack> armour = new ArrayList<>();
            for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
                if (!skeleton.getItemBySlot(slot).isEmpty()) {
                    armour.add(skeleton.getItemBySlot(slot));
                }
            }
            helper.assertFalse(armour.isEmpty(), "a skeleton spawned with armourChance 1 wears no armour");
            for (ItemStack piece : armour) {
                helper.assertTrue(piece.isEnchanted(), "armour not enchanted with enchantChance 1: " + piece);
            }
            // 0.35 of vanilla's 0.15 base, at the lowest local difficulty
            config.armourChance = 0.35;
            helper.assertTrue(Math.abs(0.15F * BloodMoon.armourMultiplier(skeleton, 0.0F) - 0.35F) < 1e-6F,
                    "the armour chance at 0.35: " + 0.15F * BloodMoon.armourMultiplier(skeleton, 0.0F));
            config.armourChance = 0.0;
            helper.assertValueEqual(BloodMoon.armourMultiplier(skeleton, 0.5F), 0.5F, "the multiplier at a chance of 0");
            config.armourChance = 1.0;
            BloodMoon.stop(server);
            helper.assertValueEqual(BloodMoon.armourMultiplier(skeleton, 0.5F), 0.5F, "the multiplier with no blood moon up");
        } finally {
            BloodMoon.stop(server);
            config.armourChance = armourBefore;
            config.enchantChance = enchantBefore;
            spawned.forEach(Entity::discard);
        }
        helper.succeed();
    }

    // A creeper spawned during one is charged with chargedCreeperChance 1, never with 0
    @GameTest(environment = MOON)
    public void blood2ChargedCreepersFollowTheirChance(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        HardcoreConfig.BloodMoonSection config = Settings.get().hardcore.bloodMoon;
        double chanceBefore = config.chargedCreeperChance;
        List<Entity> spawned = new ArrayList<>();
        try {
            // Set and put back within this tick
            BloodMoon.start(server);
            config.chargedCreeperChance = 1.0;
            Creeper charged = HardcoreMobsGameTests.create(helper, spawned, EntityTypes.CREEPER, EntitySpawnReason.NATURAL, MOB_AT);
            helper.assertTrue(charged.isPowered(), "a creeper spawned at a chance of 1 isn't charged");
            config.chargedCreeperChance = 0.0;
            Creeper plain = HardcoreMobsGameTests.create(helper, spawned, EntityTypes.CREEPER, EntitySpawnReason.NATURAL, MOB_AT);
            helper.assertTrue(plain.entityTags().contains(MobBoosts.BLOOD_MOON_MARK), "the second creeper isn't marked");
            helper.assertFalse(plain.isPowered(), "a creeper spawned at a chance of 0 is charged");
        } finally {
            BloodMoon.stop(server);
            config.chargedCreeperChance = chanceBefore;
            spawned.forEach(Entity::discard);
        }
        helper.succeed();
    }

    // Off takes the mark's boost off a mob the next time it loads, and its XP goes back to vanilla's, though the mark stays
    @GameTest(environment = MOON)
    public void blood2OffTakesTheMarkBoostOffAtTheNextLoad(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        HardcoreConfig.BloodMoonSection config = Settings.get().hardcore.bloodMoon;
        HardcoreConfig.TougherMobsSection tougher = Settings.get().hardcore.tougherMobs;
        boolean tougherBefore = tougher.enabled;
        boolean enabledBefore = config.enabled;
        List<Entity> spawned = new ArrayList<>();
        ServerPlayer killer = TestPlayers.join(helper, "MoonUnmarker", GameType.SURVIVAL, PLAYER_AT);
        try {
            // Set and put back within this tick
            tougher.enabled = false;
            BloodMoon.start(server);
            Mob marked = HardcoreMobsGameTests.create(helper, spawned, EntityTypes.ZOMBIE, EntitySpawnReason.NATURAL, MOB_AT);
            BloodMoon.stop(server);
            Mob unmarked = HardcoreMobsGameTests.create(helper, spawned, EntityTypes.ZOMBIE, EntitySpawnReason.NATURAL, MOB_AT);
            assertMarked(helper, marked, 25.0F, "before the switch went off");
            int vanillaXp = unmarked.getExperienceReward(level, killer);
            helper.assertValueEqual(marked.getExperienceReward(level, killer), vanillaXp * 2, "XP of the marked zombie while on");

            config.enabled = false;
            MobBoosts.onLoad(marked, level);
            helper.assertValueEqual(marked.getMaxHealth(), 20.0F, "max health loaded with the blood moon off");
            helper.assertTrue(HardcoreMobsGameTests.attribute(marked, Attributes.MAX_HEALTH).getModifier(MobBoosts.BLOOD_MOON) == null,
                    "the blood moon modifier stayed with it off");
            helper.assertValueEqual(marked.getExperienceReward(level, killer), vanillaXp, "XP of the marked zombie with it off");
            helper.assertTrue(marked.entityTags().contains(MobBoosts.BLOOD_MOON_MARK), "the mark itself went");
        } finally {
            config.enabled = enabledBefore;
            BloodMoon.stop(server);
            tougher.enabled = tougherBefore;
            spawned.forEach(Entity::discard);
            TestPlayers.leave(killer);
        }
        helper.succeed();
    }

    // Off means off: the next tick ends it with the bar taken down, no line at dawn, and no day saved as ended, so it
    // comes back on as it was
    @GameTest(environment = OFF_LIVE, maxTicks = 40)
    public void blood4TurningItOffEndsTheNightSilently(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        HardcoreConfig.BloodMoonSection config = Settings.get().hardcore.bloodMoon;
        boolean enabledBefore = config.enabled;
        // A day of its own on a stand-in clock: a night started on the real clock's day and never ended would be picked
        // up again as a restart's, by any later batch with the blood moon on while the real clock is still on that day
        long[] clock = {freshDay() * DAY + config.startTick};
        Runnable realClock = BloodMoon.useClockForTesting(() -> clock[0]);
        ServerPlayer player = TestPlayers.join(helper, "MoonSwitched", GameType.SURVIVAL, PLAYER_AT);
        Runnable cleanUp = BodyTestKit.once(() -> {
            // The real clock first, so the stand-in night isn't taken up again as the switch comes back on
            realClock.run();
            config.enabled = enabledBefore;
            BloodMoon.stop(server);
            TestPlayers.leave(player);
        });
        helper.runBeforeTestEnd(cleanUp);
        int[] mark = new int[1];
        OptionalLong endedBefore = HardcoreStates.world(BloodMoon.ENDED_DAY);
        try {
            BloodMoon.start(server);
            helper.assertTrue(BloodMoon.active(), "no blood moon after the start");
            mark[0] = TestPlayers.sentPackets(player).size();
            config.enabled = false;
            helper.startSequence()
                    .thenIdle(1)
                    .thenExecute(guarded(cleanUp, () -> {
                        helper.assertFalse(BloodMoon.active(), "still up a tick after the switch went off");
                        List<String> sent = moonPackets(player, mark[0]);
                        helper.assertTrue(sent.contains("bar remove"), "the bar wasn't taken down: " + sent);
                        helper.assertFalse(sent.contains("chat " + BloodMoon.SET_LINE), "dawn was announced: " + sent);
                        helper.assertValueEqual(HardcoreStates.world(BloodMoon.ENDED_DAY), endedBefore, "the day saved as ended");
                        cleanUp.run();
                    }))
                    .thenSucceed();
        } catch (RuntimeException | Error e) {
            cleanUp.run();
            throw e;
        }
    }

    // /yolt bloodmoon stop ends tonight's for good: with the clock still inside the night, it doesn't rise again
    @GameTest(environment = STOPPED, maxTicks = 200)
    public void blood4AStoppedNightDoesNotRiseAgain(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        HardcoreConfig.BloodMoonSection config = Settings.get().hardcore.bloodMoon;
        long day = freshDay();
        long[] clock = {day * DAY + config.startTick};
        Runnable realClock = BloodMoon.useClockForTesting(() -> clock[0]);
        Runnable cleanUp = BodyTestKit.once(() -> {
            BloodMoon.stop(server);
            realClock.run();
        });
        helper.runBeforeTestEnd(cleanUp);
        try {
            helper.startSequence()
                    .thenWaitUntil(() -> helper.assertTrue(BloodMoon.active(), "no blood moon at startTick"))
                    .thenExecute(guarded(cleanUp, () -> {
                        helper.assertValueEqual(CommandGameTests.run(server, "yolt bloodmoon stop"), List.of("Stopped the blood moon."),
                                "the stop");
                        clock[0] = day * DAY + config.startTick + 100;
                    }))
                    .thenIdle(40)
                    .thenExecute(guarded(cleanUp, () -> {
                        helper.assertFalse(BloodMoon.active(), "it rose again inside the stopped night");
                        cleanUp.run();
                    }))
                    .thenSucceed();
        } catch (RuntimeException | Error e) {
            cleanUp.run();
            throw e;
        }
    }

    // A restart after the warning but before dark warns once more, then the night rises as usual
    @GameTest(environment = REWARN, maxTicks = 200)
    public void blood4ARestartBeforeDarkWarnsAgain(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        HardcoreConfig.BloodMoonSection config = Settings.get().hardcore.bloodMoon;
        String risen = Settings.get().messages.bloodMoonRisenLine;
        long day = freshDay();
        long[] clock = {day * DAY + config.warnTick};
        Runnable realClock = BloodMoon.useClockForTesting(() -> clock[0]);
        List<ServerPlayer> players = new ArrayList<>();
        Runnable cleanUp = BodyTestKit.once(() -> {
            BloodMoon.stop(server);
            realClock.run();
            players.forEach(TestPlayers::leave);
        });
        helper.runBeforeTestEnd(cleanUp);
        int[] mark = new int[1];
        try {
            ServerPlayer player = TestPlayers.join(helper, "MoonRewarned", GameType.SURVIVAL, PLAYER_AT);
            players.add(player);
            mark[0] = TestPlayers.sentPackets(player).size();
            helper.startSequence()
                    .thenWaitUntil(() -> helper.assertTrue(chatLines(player, mark[0]).contains(BloodMoon.WARNING_LINE),
                            "no warning at warnTick"))
                    .thenExecute(guarded(cleanUp, () -> {
                        helper.assertTrue(BloodMoon.tonight(), "the warning didn't make it tonight");
                        mark[0] = TestPlayers.sentPackets(player).size();
                        BloodMoon.restartForTesting();
                        clock[0] = day * DAY + config.warnTick + 10;
                    }))
                    .thenWaitUntil(() -> helper.assertTrue(BloodMoon.tonight(), "no warning after the restart"))
                    .thenExecute(guarded(cleanUp, () -> {
                        helper.assertValueEqual(chatLines(player, mark[0]), List.of(BloodMoon.WARNING_LINE), "lines after the restart");
                        mark[0] = TestPlayers.sentPackets(player).size();
                        clock[0] = day * DAY + config.startTick;
                    }))
                    .thenWaitUntil(() -> helper.assertTrue(BloodMoon.active(), "no blood moon at startTick"))
                    .thenExecute(guarded(cleanUp, () -> {
                        helper.assertValueEqual(chatLines(player, mark[0]), List.of(risen), "lines at startTick");
                        cleanUp.run();
                    }))
                    .thenSucceed();
        } catch (RuntimeException | Error e) {
            cleanUp.run();
            throw e;
        }
    }

    // The bar is the overworld's: it comes off a player who leaves for the Nether, and goes back on, with one line, when
    // they come back
    @GameTest(environment = MOON)
    public void info1BarFollowsThePlayerOutAndBack(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel nether = server.getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "no Nether to go to");
        ServerPlayer player = TestPlayers.join(helper, "MoonTraveller", GameType.SURVIVAL, PLAYER_AT);
        try {
            BloodMoon.start(server);
            int mark = TestPlayers.sentPackets(player).size();
            player.teleport(new TeleportTransition(nether, NETHER_SPOT, Vec3.ZERO, 0.0F, 0.0F, Set.of(), TeleportTransition.DO_NOTHING));
            helper.assertTrue(player.level() == nether, "the player didn't reach the Nether");
            helper.assertValueEqual(moonPackets(player, mark), List.of("bar remove"), "packets on leaving for the Nether");
            mark = TestPlayers.sentPackets(player).size();
            player.teleport(new TeleportTransition(helper.getLevel(), helper.absoluteVec(Vec3.atBottomCenterOf(PLAYER_AT)), Vec3.ZERO,
                    0.0F, 0.0F, Set.of(), TeleportTransition.DO_NOTHING));
            List<String> back = moonPackets(player, mark);
            helper.assertValueEqual(back.stream().filter(line -> line.startsWith("bar add ")).count(), 1L, "bars added on the way back: " + back);
            helper.assertValueEqual(chatLines(player, mark), List.of(BloodMoon.UP_LINE), "lines on the way back");
        } finally {
            BloodMoon.stop(server);
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // With the title and the sound switched off an overworld player gets the chat line alone, and a player in the Nether
    // gets nothing at all
    @GameTest(environment = QUIET_WARNING, maxTicks = 100)
    public void info1WarningSkipsTheNetherAndHonoursItsSwitches(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel nether = server.getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "no Nether to go to");
        HardcoreConfig.BloodMoonSection config = Settings.get().hardcore.bloodMoon;
        long[] clock = {freshDay() * DAY + config.warnTick};
        List<ServerPlayer> players = new ArrayList<>();
        Runnable[] realClock = {() -> {}};
        Runnable cleanUp = BodyTestKit.once(() -> {
            BloodMoon.stop(server);
            realClock[0].run();
            // A warning saves no day, so the next batch would pick this same day and find it warned already
            BloodMoon.restartForTesting();
            players.forEach(TestPlayers::leave);
        });
        helper.runBeforeTestEnd(cleanUp);
        try {
            helper.assertFalse(config.warning.title || config.warning.sound, "the environment left the title or sound on");
            ServerPlayer here = TestPlayers.join(helper, "MoonQuietHere", GameType.SURVIVAL, PLAYER_AT);
            players.add(here);
            ServerPlayer away = TestPlayers.join(helper, "MoonQuietAway", GameType.SURVIVAL, SECOND_PLAYER_AT);
            players.add(away);
            away.teleport(new TeleportTransition(nether, NETHER_SPOT, Vec3.ZERO, 0.0F, 0.0F, Set.of(), TeleportTransition.DO_NOTHING));
            helper.assertTrue(away.level() == nether, "the second player didn't reach the Nether");
            int[] marks = {TestPlayers.sentPackets(here).size(), TestPlayers.sentPackets(away).size()};
            realClock[0] = BloodMoon.useClockForTesting(() -> clock[0]);
            helper.startSequence()
                    .thenWaitUntil(() -> helper.assertTrue(chatLines(here, marks[0]).contains(BloodMoon.WARNING_LINE),
                            "no warning at warnTick"))
                    .thenExecute(guarded(cleanUp, () -> {
                        helper.assertValueEqual(moonPackets(here, marks[0]), List.of("chat " + BloodMoon.WARNING_LINE),
                                "the warning in the overworld");
                        helper.assertValueEqual(moonPackets(away, marks[1]), List.of(), "the warning in the Nether");
                        cleanUp.run();
                    }))
                    .thenSucceed();
        } catch (RuntimeException | Error e) {
            cleanUp.run();
            throw e;
        }
    }

    // darkenSky and fog go on the bar as it is made; with bossBar off there is no bar, but the line still comes
    @GameTest(environment = MOON)
    public void info1BarFlagsFollowTheConfig(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        HardcoreConfig.BloodMoonSection config = Settings.get().hardcore.bloodMoon;
        boolean darkenBefore = config.darkenSky;
        boolean fogBefore = config.fog;
        boolean barBefore = config.bossBar;
        String risen = Settings.get().messages.bloodMoonRisenLine;
        ServerPlayer player = TestPlayers.join(helper, "MoonFlags", GameType.SURVIVAL, PLAYER_AT);
        try {
            // Set and put back within this tick
            config.darkenSky = false;
            config.fog = true;
            int mark = TestPlayers.sentPackets(player).size();
            BloodMoon.start(server);
            List<String> added = moonPackets(player, mark).stream().filter(line -> line.startsWith("bar add ")).toList();
            helper.assertValueEqual(added.size(), 1, "bars added: " + added);
            helper.assertTrue(added.getFirst().contains(" darken false ") && added.getFirst().endsWith(" fog true"),
                    "the bar's flags: " + added.getFirst());
            BloodMoon.stop(server);

            config.bossBar = false;
            mark = TestPlayers.sentPackets(player).size();
            BloodMoon.start(server);
            List<String> sent = moonPackets(player, mark);
            helper.assertTrue(sent.stream().noneMatch(line -> line.startsWith("bar add ")), "a bar with bossBar off: " + sent);
            helper.assertTrue(BloodMoon.barProgress().isEmpty(), "a bar's progress with bossBar off");
            helper.assertValueEqual(chatLines(player, mark), List.of(risen), "lines with bossBar off");
        } finally {
            BloodMoon.stop(server);
            config.darkenSky = darkenBefore;
            config.fog = fogBefore;
            config.bossBar = barBefore;
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // The cap goes up only for monsters in the overworld: the Nether's monsters and every other category stay vanilla,
    // while the overworld's monsters double, the check that this isn't vanilla everywhere
    @GameTest(environment = MOON)
    public void blood5NetherAndOtherCategoriesStayVanilla(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ServerLevel nether = server.getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "no Nether");
        try {
            BloodMoon.start(server);
            int vanilla = MobCategory.MONSTER.getMaxInstancesPerChunk();
            helper.assertValueEqual(BloodMoon.monsterCap(nether, vanilla), vanilla, "the Nether's monster cap");
            BloodMoon.spawning(level);
            helper.assertValueEqual(BloodMoon.spawnCap(MobCategory.MONSTER, vanilla), vanilla * 2, "the overworld's monster cap");
            for (MobCategory category : List.of(MobCategory.CREATURE, MobCategory.AMBIENT, MobCategory.WATER_CREATURE)) {
                int cap = category.getMaxInstancesPerChunk();
                helper.assertValueEqual(BloodMoon.spawnCap(category, cap), cap, "the overworld's " + category + " cap");
            }
            BloodMoon.spawning(nether);
            helper.assertValueEqual(BloodMoon.spawnCap(MobCategory.MONSTER, vanilla), vanilla, "the Nether's spawning cap");
        } finally {
            BloodMoon.spawning(null);
            BloodMoon.stop(server);
        }
        helper.succeed();
    }

    // damage multiplies a marked mob's hit on top of tougher mobs': 3 × 1.25 × 2 for a marked zombie, 3 × 1.25 for one
    // spawned after. Hits of 3 on Normal, which leaves a player's damage as dealt.
    @GameTest(environment = MOON)
    public void blood2MarkedDamageStacksOnTougher(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        HardcoreConfig.BloodMoonSection config = Settings.get().hardcore.bloodMoon;
        HardcoreConfig.TougherMobsSection tougher = Settings.get().hardcore.tougherMobs;
        boolean tougherBefore = tougher.enabled;
        double damageBefore = config.damage;
        List<Entity> spawned = new ArrayList<>();
        List<ServerPlayer> players = new ArrayList<>();
        try {
            // Set and put back within this tick
            tougher.enabled = true;
            config.damage = 2.0;
            BloodMoon.start(server);
            Mob marked = HardcoreMobsGameTests.create(helper, spawned, EntityTypes.ZOMBIE, EntitySpawnReason.NATURAL, MOB_AT);
            BloodMoon.stop(server);
            Mob tough = HardcoreMobsGameTests.create(helper, spawned, EntityTypes.ZOMBIE, EntitySpawnReason.NATURAL, MOB_AT);
            helper.assertTrue(marked.entityTags().contains(MobBoosts.BLOOD_MOON_MARK) && marked.entityTags().contains(MobBoosts.TOUGH),
                    "the first zombie isn't both marked and tough: " + marked.entityTags());
            playerTakes(helper, players, "MoonBitten", level.damageSources().mobAttack(marked), 3.0F, 7.5F);
            playerTakes(helper, players, "ToughBitten", level.damageSources().mobAttack(tough), 3.0F, 3.75F);
        } finally {
            BloodMoon.stop(server);
            tougher.enabled = tougherBefore;
            config.damage = damageBefore;
            players.forEach(TestPlayers::leave);
            spawned.forEach(Entity::discard);
        }
        helper.succeed();
    }

    // Setting the clock back before the night began ends it, with the line at dawn
    @GameTest(environment = SETBACK, maxTicks = 200)
    public void blood4TimeSetBackEndsIt(GameTestHelper helper) {
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
        int[] mark = new int[1];
        try {
            ServerPlayer player = TestPlayers.join(helper, "MoonSetBack", GameType.SURVIVAL, PLAYER_AT);
            players.add(player);
            helper.startSequence()
                    .thenWaitUntil(() -> helper.assertTrue(BloodMoon.active(), "no blood moon at startTick"))
                    .thenExecute(() -> {
                        mark[0] = TestPlayers.sentPackets(player).size();
                        clock[0] = day * DAY + config.startTick - 100;
                    })
                    .thenWaitUntil(() -> helper.assertFalse(BloodMoon.active(), "still up with the clock set back"))
                    .thenExecute(guarded(cleanUp, () -> {
                        helper.assertValueEqual(chatLines(player, mark[0]), List.of(BloodMoon.SET_LINE), "lines as it ended");
                        cleanUp.run();
                    }))
                    .thenSucceed();
        } catch (RuntimeException | Error e) {
            cleanUp.run();
            throw e;
        }
    }

    // A server that turns capBoostNearPlayerBodies on gets the doubled cap near player bodies too
    @GameTest(environment = MOON)
    public void blood5BodiesBoostedWhenAllowed(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        HardcoreConfig.BloodMoonSection config = Settings.get().hardcore.bloodMoon;
        boolean boostBefore = config.capBoostNearPlayerBodies;
        ServerPlayer player = TestPlayers.join(helper, "CapNearBody", GameType.SURVIVAL, PLAYER_AT);
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = BodyTestKit.usePlayerBodies();
        try {
            PlayerBody body = BodyTestKit.leavePlayerBody(helper, player);
            BloodMoon.start(server);
            int vanilla = MobCategory.MONSTER.getMaxInstancesPerChunk();
            helper.assertValueEqual(BloodMoon.monsterCapNear(body, vanilla), vanilla, "the cap near a player body as it ships");
            // Set and put back within this tick
            config.capBoostNearPlayerBodies = true;
            helper.assertValueEqual(BloodMoon.monsterCapNear(body, vanilla), vanilla * 2, "the cap near a player body when allowed");
        } finally {
            config.capBoostNearPlayerBodies = boostBefore;
            BloodMoon.stop(server);
            BodyTestKit.cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    // As a natural spawn makes it: made with its reason, its spawn finished as the spawner finishes it, which rolls its
    // gear, then added to the world, which loads it
    private static <T extends Mob> T spawnNaturally(GameTestHelper helper, List<Entity> spawned, EntityType<T> type, BlockPos pos) {
        ServerLevel level = helper.getLevel();
        T mob = type.create(level, EntitySpawnReason.NATURAL);
        helper.assertTrue(mob != null, "no " + type + " made");
        Vec3 at = helper.absoluteVec(Vec3.atBottomCenterOf(pos));
        mob.snapTo(at.x, at.y, at.z, 0.0F, 0.0F);
        mob.setPersistenceRequired();
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(mob.blockPosition()), EntitySpawnReason.NATURAL, null);
        helper.assertTrue(level.addFreshEntity(mob), "the " + type + " was not added");
        spawned.add(mob);
        mob.removeFreeWill();
        return mob;
    }

    // A step that fails ends the test there, before runBeforeTestEnd's turn would come, so it cleans up first
    private static Runnable guarded(Runnable cleanUp, Runnable step) {
        return () -> {
            try {
                step.run();
            } catch (RuntimeException | Error e) {
                cleanUp.run();
                throw e;
            }
        };
    }

    // A fresh player for each hit, so no hurt cooldown cuts it
    private static void playerTakes(GameTestHelper helper, List<ServerPlayer> players, String name, DamageSource source,
                                    float dealt, float expected) {
        ServerPlayer player = TestPlayers.join(helper, name, GameType.SURVIVAL, PLAYER_AT);
        players.add(player);
        // A test player is invulnerable until its client would say the world loaded
        TestPlayers.markLoaded(player);
        helper.assertTrue(player.hurtServer(helper.getLevel(), source, dealt), "the hit on " + name + " did not land");
        helper.assertValueEqual(player.getMaxHealth() - player.getHealth(), expected, "damage to " + name);
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

    // Set and put back within the tick
    private static void assertSkipBlockedUnlessSleptThrough(GameTestHelper helper, String when) {
        YoltConfig.SleepSection sleep = Settings.get().sleep;
        HardcoreConfig.BloodMoonSection config = Settings.get().hardcore.bloodMoon;
        boolean noSkipBefore = sleep.noNightSkip;
        boolean sleepThroughBefore = config.canSleepThrough;
        try {
            sleep.noNightSkip = false;
            config.canSleepThrough = false;
            helper.assertTrue(Sleep.blocksNightSkip(), "the night skip is not blocked " + when);
            config.canSleepThrough = true;
            helper.assertFalse(Sleep.blocksNightSkip(), "the night skip is blocked " + when + " with canSleepThrough on");
        } finally {
            sleep.noNightSkip = noSkipBefore;
            config.canSleepThrough = sleepThroughBefore;
        }
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
