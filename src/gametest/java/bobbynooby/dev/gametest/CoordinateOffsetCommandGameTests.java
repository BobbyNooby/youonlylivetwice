package bobbynooby.dev.gametest;

import bobbynooby.dev.coords.CoordinateOffsets;
import bobbynooby.dev.coords.Offset;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

// Coordinates an op with an offset types, and the texts that would tell a player real coordinates. The tests join
// players and ops them, so the class has batches of its own.
public class CoordinateOffsetCommandGameTests {
    private static final String COMMANDS = "you-only-live-twice-gametest:coordinate_offset_commands";

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
            run(player.createCommandSourceStack(), "tp @s " + clientX + " 120 " + clientZ);
            Vec3 landed = new Vec3(clientX + 0.5 - offset.x(), 120.0, clientZ + 0.5 - offset.z());
            helper.assertValueEqual(player.position(), landed, "where the typed teleport went");
            run(player.createCommandSourceStack(), "tp @s ~1 ~ ~");
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

    // Angles go through the same argument type, but are never shifted
    @GameTest(environment = COMMANDS)
    public void rotationArgumentUntouched(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "cmrotation", GameType.CREATIVE);
        try {
            TestPlayers.op(player);
            Vec3 before = player.position();
            run(player.createCommandSourceStack(), "tp @s ~ ~ ~ 90 0");
            helper.assertValueEqual(player.getYRot(), 90.0F, "yaw");
            helper.assertValueEqual(player.position(), before, "position after turning");
        } finally {
            TestPlayers.deop(player);
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    private static void run(CommandSourceStack source, String command) {
        source.getServer().getCommands().performPrefixedCommand(source, command);
    }
}
