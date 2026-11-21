package bobbynooby.dev.gametest.client;

import bobbynooby.dev.anvils.YoltAnvils;
import bobbynooby.dev.config.HardcoreConfig;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.elytra.YoltElytra;
import bobbynooby.dev.mending.YoltMending;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.gamerules.GameRules;

import java.util.ArrayList;
import java.util.List;

public class SingleplayerClientTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!ClientTests.selected(getClass())) {
            return;
        }
        try (TestSingleplayerContext world = ctx.worldBuilder().create()) {
            if (world.getServer().computeOnServer(s -> Settings.active())) {
                throw new AssertionError("YOLT is active in single-player");
            }
            // The anvil and Mending rules ship on and the elytra one off, and the test set-up turns the first two off at
            // every server start, so all three are switched on for the reading. The switches are static, so later client
            // tests in this JVM would see them: they go back in the same task, before the server ticks again.
            List<String> wrong = world.getServer().computeOnServer(s -> {
                HardcoreConfig.HardcoreSection hardcore = Settings.get().hardcore;
                boolean anvilWas = hardcore.anvil.enabled;
                boolean mendingWas = hardcore.mending.enabled;
                boolean elytraWas = hardcore.elytra.enabled;
                hardcore.anvil.enabled = true;
                hardcore.mending.enabled = true;
                hardcore.elytra.enabled = true;
                try {
                    List<String> found = new ArrayList<>();
                    hostedAndOff(found, "the anvil rule", YoltAnvils.hosted(), YoltAnvils.settings().enabled());
                    hostedAndOff(found, "the Mending rule", YoltMending.hosted(), YoltMending.settings().enabled());
                    hostedAndOff(found, "the elytra rule", YoltElytra.hosted(), YoltElytra.settings().enabled());
                    return found;
                } finally {
                    hardcore.anvil.enabled = anvilWas;
                    hardcore.mending.enabled = mendingWas;
                    hardcore.elytra.enabled = elytraWas;
                }
            });
            if (!wrong.isEmpty()) {
                throw new AssertionError("in single-player with its switch on, " + String.join(", ", wrong));
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

    // A hosted rule takes its switch from YOLT, so it stays off wherever YOLT is inactive
    private static void hostedAndOff(List<String> wrong, String rule, boolean hosted, boolean on) {
        if (!hosted) {
            wrong.add(rule + " isn't hosted");
        }
        if (on) {
            wrong.add(rule + " is on");
        }
    }
}
