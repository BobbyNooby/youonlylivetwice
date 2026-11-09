package bobbynooby.dev.gametest;

import bobbynooby.dev.config.Settings;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ScoreAccess;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

// Each test flips the switch and restores it inside one tick, so no other test sees it
public class CommandRestrictionGameTests {
    @GameTest
    public void restrictedHelpGetsReply(GameTestHelper helper) {
        TestPlayers.Recording player = TestPlayers.joinRecording(helper, "Help", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        boolean before = Settings.get().restrictCommands;
        try {
            Settings.get().restrictCommands = true;
            run(player, "help");

            boolean answered = player.messages.stream().map(Component::getString).anyMatch("But nobody answered...."::equals);
            helper.assertTrue(answered, "restricted player got no reply to help");
        } finally {
            Settings.get().restrictCommands = before;
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // trigger is a level 0 command, so only the restriction can keep it from running
    @GameTest
    public void restrictedCommandDoesNothing(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Scoreboard scoreboard = server.getScoreboard();
        Objective objective = scoreboard.addObjective("yolt_t", ObjectiveCriteria.TRIGGER, Component.literal("yolt_t"),
                ObjectiveCriteria.RenderType.INTEGER, false, null);
        ServerPlayer player = TestPlayers.join(helper, "Trigger", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        boolean before = Settings.get().restrictCommands;
        try {
            ScoreAccess score = scoreboard.getOrCreatePlayerScore(player, objective);
            score.unlock();

            Settings.get().restrictCommands = true;
            run(player, "trigger yolt_t set 1");
            helper.assertValueEqual(score.get(), 0, "score after a restricted trigger");

            Settings.get().restrictCommands = false;
            run(player, "trigger yolt_t set 1");
            helper.assertValueEqual(score.get(), 1, "score after an unrestricted trigger");
        } finally {
            Settings.get().restrictCommands = before;
            scoreboard.removeObjective(objective);
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    private static void run(ServerPlayer player, String command) {
        player.level().getServer().getCommands().performPrefixedCommand(player.createCommandSourceStack(), command);
    }
}
