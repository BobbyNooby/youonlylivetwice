package bobbynooby.dev.coords.client;

import bobbynooby.dev.coords.ClientBridge;
import bobbynooby.dev.coords.Offset;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

import static bobbynooby.dev.coords.client.ClientKit.check;

/**
 * A real client with a shift still acts on the real world: it walks with its own forward key, breaks a block with its
 * attack key and places one with its use key. Each goes to the server in the client's shifted numbers, so each lands
 * on the real spot only if the mod shifts it back: the server sees the player move as far as the client did, at the
 * real spot, and the real block is broken and then placed. Untranslated, the server would call the walk moving too
 * quickly, and the break and the place would name a block a thousand blocks away.
 */
public class WalkAndBuildAloneClientTest implements FabricClientGameTest {
    private static final float NORTH = 180.0F;
    private static final float LEVEL = 0.0F;
    private static final int WALK_TICKS = 20;
    // A 20 tick walk on flat ground goes about 4 blocks
    private static final double MIN_WALK = 2.0;
    private static final int SPOT_DISTANCE = 2;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        ClientKit.withServer(ctx, (server, connection) -> {
            GameType modeWas = server.computeOnServer(s -> connection.getServerPlayer().gameMode());
            try {
                Offset offset = ClientKit.offset(server, connection);
                check(!offset.isZero(), () -> "the join's stretch has no shift");
                check(server.computeOnServer(s -> ClientBridge.incomingOffset(connection.getServerPlayer())).equals(offset),
                        () -> "what the client sends isn't read in the join's shift");
                walk(ctx, server, connection, offset);
                BlockPos spot = breakBlock(ctx, server, connection, offset);
                placeBlock(ctx, server, connection, offset, spot);
                showPlaced(ctx, offset, spot);
            } finally {
                ClientKit.runAll(List.of(
                        () -> ClientKit.hideF3(ctx),
                        () -> server.runOnServer(s -> {
                            ServerPlayer player = connection.getServerPlayer();
                            player.getInventory().clearContent();
                            player.setGameMode(modeWas);
                        })));
            }
        });
    }

    // North with the forward key held: the server's player moves as far as the client's, and stands where the client
    // stands, shifted back
    private static void walk(ClientGameTestContext ctx, TestDedicatedServerContext server,
                             TestDedicatedServerConnection connection, Offset offset) {
        Vec3 clientFrom = ClientKit.checkShifted(ctx, server, connection, offset, "before the walk");
        Vec3 realFrom = server.computeOnServer(s -> connection.getServerPlayer().position());
        ctx.getInput().lookAt(NORTH, LEVEL);
        ctx.getInput().holdKey(options -> options.keyUp);
        try {
            ctx.waitTicks(WALK_TICKS);
        } finally {
            // A key left held would walk the player on through the rest of the run
            ctx.getInput().releaseKey(options -> options.keyUp);
        }
        Vec3 clientTo = ClientKit.checkShifted(ctx, server, connection, offset, "after the walk");
        Vec3 realTo = server.computeOnServer(s -> connection.getServerPlayer().position());
        Vec3 walked = clientTo.subtract(clientFrom);
        Vec3 moved = realTo.subtract(realFrom);
        ClientKit.LOGGER.info("The client walked {} from {} to {}; the server moved its player {} from {} to {}", walked,
                clientFrom, clientTo, moved, realFrom, realTo);
        check(walked.horizontalDistance() > MIN_WALK, () -> "the client walked only " + walked + " from " + clientFrom);
        check(walked.z < 0.0, () -> "the client walked " + walked + ", not north");
        check(moved.subtract(walked).length() < ClientKit.CLOSE,
                () -> "the server moved its player " + moved + " while the client walked " + walked);
    }

    // A gold block put in front of the player on the server, which the client sees at the spot shifted, aims at there
    // and breaks with its attack key. In creative a press breaks a block at once.
    private static BlockPos breakBlock(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                       TestDedicatedServerConnection connection, Offset offset) {
        BlockPos spot = server.computeOnServer(s -> {
            ServerPlayer player = connection.getServerPlayer();
            player.setGameMode(GameType.CREATIVE);
            player.getInventory().clearContent();
            BlockPos at = player.blockPosition().relative(Direction.NORTH, SPOT_DISTANCE);
            player.level().setBlock(at, Blocks.GOLD_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
            return at;
        });
        BlockPos shown = offset.shift(spot);
        ClientKit.waitOnClient(ctx, ClientKit.LONG_TIMEOUT, mc -> mc.player.isCreative()
                        && mc.player.getMainHandItem().isEmpty() && mc.level.getBlockState(shown).is(Blocks.GOLD_BLOCK),
                mc -> "the client shows " + mc.level.getBlockState(shown) + " at " + shown.toShortString()
                        + ", for the gold block at the real " + spot.toShortString());
        ctx.getInput().lookAt(shown);
        waitToLookAt(ctx, shown, null);
        ctx.getInput().pressKey(options -> options.keyAttack);
        ClientKit.waitOnServer(server, ClientKit.LONG_TIMEOUT,
                s -> connection.getServerPlayer().level().getBlockState(spot).isAir(),
                s -> "the real block at " + spot.toShortString() + " is "
                        + connection.getServerPlayer().level().getBlockState(spot) + " after the client broke it");
        ClientKit.waitOnClient(ctx, ClientKit.LONG_TIMEOUT, mc -> mc.level.getBlockState(shown).isAir(),
                mc -> "the client shows " + mc.level.getBlockState(shown) + " at " + shown.toShortString() + " after the break");
        return spot;
    }

    // A diamond block in hand, put there on the server, placed with the use key on top of the ground where the gold
    // block stood
    private static void placeBlock(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                   TestDedicatedServerConnection connection, Offset offset, BlockPos spot) {
        BlockPos ground = spot.below();
        BlockPos shownGround = offset.shift(ground);
        BlockPos shown = offset.shift(spot);
        server.runOnServer(s -> {
            ServerPlayer player = connection.getServerPlayer();
            check(player.level().getBlockState(spot).isAir() && !player.level().getBlockState(ground).isAir(),
                    () -> "the real " + spot.toShortString() + " isn't air on the ground before the place");
            player.getInventory().setItem(player.getInventory().getSelectedSlot(), new ItemStack(Items.DIAMOND_BLOCK));
        });
        ClientKit.waitOnClient(ctx, ClientKit.LONG_TIMEOUT, mc -> mc.player.getMainHandItem().is(Items.DIAMOND_BLOCK),
                mc -> "the client holds " + mc.player.getMainHandItem() + ", not the diamond block");
        // The middle of the ground's top face
        ClientKit.lookAt(ctx, Vec3.atCenterOf(shownGround).add(0.0, 0.5, 0.0));
        waitToLookAt(ctx, shownGround, Direction.UP);
        ctx.getInput().pressKey(options -> options.keyUse);
        ClientKit.waitOnServer(server, ClientKit.LONG_TIMEOUT,
                s -> connection.getServerPlayer().level().getBlockState(spot).is(Blocks.DIAMOND_BLOCK),
                s -> "the real block at " + spot.toShortString() + " is "
                        + connection.getServerPlayer().level().getBlockState(spot) + " after the client placed one");
        ClientKit.waitOnClient(ctx, ClientKit.LONG_TIMEOUT, mc -> mc.level.getBlockState(shown).is(Blocks.DIAMOND_BLOCK),
                mc -> "the client shows " + mc.level.getBlockState(shown) + " at " + shown.toShortString() + " after the place");
    }

    // The placed block in the crosshair, and the F3 screen with where the client stands in its own numbers. The block the
    // crosshair is on isn't among what vanilla's F3 screen shows as it ships.
    private static void showPlaced(ClientGameTestContext ctx, Offset offset, BlockPos spot) {
        BlockPos shown = offset.shift(spot);
        ctx.getInput().lookAt(shown);
        waitToLookAt(ctx, shown, null);
        ClientKit.showF3(ctx);
        ClientKit.checkF3Position(ctx, ctx.computeOnClient(mc -> mc.player.position()), "after the place");
        ClientKit.screenshot(ctx, "coordinates-alone-build");
    }

    // The client's own aim, which is what its attack and use keys act on
    private static void waitToLookAt(ClientGameTestContext ctx, BlockPos shown, Direction face) {
        ClientKit.waitOnClient(ctx, ClientKit.LONG_TIMEOUT, mc -> looksAt(mc, shown, face),
                mc -> "the client looks at " + describe(mc) + ", not " + shown.toShortString()
                        + (face == null ? "" : " from " + face));
    }

    private static boolean looksAt(Minecraft mc, BlockPos shown, Direction face) {
        return mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(shown) && (face == null || hit.getDirection() == face);
    }

    private static String describe(Minecraft mc) {
        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            return hit.getBlockPos().toShortString() + " from " + hit.getDirection();
        }
        return String.valueOf(mc.hitResult);
    }
}
