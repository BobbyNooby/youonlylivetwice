package bobbynooby.dev.gametest.client;

import bobbynooby.dev.bodies.BodyStore;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.List;
import java.util.Map;
import java.util.UUID;

// Bob's ask, on a real client: the client logs out, which leaves its body, and logs back in, which takes the body over.
// Its hands then hold exactly what they held, in the same selected hotbar slot. ItemStack.matches compares the item, the
// count and every component, so a lost name, enchantment or bit of wear fails.
public class HandsClientTest implements FabricClientGameTest {
    // Not the first slot, so a selected slot that came back as the default fails
    private static final int HOTBAR_SLOT = 4;
    private static final int TORCHES = 37;
    private static final int SWORD_WEAR = 117;
    private static final int SETTLE_TIMEOUT = 100;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        UUID self = ctx.computeOnClient(mc -> mc.getGameProfile().id());
        // Its own server, since the client leaves and joins again in the middle
        try (TestDedicatedServerContext server = ctx.worldBuilder().createServer()) {
            boolean enabledWas = server.computeOnServer(s -> Settings.get().bodies.enabled);
            YoltConfig.BodyType typeWas = server.computeOnServer(s -> Settings.get().bodies.type);
            try {
                server.runOnServer(s -> {
                    // On by default; the test server turns bodies off for its test players
                    Settings.get().bodies.enabled = true;
                    Settings.get().bodies.type = YoltConfig.BodyType.MANNEQUIN;
                });
                logOutHolding(ctx, server);
                checkBodyHolds(server, self);
                try (TestDedicatedServerConnection connection = server.connect()) {
                    try {
                        connection.waitForChunksRender();
                        showHandsBack(ctx, server, connection, self);
                    } finally {
                        // Before this connection closes, so the client's last logout leaves no body
                        server.runOnServer(s -> Settings.get().bodies.enabled = enabledWas);
                    }
                }
            } finally {
                // The switches are static, so later client tests in this JVM would see them
                ClientTests.runAll(List.of(
                        () -> server.runOnServer(s -> Settings.get().bodies.enabled = enabledWas),
                        () -> server.runOnServer(s -> Settings.get().bodies.type = typeWas),
                        () -> server.runOnServer(s -> BodyStoryClientTest.removeBody(s, self))));
            }
        }
    }

    // The client joins, takes the sword in a hotbar slot it picks with its own key and the torches in its offhand, and
    // leaves again
    private static void logOutHolding(ClientGameTestContext ctx, TestDedicatedServerContext server) {
        try (TestDedicatedServerConnection connection = server.connect()) {
            connection.waitForChunksRender();
            server.runOnServer(s -> {
                ServerPlayer player = connection.getServerPlayer();
                player.getInventory().setItem(HOTBAR_SLOT, sword(s.registryAccess()));
                player.setItemSlot(EquipmentSlot.OFFHAND, torches());
            });
            ClientTests.selectSlot(ctx, server, connection, HOTBAR_SLOT);
            // The control: the client holds exactly this before it leaves, so the same check after the login means
            // nothing was lost on the way
            ClientTests.waitOnClient(ctx, ClientGameTestContext.DEFAULT_TIMEOUT, HandsClientTest::holdsBoth,
                    mc -> "before the logout the client holds " + hands(mc));
        }
    }

    // The leave, on the server's own thread a moment after the client went, made a body holding the same two stacks
    private static void checkBodyHolds(TestDedicatedServerContext server, UUID self) {
        ClientTests.waitOnServer(server, SETTLE_TIMEOUT,
                s -> s.getPlayerList().getPlayer(self) == null && BodyStore.get(s).byOwner(self).isPresent(),
                s -> "the client's logout left no body");
        server.runOnServer(s -> {
            // The record's copy, since nothing keeps the body's chunk loaded once the client has gone
            Map<EquipmentSlot, ItemStack> gear = BodyStore.get(s).byOwner(self).orElseThrow().snapshot().gear();
            ItemStack mainHand = gear.getOrDefault(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            ItemStack offhand = gear.getOrDefault(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            if (!ItemStack.matches(mainHand, sword(s.registryAccess())) || !ItemStack.matches(offhand, torches())) {
                throw new AssertionError("the client's body holds " + mainHand + " and " + offhand);
            }
        });
    }

    private static void showHandsBack(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                      TestDedicatedServerConnection connection, UUID self) {
        boolean bodyLeft = server.computeOnServer(s -> BodyStore.get(s).byOwner(self).filter(record -> !record.pendingRemoval())
                .isPresent());
        if (bodyLeft) {
            throw new AssertionError("the login did not take the client's body over");
        }
        // Each slot the server sends carries its menu's next state id. The join is long past and each change goes out at
        // the player's next tick, so the client has every slot once its id is the server's.
        int sent = server.computeOnServer(s -> connection.getServerPlayer().inventoryMenu.getStateId());
        ClientTests.waitOnClient(ctx, SETTLE_TIMEOUT, mc -> mc.player.inventoryMenu.getStateId() == sent,
                mc -> "the client's inventory is at state " + mc.player.inventoryMenu.getStateId() + ", not the server's " + sent);
        // The sword in hand at login restarts the attack cooldown, whose meter would sit under the crosshair
        ClientTests.waitOnClient(ctx, ClientGameTestContext.DEFAULT_TIMEOUT, mc -> mc.player.getAttackStrengthScale(0.0F) >= 1.0F,
                mc -> "the client's attack cooldown never filled up");
        ClientTests.waitUntilHandsUp(ctx);
        ctx.runOnClient(mc -> {
            int selected = mc.player.getInventory().getSelectedSlot();
            if (selected != HOTBAR_SLOT) {
                throw new AssertionError("after the login the client has slot " + selected + " selected, not " + HOTBAR_SLOT);
            }
            if (!ItemStack.matches(mc.player.getMainHandItem(), sword(mc.level.registryAccess()))) {
                throw new AssertionError("after the login the client's main hand holds " + mc.player.getMainHandItem());
            }
            if (!ItemStack.matches(mc.player.getOffhandItem(), torches())) {
                throw new AssertionError("after the login the client's offhand holds " + mc.player.getOffhandItem());
            }
            // Nothing came back twice either, in another slot or split up. The one sword is the one in hand, checked above.
            Inventory inventory = mc.player.getInventory();
            int swords = 0;
            int torches = 0;
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (stack.is(Items.DIAMOND_SWORD)) {
                    swords++;
                }
                if (stack.is(Items.TORCH)) {
                    torches += stack.getCount();
                }
            }
            if (swords != 1) {
                throw new AssertionError("after the login the client's inventory has " + swords + " diamond swords, not 1");
            }
            if (torches != TORCHES) {
                throw new AssertionError("after the login the client's inventory has " + torches + " torches, not " + TORCHES);
            }
        });
        ClientTests.screenshot(ctx, "08-hands-after-relogin");
    }

    private static boolean holdsBoth(Minecraft mc) {
        return mc.player.getInventory().getSelectedSlot() == HOTBAR_SLOT
                && ItemStack.matches(mc.player.getMainHandItem(), sword(mc.level.registryAccess()))
                && ItemStack.matches(mc.player.getOffhandItem(), torches());
    }

    private static String hands(Minecraft mc) {
        return mc.player.getMainHandItem() + " in slot " + mc.player.getInventory().getSelectedSlot() + " and "
                + mc.player.getOffhandItem();
    }

    // Built from the registries of the side that compares it: an enchantment only equals one from the same registry, and
    // the client gets new registries with each connection
    private static ItemStack sword(HolderLookup.Provider registries) {
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.set(DataComponents.CUSTOM_NAME, Component.literal("Oathkeeper"));
        sword.enchant(registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS), 3);
        sword.setDamageValue(SWORD_WEAR);
        return sword;
    }

    private static ItemStack torches() {
        return new ItemStack(Items.TORCH, TORCHES);
    }
}
