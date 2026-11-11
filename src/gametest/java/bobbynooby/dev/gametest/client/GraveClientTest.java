package bobbynooby.dev.gametest.client;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.graves.Grave;
import bobbynooby.dev.graves.GraveItems;
import bobbynooby.dev.graves.GraveMenus;
import bobbynooby.dev.graves.GraveStore;
import bobbynooby.dev.graves.Graves;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;
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
            boolean compassWas = server.computeOnServer(s -> Settings.get().graves.compass);
            server.runOnServer(s -> {
                Settings.get().deathLockout.enabled = false;
                Settings.get().graves.compass = true;
            });
            try {
                Grave grave = dieWithDiamond(ctx, server, connection);
                int compassSlot = selectAwayFromCompass(ctx, server, connection);
                showGraveHead(ctx, server, connection, grave);
                showGraveMenu(ctx, server, connection, grave);
                showReviveHead(ctx, server, connection, grave);
                // Last, since they take the grave away
                showGraveCompass(ctx, server, connection, grave, compassSlot);
                showSpinningCompass(ctx, server, grave);
            } finally {
                server.runOnServer(s -> {
                    Settings.get().deathLockout.enabled = lockoutWas;
                    Settings.get().graves.compass = compassWas;
                });
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
        server.runOnServer(s -> GraveMenus.open(connection.getServerPlayer(), grave));
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
        // Vanilla never draws a glint on a player head, so only this check shows the glint reached the client
        ctx.runOnClient(mc -> {
            ItemStack held = mc.player.getMainHandItem();
            if (!held.hasFoil() || held.getRarity() != Rarity.EPIC) {
                throw new AssertionError("the client's revive head has no glint or isn't epic");
            }
        });

        // The hand needs a few ticks to rise; the name above the hotbar, in the rarity colour, stays for two seconds
        ctx.waitTicks(10);
        screenshot(ctx, "02-revive-head");
    }

    // The respawn puts the compass in the first free slot, which can be the selected one. The revive head goes into
    // whichever slot is selected, so another one is picked first, and the compass keeps its own.
    private static int selectAwayFromCompass(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                             TestDedicatedServerConnection connection) {
        ClientTests.waitOnClient(ctx, ClientGameTestContext.DEFAULT_TIMEOUT, mc -> compassSlot(mc) != -1,
                mc -> "the client has no grave compass in its hotbar");
        int compass = ctx.computeOnClient(GraveClientTest::compassSlot);
        int other = ctx.computeOnClient(mc -> {
            for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
                if (slot != compass && mc.player.getInventory().getItem(slot).isEmpty()) {
                    return slot;
                }
            }
            throw new AssertionError("the client's hotbar has no empty slot");
        });
        selectSlot(ctx, server, connection, other);
        return compass;
    }

    private static void showGraveCompass(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                         TestDedicatedServerConnection connection, Grave grave, int compassSlot) {
        selectSlot(ctx, server, connection, compassSlot);
        GlobalPos gravePos = GlobalPos.of(grave.dimension(), grave.pos());
        ctx.runOnClient(mc -> {
            ItemStack held = mc.player.getMainHandItem();
            LodestoneTracker tracker = held.get(DataComponents.LODESTONE_TRACKER);
            if (!held.is(Items.COMPASS) || tracker == null) {
                throw new AssertionError("the client holds " + held + ", not the grave compass");
            }
            if (!tracker.target().equals(Optional.of(gravePos))) {
                throw new AssertionError("the client's compass points at " + tracker.target() + ", not the grave at " + gravePos);
            }
            String name = mc.player.getName().getString() + "'s grave";
            if (!held.getHoverName().getString().equals(name)) {
                throw new AssertionError("the client's compass is called " + held.getHoverName().getString() + ", not " + name);
            }
        });
        // The player still faces the grave head, so the needle points up
        ClientTests.waitUntilHandUp(ctx);
        screenshot(ctx, "02-grave-compass");
    }

    private static void showSpinningCompass(ClientGameTestContext ctx, TestDedicatedServerContext server, Grave grave) {
        if (!(server.computeOnServer(s -> Graves.removeAndDrop(s, grave)) instanceof Graves.Removal.Dropped)) {
            throw new AssertionError("the grave was not removed");
        }
        // The server's compass drops its target the next time it ticks in the inventory, and the slot is sent over
        ClientTests.waitOnClient(ctx, ClientGameTestContext.DEFAULT_TIMEOUT,
                mc -> hasNoTarget(mc.player.getMainHandItem()) && !mc.level.getBlockState(grave.pos()).is(Blocks.PLAYER_HEAD),
                mc -> hasNoTarget(mc.player.getMainHandItem()) ? "the client still has the grave's head"
                        : "the client's compass still has a target: " + mc.player.getMainHandItem().get(DataComponents.LODESTONE_TRACKER));
        // A changed stack can bring the hand down and up again
        ClientTests.waitUntilHandUp(ctx);
        screenshot(ctx, "02-grave-compass-spinning");
    }

    // A compass with a tracker that has no target spins
    private static boolean hasNoTarget(ItemStack stack) {
        LodestoneTracker tracker = stack.get(DataComponents.LODESTONE_TRACKER);
        return stack.is(Items.COMPASS) && tracker != null && tracker.target().isEmpty();
    }

    // The hotbar slot holding a compass with a tracker, or -1
    private static int compassSlot(Minecraft mc) {
        for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack.is(Items.COMPASS) && stack.has(DataComponents.LODESTONE_TRACKER)) {
                return slot;
            }
        }
        return -1;
    }

    // With the hotbar key a player would press. The server must know before it puts anything in the hand.
    private static void selectSlot(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                   TestDedicatedServerConnection connection, int slot) {
        ctx.getInput().pressKey(options -> options.keyHotbarSlots[slot]);
        ClientTests.waitOnClient(ctx, ClientGameTestContext.DEFAULT_TIMEOUT, mc -> mc.player.getInventory().getSelectedSlot() == slot,
                mc -> "the client has slot " + mc.player.getInventory().getSelectedSlot() + " selected, not " + slot);
        ClientTests.waitOnServer(server, ClientGameTestContext.DEFAULT_TIMEOUT,
                s -> connection.getServerPlayer().getInventory().getSelectedSlot() == slot,
                s -> "the server has slot " + connection.getServerPlayer().getInventory().getSelectedSlot() + " selected, not " + slot);
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
