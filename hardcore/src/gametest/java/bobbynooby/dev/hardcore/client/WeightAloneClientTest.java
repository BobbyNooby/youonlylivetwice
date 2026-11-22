package bobbynooby.dev.hardcore.client;

import bobbynooby.dev.hardcore.ClientBridge;
import bobbynooby.dev.hardcore.Slowdowns;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;

import static bobbynooby.dev.hardcore.client.ClientKit.check;

// Weight as a real client feels it: a heavy load slows the server's player, the slowdown reaches the client's own
// movement speed, and the client, which moves itself, walks that much slower
public class WeightAloneClientTest implements FabricClientGameTest {
    // 12 stacks of cobblestone weigh 36: (36 - 16) / (144 - 16) × 0.5 is 0.078, which the slowdown's steps make 0.1
    private static final int LOAD_STACKS = 12;
    private static final double SLOWED = 0.1;
    private static final double CLOSE = 1.0E-6;
    // A walk on flat ground settles at a pace in step with the speed within a few ticks, so the two paces differ by the
    // slowdown alone. The pace is measured over ticks of its own once settled: a key held for a count of ticks can move
    // the player for one tick more or less, which is 5% of a 20 tick walk.
    private static final double PACE_CLOSE = 0.002;
    private static final int SETTLE_PACE_TICKS = 20;
    private static final int PACE_TICKS = 10;
    private static final float SOUTH = 0.0F;
    private static final float LEVEL = 0.0F;
    private static final double AT_HOME = 0.01;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        ClientKit.withServer(ctx, (server, connection) -> {
            Vec3 home = server.computeOnServer(s -> connection.getServerPlayer().position());
            // Last before the try, so nothing can fail between the swap and what puts it back
            Runnable putBack = server.computeOnServer(s -> ClientBridge.use(c -> c.weight.enabled = true));
            try {
                check(server.computeOnServer(s -> connection.getServerPlayer().gameMode()) == GameType.SURVIVAL,
                        () -> "the client's player isn't in survival, where weight counts");
                ClientKit.waitOnClient(ctx, ClientKit.LONG_TIMEOUT, mc -> Math.abs(speed(mc) - baseSpeed(mc)) < CLOSE,
                        mc -> "the client is slowed before it carries anything, at speed " + speed(mc));
                double free = pace(ctx, server, connection, home);

                server.runOnServer(s -> {
                    ServerPlayer player = connection.getServerPlayer();
                    for (int i = 0; i < LOAD_STACKS; i++) {
                        player.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));
                    }
                });
                // The load is weighed again every 10 ticks
                ClientKit.waitOnServer(server, ClientKit.LONG_TIMEOUT,
                        s -> Math.abs(Slowdowns.applied(connection.getServerPlayer()) - SLOWED) < CLOSE,
                        s -> "the server slows the loaded player by " + Slowdowns.applied(connection.getServerPlayer())
                                + ", not " + SLOWED);
                ClientKit.waitOnClient(ctx, ClientKit.LONG_TIMEOUT,
                        mc -> Math.abs(speed(mc) - baseSpeed(mc) * (1.0 - SLOWED)) < CLOSE,
                        mc -> "the client's own speed is " + speed(mc) + ", not " + baseSpeed(mc) * (1.0 - SLOWED));
                double loaded = pace(ctx, server, connection, home);
                ClientKit.LOGGER.info("The client walked {} blocks a tick carrying nothing and {} loaded, {} of the pace",
                        free, loaded, loaded / free);
                check(Math.abs(loaded / free - (1.0 - SLOWED)) < PACE_CLOSE,
                        () -> "the loaded client walked " + loaded + " blocks a tick to the free one's " + free + ", "
                                + loaded / free + " of its pace, not " + (1.0 - SLOWED));

                ctx.getInput().pressKey(options -> options.keyInventory);
                ctx.waitForScreen(InventoryScreen.class);
                // Off the inventory, so no slot is highlighted
                ctx.getInput().setCursorPos(0, 0);
                ClientKit.screenshot(ctx, "hardcore-alone-weight");
            } finally {
                ClientKit.runAll(List.of(
                        () -> {
                            if (ctx.computeOnClient(mc -> mc.gui.screen() instanceof InventoryScreen)) {
                                ctx.setScreen(() -> null);
                            }
                        },
                        () -> server.runOnServer(s -> connection.getServerPlayer().getInventory().clearContent()),
                        () -> ClientKit.waitOnServer(server, ClientKit.LONG_TIMEOUT,
                                s -> Slowdowns.applied(connection.getServerPlayer()) == 0.0,
                                s -> "the slowdown is still " + Slowdowns.applied(connection.getServerPlayer())
                                        + " with nothing carried"),
                        () -> server.runOnServer(s -> putBack.run())));
            }
        });
    }

    // From home, facing south over the flat test world, with the forward key held: blocks a tick once the pace has
    // settled. The client moves its player itself, so this is the pace of its own speed.
    private static double pace(ClientGameTestContext ctx, TestDedicatedServerContext server,
                               TestDedicatedServerConnection connection, Vec3 home) {
        server.runOnServer(s -> {
            ServerPlayer player = connection.getServerPlayer();
            player.teleportTo(player.level(), home.x, home.y, home.z, Set.of(), SOUTH, LEVEL, true);
        });
        ClientKit.waitOnClient(ctx, ClientKit.LONG_TIMEOUT,
                mc -> mc.player.position().distanceTo(home) < AT_HOME && still(mc) && mc.player.onGround(),
                mc -> "the client is at " + mc.player.position() + ", moving " + mc.player.getDeltaMovement()
                        + ", not standing at " + home);
        ctx.getInput().lookAt(SOUTH, LEVEL);
        double pace;
        ctx.getInput().holdKey(options -> options.keyUp);
        try {
            ctx.waitTicks(SETTLE_PACE_TICKS);
            Vec3 from = ctx.computeOnClient(mc -> mc.player.position());
            ctx.waitTicks(PACE_TICKS);
            Vec3 to = ctx.computeOnClient(mc -> mc.player.position());
            check(ctx.computeOnClient(mc -> mc.player.onGround()), () -> "the client left the ground during the walk");
            pace = to.subtract(from).horizontalDistance() / PACE_TICKS;
        } finally {
            // A key left held would walk the player on through the rest of the run
            ctx.getInput().releaseKey(options -> options.keyUp);
        }
        ClientKit.waitOnClient(ctx, ClientKit.LONG_TIMEOUT, WeightAloneClientTest::still,
                mc -> "the client is still moving " + mc.player.getDeltaMovement() + " after the walk");
        check(pace > 0.1, () -> "the client walked only " + pace + " blocks a tick");
        return pace;
    }

    private static boolean still(Minecraft mc) {
        return mc.player.getDeltaMovement().horizontalDistanceSqr() < CLOSE;
    }

    private static double speed(Minecraft mc) {
        return mc.player.getAttributeValue(Attributes.MOVEMENT_SPEED);
    }

    private static double baseSpeed(Minecraft mc) {
        return mc.player.getAttributeBaseValue(Attributes.MOVEMENT_SPEED);
    }
}
