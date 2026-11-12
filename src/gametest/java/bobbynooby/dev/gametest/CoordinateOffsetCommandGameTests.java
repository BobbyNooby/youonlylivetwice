package bobbynooby.dev.gametest;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.coords.CoordinateOffsets;
import bobbynooby.dev.coords.Offset;
import bobbynooby.dev.graves.Grave;
import bobbynooby.dev.graves.GraveStore;
import bobbynooby.dev.lockout.DeathLockout;
import bobbynooby.dev.visibility.AdminView;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.advancements.AdvancementRewards;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

// Coordinates an op with an offset types, and the texts that would tell a player real coordinates. The tests join
// players and ops them, so the class has batches of its own.
public class CoordinateOffsetCommandGameTests {
    private static final String COMMANDS = "you-only-live-twice-gametest:coordinate_offset_commands";
    // Holds the lockout and graves.tellLocation while a dead player's notice is read
    private static final String GRAVE_NOTICE = "you-only-live-twice-gametest:coordinate_offset_grave_notice";
    // The test data pack's function, which teleports whoever runs it to a fixed real spot, written as "tp @s 40 120 -73"
    private static final Identifier TELEPORT_FUNCTION = Identifier.fromNamespaceAndPath("you-only-live-twice-gametest", "offset_tp");
    private static final Vec3 FUNCTION_SPOT = new Vec3(40.5, 120.0, -72.5);

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
            type(player, "tp @s " + clientX + " 120 " + clientZ);
            Vec3 landed = new Vec3(clientX + 0.5 - offset.x(), 120.0, clientZ + 0.5 - offset.z());
            helper.assertValueEqual(player.position(), landed, "where the typed teleport went");
            type(player, "tp @s ~1 ~ ~");
            helper.assertValueEqual(player.position(), landed.add(1.0, 0.0, 0.0), "where the relative teleport went");
        } finally {
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
            type(player, "function " + TELEPORT_FUNCTION);
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
            type(player, "tp @s ~ ~ ~ 90 0");
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
            type(player, "particle minecraft:flame " + clientX + " 100 " + clientZ + " 0 0 0 0 1 force @s");
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
            helper.assertValueEqual(replies, List.of("Admin view on. Rejoin to see real coordinates.",
                    "Admin view off. Rejoin to see offset coordinates."), "replies");
        } finally {
            AdminView.set(admin, false);
            TestPlayers.deop(admin);
            TestPlayers.leave(admin);
        }
        helper.succeed();
    }

    // The chat line after a respawn goes out in the new frame, so it shows the grave where that client has it
    @GameTest(environment = GRAVE_NOTICE)
    public void graveNoticeShiftedAfterRespawn(GameTestHelper helper) {
        YoltConfig config = Settings.get();
        boolean lockoutBefore = config.deathLockout.enabled;
        boolean gravesBefore = config.graves.enabled;
        YoltConfig.TellLocation tellBefore = config.graves.tellLocation;
        ServerPlayer player = OffsetTestKit.join(helper, "cmgrave", GameType.SURVIVAL);
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
            int mark = OffsetTestKit.mark(player);
            current = OffsetTestKit.respawn(helper, player, helper.absolutePos(new BlockPos(5, 1, 5)), false);
            Offset offset = CoordinateOffsets.offset(current);
            helper.assertFalse(offset.isZero(), "the respawn got no offset");
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

    // The refusal goes out before any frame, so it names only the dimension
    @GameTest(environment = GRAVE_NOTICE)
    public void loginRefusalOmitsXAndZ(GameTestHelper helper) {
        YoltConfig config = Settings.get();
        boolean lockoutBefore = config.deathLockout.enabled;
        boolean gravesBefore = config.graves.enabled;
        YoltConfig.TellLocation tellBefore = config.graves.tellLocation;
        ServerPlayer player = OffsetTestKit.join(helper, "cmrefusal", GameType.SURVIVAL);
        try {
            config.deathLockout.enabled = true;
            config.graves.enabled = true;
            config.graves.tellLocation = YoltConfig.TellLocation.ALWAYS;
            player.getInventory().add(new ItemStack(Items.DIAMOND));
            TestPlayers.markLoaded(player);
            player.kill(player.level());
            helper.assertTrue(player.isDeadOrDying(), "the player did not die");
            onlyGrave(helper, player);
            MinecraftServer server = helper.getLevel().getServer();
            Component refusal = OffsetTestKit.withCoordinates(
                    () -> DeathLockout.refusal(server, player.getUUID(), player.getGameProfile().name()));
            helper.assertTrue(refusal != null && refusal.getString().endsWith("\nYour grave is in the Overworld."),
                    "login refusal: " + (refusal == null ? null : refusal.getString()));
        } finally {
            config.deathLockout.enabled = lockoutBefore;
            config.graves.enabled = gravesBefore;
            config.graves.tellLocation = tellBefore;
            TestPlayers.leave(player);
            removeGraves(helper, player.getUUID());
        }
        helper.succeed();
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

    // As the player's client sends a typed command, which is all that is read in their frame
    private static void type(ServerPlayer player, String command) {
        player.connection.handleChatCommand(new ServerboundChatCommandPacket(command));
    }
}
