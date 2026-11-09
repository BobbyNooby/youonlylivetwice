package bobbynooby.dev.gametest.client;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.graves.Grave;
import bobbynooby.dev.graves.GraveItems;
import bobbynooby.dev.graves.GraveStore;
import bobbynooby.dev.graves.Graves;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;
import java.util.UUID;

// Test players have no skin, so every head here shows a default skin
public class GraveClientTest implements FabricClientGameTest {
    private static final double VIEW_DISTANCE = 2.0;
    private static final int MENU_SLOTS = 54 + 36;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        ClientTests.withServer(ctx, (server, connection) -> {
            // The lockout would kick the client the moment it dies
            boolean lockoutWas = server.computeOnServer(s -> Settings.get().deathLockout.enabled);
            server.runOnServer(s -> Settings.get().deathLockout.enabled = false);
            try {
                Grave grave = dieWithDiamond(ctx, server, connection);
                showGraveHead(ctx, server, connection, grave);
                showGraveMenu(ctx, server, connection, grave);
                showReviveHead(ctx, server, connection, grave);
            } finally {
                server.runOnServer(s -> Settings.get().deathLockout.enabled = lockoutWas);
            }
        });
    }

    private static Grave dieWithDiamond(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                        TestDedicatedServerConnection connection) {
        LocalPlayer before = ctx.computeOnClient(mc -> mc.player);
        Grave grave = server.computeOnServer(s -> {
            ServerPlayer player = connection.getServerPlayer();
            player.getInventory().add(new ItemStack(Items.DIAMOND));
            player.kill(player.level());
            if (!player.isDeadOrDying()) {
                throw new AssertionError("the client's player did not die");
            }
            UUID owner = player.getUUID();
            return GraveStore.get(s).all().stream()
                    .filter(g -> g.owner().equals(owner))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no grave for the client's player"));
        });
        // Immediate respawn is on, so the client respawns by itself; the loading screen stays until the random spawn loads
        ctx.waitFor(mc -> mc.player != before && mc.gui.screen() == null, TestServerConnection.DEFAULT_CHUNK_LOAD_TIMEOUT);
        return grave;
    }

    private static void showGraveHead(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                      TestDedicatedServerConnection connection, Grave grave) {
        server.runOnServer(s -> {
            ServerLevel level = s.getLevel(grave.dimension());
            // A head shows its face to someone looking at it with the yaw it was placed at, so stand where they would
            float yaw = RotationSegment.convertToDegrees(level.getBlockState(grave.pos()).getValue(SkullBlock.ROTATION));
            double radians = Math.toRadians(yaw);
            Vec3 base = Vec3.atBottomCenterOf(grave.pos());
            ServerPlayer player = connection.getServerPlayer();
            player.teleportTo(level, base.x + Math.sin(radians) * VIEW_DISTANCE, base.y,
                    base.z - Math.cos(radians) * VIEW_DISTANCE, Set.of(), yaw, 0.0F, true);
            // The middle of the head, which is half a block tall
            player.lookAt(EntityAnchorArgument.Anchor.EYES, base.add(0.0, 0.25, 0.0));
        });
        connection.waitForClientboundPackets();
        connection.waitForChunksRender();
        ctx.waitFor(mc -> mc.level.getBlockState(grave.pos()).is(Blocks.PLAYER_HEAD));

        // Without the HUD the crosshair doesn't cover the face
        ctx.getInput().pressKey(options -> options.keyToggleGui);
        ctx.waitFor(mc -> mc.gui.hud.isHidden());
        screenshot(ctx, "02-grave-head");
        ctx.getInput().pressKey(options -> options.keyToggleGui);
        ctx.waitFor(mc -> !mc.gui.hud.isHidden());
    }

    private static void showGraveMenu(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                      TestDedicatedServerConnection connection, Grave grave) {
        server.runOnServer(s -> Graves.open(connection.getServerPlayer(), grave));
        ctx.waitForScreen(ContainerScreen.class);
        // The slot contents come in their own packet after the menu opens
        connection.waitForClientboundPackets();
        ctx.runOnClient(mc -> {
            List<Slot> slots = mc.player.containerMenu.slots;
            if (slots.size() != MENU_SLOTS) {
                throw new AssertionError("the grave menu has " + slots.size() + " slots, not " + MENU_SLOTS);
            }
            if (!slots.get(0).getItem().is(Items.DIAMOND) && !slots.get(1).getItem().is(Items.DIAMOND)) {
                throw new AssertionError("the diamond is not in the first two grave slots");
            }
        });

        // Off the menu, so no slot is highlighted
        ctx.getInput().setCursorPos(0, 0);
        screenshot(ctx, "02-grave-menu");
        ctx.getInput().pressKey(InputConstants.KEY_ESCAPE);
        ctx.waitForScreen(null);
        // The server must have closed the menu before the hand changes
        connection.waitForServerboundPackets();
    }

    private static void showReviveHead(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                       TestDedicatedServerConnection connection, Grave grave) {
        server.runOnServer(s -> connection.getServerPlayer().setItemInHand(InteractionHand.MAIN_HAND, craftReviveHead(s, grave)));
        ctx.waitFor(mc -> GraveItems.isReviveHead(mc.player.getMainHandItem()));
        ctx.runOnClient(mc -> {
            ItemStack held = mc.player.getMainHandItem();
            if (!held.hasFoil() || held.getRarity() != Rarity.EPIC) {
                throw new AssertionError("the client's revive head has no glint or isn't epic");
            }
        });

        // The hand needs a few ticks to rise; the item name stays above the hotbar for two seconds
        ctx.waitTicks(10);
        screenshot(ctx, "02-revive-head");
    }

    // Crafted by the real recipe, so it carries the lore and looks a player would get
    private static ItemStack craftReviveHead(MinecraftServer server, Grave grave) {
        ItemStack graveHead = grave.items().stream()
                .filter(GraveItems::isGraveHead)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the grave holds no grave head"));
        CraftingInput input = CraftingInput.of(2, 1, List.of(graveHead.copy(), new ItemStack(Items.NETHER_STAR)));
        return server.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, server.getLevel(grave.dimension()))
                .orElseThrow(() -> new AssertionError("no recipe for a grave head and a nether star"))
                .value()
                .assemble(input);
    }

    private static void screenshot(ClientGameTestContext ctx, String name) {
        // Toasts from the diamond can arrive after the join toasts were cleared
        ctx.runOnClient(mc -> mc.gui.toastManager().clear());
        // No counter prefix, so the CI artifact keeps the plain file names
        ctx.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix());
    }
}
