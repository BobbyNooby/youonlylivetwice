package bobbynooby.dev.gametest.client;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.Yolt;
import bobbynooby.dev.graves.Grave;
import bobbynooby.dev.graves.GraveItems;
import bobbynooby.dev.graves.GraveMenus;
import bobbynooby.dev.graves.GraveStore;
import bobbynooby.dev.graves.Graves;
import bobbynooby.dev.lockout.DeathLockout;
import bobbynooby.dev.world.RandomSpawn;
import bobbynooby.dev.world.WorldBorders;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

// Test players have no skin, so every head here shows a default skin
public class GraveClientTest implements FabricClientGameTest {
    private static final double VIEW_DISTANCE = 2.0;
    private static final int MENU_SLOTS = 54 + 36;
    // YOLT's own border, the one a random respawn has to land inside
    private static final double BORDER = 10_000.0;
    // Vanilla respawns at the world spawn, and the test world's respawn radius is 0
    private static final double AWAY = 32.0;
    // Fixed, with the seed, so the respawn lands in the same column every run, and the checks against both come out the
    // same every time
    private static final int SPAWN_X = 0;
    private static final int SPAWN_Z = 0;
    private static final int DEATH_X = 100;
    private static final int DEATH_Z = 100;
    private static final long RESPAWN_SEED = 3L;
    // Two, so the use leaves one in the hand
    private static final int REVIVE_HEADS = 2;
    // Past where the grave menu still reaches
    private static final double ITEM_SEARCH = 8.0;
    // A chest screen's size as vanilla's ContainerScreen lays it out; its slots sit inside it
    private static final int CHEST_WIDTH = 176;
    private static final int CHEST_HEIGHT_BASE = 114;
    private static final int ROW_HEIGHT = 18;
    private static final int SLOT_MIDDLE = 8;

    private record Death(Grave grave, Vec3 at) {
    }

    // In GUI pixels
    private record Point(double x, double y) {
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        ClientTests.withServer(ctx, (server, connection) -> {
            // The lockout would kick the client the moment it dies
            boolean lockoutWas = server.computeOnServer(s -> Settings.get().deathLockout.enabled);
            boolean compassWas = server.computeOnServer(s -> Settings.get().graves.compass);
            boolean borderWas = server.computeOnServer(s -> Settings.get().worldBorder.enabled);
            server.runOnServer(s -> {
                Settings.get().deathLockout.enabled = false;
                Settings.get().graves.compass = true;
                // The test server keeps YOLT's border off. The world is new for each test, so the border itself needs no
                // setting back.
                Settings.get().worldBorder.enabled = true;
                WorldBorders.apply(s);
            });
            try {
                Death death = dieWithDiamond(ctx, server, connection);
                Grave grave = death.grave();
                int compassSlot = selectAwayFromCompass(ctx, server, connection);
                showRespawnSpot(ctx, server, connection, death.at());
                showGraveHead(ctx, server, connection, grave);
                showGraveMenu(ctx, server, connection, grave);
                showReviveHead(ctx, server, connection, grave);
                useReviveHead(ctx, server, connection);
                // Last, since they take the grave away
                showGraveCompass(ctx, server, connection, grave, compassSlot);
                showSpinningCompass(ctx, server, grave);
            } finally {
                ClientTests.runAll(List.of(
                        () -> server.runOnServer(s -> Settings.get().deathLockout.enabled = lockoutWas),
                        () -> server.runOnServer(s -> Settings.get().graves.compass = compassWas),
                        () -> server.runOnServer(s -> Settings.get().worldBorder.enabled = borderWas),
                        // Static, so later tests in this JVM would get the seeded spots
                        () -> RandomSpawn.useRandomForTesting(null)));
            }
        });
    }

    private static Death dieWithDiamond(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                        TestDedicatedServerConnection connection) {
        LocalPlayer before = ctx.computeOnClient(mc -> mc.player);
        server.runOnServer(s -> {
            ServerLevel overworld = s.overworld();
            // The world is new for each test, so its spawn needs no setting back
            s.setRespawnData(LevelData.RespawnData.of(Level.OVERWORLD, ground(overworld, SPAWN_X, SPAWN_Z), 0.0F, 0.0F));
            Vec3 deathSpot = Vec3.atBottomCenterOf(ground(overworld, DEATH_X, DEATH_Z));
            ServerPlayer player = connection.getServerPlayer();
            player.teleportTo(overworld, deathSpot.x, deathSpot.y, deathSpot.z, Set.of(), player.getYRot(), player.getXRot(), true);
        });
        ClientTests.waitOnClient(ctx, ClientGameTestContext.DEFAULT_TIMEOUT,
                mc -> mc.player.blockPosition().getX() == DEATH_X && mc.player.blockPosition().getZ() == DEATH_Z,
                mc -> "the client is at " + mc.player.blockPosition().toShortString() + ", not where it is to die");
        connection.waitForChunksRender();
        // Right before the death, so the respawn is the first spot it picks
        RandomSpawn.useRandomForTesting(new Random(RESPAWN_SEED));
        Death death = server.computeOnServer(s -> {
            ServerPlayer player = connection.getServerPlayer();
            player.getInventory().add(new ItemStack(Items.DIAMOND));
            Vec3 at = player.position();
            // On for the death alone, so the death is recorded and the revive head later has a lockout to end. It is
            // off again before the kick that would come after this tick, so the client stays on.
            Settings.get().deathLockout.enabled = true;
            try {
                player.kill(player.level());
            } finally {
                Settings.get().deathLockout.enabled = false;
            }
            if (!player.isDeadOrDying()) {
                throw new AssertionError("the client's player did not die");
            }
            UUID owner = player.getUUID();
            Grave grave = GraveStore.get(s).all().stream()
                    .filter(g -> g.owner().equals(owner))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no grave for the client's player"));
            return new Death(grave, at);
        });
        // Immediate respawn is on, so the client respawns by itself; the loading screen stays until the random spawn loads
        ctx.waitFor(mc -> mc.player != before && mc.gui.screen() == null, TestServerConnection.DEFAULT_CHUNK_LOAD_TIMEOUT);
        return death;
    }

    // Where a player can stand in that column of the overworld
    private static BlockPos ground(ServerLevel overworld, int x, int z) {
        BlockPos spot = RandomSpawn.spotAt(overworld, x, z);
        if (spot == null) {
            throw new AssertionError("nowhere to stand at " + x + ", " + z);
        }
        return spot;
    }

    // A random respawn inside YOLT's border: away from where the client died, and from the world spawn vanilla would use
    private static void showRespawnSpot(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                        TestDedicatedServerConnection connection, Vec3 died) {
        double serverBorder = server.computeOnServer(s -> s.overworld().getWorldBorder().getSize());
        if (serverBorder != BORDER) {
            throw new AssertionError("the server's border is " + serverBorder + " wide, not YOLT's " + BORDER);
        }
        BlockPos worldSpawn = server.computeOnServer(s -> s.overworld().getRespawnData().pos());
        BlockPos picked = server.computeOnServer(s -> connection.getServerPlayer().blockPosition());
        // The respawn's spawn packet comes wrapped in a whole-world border for the north compass, so the client only has
        // the real border once that is done
        ClientTests.waitOnClient(ctx, ClientGameTestContext.DEFAULT_TIMEOUT, mc -> mc.level.getWorldBorder().getSize() == BORDER,
                mc -> "the client's border is " + mc.level.getWorldBorder().getSize() + " wide, not " + BORDER);
        connection.waitForChunksRender();
        ctx.runOnClient(mc -> {
            Vec3 at = mc.player.position();
            BlockPos column = mc.player.blockPosition();
            if (column.getX() != picked.getX() || column.getZ() != picked.getZ()) {
                throw new AssertionError("the client stands at " + column.toShortString() + ", not where the server put it at "
                        + picked.toShortString());
            }
            if (!mc.level.getWorldBorder().isWithinBounds(mc.player.getBoundingBox())) {
                throw new AssertionError("the client respawned at " + at + ", outside its border");
            }
            if (BodyStoryClientTest.flatDistance(at, died) < AWAY) {
                throw new AssertionError("the client respawned at " + at + ", next to where it died at " + died);
            }
            if (BodyStoryClientTest.flatDistance(at, Vec3.atBottomCenterOf(worldSpawn)) < AWAY) {
                throw new AssertionError("the client respawned at " + at + ", at the world spawn " + worldSpawn.toShortString()
                        + " as vanilla would");
            }
            Yolt.LOGGER.info("The client respawned at {}, having died at {}", at, died);
        });
        screenshot(ctx, "02-respawn-spot");
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
        int diamondSlot = ctx.computeOnClient(mc -> {
            List<Slot> slots = mc.player.containerMenu.slots;
            if (slots.size() != MENU_SLOTS) {
                throw new AssertionError("the grave menu has " + slots.size() + " slots, not " + MENU_SLOTS);
            }
            if (slots.get(0).getItem().is(Items.DIAMOND)) {
                return 0;
            }
            if (slots.get(1).getItem().is(Items.DIAMOND)) {
                return 1;
            }
            throw new AssertionError("the diamond is not in the first two grave slots");
        });

        // Off the menu, so no slot is highlighted
        ctx.getInput().setCursorPos(0, 0);
        screenshot(ctx, "02-grave-menu");
        takeWithShiftClick(ctx, server, connection, grave, diamondSlot);
        ctx.getInput().pressKey(InputConstants.KEY_ESCAPE);
        ctx.waitForScreen(null);
        // The server must have closed the menu before the hand changes
        connection.waitForServerboundPackets();
    }

    // A real shift-click moves the diamond straight into the client's inventory, and nothing drops
    private static void takeWithShiftClick(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                           TestDedicatedServerConnection connection, Grave grave, int slot) {
        int stored = server.computeOnServer(s -> diamonds(storedItems(s, grave)));
        if (stored < 1) {
            throw new AssertionError("the grave's record holds no diamond to take");
        }
        if (ctx.computeOnClient(GraveClientTest::diamondsHeld) != 0) {
            throw new AssertionError("the client holds a diamond before it takes one");
        }
        Point middle = ctx.computeOnClient(mc -> slotMiddle(mc, slot));
        ClientTests.moveCursorToGui(ctx, middle.x(), middle.y());
        ctx.getInput().holdShift();
        try {
            // TestInput's clicks carry no modifier keys, so this one goes to vanilla's mouse handler with shift in it,
            // the way an SDL click with shift held would
            ctx.runOnClient(mc -> {
                MouseButtonInfo click = new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, InputConstants.MOD_SHIFT);
                mc.mouseHandler.onButton(mc.getWindow().handle(), click, InputConstants.PRESS);
                mc.mouseHandler.onButton(mc.getWindow().handle(), click, InputConstants.RELEASE);
            });
        } finally {
            ctx.getInput().releaseShift();
        }
        // The client moves the diamond at once on its own; what counts is the server taking it out of the record
        ClientTests.waitOnServer(server, ClientGameTestContext.DEFAULT_TIMEOUT, s -> diamonds(storedItems(s, grave)) == stored - 1,
                s -> "the grave's record still holds " + diamonds(storedItems(s, grave)) + " diamonds, not " + (stored - 1));
        // So the server's answer to the click, a correction included, has reached the client
        connection.waitForClientboundPackets();
        if (server.computeOnServer(s -> !s.getLevel(grave.dimension())
                .getEntitiesOfClass(ItemEntity.class, new AABB(grave.pos()).inflate(ITEM_SEARCH)).isEmpty())) {
            throw new AssertionError("the click dropped items by the grave");
        }
        ctx.runOnClient(mc -> {
            int held = diamondsHeld(mc);
            if (held != 1) {
                throw new AssertionError("the client holds " + held + " diamonds after the click, not 1");
            }
            ItemStack carried = mc.player.containerMenu.getCarried();
            if (!carried.isEmpty()) {
                throw new AssertionError("the click left " + carried + " on the cursor, so it was no shift-click");
            }
            if (mc.player.containerMenu.slots.get(slot).hasItem()) {
                throw new AssertionError("the grave slot still shows " + mc.player.containerMenu.slots.get(slot).getItem());
            }
            List<ItemEntity> dropped = mc.level.getEntitiesOfClass(ItemEntity.class, mc.player.getBoundingBox().inflate(ITEM_SEARCH));
            if (!dropped.isEmpty()) {
                throw new AssertionError("the client sees items on the ground: " + dropped);
            }
        });
        // Off the menu again, so no slot is highlighted
        ctx.getInput().setCursorPos(0, 0);
        screenshot(ctx, "02-grave-take-click");
    }

    private static void showReviveHead(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                       TestDedicatedServerConnection connection, Grave grave) {
        server.runOnServer(s -> connection.getServerPlayer().setItemInHand(InteractionHand.MAIN_HAND,
                craftReviveHead(s, grave).copyWithCount(REVIVE_HEADS)));
        ctx.waitFor(mc -> GraveItems.isReviveHead(mc.player.getMainHandItem()));
        // Vanilla never draws a glint on a player head, so only this check shows the glint reached the client
        ctx.runOnClient(mc -> {
            ItemStack held = mc.player.getMainHandItem();
            if (!held.hasFoil() || held.getRarity() != Rarity.EPIC) {
                throw new AssertionError("the client's revive head has no glint or isn't epic");
            }
        });

        // The name above the hotbar, in the rarity colour, stays for two seconds, longer than the hand takes to rise
        ClientTests.waitUntilHandsUp(ctx);
        screenshot(ctx, "02-revive-head");
    }

    // A real right-click into the air with the revive head ends the lockout of the client's own death
    private static void useReviveHead(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                      TestDedicatedServerConnection connection) {
        UUID self = server.computeOnServer(s -> connection.getServerPlayer().getUUID());
        String name = server.computeOnServer(s -> connection.getServerPlayer().nameAndId().name());
        String line = name + " can come back now.";
        float yaw = ctx.computeOnClient(mc -> mc.player.getYRot());
        float pitch = ctx.computeOnClient(mc -> mc.player.getXRot());
        server.runOnServer(s -> Settings.get().deathLockout.enabled = true);
        try {
            // The control: the client's death keeps it out now, so there is a lockout for the head to end
            if (server.computeOnServer(s -> DeathLockout.refusal(self, name) == null)) {
                throw new AssertionError("the client's death keeps nobody out, so the revive would have nothing to end");
            }
            int before = ctx.computeOnClient(mc -> mc.player.getMainHandItem().getCount());
            // Turned away from the grave, since a click on it opens it instead
            ctx.getInput().lookAt(yaw + 180.0F, 0.0F);
            ClientTests.waitOnClient(ctx, ClientGameTestContext.DEFAULT_TIMEOUT,
                    mc -> mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.MISS,
                    mc -> "the client looks at " + mc.hitResult + ", so its click would not be a use in the air");
            int mark = ChatStoryClientTest.chatLogEnd(ctx);
            ctx.getInput().pressKey(options -> options.keyUse);
            ClientTests.waitOnClient(ctx, ClientGameTestContext.DEFAULT_TIMEOUT,
                    mc -> ChatStoryClientTest.chatLogHas(mc, mark, line) && mc.player.getMainHandItem().getCount() == before - 1,
                    mc -> ChatStoryClientTest.chatLogHas(mc, mark, line)
                            ? "the client holds " + mc.player.getMainHandItem() + " after the use, not " + (before - 1) + " revive heads"
                            : "the client never got the line \"" + line + "\"");
            if (!ctx.computeOnClient(mc -> GraveItems.isReviveHead(mc.player.getMainHandItem()))) {
                throw new AssertionError("the client's hand no longer holds a revive head");
            }
            if (server.computeOnServer(s -> DeathLockout.refusal(self, name) != null)) {
                throw new AssertionError("the revive head left the client locked out");
            }
            // A changed stack brings the hand down and up again
            ClientTests.waitUntilHandsUp(ctx);
            screenshot(ctx, "02-revive-used");
        } finally {
            ClientTests.runAll(List.of(
                    () -> server.runOnServer(s -> Settings.get().deathLockout.enabled = false),
                    // Facing the grave again, which the compass shots need
                    () -> ctx.getInput().lookAt(yaw, pitch)));
        }
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
        ClientTests.selectSlot(ctx, server, connection, other);
        return compass;
    }

    private static void showGraveCompass(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                         TestDedicatedServerConnection connection, Grave grave, int compassSlot) {
        ClientTests.selectSlot(ctx, server, connection, compassSlot);
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
        ClientTests.waitUntilHandsUp(ctx);
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
        ClientTests.waitUntilHandsUp(ctx);
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

    // The middle of a slot of the open chest menu, where vanilla's chest screen draws it
    private static Point slotMiddle(Minecraft mc, int slot) {
        if (!(mc.player.containerMenu instanceof ChestMenu menu) || mc.gui.screen() == null) {
            throw new AssertionError("the client has no chest menu open");
        }
        int left = (mc.gui.screen().width - CHEST_WIDTH) / 2;
        int top = (mc.gui.screen().height - (CHEST_HEIGHT_BASE + menu.getRowCount() * ROW_HEIGHT)) / 2;
        Slot target = menu.slots.get(slot);
        return new Point(left + target.x + SLOT_MIDDLE, top + target.y + SLOT_MIDDLE);
    }

    // What the grave's record holds now, not the copy taken at the death
    private static List<ItemStack> storedItems(MinecraftServer server, Grave grave) {
        return GraveStore.get(server).byId(grave.id()).map(Grave::items)
                .orElseThrow(() -> new AssertionError("the grave's record is gone"));
    }

    private static int diamonds(List<ItemStack> stacks) {
        int count = 0;
        for (ItemStack stack : stacks) {
            if (stack.is(Items.DIAMOND)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static int diamondsHeld(Minecraft mc) {
        Inventory inventory = mc.player.getInventory();
        int count = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(Items.DIAMOND)) {
                count += stack.getCount();
            }
        }
        return count;
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
