package bobbynooby.dev.gametest.client;

import bobbynooby.dev.YouOnlyLiveTwice;
import bobbynooby.dev.bodies.BodyStore;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// The client's own logout leaves a body. While the client is away the body is hit and moved, and the client's next
// login takes it over: the client comes back where the body lay, with its health and its armour's wear.
public class BodyTakeoverClientTest implements FabricClientGameTest {
    private static final float BODY_HEALTH = 10.0F;
    private static final float HIT = 6.0F;
    private static final int HITS = 2;
    private static final int MOVE_BLOCKS = 4;
    // Vanilla heals nobody with less food than this, so the hearts keep showing the body's health
    private static final int NO_HEALING_FOOD = 17;
    private static final int SETTLE_TIMEOUT = 100;
    private static final double TOLERANCE = 0.01;

    private record Logout(Vec3 pos, Direction facing) {
    }

    private record Away(UUID body, Vec3 pos, float health, int chestWear) {
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        UUID self = ctx.computeOnClient(mc -> mc.getGameProfile().id());
        // Its own server, since the client leaves and joins again in the middle
        try (TestDedicatedServerContext server = ctx.worldBuilder().createServer()) {
            boolean enabledWas = server.computeOnServer(s -> Settings.get().bodies.enabled);
            YoltConfig.BodyType typeWas = server.computeOnServer(s -> Settings.get().bodies.type);
            // Chunks this test forced to stay loaded. Only touched on the server thread.
            List<BlockPos> forced = new ArrayList<>();
            try {
                server.runOnServer(s -> {
                    Settings.get().bodies.enabled = true;
                    Settings.get().bodies.type = YoltConfig.BodyType.MANNEQUIN;
                });
                Logout logout = logOutInArmour(server, forced);
                Away away = hurtAndMoveBody(server, self, logout);
                try (TestDedicatedServerConnection connection = server.connect()) {
                    try {
                        connection.waitForChunksRender();
                        connection.waitForClientboundPackets();
                        checkTakenOver(ctx, server, self, away);
                        showTakeover(ctx);
                    } finally {
                        // Before this connection closes, so the client's last logout leaves no body
                        server.runOnServer(s -> Settings.get().bodies.enabled = enabledWas);
                    }
                }
            } finally {
                server.runOnServer(s -> {
                    Settings.get().bodies.enabled = enabledWas;
                    Settings.get().bodies.type = typeWas;
                    for (BlockPos pos : forced) {
                        s.overworld().setChunkForced(SectionPos.blockToSectionCoord(pos.getX()),
                                SectionPos.blockToSectionCoord(pos.getZ()), false);
                    }
                    BodyStoryClientTest.removeBody(s, self);
                });
            }
        }
    }

    // The client joins, puts on diamond armour and leaves again, which leaves its body
    private static Logout logOutInArmour(TestDedicatedServerContext server, List<BlockPos> forced) {
        try (TestDedicatedServerConnection connection = server.connect()) {
            connection.waitForChunksRender();
            return server.computeOnServer(s -> {
                ServerPlayer player = connection.getServerPlayer();
                BodyStoryClientTest.DIAMOND_GEAR.forEach((slot, item) -> player.setItemSlot(slot, new ItemStack(item)));
                player.getFoodData().setFoodLevel(NO_HEALING_FOOD);
                player.getFoodData().setSaturation(0.0F);
                Direction facing = player.getDirection();
                // Without the client nothing keeps these loaded, and the body has to stay where the test can reach it
                force(s, forced, player.blockPosition());
                force(s, forced, player.blockPosition().relative(facing.getOpposite(), MOVE_BLOCKS));
                return new Logout(player.position(), facing);
            });
        }
    }

    private static Away hurtAndMoveBody(TestDedicatedServerContext server, UUID self, Logout logout) {
        // The server handles the leaving on its own thread, a moment after the client went
        ClientTests.waitOnServer(server, SETTLE_TIMEOUT,
                s -> s.getPlayerList().getPlayer(self) == null && BodyStore.get(s).byOwner(self).isPresent(),
                s -> "the client's logout left no body");
        UUID body = server.computeOnServer(s -> {
            UUID id = BodyStore.get(s).byOwner(self).orElseThrow().bodyEntity();
            if (!(s.overworld().getEntity(id) instanceof Mannequin)) {
                throw new AssertionError("the client's body is not a mannequin in the world");
            }
            return id;
        });
        for (int i = 0; i < HITS; i++) {
            // A hit too soon after the last one only does the difference
            ClientTests.waitOnServer(server, SETTLE_TIMEOUT, s -> BodyStoryClientTest.resting(BodyStoryClientTest.living(s, body)),
                    s -> "the client's body never came to rest");
            server.runOnServer(s -> {
                // Cactus wears armour like a hit but knocks nothing back, so the body stays in its loaded chunk
                if (!BodyStoryClientTest.living(s, body).hurtServer(s.overworld(), s.overworld().damageSources().cactus(), HIT)) {
                    throw new AssertionError("a hit did not land on the client's body");
                }
            });
        }
        Vec3 to = logout.pos().add(logout.facing().getOpposite().getStepX() * MOVE_BLOCKS, 0.0,
                logout.facing().getOpposite().getStepZ() * MOVE_BLOCKS);
        server.runOnServer(s -> {
            LivingEntity entity = BodyStoryClientTest.living(s, body);
            entity.teleportTo(to.x, to.y, to.z);
            entity.setDeltaMovement(Vec3.ZERO);
            entity.setHealth(BODY_HEALTH);
        });
        // Settled where it was put, so the spot read here is the one the login takes over
        ClientTests.waitOnServer(server, SETTLE_TIMEOUT, s -> BodyStoryClientTest.resting(BodyStoryClientTest.living(s, body)),
                s -> "the moved body never came to rest");
        return server.computeOnServer(s -> {
            LivingEntity entity = BodyStoryClientTest.living(s, body);
            if (entity.position().distanceTo(to) > TOLERANCE) {
                throw new AssertionError("the body is at " + entity.position() + ", not where it was moved to at " + to);
            }
            int wear = entity.getItemBySlot(EquipmentSlot.CHEST).getDamageValue();
            if (wear <= 0) {
                throw new AssertionError("the hits did not wear the body's chestplate");
            }
            return new Away(body, entity.position(), entity.getHealth(), wear);
        });
    }

    private static void checkTakenOver(ClientGameTestContext ctx, TestDedicatedServerContext server, UUID self, Away away) {
        boolean bodyLeft = server.computeOnServer(s -> BodyStore.get(s).byOwner(self).isPresent()
                || s.overworld().getEntity(away.body()) != null);
        if (bodyLeft) {
            throw new AssertionError("the client's body is still on the server after its login");
        }
        // Health and gear reach the client in its player's next tick, after the login itself
        ClientTests.waitOnClient(ctx, SETTLE_TIMEOUT, mc -> Math.abs(mc.player.getHealth() - away.health()) < TOLERANCE,
                mc -> "the client has " + mc.player.getHealth() + " health, not its body's " + away.health());
        ClientTests.waitOnClient(ctx, SETTLE_TIMEOUT, mc -> mc.player.position().distanceTo(away.pos()) < TOLERANCE,
                mc -> "the client is at " + mc.player.position() + ", not where its body lay at " + away.pos());
        ClientTests.waitOnClient(ctx, SETTLE_TIMEOUT,
                mc -> mc.player.getItemBySlot(EquipmentSlot.CHEST).getDamageValue() == away.chestWear(),
                mc -> "the client's chestplate has " + mc.player.getItemBySlot(EquipmentSlot.CHEST).getDamageValue()
                        + " wear, not its body's " + away.chestWear());
        if (ctx.computeOnClient(mc -> mc.level.getEntity(away.body()) != null)) {
            throw new AssertionError("the client still sees the body it took over");
        }
        // Listed in the log, so whatever shows in the screenshot can be told apart
        List<String> seen = ctx.computeOnClient(mc -> {
            List<String> entities = new ArrayList<>();
            for (Entity entity : mc.level.entitiesForRendering()) {
                if (entity != mc.player) {
                    entities.add(EntityType.getKey(entity.getType()) + " at " + entity.position());
                }
            }
            return entities;
        });
        YouOnlyLiveTwice.LOGGER.info("After the takeover the client at {} sees {}", away.pos(), seen);
        // Any mannequin at all would be a second body
        if (ctx.computeOnClient(mc -> {
            for (Entity entity : mc.level.entitiesForRendering()) {
                if (entity instanceof Mannequin) {
                    return true;
                }
            }
            return false;
        })) {
            throw new AssertionError("the client sees a mannequin after taking over its body: " + seen);
        }
    }

    private static void showTakeover(ClientGameTestContext ctx) {
        // The sword in hand at login restarts the attack cooldown, whose meter would sit under the crosshair
        ClientTests.waitOnClient(ctx, ClientGameTestContext.DEFAULT_TIMEOUT, mc -> mc.player.getAttackStrengthScale(0.0F) >= 1.0F,
                mc -> "the client's attack cooldown never filled up");
        // Five hearts: the body's health, not the full health the client left with
        ClientTests.screenshot(ctx, "05-login-takeover");
        ctx.getInput().pressKey(options -> options.keyInventory);
        ctx.waitForScreen(InventoryScreen.class);
        // Off the slots, so none is highlighted
        ctx.getInput().setCursorPos(0, 0);
        // Each armour piece now has a durability bar: the body's wear came back with it
        ClientTests.screenshot(ctx, "05-login-takeover-armour");
        ctx.getInput().pressKey(InputConstants.KEY_ESCAPE);
        ctx.waitForScreen(null);
    }

    private static void force(MinecraftServer s, List<BlockPos> forced, BlockPos pos) {
        // Only the ones this test forced are let go again
        if (s.overworld().setChunkForced(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()), true)) {
            forced.add(pos);
        }
    }
}
