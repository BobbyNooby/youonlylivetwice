package bobbynooby.dev.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;

public class SmokeGameTests {
    @GameTest
    public void testPlayerIsSurvivalAndNamed(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "Alex", GameType.SURVIVAL, new BlockPos(1, 1, 1));

        helper.assertTrue(player.gameMode() == GameType.SURVIVAL, "test player is not in survival");
        helper.assertTrue(player.getGameProfile().name().startsWith("Alex"), "test player name does not start with Alex");
        helper.assertTrue(helper.getLevel().getServer().getPlayerList().getPlayer(player.getUUID()) != null, "test player is not in the player list");

        TestPlayers.leave(player);
        helper.succeed();
    }

    // Survival is also a new player's default, so this checks the requested mode is really applied
    @GameTest
    public void testPlayerGetsRequestedGameMode(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "Steve", GameType.CREATIVE, new BlockPos(1, 1, 1));

        helper.assertTrue(player.gameMode() == GameType.CREATIVE, "test player is not in creative");

        TestPlayers.leave(player);
        helper.succeed();
    }

    @GameTest
    public void modLoadsAndImmediateRespawnIsOn(GameTestHelper helper) {
        helper.assertTrue(FabricLoader.getInstance().isModLoaded("you-only-live-twice"), "mod not loaded");
        helper.assertTrue(helper.getLevel().getGameRules().get(GameRules.IMMEDIATE_RESPAWN), "immediate respawn is off");
        helper.succeed();
    }
}
