package bobbynooby.dev.hardcore.client;

import bobbynooby.dev.hardcore.BloodMoon;
import bobbynooby.dev.hardcore.ClientBridge;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.world.BossEvent;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static bobbynooby.dev.hardcore.client.ClientKit.check;

// A blood moon an op starts with the typed command, as a real client sees it: the red bar with its title at the top of
// the screen, darkening the sky
public class BloodMoonAloneClientTest implements FabricClientGameTest {
    // The bar's title and the rising line as the shipped texts word them
    private static final String TITLE = "Blood Moon";
    private static final String RISEN = "The blood moon has risen. Mobs are stronger and more numerous until dawn.";
    private static final String STARTED = "Started a blood moon.";
    // The darkening takes about 80 ticks to fade once its bar is gone, and could colour the next test's shots
    private static final int UNDARKEN_TICKS = 100;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        ClientKit.withServer(ctx, (server, connection) -> {
            Runnable putBack = server.computeOnServer(s -> ClientBridge.use(c -> c.bloodMoon.enabled = true));
            try {
                check(!server.computeOnServer(s -> BloodMoon.active()), () -> "a blood moon is up before the command");
                check(ctx.computeOnClient(mc -> bars(mc).isEmpty()), () -> "the client shows a boss bar before the command");
                server.runOnServer(s -> ClientKit.op(connection.getServerPlayer()));
                ClientKit.waitOnClient(ctx, ClientKit.LONG_TIMEOUT, mc -> ClientKit.clientHasCommand(mc, "yolt-hardcore"),
                        mc -> "an op's command tree has no /yolt-hardcore");
                ClientKit.clearChat(ctx);
                int mark = ClientKit.mark();
                ClientKit.type(ctx, connection, "/yolt-hardcore bloodmoon start");
                ClientKit.waitForChat(ctx, mark, STARTED);
                ClientKit.waitForChat(ctx, mark, RISEN);
                check(server.computeOnServer(s -> BloodMoon.active()), () -> "no blood moon is up after the command said so");
                ClientKit.waitOnClient(ctx, ClientKit.LONG_TIMEOUT, mc -> bars(mc).stream().anyMatch(bar ->
                                bar.getName().getString().equals(TITLE) && bar.getColor() == BossEvent.BossBarColor.RED
                                        && bar.shouldDarkenScreen()),
                        mc -> "the client shows no red, darkening \"" + TITLE + "\" bar, only " + bars(mc).stream()
                                .map(bar -> bar.getName().getString() + " " + bar.getColor()).toList());
                ctx.waitTicks(ClientKit.SETTLE_TICKS);
                ClientKit.screenshot(ctx, "hardcore-alone-blood-moon");
            } finally {
                ClientKit.runAll(List.of(
                        () -> server.runOnServer(BloodMoon::stop),
                        () -> ClientKit.waitOnClient(ctx, ClientKit.LONG_TIMEOUT, mc -> bars(mc).isEmpty(),
                                mc -> "the client still shows a boss bar after the blood moon stopped"),
                        () -> ctx.waitTicks(UNDARKEN_TICKS),
                        // The op list is a file in the client's run folder, which every later server reads
                        () -> server.runOnServer(s -> ClientKit.deop(connection.getServerPlayer())),
                        () -> server.runOnServer(s -> putBack.run()),
                        () -> ClientKit.clearChat(ctx)));
            }
        });
    }

    // The overlay keeps its bars to itself, and Minecraft 26.3 keeps its own names at run time, so the field is read
    // by name
    @SuppressWarnings("unchecked")
    private static List<BossEvent> bars(Minecraft mc) {
        try {
            Field events = BossHealthOverlay.class.getDeclaredField("events");
            events.setAccessible(true);
            return List.copyOf(((Map<?, ? extends BossEvent>) events.get(mc.gui.hud.getBossOverlay())).values());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not read the client's boss bars", e);
        }
    }
}
