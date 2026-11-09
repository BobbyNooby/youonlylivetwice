package bobbynooby.dev.gametest.client;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;

public final class ClientTests {
    // The screen is 35 degrees tall each way at the default field of view, so this keeps an entity fully on it
    private static final double MAX_VIEW_ANGLE = 30.0;

    private ClientTests() {
    }

    // The server runs inside the client process, but it is a real dedicated server, so YOLT is active on it
    public static void withServer(ClientGameTestContext ctx, BiConsumer<TestDedicatedServerContext, TestDedicatedServerConnection> body) {
        try (TestDedicatedServerContext server = ctx.worldBuilder().createServer();
             TestDedicatedServerConnection connection = server.connect()) {
            connection.waitForChunksRender();
            // Join toasts (offline chat warning, social tutorial) would cover the top right of every screenshot
            ctx.runOnClient(mc -> mc.gui.toastManager().clear());
            body.accept(server, connection);
        }
    }

    public static void screenshot(ClientGameTestContext ctx, String name) {
        ctx.runOnClient(mc -> mc.gui.toastManager().clear());
        // No counter prefix, so the CI artifact keeps the plain file names
        ctx.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix());
    }

    // Without the HUD the crosshair covers nothing. It also hides every nametag, so not for nametag shots.
    public static void screenshotWithoutHud(ClientGameTestContext ctx, String name) {
        ctx.getInput().pressKey(options -> options.keyToggleGui);
        ctx.waitFor(mc -> mc.gui.hud.isHidden());
        try {
            screenshot(ctx, name);
        } finally {
            ctx.getInput().pressKey(options -> options.keyToggleGui);
            ctx.waitFor(mc -> !mc.gui.hud.isHidden());
        }
    }

    // Like waitFor, but a timeout says what was wrong at the end, read on the server
    public static void waitOnServer(TestServerContext server, int timeout, Predicate<MinecraftServer> condition,
                                    Function<MinecraftServer, String> failure) {
        try {
            server.waitFor(condition, timeout);
        } catch (AssertionError e) {
            throw new AssertionError(server.computeOnServer(failure::apply), e);
        }
    }

    public static void waitOnClient(ClientGameTestContext ctx, int timeout, Predicate<Minecraft> condition,
                                    Function<Minecraft, String> failure) {
        try {
            ctx.waitFor(condition, timeout);
        } catch (AssertionError e) {
            throw new AssertionError(ctx.computeOnClient(failure::apply), e);
        }
    }

    // Checks the camera points at the entity as the client has it, so a screenshot taken now shows it
    public static void assertInView(ClientGameTestContext ctx, UUID id, String what) {
        double angle = ctx.computeOnClient(mc -> {
            Entity entity = mc.level.getEntity(id);
            if (entity == null) {
                throw new AssertionError("the client has no " + what);
            }
            Vec3 toEntity = entity.getBoundingBox().getCenter().subtract(mc.player.getEyePosition()).normalize();
            return Math.toDegrees(Math.acos(Math.clamp(toEntity.dot(mc.player.getLookAngle()), -1.0, 1.0)));
        });
        if (angle > MAX_VIEW_ANGLE) {
            throw new AssertionError("the " + what + " is " + angle + " degrees off the middle of the screen");
        }
    }
}
