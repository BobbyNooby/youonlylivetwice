package bobbynooby.dev.gametest;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.graves.Grave;
import bobbynooby.dev.graves.GraveItems;
import bobbynooby.dev.graves.GraveStore;
import bobbynooby.dev.lockout.DeathLockout;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.SleepStatus;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.clock.ClockTimeMarkers;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Every switch and the clock are flipped and restored inside one tick, so no other test sees them
public class SleepGameTests {
    static final BlockPos NEXT_TO_BED = new BlockPos(1, 1, 0);
    // The action-bar lines vanilla sends everyone in the dimension when the sleeper count changes
    private static final Set<String> SLEEP_LINES = Set.of("sleep.players_sleeping", "sleep.skipping_night");
    private static final String SPAWN_SET = "block.minecraft.set_spawn";

    // The status is fed directly, so no clock is involved
    @GameTest
    public void hideSleepersReportsNoSleepers(GameTestHelper helper) {
        BlockPos head = placeBed(helper);
        ServerPlayer player = TestPlayers.join(helper, "Sleepy", GameType.SURVIVAL, NEXT_TO_BED);
        boolean before = Settings.get().sleep.hideSleepers;
        try {
            helper.assertTrue(player.startSleeping(helper.absolutePos(head)), "the test player could not get into the bed");
            helper.assertTrue(player.isSleeping(), "the test player is not sleeping");
            SleepStatus status = new SleepStatus();
            status.update(List.of(player));

            Settings.get().sleep.hideSleepers = true;
            helper.assertValueEqual(status.amountSleeping(), 0, "sleepers counted with hide sleepers on");

            Settings.get().sleep.hideSleepers = false;
            helper.assertValueEqual(status.amountSleeping(), 1, "sleepers counted with hide sleepers off");
        } finally {
            Settings.get().sleep.hideSleepers = before;
            player.stopSleeping();
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    @GameTest(skyAccess = true)
    public void sleepOverlayNotSentToOthers(GameTestHelper helper) {
        BlockPos head = placeBed(helper);
        ServerPlayer sleeper = TestPlayers.join(helper, "Sleeper", GameType.SURVIVAL, NEXT_TO_BED);
        ServerPlayer watcher = TestPlayers.join(helper, "Watcher", GameType.SURVIVAL, new BlockPos(4, 1, 4));
        YoltConfig.SleepSection sleep = Settings.get().sleep;
        boolean hideBefore = sleep.hideSleepers;
        boolean skipBefore = sleep.noNightSkip;
        long savedTicks = clockTicks(helper);
        try {
            toMidnight(helper);
            sleep.hideSleepers = true;
            sleep.noNightSkip = true;
            sleepAndWake(helper, sleeper, head);
            helper.assertValueEqual(sleepLines(watcher, 0), 0, "sleep lines sent to the watcher with both switches on");
            helper.assertValueEqual(sleepLines(sleeper, 0), 0, "sleep lines sent to the sleeper with both switches on");

            // The night block alone would show "1/727", which still tells everyone someone is in bed
            sleep.hideSleepers = false;
            int mark = TestPlayers.sentPackets(watcher).size();
            sleepAndWake(helper, sleeper, head);
            helper.assertValueEqual(sleepLines(watcher, mark), 0, "sleep lines sent to the watcher with the night blocked");

            // Both off is vanilla, which shows the line does reach the watcher when nothing stops it
            sleep.noNightSkip = false;
            mark = TestPlayers.sentPackets(watcher).size();
            sleepAndWake(helper, sleeper, head);
            helper.assertTrue(sleepLines(watcher, mark) > 0, "no sleep line sent to the watcher with both switches off");
        } finally {
            sleep.hideSleepers = hideBefore;
            sleep.noNightSkip = skipBefore;
            setClock(helper, savedTicks);
            wakeAndLeave(sleeper);
            TestPlayers.leave(watcher);
        }
        helper.succeed();
    }

    @GameTest(skyAccess = true)
    public void noOverlayWhenNightCanSkip(GameTestHelper helper) {
        BlockPos head = placeBed(helper);
        ServerPlayer sleeper = TestPlayers.join(helper, "Sleeper", GameType.SURVIVAL, NEXT_TO_BED);
        ServerPlayer watcher = TestPlayers.join(helper, "Watcher", GameType.SURVIVAL, new BlockPos(4, 1, 4));
        YoltConfig.SleepSection sleep = Settings.get().sleep;
        boolean hideBefore = sleep.hideSleepers;
        boolean skipBefore = sleep.noNightSkip;
        long savedTicks = clockTicks(helper);
        try {
            toMidnight(helper);
            sleep.hideSleepers = true;
            sleep.noNightSkip = false;
            sleepAndWake(helper, sleeper, head);
            helper.assertValueEqual(sleepLines(watcher, 0), 0, "sleep lines sent to the watcher with the night able to skip");
        } finally {
            sleep.hideSleepers = hideBefore;
            sleep.noNightSkip = skipBefore;
            setClock(helper, savedTicks);
            wakeAndLeave(sleeper);
            TestPlayers.leave(watcher);
        }
        helper.succeed();
    }

    // Vanilla announces the count again whenever a player comes into the dimension while someone sleeps
    @GameTest(skyAccess = true)
    public void noOverlayWhenPlayerEntersSleepersDimension(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerLevel nether = level.getServer().getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "no nether to go to");
        BlockPos head = placeBed(helper);
        ServerPlayer sleeper = TestPlayers.join(helper, "Sleeper", GameType.SURVIVAL, NEXT_TO_BED);
        ServerPlayer traveller = TestPlayers.join(helper, "Traveller", GameType.SURVIVAL, new BlockPos(4, 1, 4));
        ServerPlayer late = null;
        YoltConfig.SleepSection sleep = Settings.get().sleep;
        boolean hideBefore = sleep.hideSleepers;
        boolean skipBefore = sleep.noNightSkip;
        long savedTicks = clockTicks(helper);
        try {
            toMidnight(helper);
            sleep.hideSleepers = true;
            sleep.noNightSkip = true;
            // Out of the dimension before anyone is in bed
            traveller.teleportTo(nether, 0.5, 64.0, 0.5, Set.of(), 0.0F, 0.0F, true);
            helper.assertTrue(traveller.level() == nether, "the traveller did not reach the nether");
            useBed(helper, sleeper, head);
            helper.assertTrue(sleeper.isSleeping(), "the sleeper could not get into the bed");

            int mark = TestPlayers.sentPackets(traveller).size();
            BlockPos back = helper.absolutePos(new BlockPos(4, 1, 4));
            traveller.teleportTo(level, back.getX() + 0.5, back.getY(), back.getZ() + 0.5, Set.of(), 0.0F, 0.0F, true);
            helper.assertTrue(level.players().contains(traveller), "the traveller is not among the overworld's players");
            helper.assertValueEqual(sleepLines(traveller, mark), 0, "sleep lines sent to the traveller coming back");

            // Logging in is the other way into the dimension
            late = TestPlayers.join(helper, "Late", GameType.SURVIVAL, new BlockPos(5, 1, 5));
            helper.assertTrue(level.players().contains(late), "the late player is not among the overworld's players");
            helper.assertValueEqual(sleepLines(late, 0), 0, "sleep lines sent to a player joining");
        } finally {
            sleep.hideSleepers = hideBefore;
            sleep.noNightSkip = skipBefore;
            setClock(helper, savedTicks);
            wakeAndLeave(sleeper);
            TestPlayers.leave(traveller);
            if (late != null) {
                TestPlayers.leave(late);
            }
        }
        helper.succeed();
    }

    // Sleep is there mainly to reset the phantom timer, so a bed must still work with every switch on
    @GameTest(skyAccess = true)
    public void bedStillResetsPhantomTimer(GameTestHelper helper) {
        BlockPos head = placeBed(helper);
        ServerPlayer player = TestPlayers.join(helper, "Tired", GameType.SURVIVAL, NEXT_TO_BED);
        YoltConfig.SleepSection sleep = Settings.get().sleep;
        boolean respawnBefore = sleep.noBedRespawn;
        boolean skipBefore = sleep.noNightSkip;
        long savedTicks = clockTicks(helper);
        try {
            toMidnight(helper);
            sleep.noBedRespawn = true;
            sleep.noNightSkip = true;
            Stat<?> timeSinceRest = Stats.CUSTOM.get(Stats.TIME_SINCE_REST);
            player.getStats().setValue(player, timeSinceRest, 80000);

            useBed(helper, player, head);
            helper.assertTrue(player.isSleeping(), "the bed did not let the player sleep");
            helper.assertValueEqual(player.getStats().getValue(timeSinceRest), 0, "time since rest after getting into bed");
        } finally {
            sleep.noBedRespawn = respawnBefore;
            sleep.noNightSkip = skipBefore;
            setClock(helper, savedTicks);
            wakeAndLeave(player);
        }
        helper.succeed();
    }

    @GameTest(skyAccess = true)
    public void bedSetsNoRespawnPoint(GameTestHelper helper) {
        BlockPos head = placeBed(helper);
        ServerPlayer player = TestPlayers.join(helper, "Bed", GameType.SURVIVAL, NEXT_TO_BED);
        YoltConfig.SleepSection sleep = Settings.get().sleep;
        boolean before = sleep.noBedRespawn;
        long savedTicks = clockTicks(helper);
        try {
            toMidnight(helper);
            sleep.noBedRespawn = true;
            useBed(helper, player, head);
            helper.assertTrue(player.isSleeping(), "the bed did not let the player sleep with no-bed-respawn on");
            helper.assertTrue(player.getRespawnConfig() == null, "the bed set a respawn point with no-bed-respawn on");
            helper.assertValueEqual(messages(player, SPAWN_SET), 0, "spawn-set messages with no-bed-respawn on");
            player.stopSleepInBed(false, true);

            sleep.noBedRespawn = false;
            useBed(helper, player, head);
            ServerPlayer.RespawnConfig config = player.getRespawnConfig();
            helper.assertTrue(config != null && config.respawnData().pos().equals(helper.absolutePos(head)),
                    "the bed set no respawn point with no-bed-respawn off");
            helper.assertValueEqual(messages(player, SPAWN_SET), 1, "spawn-set messages with no-bed-respawn off");
        } finally {
            sleep.noBedRespawn = before;
            setClock(helper, savedTicks);
            wakeAndLeave(player);
        }
        helper.succeed();
    }

    // The anchor only reads the level it stands in, so the player can stay in the test's level
    @GameTest
    public void anchorSetsNoRespawnPointOrCharge(GameTestHelper helper) {
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "no nether for the anchor");
        BlockPos anchor = new BlockPos(8, 100, 8);
        BlockState replaced = nether.getBlockState(anchor);
        ServerPlayer player = TestPlayers.join(helper, "Anchor", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        YoltConfig.SleepSection sleep = Settings.get().sleep;
        boolean before = sleep.noBedRespawn;
        try {
            nether.setBlockAndUpdate(anchor, Blocks.RESPAWN_ANCHOR.defaultBlockState().setValue(RespawnAnchorBlock.CHARGE, 1));
            sleep.noBedRespawn = true;
            InteractionResult result = useBlock(nether, player, anchor);
            helper.assertTrue(player.getRespawnConfig() == null, "the anchor set a respawn point with no-bed-respawn on");
            helper.assertValueEqual(nether.getBlockState(anchor).getValue(RespawnAnchorBlock.CHARGE), 1, "anchor charge with no-bed-respawn on");
            helper.assertValueEqual(messages(player, SPAWN_SET), 0, "spawn-set messages with no-bed-respawn on");
            // Vanilla's answer for an anchor that is already your point: no set-spawn sound, no swing
            helper.assertValueEqual(result, InteractionResult.CONSUME, "anchor use with no-bed-respawn on");

            sleep.noBedRespawn = false;
            useBlock(nether, player, anchor);
            ServerPlayer.RespawnConfig config = player.getRespawnConfig();
            helper.assertTrue(config != null && config.respawnData().dimension() == Level.NETHER && config.respawnData().pos().equals(anchor),
                    "the anchor set no respawn point with no-bed-respawn off");
            helper.assertValueEqual(nether.getBlockState(anchor).getValue(RespawnAnchorBlock.CHARGE), 1, "anchor charge with no-bed-respawn off");
            helper.assertValueEqual(messages(player, SPAWN_SET), 1, "spawn-set messages with no-bed-respawn off");
        } finally {
            sleep.noBedRespawn = before;
            nether.setBlockAndUpdate(anchor, replaced);
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // The anchor hook sits after vanilla's check for a dimension where anchors work, so anywhere else a charged anchor
    // still blows up when used. The barriers round the test hold the blast in.
    @GameTest
    public void anchorStillExplodesInOverworld(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos anchor = helper.absolutePos(new BlockPos(4, 1, 4));
        // Glass drops nothing, so the blast leaves no item behind
        BlockPos glass = anchor.east();
        ServerPlayer player = TestPlayers.join(helper, "Boom", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        YoltConfig.SleepSection sleep = Settings.get().sleep;
        boolean before = sleep.noBedRespawn;
        try {
            level.setBlockAndUpdate(anchor, Blocks.RESPAWN_ANCHOR.defaultBlockState().setValue(RespawnAnchorBlock.CHARGE, 1));
            level.setBlockAndUpdate(glass, Blocks.GLASS.defaultBlockState());
            sleep.noBedRespawn = true;
            InteractionResult result = rightClick(level, player, anchor);
            // Vanilla's answer for the blast; the hook answers CONSUME
            helper.assertValueEqual(result, InteractionResult.SUCCESS_SERVER, "anchor use in the overworld with no-bed-respawn on");
            helper.assertFalse(level.getBlockState(anchor).is(Blocks.RESPAWN_ANCHOR), "the anchor is still there");
            helper.assertFalse(level.getBlockState(glass).is(Blocks.GLASS), "the glass beside the anchor is still there, so nothing blew up");
        } finally {
            sleep.noBedRespawn = before;
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // Glowstone goes through the anchor's item use, which the hook leaves alone, so charging works with the switch on
    @GameTest(dimension = "minecraft:the_nether")
    public void anchorStillChargesWithGlowstone(GameTestHelper helper) {
        ServerLevel nether = helper.getLevel();
        helper.assertValueEqual(nether.dimension(), Level.NETHER, "the test's dimension");
        BlockPos anchor = helper.absolutePos(new BlockPos(3, 1, 3));
        ServerPlayer player = TestPlayers.join(helper, "Charger", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        YoltConfig.SleepSection sleep = Settings.get().sleep;
        boolean before = sleep.noBedRespawn;
        try {
            nether.setBlockAndUpdate(anchor, Blocks.RESPAWN_ANCHOR.defaultBlockState());
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GLOWSTONE, 2));
            sleep.noBedRespawn = true;
            InteractionResult result = rightClick(nether, player, anchor);
            helper.assertTrue(result.consumesAction(), "the glowstone was not used on the anchor: " + result);
            helper.assertValueEqual(nether.getBlockState(anchor).getValue(RespawnAnchorBlock.CHARGE), 1, "anchor charge after one glowstone");
            helper.assertValueEqual(player.getMainHandItem().getCount(), 1, "glowstone left in the hand");
        } finally {
            sleep.noBedRespawn = before;
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // An old anchor point that YOLT ignores leaves the anchor alone at a death respawn, so vanilla's deplete sound must
    // not play either. With the switch off the point is used: a charge goes and the sound plays at the anchor.
    @GameTest
    public void ignoredAnchorPlaysNoSound(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel nether = server.getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "no nether for the anchor");
        BlockPos anchor = new BlockPos(24, 100, 8);
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        ServerPlayer switchOn = TestPlayers.join(helper, "AnchorOn", GameType.SURVIVAL, new BlockPos(5, 1, 5));
        ServerPlayer switchOff = TestPlayers.join(helper, "AnchorOff", GameType.SURVIVAL, new BlockPos(6, 1, 6));
        List<UUID> ids = List.of(switchOn.getUUID(), switchOff.getUUID());
        YoltConfig config = Settings.get();
        boolean respawnBefore = config.sleep.noBedRespawn;
        boolean gravesBefore = config.graves.enabled;
        try {
            // A floor round the anchor, so vanilla has somewhere to stand the player up when the point is used
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    VoidGraveGameTests.setRemembered(nether, anchor.offset(dx, -1, dz), Blocks.STONE, before);
                }
            }
            before.putIfAbsent(anchor, nether.getBlockState(anchor));
            nether.setBlockAndUpdate(anchor, Blocks.RESPAWN_ANCHOR.defaultBlockState().setValue(RespawnAnchorBlock.CHARGE, 2));
            // No graves: this test is only about the respawn
            config.graves.enabled = false;
            storeOldPoint(switchOn, Level.NETHER, anchor);
            storeOldPoint(switchOff, Level.NETHER, anchor);

            config.sleep.noBedRespawn = true;
            int mark = TestPlayers.sentPackets(switchOn).size();
            ServerPlayer back = dieAndRespawn(helper, switchOn);
            helper.assertTrue(back.level() != nether, "respawned at " + back.blockPosition() + " in the nether, by the old anchor point, with no-bed-respawn on");
            helper.assertValueEqual(nether.getBlockState(anchor).getValue(RespawnAnchorBlock.CHARGE), 2, "anchor charge after the respawn with no-bed-respawn on");
            helper.assertValueEqual(depleteSounds(back, mark), List.of(), "anchor deplete sounds with no-bed-respawn on");

            config.sleep.noBedRespawn = false;
            mark = TestPlayers.sentPackets(switchOff).size();
            back = dieAndRespawn(helper, switchOff);
            helper.assertTrue(back.level() == nether && back.position().distanceTo(Vec3.atCenterOf(anchor)) <= 2.0,
                    "respawned at " + back.blockPosition() + " in " + back.level().dimension().identifier()
                            + ", away from the anchor, with no-bed-respawn off");
            helper.assertValueEqual(nether.getBlockState(anchor).getValue(RespawnAnchorBlock.CHARGE), 1, "anchor charge after the respawn with no-bed-respawn off");
            helper.assertValueEqual(depleteSounds(back, mark), List.of(anchor), "anchor deplete sounds with no-bed-respawn off");
        } finally {
            config.sleep.noBedRespawn = respawnBefore;
            config.graves.enabled = gravesBefore;
            for (UUID id : ids) {
                ServerPlayer online = server.getPlayerList().getPlayer(id);
                if (online != null) {
                    TestPlayers.leave(online);
                }
                DeathLockout.revive(id, "Test", "console");
                removeGraves(server, id);
            }
            VoidGraveGameTests.putBack(nether, before);
        }
        helper.succeed();
    }

    @GameTest
    public void spawnpointCommandStillWorks(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "Pointed", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        BlockPos spot = helper.absolutePos(new BlockPos(3, 1, 3));
        YoltConfig.SleepSection sleep = Settings.get().sleep;
        boolean before = sleep.noBedRespawn;
        try {
            sleep.noBedRespawn = true;
            setSpawnpoint(helper, player, spot);
            ServerPlayer.RespawnConfig config = player.getRespawnConfig();
            helper.assertTrue(config != null, "/spawnpoint set no respawn point with no-bed-respawn on");
            helper.assertValueEqual(config.respawnData().pos(), spot, "respawn point from /spawnpoint");
            helper.assertValueEqual(config.respawnData().dimension(), helper.getLevel().dimension(), "respawn dimension from /spawnpoint");
        } finally {
            sleep.noBedRespawn = before;
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    @GameTest
    public void storedPointStillUsed(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TestPlayers.join(helper, "Stored", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        ServerPlayer current = player;
        BlockPos spot = helper.absolutePos(new BlockPos(3, 1, 3));
        YoltConfig.SleepSection sleep = Settings.get().sleep;
        boolean before = sleep.noBedRespawn;
        try {
            sleep.noBedRespawn = true;
            setSpawnpoint(helper, player, spot);
            current = server.getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
            helper.assertTrue(current.level() == helper.getLevel(), "respawned in another dimension");
            helper.assertTrue(current.position().distanceTo(Vec3.atBottomCenterOf(spot)) <= 2.0,
                    "respawned at " + current.blockPosition() + ", away from the stored point " + spot);
        } finally {
            sleep.noBedRespawn = before;
            TestPlayers.leave(current);
        }
        helper.succeed();
    }

    // Bob's case: use a bed, die, come back after the lockout, and you are not brought back to the bed
    @GameTest(skyAccess = true)
    public void bedThenDeathThenRejoinNotAtBed(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        BlockPos head = placeBed(helper);
        Vec3 bed = Vec3.atCenterOf(helper.absolutePos(head));
        ServerPlayer player = TestPlayers.join(helper, "Rested", GameType.SURVIVAL, NEXT_TO_BED);
        UUID id = player.getUUID();
        GameProfile profile = player.getGameProfile();
        YoltConfig.SleepSection sleep = Settings.get().sleep;
        boolean before = sleep.noBedRespawn;
        long savedTicks = clockTicks(helper);
        try {
            toMidnight(helper);
            sleep.noBedRespawn = true;
            useBed(helper, player, head);
            helper.assertTrue(player.isSleeping(), "the player could not get into the bed");

            TestPlayers.markLoaded(player);
            player.kill(player.level());
            helper.assertTrue(player.isDeadOrDying(), "the player did not die");
            helper.assertTrue(DeathLockout.refusal(id, "Test") != null, "the death started no lockout");

            // A real logout saves the dead player, and a revive lets them back in
            TestPlayers.leave(player);
            helper.assertValueEqual(DeathLockout.revive(id, "Test", "console"), DeathLockout.ReviveResult.REVIVED, "revive result");
            ServerPlayer back = TestPlayers.rejoin(helper, profile, GameType.SURVIVAL, new BlockPos(4, 1, 4));
            helper.assertTrue(back.isDeadOrDying(), "the player did not join dead");

            // What the client sends from the death screen
            back.connection.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
            ServerPlayer respawned = server.getPlayerList().getPlayer(id);
            helper.assertTrue(respawned != null && respawned != back && respawned.isAlive(), "the player did not respawn");
            helper.assertTrue(respawned.level() != helper.getLevel() || respawned.position().distanceTo(bed) > 8.0,
                    "respawned at " + respawned.blockPosition() + ", next to the bed");
            helper.assertTrue(respawned.getRespawnConfig() == null, "the bed left a respawn point");
        } finally {
            sleep.noBedRespawn = before;
            setClock(helper, savedTicks);
            ServerPlayer online = server.getPlayerList().getPlayer(id);
            if (online != null) {
                TestPlayers.leave(online);
            }
            DeathLockout.revive(id, "Test", "console");
            removeGraves(server, id);
        }
        helper.succeed();
    }

    // A client's disconnect wakes the sleeper before the logout, so the body lies where vanilla stands them up
    @GameTest(skyAccess = true)
    public void logoutWhileSleeping(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos head = placeBed(helper);
        floorRoundBed(helper);
        BlockPos headAt = helper.absolutePos(head);
        BlockPos footAt = headAt.relative(Direction.NORTH);
        ServerPlayer player = TestPlayers.join(helper, "BedLogout", GameType.SURVIVAL, NEXT_TO_BED);
        UUID owner = player.getUUID();
        long savedTicks = clockTicks(helper);
        YoltConfig.BodiesSection before = BodyTestKit.useDefaultBodies();
        try {
            toMidnight(helper);
            useBed(helper, player, head);
            helper.assertTrue(player.isSleeping(), "the player could not get into the bed");
            helper.assertTrue(player.getBoundingBox().intersects(new AABB(headAt)), "the sleeper is not in the bed");

            Mannequin body = BodyTestKit.disconnectBody(helper, player);
            AABB box = body.getBoundingBox();
            helper.assertFalse(box.intersects(new AABB(headAt)) || box.intersects(new AABB(footAt)),
                    "the body lies in the bed at " + body.position());
            helper.assertTrue(level.noBlockCollision(body, box), "the body is inside a block at " + body.position());
            helper.assertFalse(body.isInWall(), "the body is in a wall at " + body.position());
            helper.assertFalse(level.getBlockState(headAt).getValue(BedBlock.OCCUPIED), "the bed is still taken");

            ServerPlayer back = TestPlayers.rejoin(helper, player.getGameProfile(), GameType.SURVIVAL, NEXT_TO_BED);
            helper.assertFalse(back.isSleeping(), "the owner logged back in asleep");
            helper.assertTrue(back.getSleepingPos().isEmpty(), "the owner still has a bed to sleep in");
            helper.assertValueEqual(back.position(), body.position(), "the owner's spot after login");
        } finally {
            setClock(helper, savedTicks);
            BodyTestKit.cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    // A normal death: the zombie wakes the sleeper as it hits, the grave goes where vanilla stood them up beside the
    // bed, the lockout starts, and the bed left no point to bring them back to
    @GameTest(skyAccess = true)
    public void mobKillsSleepingPlayer(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel level = helper.getLevel();
        BlockPos head = placeBed(helper);
        floorRoundBed(helper);
        BlockPos headAt = helper.absolutePos(head);
        BlockPos footAt = headAt.relative(Direction.NORTH);
        ServerPlayer player = TestPlayers.join(helper, "Ambushed", GameType.SURVIVAL, NEXT_TO_BED);
        UUID id = player.getUUID();
        GameProfile profile = player.getGameProfile();
        YoltConfig.SleepSection sleep = Settings.get().sleep;
        boolean before = sleep.noBedRespawn;
        long savedTicks = clockTicks(helper);
        try {
            toMidnight(helper);
            sleep.noBedRespawn = true;
            player.getInventory().setItem(9, new ItemStack(Items.DIAMOND, 3));
            useBed(helper, player, head);
            helper.assertTrue(player.isSleeping(), "the player could not get into the bed");

            TestPlayers.markLoaded(player);
            // Never added to the world: the hit only needs someone to come from
            player.hurtServer(level, level.damageSources().mobAttack(new Zombie(level)), Float.MAX_VALUE);
            helper.assertTrue(player.isDeadOrDying(), "the zombie did not kill the sleeper");
            helper.assertFalse(player.isSleeping(), "the player died in bed");

            List<Grave> graves = GraveStore.get(server).all().stream().filter(grave -> grave.owner().equals(id)).toList();
            helper.assertValueEqual(graves.size(), 1, "graves");
            Grave grave = graves.get(0);
            helper.assertValueEqual(grave.pos(), player.blockPosition(), "grave position");
            helper.assertFalse(grave.pos().equals(headAt) || grave.pos().equals(footAt), "grave in the bed at " + grave.pos());
            helper.assertTrue(grave.pos().closerThan(headAt, 2.5) || grave.pos().closerThan(footAt, 2.5),
                    "grave at " + grave.pos() + ", away from the bed");
            helper.assertValueEqual(grave.items().size(), 2, "grave stacks");
            helper.assertTrue(GraveItems.isGraveHead(grave.items().get(0)), "the first grave item is not the grave head");
            helper.assertValueEqual(GraveAsserts.count(grave.items(), Items.DIAMOND), 3, "diamonds in the grave");
            String refusal = LockoutGameTests.refusalText(id);
            helper.assertTrue(refusal.startsWith("You died: " + profile.name() + " was slain by Zombie\n"), "lockout: " + refusal);
            helper.assertTrue(player.getRespawnConfig() == null, "the bed set a respawn point");

            // A real logout saves the dead player, and a revive lets them back in to respawn
            TestPlayers.leave(player);
            helper.assertValueEqual(DeathLockout.revive(id, "Test", "console"), DeathLockout.ReviveResult.REVIVED, "revive result");
            ServerPlayer back = TestPlayers.rejoin(helper, profile, GameType.SURVIVAL, new BlockPos(4, 1, 4));
            back.connection.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
            ServerPlayer respawned = server.getPlayerList().getPlayer(id);
            helper.assertTrue(respawned != null && respawned != back && respawned.isAlive(), "the player did not respawn");
            helper.assertTrue(respawned.level() != level || respawned.position().distanceTo(Vec3.atCenterOf(headAt)) > 8.0,
                    "respawned at " + respawned.blockPosition() + ", next to the bed");
        } finally {
            sleep.noBedRespawn = before;
            setClock(helper, savedTicks);
            ServerPlayer online = server.getPlayerList().getPlayer(id);
            if (online != null) {
                TestPlayers.leave(online);
            }
            DeathLockout.revive(id, "Test", "console");
            removeGraves(server, id);
        }
        helper.succeed();
    }

    // Older builds stored an unforced point at every bed used, even with the switch on
    @GameTest
    public void oldBedPointIsIgnored(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        BlockPos head = placeBed(helper);
        // A floor round the bed, so vanilla has somewhere to stand the player up when the point is used
        for (int x = 0; x <= 2; x++) {
            for (int z = 0; z <= 3; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        Vec3 bed = Vec3.atCenterOf(helper.absolutePos(head));
        ServerPlayer switchOn = TestPlayers.join(helper, "OldBedOn", GameType.SURVIVAL, new BlockPos(5, 1, 5));
        ServerPlayer switchOff = TestPlayers.join(helper, "OldBedOff", GameType.SURVIVAL, new BlockPos(6, 1, 6));
        List<UUID> ids = List.of(switchOn.getUUID(), switchOff.getUUID());
        YoltConfig config = Settings.get();
        boolean respawnBefore = config.sleep.noBedRespawn;
        boolean gravesBefore = config.graves.enabled;
        try {
            // No graves: this test is only about where the player comes back
            config.graves.enabled = false;
            storeOldBedPoint(helper, switchOn, head);
            storeOldBedPoint(helper, switchOff, head);

            config.sleep.noBedRespawn = true;
            ServerPlayer back = dieAndRespawn(helper, switchOn);
            helper.assertTrue(back.level() != helper.getLevel() || back.position().distanceTo(bed) > 8.0,
                    "respawned at " + back.blockPosition() + ", the old bed point, with no-bed-respawn on");

            config.sleep.noBedRespawn = false;
            back = dieAndRespawn(helper, switchOff);
            helper.assertTrue(back.level() == helper.getLevel() && back.position().distanceTo(bed) <= 3.0,
                    "respawned at " + back.blockPosition() + ", away from the old bed point, with no-bed-respawn off");
        } finally {
            config.sleep.noBedRespawn = respawnBefore;
            config.graves.enabled = gravesBefore;
            for (UUID id : ids) {
                ServerPlayer online = server.getPlayerList().getPlayer(id);
                if (online != null) {
                    TestPlayers.leave(online);
                }
                DeathLockout.revive(id, "Test", "console");
                removeGraves(server, id);
            }
        }
        helper.succeed();
    }

    // An old build's bed point is lost the same way through the End's exit portal, which asks for the point too. With
    // the switch off the portal takes the player to the bed, as in vanilla.
    @GameTest
    public void endExitIgnoresOldBedPoint(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel overworld = server.overworld();
        ServerLevel end = server.getLevel(Level.END);
        helper.assertTrue(end != null, "no End to leave");
        BlockPos head = placeBed(helper);
        floorRoundBed(helper);
        Vec3 bed = Vec3.atCenterOf(helper.absolutePos(head));
        // In the air over the End's floor, away from every other test there
        BlockPos portal = new BlockPos(-2000, 100, -2000);
        BlockState replaced = end.getBlockState(portal);
        ServerPlayer switchOn = TestPlayers.join(helper, "EndExitOn", GameType.SURVIVAL, new BlockPos(5, 1, 5));
        ServerPlayer switchOff = TestPlayers.join(helper, "EndExitOff", GameType.SURVIVAL, new BlockPos(6, 1, 6));
        YoltConfig config = Settings.get();
        boolean respawnBefore = config.sleep.noBedRespawn;
        boolean randomBefore = config.randomSpawn;
        try {
            end.setBlockAndUpdate(portal, Blocks.END_PORTAL.defaultBlockState());
            config.randomSpawn = true;
            storeOldBedPoint(helper, switchOn, head);
            storeOldBedPoint(helper, switchOff, head);

            config.sleep.noBedRespawn = true;
            throughExitPortal(helper, switchOn, end, portal);
            BlockPos feet = switchOn.blockPosition();
            helper.assertTrue(switchOn.level() == overworld, "left the End to " + switchOn.level().dimension().identifier());
            helper.assertTrue(switchOn.position().distanceTo(bed) > 8.0,
                    "left the End to " + feet.toShortString() + ", by the old bed point, with no-bed-respawn on");
            // The random spawn, not vanilla's spot round the world spawn
            BlockPos spawn = overworld.getRespawnData().pos();
            int radius = overworld.getGameRules().get(GameRules.RESPAWN_RADIUS);
            helper.assertTrue(Math.max(Math.abs(feet.getX() - spawn.getX()), Math.abs(feet.getZ() - spawn.getZ())) > radius + 1,
                    "left the End to " + feet.toShortString() + ", where vanilla puts a player round the world spawn " + spawn.toShortString());

            config.sleep.noBedRespawn = false;
            throughExitPortal(helper, switchOff, end, portal);
            helper.assertTrue(switchOff.level() == overworld && switchOff.position().distanceTo(bed) <= 3.0,
                    "left the End to " + switchOff.blockPosition().toShortString() + " in " + switchOff.level().dimension().identifier()
                            + ", away from the old bed point, with no-bed-respawn off");
        } finally {
            config.sleep.noBedRespawn = respawnBefore;
            config.randomSpawn = randomBefore;
            end.setBlockAndUpdate(portal, replaced);
            TestPlayers.leave(switchOn);
            TestPlayers.leave(switchOff);
        }
        helper.succeed();
    }

    // Into the End and its exit portal, as a player who has seen the credits, then the player's tick, in which the portal
    // takes them out: EndPortalBlock asks findRespawnPositionAndUseSpawnBlock where to
    private static void throughExitPortal(GameTestHelper helper, ServerPlayer player, ServerLevel end, BlockPos portal) {
        player.teleportTo(end, portal.getX() + 0.5, portal.getY(), portal.getZ() + 0.5, Set.of(), 0.0F, 0.0F, true);
        helper.assertTrue(player.level() == end, player.getPlainTextName() + " did not reach the End");
        player.seenCredits = true;
        end.getBlockState(portal).entityInside(end, portal, player, InsideBlockEffectApplier.NOOP, true);
        player.doTick();
        helper.assertTrue(player.level() != end, "the exit portal did not take " + player.getPlainTextName() + " out of the End");
    }

    private static void storeOldBedPoint(GameTestHelper helper, ServerPlayer player, BlockPos head) {
        storeOldPoint(player, helper.getLevel().dimension(), helper.absolutePos(head));
    }

    // Unforced, like every point a bed or anchor stored
    private static void storeOldPoint(ServerPlayer player, ResourceKey<Level> dimension, BlockPos pos) {
        LevelData.RespawnData data = LevelData.RespawnData.of(dimension, pos, 0.0F, 0.0F);
        player.setRespawnPosition(new ServerPlayer.RespawnConfig(data, false), false);
    }

    // A real death, then the respawn the client asks for from the death screen
    private static ServerPlayer dieAndRespawn(GameTestHelper helper, ServerPlayer player) {
        TestPlayers.markLoaded(player);
        player.kill(player.level());
        helper.assertTrue(player.isDeadOrDying(), player.getPlainTextName() + " did not die");
        player.connection.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
        ServerPlayer respawned = helper.getLevel().getServer().getPlayerList().getPlayer(player.getUUID());
        helper.assertTrue(respawned != null && respawned != player && respawned.isAlive(), player.getPlainTextName() + " did not respawn");
        return respawned;
    }

    // A red bed with its foot at (1, 1, 1), facing south; returns the head
    static BlockPos placeBed(GameTestHelper helper) {
        BlockPos foot = new BlockPos(1, 1, 1);
        BlockPos head = foot.relative(Direction.SOUTH);
        BlockState bed = Blocks.BED.pick(DyeColor.RED).defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH);
        helper.setBlock(foot, bed.setValue(BedBlock.PART, BedPart.FOOT));
        helper.setBlock(head, bed.setValue(BedBlock.PART, BedPart.HEAD));
        return head;
    }

    // A floor round the bed, so vanilla has somewhere to stand a sleeper up
    private static void floorRoundBed(GameTestHelper helper) {
        for (int x = 0; x <= 2; x++) {
            for (int z = 0; z <= 3; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
    }

    // What a right click on the bed runs on the server
    static void useBed(GameTestHelper helper, ServerPlayer player, BlockPos head) {
        useBlock(helper.getLevel(), player, helper.absolutePos(head));
    }

    private static InteractionResult useBlock(ServerLevel level, ServerPlayer player, BlockPos pos) {
        return level.getBlockState(pos).useWithoutItem(level, player, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
    }

    // A right click on top of the block with whatever is in the main hand, the way the server takes it from a client
    private static InteractionResult rightClick(ServerLevel level, ServerPlayer player, BlockPos pos) {
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos).add(0.0, 0.5, 0.0), Direction.UP, pos, false);
        return player.gameMode.useItemOn(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
    }

    // Getting out of bed the way the client asks for it
    private static void sleepAndWake(GameTestHelper helper, ServerPlayer player, BlockPos head) {
        useBed(helper, player, head);
        helper.assertTrue(player.isSleeping(), "the sleeper could not get into the bed");
        player.stopSleepInBed(false, true);
    }

    static void wakeAndLeave(ServerPlayer player) {
        if (player.isSleeping()) {
            player.stopSleepInBed(true, true);
        }
        TestPlayers.leave(player);
    }

    // An admin's /spawnpoint, run from the console
    private static void setSpawnpoint(GameTestHelper helper, ServerPlayer player, BlockPos pos) {
        MinecraftServer server = helper.getLevel().getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                "spawnpoint " + player.getGameProfile().name() + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
    }

    // Beds only work in the dark, and the sky brightness is cached until the next level tick
    static void toMidnight(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        level.getServer().clockManager().moveToTimeMarker(clock(level), ClockTimeMarkers.MIDNIGHT);
        level.updateSkyBrightness();
        helper.assertTrue(level.isDarkOutside(), "it is not dark after moving the clock to midnight");
    }

    static long clockTicks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        return level.getServer().clockManager().getInstance(clock(level)).totalTicks();
    }

    private static void setClock(GameTestHelper helper, long totalTicks) {
        ServerLevel level = helper.getLevel();
        level.getServer().clockManager().setTotalTicks(clock(level), totalTicks);
        level.updateSkyBrightness();
    }

    private static Holder<WorldClock> clock(ServerLevel level) {
        return level.dimensionType().defaultClock().orElseThrow();
    }

    // Sleep lines sent to this player's action bar, counting from packet number "from"
    private static int sleepLines(ServerPlayer player, int from) {
        List<Packet<?>> packets = TestPlayers.sentPackets(player);
        return (int) packets.subList(from, packets.size()).stream()
                .filter(p -> p instanceof ClientboundSystemChatPacket chat && chat.overlay() && SLEEP_LINES.contains(key(chat.content())))
                .count();
    }

    // Where each anchor deplete sound sent to this player played, counting from packet number "from"
    private static List<BlockPos> depleteSounds(ServerPlayer player, int from) {
        List<Packet<?>> packets = TestPlayers.sentPackets(player);
        List<BlockPos> at = new ArrayList<>();
        for (Packet<?> packet : packets.subList(from, packets.size())) {
            if (packet instanceof ClientboundSoundPacket sound && sound.getSound().value() == SoundEvents.RESPAWN_ANCHOR_DEPLETE.value()) {
                at.add(BlockPos.containing(sound.getX(), sound.getY(), sound.getZ()));
            }
        }
        return at;
    }

    private static int messages(ServerPlayer player, String key) {
        return (int) TestPlayers.sentPackets(player).stream()
                .filter(p -> p instanceof ClientboundSystemChatPacket chat && key.equals(key(chat.content())))
                .count();
    }

    private static String key(Component message) {
        return message.getContents() instanceof TranslatableContents text ? text.getKey() : "";
    }

    private static void removeGraves(MinecraftServer server, UUID owner) {
        GraveStore store = GraveStore.get(server);
        List<Grave> graves = store.all().stream().filter(grave -> grave.owner().equals(owner)).toList();
        for (Grave grave : graves) {
            store.remove(grave.id());
            ServerLevel level = server.getLevel(grave.dimension());
            if (level != null) {
                level.setBlock(grave.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }
}
