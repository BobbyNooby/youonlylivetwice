package bobbynooby.dev.gametest.client;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.gametest.TestPlayers;
import bobbynooby.dev.visibility.AdminView;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.border.WorldBorder;

import java.util.List;

/**
 * What a player's own client is told about where it is: the debug screen (F3), the server brand it names, and the spawn
 * a plain compass points at. Admin view gives an op the full screen and the real spawn back.
 */
public class DebugPrivacyClientTest implements FabricClientGameTest {
    // Smaller than the whole-world border the compass bundle starts with, so the client's border shows which one it kept
    private static final double REAL_BORDER = 1_000_000.0;
    // A million blocks in from the world's northern edge, at the player's own X
    private static final int NORTH_TARGET_Z = -28_999_984;
    private static final float NORTH = 180.0F;

    private record Border(double size, double centerX, double centerZ) {
        static Border of(WorldBorder border) {
            return new Border(border.getSize(), border.getCenterX(), border.getCenterZ());
        }
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        // The config outlives each test's server, and between servers nothing else reads it
        boolean brandWas = Settings.get().hide.serverBrand;
        try {
            ClientTests.withServer(ctx, server -> server.runOnServer(s -> {
                // The brand goes out as the client logs in. The world is new for each test, so the border needs no
                // setting back.
                Settings.get().hide.serverBrand = true;
                s.overworld().getWorldBorder().setSize(REAL_BORDER);
            }), (server, connection) -> {
                boolean wasOp = server.computeOnServer(s -> s.getPlayerList().isOp(connection.getServerPlayer().nameAndId()));
                try {
                    showCompassNorth(ctx, server, connection);
                    showReducedDebugScreen(ctx);
                    showAdminDebugScreen(ctx, server, connection);
                } finally {
                    ClientTests.runAll(List.of(
                            // The client keeps the overlay up into the tests after this one otherwise
                            () -> ctx.runOnClient(mc -> mc.debugEntries.setOverlayVisible(false)),
                            () -> server.runOnServer(s -> {
                                ServerPlayer self = connection.getServerPlayer();
                                // Toggled, so the client is sent the reduced screen and the north target again
                                if (self.getAttachedOrElse(AdminView.ATTACHMENT, false)) {
                                    AdminView.toggle(self);
                                }
                                // Only undo the op this test gave
                                if (!wasOp) {
                                    TestPlayers.deop(self);
                                }
                            })));
                }
            });
        } finally {
            // Only once the server is gone, so a join that failed can't leave it on for the tests after this one
            Settings.get().hide.serverBrand = brandWas;
        }
    }

    // A plain compass in the hand while facing north, whose needle points straight up
    private static void showCompassNorth(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                         TestDedicatedServerConnection connection) {
        server.runOnServer(s -> connection.getServerPlayer().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COMPASS)));
        ClientTests.waitOnClient(ctx, ClientGameTestContext.DEFAULT_TIMEOUT, mc -> mc.player.getMainHandItem().is(Items.COMPASS),
                mc -> "the client never got the compass");
        ClientTests.waitUntilHandsUp(ctx);
        ctx.getInput().lookAt(NORTH, 0.0F);

        // With ticks frozen the client draws the needle right at its target, rather than swinging it there over a while
        server.runOnServer(s -> s.tickRateManager().setFrozen(true));
        try {
            ClientTests.waitOnClient(ctx, ClientGameTestContext.DEFAULT_TIMEOUT, mc -> !mc.level.tickRateManager().runsNormally(),
                    mc -> "the client's ticks never froze");
            checkNorthTarget(ctx, server, connection);
            ClientTests.screenshot(ctx, "07-compass-north");
        } finally {
            // A new server's ticks run
            server.runOnServer(s -> s.tickRateManager().setFrozen(false));
        }
        // Otherwise the debug screen's server line would still say frozen
        ClientTests.waitOnClient(ctx, ClientGameTestContext.DEFAULT_TIMEOUT, mc -> mc.level.tickRateManager().runsNormally(),
                mc -> "the client's ticks stayed frozen");
    }

    private static void checkNorthTarget(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                         TestDedicatedServerConnection connection) {
        Border real = server.computeOnServer(s -> Border.of(connection.getServerPlayer().level().getWorldBorder()));
        if (real.size() != REAL_BORDER) {
            throw new AssertionError("the server's border is " + real.size() + " wide, not " + REAL_BORDER);
        }
        ctx.runOnClient(mc -> {
            GlobalPos target = mc.level.getRespawnData().globalPos();
            if (!target.dimension().equals(mc.level.dimension())) {
                throw new AssertionError("the client's compass target is in " + target.dimension().identifier()
                        + ", not in " + mc.level.dimension().identifier());
            }
            if (target.pos().getX() != mc.player.getBlockX() || target.pos().getZ() != NORTH_TARGET_Z) {
                throw new AssertionError("the client's compass target is at " + target.pos().toShortString()
                        + ", not at x " + mc.player.getBlockX() + ", z " + NORTH_TARGET_Z);
            }
            // The whole-world border that let the target in has given way to the real one
            Border border = Border.of(mc.level.getWorldBorder());
            if (!border.equals(real)) {
                throw new AssertionError("the client's border is " + border + ", not the server's " + real);
            }
            if (Math.abs(Mth.wrapDegrees(mc.player.getYRot() - NORTH)) > 1.0E-3F) {
                throw new AssertionError("the client faces " + mc.player.getYRot() + ", not north");
            }
        });
    }

    private static void showReducedDebugScreen(ClientGameTestContext ctx) {
        // Sent at the join
        ClientTests.waitOnClient(ctx, ClientGameTestContext.DEFAULT_TIMEOUT, Minecraft::showOnlyReducedInfo,
                mc -> "the client never got the reduced debug screen");
        ctx.runOnClient(mc -> {
            mc.debugEntries.setOverlayVisible(true);
            // The control: the screen is up, with the line that names the server's brand
            if (!mc.debugEntries.isCurrentlyEnabled(DebugScreenEntries.TPS)) {
                throw new AssertionError("the debug screen doesn't show its server line");
            }
            if (mc.debugEntries.isCurrentlyEnabled(DebugScreenEntries.PLAYER_POSITION)) {
                throw new AssertionError("the reduced debug screen shows the player's position");
            }
            String brand = mc.getConnection().serverBrand();
            if (!"vanilla".equals(brand)) {
                throw new AssertionError("the client was told the server's brand is " + brand + ", not vanilla");
            }
        });
        ClientTests.screenshot(ctx, "10-f3-reduced");
    }

    private static void showAdminDebugScreen(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                             TestDedicatedServerConnection connection) {
        GlobalPos realSpawn = server.computeOnServer(s -> {
            ServerPlayer self = connection.getServerPlayer();
            // Admin view only counts for ops. Toggled, since set only writes the flag and sends the client nothing.
            TestPlayers.op(self);
            if (!AdminView.toggle(self)) {
                throw new AssertionError("admin view was already on, so the toggle turned it off");
            }
            return self.level().getRespawnData().globalPos();
        });
        ClientTests.waitOnClient(ctx, ClientGameTestContext.DEFAULT_TIMEOUT,
                mc -> !mc.showOnlyReducedInfo() && mc.level.getRespawnData().globalPos().equals(realSpawn),
                mc -> mc.showOnlyReducedInfo() ? "admin view did not give the client the full debug screen"
                        : "admin view gave the client the spawn " + mc.level.getRespawnData().globalPos() + ", not the real "
                        + realSpawn);
        ctx.runOnClient(mc -> {
            // The default debug profile has it in the overlay
            if (!mc.debugEntries.isCurrentlyEnabled(DebugScreenEntries.PLAYER_POSITION)) {
                throw new AssertionError("the full debug screen doesn't show the player's position");
            }
        });
        ClientTests.screenshot(ctx, "10-f3-admin");
    }
}
