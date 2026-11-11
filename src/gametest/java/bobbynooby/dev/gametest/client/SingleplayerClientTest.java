package bobbynooby.dev.gametest.client;

import bobbynooby.dev.config.Settings;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.gamerules.GameRules;

public class SingleplayerClientTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        try (TestSingleplayerContext world = ctx.worldBuilder().create()) {
            if (world.getServer().computeOnServer(s -> Settings.active())) {
                throw new AssertionError("YOLT is active in single-player");
            }
            // YOLT turns this rule on when it starts, so the default proves it never ran here
            boolean atDefault = world.getServer().computeOnServer(s ->
                    s.getGameRules().get(GameRules.IMMEDIATE_RESPAWN).equals(GameRules.IMMEDIATE_RESPAWN.defaultValue()));
            if (!atDefault) {
                throw new AssertionError("immediate respawn is not at its vanilla default in single-player");
            }
            // YOLT would have sent the reduced flag as the player joined, so by now it would have reached the client
            world.getConnection().waitForClientboundPackets();
            if (ctx.computeOnClient(Minecraft::showOnlyReducedInfo)) {
                throw new AssertionError("the debug screen is reduced in single-player");
            }
        }
    }
}
