package bobbynooby.dev.gametest.client;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.core.Yolt;
import bobbynooby.dev.gametest.TestPlayers;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

public final class ClientTests {
    // The screen is 35 degrees tall each way at the default field of view, so this keeps an entity fully on it
    private static final double MAX_VIEW_ANGLE = 30.0;
    // The hand's height goes from 0 when lowered to 1 when fully up
    private static final float HAND_UP = 0.99F;

    private ClientTests() {
    }

    // The server runs inside the client process, but it is a real dedicated server, so YOLT is active on it
    public static void withServer(ClientGameTestContext ctx, BiConsumer<TestDedicatedServerContext, TestDedicatedServerConnection> body) {
        withServer(ctx, server -> {}, body);
    }

    // beforeConnect runs once the server is up and before the client joins, for settings that only count at a join
    public static void withServer(ClientGameTestContext ctx, Consumer<TestDedicatedServerContext> beforeConnect,
                                  BiConsumer<TestDedicatedServerContext, TestDedicatedServerConnection> body) {
        try (TestDedicatedServerContext server = ctx.worldBuilder().createServer()) {
            beforeConnect.accept(server);
            try (TestDedicatedServerConnection connection = server.connect()) {
                connection.waitForChunksRender();
                // Join toasts (offline chat warning, social tutorial) would cover the top right of every screenshot
                ctx.runOnClient(mc -> mc.gui.toastManager().clear());
                body.accept(server, connection);
            }
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
        // The HUD comes back even if the screenshot fails, and a failure there doesn't hide that one
        runAll(List.of(() -> screenshot(ctx, name), () -> {
            ctx.getInput().pressKey(options -> options.keyToggleGui);
            ctx.waitFor(mc -> !mc.gui.hud.isHidden());
        }));
    }

    // Like waitFor, but a timeout says what was wrong at the end, read on the server
    public static void waitOnServer(TestServerContext server, int timeout, Predicate<MinecraftServer> condition,
                                    Function<MinecraftServer, String> failure) {
        try {
            server.waitFor(condition, timeout);
        } catch (AssertionError e) {
            throw explained(e, () -> server.computeOnServer(failure::apply));
        }
    }

    public static void waitOnClient(ClientGameTestContext ctx, int timeout, Predicate<Minecraft> condition,
                                    Function<Minecraft, String> failure) {
        try {
            ctx.waitFor(condition, timeout);
        } catch (AssertionError e) {
            throw explained(e, () -> ctx.computeOnClient(failure::apply));
        }
    }

    // Reading what was wrong can fail too, say when the entity it looks at is gone. The wait's own failure is kept.
    static AssertionError explained(AssertionError failed, Supplier<String> message) {
        try {
            return new AssertionError(message.get(), failed);
        } catch (RuntimeException | Error e) {
            failed.addSuppressed(e);
            return failed;
        }
    }

    /**
     * Runs every step even when one fails, so one broken cleanup step can't leave the rest undone for the tests after
     * it in this JVM. The first failure is thrown at the end, with any later ones attached.
     */
    public static void runAll(List<Runnable> steps) {
        Throwable first = null;
        for (Runnable step : steps) {
            try {
                step.run();
            } catch (RuntimeException | Error e) {
                Yolt.LOGGER.error("A client test step failed", e);
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
        }
        if (first instanceof RuntimeException e) {
            throw e;
        }
        if (first instanceof Error e) {
            throw e;
        }
    }

    // With bodies on, a test player's leave would leave a body behind, so they are off for it
    public static void leaveWithoutBody(MinecraftServer server, UUID id) {
        ServerPlayer player = server.getPlayerList().getPlayer(id);
        if (player == null) {
            return;
        }
        YoltConfig.BodiesSection bodies = Settings.get().bodies;
        boolean enabled = bodies.enabled;
        bodies.enabled = false;
        try {
            TestPlayers.leave(player);
        } finally {
            bodies.enabled = enabled;
        }
    }

    // A newly held item lowers the hand and brings it back up, so a shot of it waits until the hand is all the way up
    public static void waitUntilHandUp(ClientGameTestContext ctx) {
        waitOnClient(ctx, ClientGameTestContext.DEFAULT_TIMEOUT, mc -> {
            FirstPersonHandsAndItemsRenderState hands = new FirstPersonHandsAndItemsRenderState();
            mc.player.firstPersonHandsAndItems().extractRenderState(mc.player, 1.0F, hands);
            return hands.mainHandItem == mc.player.getMainHandItem() && hands.mainHandHeight >= HAND_UP;
        }, mc -> "the hand never came all the way up with " + mc.player.getMainHandItem());
    }

    // A new prone body on the client tips over into its pose over about half a second
    public static void waitUntilProne(ClientGameTestContext ctx, UUID id) {
        waitOnClient(ctx, ClientGameTestContext.DEFAULT_TIMEOUT,
                mc -> mc.level.getEntity(id) instanceof LivingEntity body && body.getSwimAmount(0.0F) >= 1.0F,
                mc -> mc.level.getEntity(id) == null ? "the client has no body " + id : "the client's body never finished lying down");
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
