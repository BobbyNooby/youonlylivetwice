package bobbynooby.dev.gametest;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.coords.CoordinateOffsets;
import bobbynooby.dev.coords.Offset;
import bobbynooby.dev.graves.Grave;
import bobbynooby.dev.graves.GraveStore;
import bobbynooby.dev.visibility.AdminView;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.advancements.AdvancementRewards;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;
import java.util.UUID;

// Coordinates an op with an offset types, and the texts that would tell a player real coordinates. The tests join
// players and ops them, so the class has batches of its own.
public class CoordinateOffsetCommandGameTests {
    private static final String COMMANDS = "you-only-live-twice-gametest:coordinate_offset_commands";
    // Holds the lockout and graves.tellLocation while a dead player's notice is read
    private static final String GRAVE_NOTICE = "you-only-live-twice-gametest:coordinate_offset_grave_notice";
    // Holds the lockout and graves.tellLocation until a death's kick, which comes after its tick
    private static final String GRAVE_KICK = "you-only-live-twice-gametest:coordinate_offset_grave_kick";
    private static final int KICK_TICKS = 20;
    // The test data pack's function, which teleports whoever runs it to a fixed real spot, written as "tp @s 40 120 -73"
    private static final Identifier TELEPORT_FUNCTION = Identifier.fromNamespaceAndPath("you-only-live-twice-gametest", "offset_tp");
    private static final Vec3 FUNCTION_SPOT = new Vec3(40.5, 120.0, -72.5);
    // A flake hunt runs copies of the kick test at once, each holding the switches until its own kick. All want the same
    // values, so the first to start keeps what was there and the last to finish puts it back; a copy that kept and put
    // back another's values would switch them off under a kick still to come, or leave them on.
    private static int kickSwitchHolders;
    private static Runnable putBackKickSwitches = () -> {};

    // Whole numbers are centred on the block, as vanilla does; a relative step is from the real spot
    @GameTest(environment = COMMANDS)
    public void typedCoordinatesReadInTheClientFrame(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "cmtyped", GameType.CREATIVE);
        try {
            TestPlayers.op(player);
            Offset offset = CoordinateOffsets.offset(player);
            helper.assertFalse(offset.isZero(), "the player got no offset");
            int clientX = player.getBlockX() + offset.x();
            int clientZ = player.getBlockZ() + offset.z();
            OffsetTestKit.type(player, "tp @s " + clientX + " 120 " + clientZ);
            Vec3 landed = new Vec3(clientX + 0.5 - offset.x(), 120.0, clientZ + 0.5 - offset.z());
            helper.assertValueEqual(player.position(), landed, "where the typed teleport went");
            OffsetTestKit.type(player, "tp @s ~1 ~ ~");
            helper.assertValueEqual(player.position(), landed.add(1.0, 0.0, 0.0), "where the relative teleport went");
            // One axis relative and one typed: only the typed one is read in the frame
            OffsetTestKit.type(player, "tp @s ~ 120 " + (clientZ + 2));
            helper.assertValueEqual(player.position(), new Vec3(landed.x + 1.0, 120.0, landed.z + 2.0), "where the mixed teleport went");
            // Local coordinates are steps from the real spot, along where the player faces, which is south
            OffsetTestKit.type(player, "tp @s ^ ^ ^1");
            helper.assertTrue(player.position().distanceTo(new Vec3(landed.x + 1.0, 120.0, landed.z + 3.0)) < 0.01,
                    "where the local teleport went: " + player.position());
        } finally {
            TestPlayers.deop(player);
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // A typed column is read in the frame too: forceload takes the real chunk under the client's numbers. Far from the
    // test, so no chunk the test framework holds is touched.
    @GameTest(environment = COMMANDS)
    public void typedColumnReadInTheClientFrame(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "cmcolumn", GameType.CREATIVE);
        ServerLevel level = helper.getLevel();
        ChunkPos real = ChunkPos.containing(player.blockPosition().offset(1_000, 0, 1_000));
        // The chunk at the client's own numbers, which a typed column read as real would force instead
        ChunkPos asTyped = ChunkPos.containing(CoordinateOffsets.offset(player).shift(real.getMiddleBlockPosition(0)));
        try {
            TestPlayers.op(player);
            Offset offset = CoordinateOffsets.offset(player);
            helper.assertFalse(offset.isZero(), "the player got no offset");
            helper.assertFalse(level.getForceLoadedChunks().contains(real.pack()), "the chunk was forced already");
            helper.assertFalse(level.getForceLoadedChunks().contains(asTyped.pack()),
                    "the chunk at the typed numbers was forced already");
            BlockPos client = offset.shift(real.getMiddleBlockPosition(0));
            OffsetTestKit.type(player, "forceload add " + client.getX() + " " + client.getZ());
            helper.assertTrue(level.getForceLoadedChunks().contains(real.pack()), "the typed column did not force the real chunk");
        } finally {
            level.setChunkForced(real.x(), real.z(), false);
            level.setChunkForced(asTyped.x(), asTyped.z(), false);
            TestPlayers.deop(player);
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // A block argument is read in the frame too, through its own parser
    @GameTest(environment = COMMANDS)
    public void typedBlockReadInTheClientFrame(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "cmblock", GameType.CREATIVE);
        BlockPos real = player.blockPosition().above(3);
        try {
            TestPlayers.op(player);
            Offset offset = CoordinateOffsets.offset(player);
            helper.assertFalse(offset.isZero(), "the player got no offset");
            BlockPos client = offset.shift(real);
            OffsetTestKit.type(player, "setblock " + client.getX() + " " + client.getY() + " " + client.getZ() + " minecraft:gold_block");
            helper.assertTrue(helper.getLevel().getBlockState(real).is(Blocks.GOLD_BLOCK),
                    "the typed block is not at the real spot: " + helper.getLevel().getBlockState(real));
        } finally {
            helper.getLevel().setBlockAndUpdate(real, Blocks.AIR.defaultBlockState());
            TestPlayers.deop(player);
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    @GameTest(environment = COMMANDS)
    public void consoleAndExecuteUseRealCoordinates(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "cmconsole", GameType.CREATIVE);
        try {
            helper.assertFalse(CoordinateOffsets.offset(player).isZero(), "the player got no offset");
            MinecraftServer server = helper.getLevel().getServer();
            String name = player.getGameProfile().name();
            int x = player.getBlockX();
            int z = player.getBlockZ();
            run(server.createCommandSourceStack(), "tp " + name + " " + x + " 120 " + z);
            helper.assertValueEqual(player.position(), new Vec3(x + 0.5, 120.0, z + 0.5), "the console's teleport");
            run(server.createCommandSourceStack(), "execute as " + name + " run tp @s " + x + " 121 " + z);
            helper.assertValueEqual(player.position(), new Vec3(x + 0.5, 121.0, z + 0.5), "the console's teleport as the player");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // A reward function runs as the player, but its numbers were written in a data pack, in real coordinates
    @GameTest(environment = COMMANDS)
    public void rewardFunctionUsesRealCoordinates(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "cmreward", GameType.SURVIVAL);
        try {
            helper.assertFalse(CoordinateOffsets.offset(player).isZero(), "the player got no offset");
            AdvancementRewards.Builder.function(TELEPORT_FUNCTION).build().grant(player);
            helper.assertValueEqual(player.position(), FUNCTION_SPOT, "where the reward function's teleport went");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // Typing /function runs lines the player didn't type, so they keep real coordinates too
    @GameTest(environment = COMMANDS)
    public void functionTypedByAnOpUsesRealCoordinates(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "cmfunction", GameType.CREATIVE);
        try {
            TestPlayers.op(player);
            helper.assertFalse(CoordinateOffsets.offset(player).isZero(), "the player got no offset");
            OffsetTestKit.type(player, "function " + TELEPORT_FUNCTION);
            helper.assertValueEqual(player.position(), FUNCTION_SPOT, "where the function's teleport went");
        } finally {
            TestPlayers.deop(player);
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // Angles go through the same argument type, but are never shifted
    @GameTest(environment = COMMANDS)
    public void rotationArgumentUntouched(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "cmrotation", GameType.CREATIVE);
        try {
            TestPlayers.op(player);
            Vec3 before = player.position();
            OffsetTestKit.type(player, "tp @s ~ ~ ~ 90 0");
            helper.assertValueEqual(player.getYRot(), 90.0F, "yaw");
            helper.assertValueEqual(player.position(), before, "position after turning");
        } finally {
            TestPlayers.deop(player);
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // A particle's spread is no spot, so a spread of 0 stays 0 rather than become the offset itself; its spot is read in
    // the op's frame and goes back out in it
    @GameTest(environment = COMMANDS)
    public void particleSpreadReadAsTyped(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "cmparticle", GameType.CREATIVE);
        try {
            TestPlayers.op(player);
            Offset offset = CoordinateOffsets.offset(player);
            int clientX = player.getBlockX() + offset.x();
            int clientZ = player.getBlockZ() + offset.z();
            int mark = OffsetTestKit.mark(player);
            OffsetTestKit.type(player, "particle minecraft:flame " + clientX + " 100 " + clientZ + " 0 0 0 0 1 force @s");
            ClientboundLevelParticlesPacket sent = OffsetTestKit.last(helper, player, mark, ClientboundLevelParticlesPacket.class);
            helper.assertValueEqual(List.of(sent.x(), sent.z()), List.of(clientX + 0.5, clientZ + 0.5), "spot of the particle");
            helper.assertValueEqual(List.of(sent.xDist(), sent.yDist(), sent.zDist()), List.of(0.0F, 0.0F, 0.0F), "spread");
        } finally {
            TestPlayers.deop(player);
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // Admin view changes a player's offset only when a frame starts, so the reply says to rejoin
    @GameTest(environment = COMMANDS)
    public void adminViewReplyAsksToRejoin(GameTestHelper helper) {
        TestPlayers.Recording admin = TestPlayers.joinRecording(helper, "cmadmin", GameType.CREATIVE, new BlockPos(1, 1, 1));
        try {
            TestPlayers.op(admin);
            OffsetTestKit.withCoordinates(() -> {
                run(admin.createCommandSourceStack(), "yolt adminview");
                run(admin.createCommandSourceStack(), "yolt adminview");
                return null;
            });
            List<String> replies = admin.messages.stream().map(Component::getString)
                    .filter(text -> text.startsWith("Admin view"))
                    .toList();
            helper.assertValueEqual(replies, List.of(
                    "Admin view on. Real coordinates start at your next join, respawn or dimension change.",
                    "Admin view off. Offset coordinates start at your next join, respawn or dimension change."), "replies");
        } finally {
            AdminView.set(admin, false);
            TestPlayers.deop(admin);
            TestPlayers.leave(admin);
        }
        helper.succeed();
    }

    // The chat line after a respawn goes out in the new frame, so it shows the grave where that client has it. The join
    // is near the world's centre, so the frame it leaves has an offset of its own, and the player then walks into the
    // test to die there.
    @GameTest(environment = GRAVE_NOTICE)
    public void graveNoticeShiftedAfterRespawn(GameTestHelper helper) {
        YoltConfig config = Settings.get();
        boolean lockoutBefore = config.deathLockout.enabled;
        boolean gravesBefore = config.graves.enabled;
        YoltConfig.TellLocation tellBefore = config.graves.tellLocation;
        ServerPlayer player = OffsetTestKit.joinNearTheCentre(helper, "cmgrave", GameType.SURVIVAL, new BlockPos(200, 80, 300));
        ServerPlayer current = player;
        try {
            Offset joined = CoordinateOffsets.offset(player);
            BlockPos inTheTest = helper.absolutePos(new BlockPos(1, 1, 1));
            player.teleportTo(helper.getLevel(), inTheTest.getX() + 0.5, inTheTest.getY(), inTheTest.getZ() + 0.5, Set.of(),
                    0.0F, 0.0F, true);
            config.deathLockout.enabled = false;
            config.graves.enabled = true;
            config.graves.tellLocation = YoltConfig.TellLocation.ALWAYS;
            player.getInventory().add(new ItemStack(Items.DIAMOND));
            TestPlayers.markLoaded(player);
            player.kill(player.level());
            helper.assertTrue(player.isDeadOrDying(), "the player did not die");
            Grave grave = onlyGrave(helper, player);
            int mark = OffsetTestKit.mark(player);
            current = OffsetTestKit.respawn(helper, player, helper.absolutePos(new BlockPos(5, 1, 5)), false);
            Offset offset = CoordinateOffsets.offset(current);
            helper.assertFalse(offset.isZero(), "the respawn got no offset");
            helper.assertFalse(offset.equals(joined), "the respawn's frame has the old one's offset, so a notice in either passes");
            BlockPos shown = offset.shift(grave.pos());
            helper.assertValueEqual(graveLines(current, mark), List.of("Your grave is at " + shown.getX() + ", " + shown.getY() + ", "
                    + shown.getZ() + " in the Overworld."), "grave notices after the respawn");
        } finally {
            config.deathLockout.enabled = lockoutBefore;
            config.graves.enabled = gravesBefore;
            config.graves.tellLocation = tellBefore;
            TestPlayers.leave(current);
            removeGraves(helper, player.getUUID());
        }
        helper.succeed();
    }

    // A grave in another dimension has no spot in the frame the respawn starts, so the notice names only that dimension.
    // The death's grave is recorded in the nether before the respawn reads it; its head stays in the test.
    @GameTest(environment = GRAVE_NOTICE)
    public void graveNoticeNamesOnlyAnotherDimension(GameTestHelper helper) {
        YoltConfig config = Settings.get();
        boolean lockoutBefore = config.deathLockout.enabled;
        boolean gravesBefore = config.graves.enabled;
        YoltConfig.TellLocation tellBefore = config.graves.tellLocation;
        ServerPlayer player = OffsetTestKit.join(helper, "cmgravenether", GameType.SURVIVAL);
        ServerPlayer current = player;
        try {
            config.deathLockout.enabled = false;
            config.graves.enabled = true;
            config.graves.tellLocation = YoltConfig.TellLocation.ALWAYS;
            player.getInventory().add(new ItemStack(Items.DIAMOND));
            TestPlayers.markLoaded(player);
            player.kill(player.level());
            helper.assertTrue(player.isDeadOrDying(), "the player did not die");
            Grave grave = onlyGrave(helper, player);
            GraveStore.get(helper.getLevel().getServer()).put(new Grave(grave.id(), grave.owner(), grave.ownerName(),
                    grave.profile(), Level.NETHER, grave.pos(), grave.items(), grave.createdAt(), grave.deathPos(),
                    grave.compassPending()));
            int mark = OffsetTestKit.mark(player);
            current = OffsetTestKit.respawn(helper, player, helper.absolutePos(new BlockPos(5, 1, 5)), false);
            helper.assertFalse(CoordinateOffsets.offset(current).isZero(), "the respawn got no offset");
            helper.assertValueEqual(graveLines(current, mark), List.of("Your grave is in the Nether."),
                    "grave notices after the respawn");
        } finally {
            config.deathLockout.enabled = lockoutBefore;
            config.graves.enabled = gravesBefore;
            config.graves.tellLocation = tellBefore;
            TestPlayers.leave(current);
            removeGraves(helper, player.getUUID());
        }
        helper.succeed();
    }

    // The refusal goes out before any frame, so it names only the dimension. Read from a real login check, made once the
    // dead player has gone, before the kick their death queued.
    @GameTest(environment = GRAVE_NOTICE)
    public void loginRefusalOmitsXAndZ(GameTestHelper helper) {
        YoltConfig config = Settings.get();
        boolean lockoutBefore = config.deathLockout.enabled;
        boolean gravesBefore = config.graves.enabled;
        YoltConfig.TellLocation tellBefore = config.graves.tellLocation;
        ServerPlayer player = OffsetTestKit.join(helper, "cmrefusal", GameType.SURVIVAL);
        boolean online = true;
        try {
            config.deathLockout.enabled = true;
            config.graves.enabled = true;
            config.graves.tellLocation = YoltConfig.TellLocation.ALWAYS;
            player.getInventory().add(new ItemStack(Items.DIAMOND));
            TestPlayers.markLoaded(player);
            player.kill(player.level());
            helper.assertTrue(player.isDeadOrDying(), "the player did not die");
            onlyGrave(helper, player);
            TestPlayers.leave(player);
            online = false;
            MinecraftServer server = helper.getLevel().getServer();
            NameAndId who = player.nameAndId();
            Component refusal = OffsetTestKit.withCoordinates(() -> LockoutGameTests.loginRefusal(server, who));
            helper.assertTrue(refusal != null && refusal.getString().endsWith("\nYour grave is in the Overworld."),
                    "login refusal: " + (refusal == null ? null : refusal.getString()));
        } finally {
            config.deathLockout.enabled = lockoutBefore;
            config.graves.enabled = gravesBefore;
            config.graves.tellLocation = tellBefore;
            if (online) {
                TestPlayers.leave(player);
            }
            removeGraves(helper, player.getUUID());
        }
        helper.succeed();
    }

    // The kick goes to a client still in its frame, so while that has an offset the kick names only the dimension, even
    // with the switch off by then. It comes after the death's tick, so the lockout is held across ticks. The kick is a
    // queued task, which the server can hold for a few ticks when many are queued at once, so the test waits for it.
    @GameTest(environment = GRAVE_KICK, maxTicks = KICK_TICKS)
    public void kickOmitsXAndZWhileTheFrameHasAnOffset(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "cmkick", GameType.SURVIVAL);
        boolean[] held = {false};
        boolean[] cleaned = {false};
        Runnable cleanUp = () -> {
            if (cleaned[0]) {
                return;
            }
            cleaned[0] = true;
            if (held[0]) {
                releaseKickSwitches();
            }
            ServerPlayer stillOnline = helper.getLevel().getServer().getPlayerList().getPlayer(player.getUUID());
            if (stillOnline != null) {
                TestPlayers.leave(stillOnline);
            }
            removeGraves(helper, player.getUUID());
        };
        boolean waiting = false;
        try {
            helper.assertFalse(CoordinateOffsets.offset(player).isZero(), "the player got no offset");
            helper.assertFalse(CoordinateOffsets.on(), "the switch is still on after the join");
            holdKickSwitches();
            held[0] = true;
            player.getInventory().add(new ItemStack(Items.DIAMOND));
            TestPlayers.markLoaded(player);
            player.kill(player.level());
            helper.assertTrue(player.isDeadOrDying(), "the player did not die");
            onlyGrave(helper, player);
            helper.onEachTick(() -> {
                if (cleaned[0]) {
                    return;
                }
                // Waits for the kick, up to the last tick, which checks whatever there is
                if (OffsetTestKit.sent(player, 0, ClientboundDisconnectPacket.class).isEmpty() && helper.getTick() < KICK_TICKS - 1) {
                    return;
                }
                try {
                    String reason = LockoutGameTests.disconnectReason(player);
                    helper.assertTrue(reason.startsWith("You died") && reason.endsWith("\nYour grave is in the Overworld."),
                            "kick: " + reason);
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

    private static void holdKickSwitches() {
        YoltConfig config = Settings.get();
        if (kickSwitchHolders++ == 0) {
            boolean lockout = config.deathLockout.enabled;
            boolean graves = config.graves.enabled;
            YoltConfig.TellLocation tell = config.graves.tellLocation;
            putBackKickSwitches = () -> {
                config.deathLockout.enabled = lockout;
                config.graves.enabled = graves;
                config.graves.tellLocation = tell;
            };
        }
        config.deathLockout.enabled = true;
        config.graves.enabled = true;
        config.graves.tellLocation = YoltConfig.TellLocation.ALWAYS;
    }

    private static void releaseKickSwitches() {
        if (--kickSwitchHolders == 0) {
            putBackKickSwitches.run();
        }
    }

    // The grave notices the player was sent from packet "from" on
    private static List<String> graveLines(ServerPlayer player, int from) {
        return OffsetTestKit.sent(player, from, ClientboundSystemChatPacket.class).stream()
                .map(packet -> packet.content().getString())
                .filter(text -> text.startsWith("Your grave is "))
                .toList();
    }

    private static Grave onlyGrave(GameTestHelper helper, ServerPlayer player) {
        List<Grave> graves = GraveStore.get(helper.getLevel().getServer()).all().stream()
                .filter(grave -> grave.owner().equals(player.getUUID()))
                .toList();
        helper.assertValueEqual(graves.size(), 1, "graves for the player");
        return graves.getFirst();
    }

    private static void removeGraves(GameTestHelper helper, UUID owner) {
        GraveStore store = GraveStore.get(helper.getLevel().getServer());
        for (Grave grave : List.copyOf(store.all())) {
            if (grave.owner().equals(owner)) {
                store.remove(grave.id());
                helper.getLevel().setBlock(grave.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    private static void run(CommandSourceStack source, String command) {
        source.getServer().getCommands().performPrefixedCommand(source, command);
    }
}
