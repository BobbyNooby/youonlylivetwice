package bobbynooby.dev.gametest.client;

import bobbynooby.dev.anvils.YoltAnvils;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.elytra.YoltElytra;
import bobbynooby.dev.gametest.OfflineGameTests;
import bobbynooby.dev.hardcore.HardcoreConfig;
import bobbynooby.dev.mending.YoltMending;
import bobbynooby.dev.world.RandomSpawn;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

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
            // The check a first join's spot gets as the player is placed stays out too. Random spawn is switched on for it,
            // and a spot far outside the area it picks from comes back as it is, with nothing logged. The switch goes back
            // in the same task.
            String placement = world.getServer().computeOnServer(s -> {
                YoltConfig config = Settings.get();
                boolean randomWas = config.randomSpawn;
                config.randomSpawn = true;
                try {
                    ServerLevel overworld = s.overworld();
                    WorldBorder border = overworld.getWorldBorder();
                    Vec3 far = new Vec3(border.getCenterX() + 20_000.5, 64, border.getCenterZ() + 0.5);
                    if (RandomSpawn.insidePickArea(overworld, far)) {
                        return "the area random spawn picks from holds " + far;
                    }
                    Vec3[] spot = new Vec3[1];
                    List<String> log = OfflineGameTests.yoltLog(() -> spot[0] = RandomSpawn.firstJoinSpot(overworld, far, "Host"));
                    if (spot[0] != far) {
                        return "a first join's spot " + far + " became " + spot[0];
                    }
                    return log.isEmpty() ? "" : "the first join check logged " + log;
                } finally {
                    config.randomSpawn = randomWas;
                }
            });
            if (!placement.isEmpty()) {
                throw new AssertionError("in single-player, " + placement);
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
