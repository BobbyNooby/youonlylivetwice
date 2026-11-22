package bobbynooby.dev.hardcore.client;

import bobbynooby.dev.hardcore.ClientBridge;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;

import static bobbynooby.dev.hardcore.client.ClientKit.check;

// YOLT Anvils at work inside YOLT Hardcore's jar, under YOLT Hardcore's settings, as the client's own anvil screen
// shows it: a rename of a sword with prior work, priced as vanilla prices it with the flat price off, then at the flat
// rename price once it is on
public class AnvilAloneClientTest implements FabricClientGameTest {
    // Vanilla prices a rename at 1 plus the item's prior work; the flat price is the rename cost alone
    private static final int PRIOR_WORK = 10;
    private static final int VANILLA_PRICE = 1 + PRIOR_WORK;
    private static final int FLAT_PRICE = 1;
    // Enough for the flat price but not vanilla's, as a player who would be turned away without the mod
    private static final int XP_LEVELS = 5;
    private static final int USE_DISTANCE = 2;
    private static final float NORTH = 180.0F;
    private static final float LEVEL = 0.0F;
    private static final double AT_HOME = 0.01;
    // Typed in two goes, so the second's last letter prices the same rename again under the new setting
    private static final String NAME_START = "Frostbit";
    private static final String NAME_END = "e";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        ClientKit.withServer(ctx, (server, connection) -> {
            BlockPos anvil = server.computeOnServer(s -> connection.getServerPlayer().blockPosition()
                    .relative(Direction.NORTH, USE_DISTANCE));
            int levelsWas = server.computeOnServer(s -> connection.getServerPlayer().experienceLevel);
            // Last before the try, so nothing can fail between the swap and what puts it back
            Runnable putBack = server.computeOnServer(s -> ClientBridge.use(c -> c.anvil.enabled = false));
            try {
                check(server.computeOnServer(s -> connection.getServerPlayer().gameMode()) == GameType.SURVIVAL,
                        () -> "the client's player isn't in survival, where the flat price counts");
                server.runOnServer(s -> {
                    ServerPlayer player = connection.getServerPlayer();
                    player.setExperienceLevels(XP_LEVELS);
                    player.level().setBlock(anvil, Blocks.ANVIL.defaultBlockState(), Block.UPDATE_ALL);
                });
                openAnvil(ctx, server, connection, anvil);
                typeName(ctx, connection, NAME_START);
                waitForPrice(ctx, VANILLA_PRICE, "with the flat price off");

                server.runOnServer(s -> ClientBridge.use(c -> c.anvil.enabled = true));
                typeName(ctx, connection, NAME_END);
                waitForPrice(ctx, FLAT_PRICE, "with the flat price on");
                // Off the menu, so no slot is highlighted
                ctx.getInput().setCursorPos(0, 0);
                ctx.waitTicks(ClientKit.HOTBAR_LINE_TICKS);
                ClientKit.screenshot(ctx, "hardcore-alone-anvil");
            } finally {
                ClientKit.runAll(List.of(
                        () -> {
                            if (ctx.computeOnClient(mc -> mc.gui.screen() instanceof AnvilScreen)) {
                                ctx.getInput().pressKey(InputConstants.KEY_ESCAPE);
                                ctx.waitForScreen(null);
                                connection.waitForServerboundPackets();
                            }
                        },
                        () -> server.runOnServer(s -> {
                            ServerPlayer player = connection.getServerPlayer();
                            player.closeContainer();
                            // Closing the anvil hands the sword back
                            player.getInventory().clearContent();
                            player.setExperienceLevels(levelsWas);
                            player.level().setBlock(anvil, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                        }),
                        () -> server.runOnServer(s -> putBack.run())));
            }
        });
    }

    // Looking north at it from where the client joined, a real right-click, and then the sword in its first slot
    private static void openAnvil(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                  TestDedicatedServerConnection connection, BlockPos anvil) {
        Vec3 home = server.computeOnServer(s -> connection.getServerPlayer().position());
        server.runOnServer(s -> {
            ServerPlayer player = connection.getServerPlayer();
            player.teleportTo(player.level(), home.x, home.y, home.z, Set.of(), NORTH, LEVEL, true);
        });
        ClientKit.waitOnClient(ctx, ClientKit.LONG_TIMEOUT, mc -> mc.player.position().distanceTo(home) < AT_HOME
                        && mc.level.getBlockState(anvil).getBlock() instanceof AnvilBlock,
                mc -> "the client is at " + mc.player.position() + " with " + mc.level.getBlockState(anvil)
                        + " at " + anvil.toShortString());
        ctx.getInput().lookAt(anvil);
        ClientKit.waitOnClient(ctx, ClientKit.LONG_TIMEOUT, mc -> mc.hitResult instanceof BlockHitResult hit
                        && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(anvil),
                mc -> "the client looks at " + mc.hitResult + ", not the anvil");
        ctx.getInput().pressKey(options -> options.keyUse);
        ctx.waitForScreen(AnvilScreen.class);
        server.runOnServer(s -> {
            if (!(connection.getServerPlayer().containerMenu instanceof AnvilMenu menu)) {
                throw new AssertionError("the server has no anvil menu open for the client");
            }
            ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
            sword.set(DataComponents.REPAIR_COST, PRIOR_WORK);
            menu.getSlot(AnvilMenu.INPUT_SLOT).set(sword);
        });
        ClientKit.waitOnClient(ctx, ClientKit.LONG_TIMEOUT,
                mc -> mc.player.containerMenu instanceof AnvilMenu menu && menu.getSlot(AnvilMenu.INPUT_SLOT).hasItem(),
                mc -> "the client's anvil never got the sword");
        // The name box fills with the item's own name, which a player clears before typing a new one
        int length = ctx.computeOnClient(mc -> mc.player.containerMenu.getSlot(AnvilMenu.INPUT_SLOT).getItem()
                .getHoverName().getString().length());
        for (int i = 0; i < length; i++) {
            ctx.getInput().pressKey(InputConstants.KEY_BACKSPACE);
        }
    }

    private static void typeName(ClientGameTestContext ctx, TestDedicatedServerConnection connection, String text) {
        ctx.getInput().typeChars(text);
        connection.waitForServerboundPackets();
        connection.waitForClientboundPackets();
    }

    // The price the server worked out, as the client's own menu holds it and its screen draws it
    private static void waitForPrice(ClientGameTestContext ctx, int price, String when) {
        ClientKit.waitOnClient(ctx, ClientKit.LONG_TIMEOUT, mc -> mc.gui.screen() instanceof AnvilScreen
                        && mc.player.containerMenu instanceof AnvilMenu menu && menu.getSlot(AnvilMenu.RESULT_SLOT).hasItem()
                        && menu.getCost() == price,
                mc -> describe(mc) + " " + when + ", not " + price);
    }

    private static String describe(Minecraft mc) {
        if (!(mc.player.containerMenu instanceof AnvilMenu menu)) {
            return "the client's anvil menu closed";
        }
        return "the client's anvil shows " + menu.getSlot(AnvilMenu.RESULT_SLOT).getItem() + " at a price of " + menu.getCost();
    }
}
