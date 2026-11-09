package bobbynooby.dev.gametest.client;

import bobbynooby.dev.YouOnlyLiveTwice;
import bobbynooby.dev.bodies.BodyRecord;
import bobbynooby.dev.bodies.BodyStore;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.gametest.TestPlayers;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

// Reads each body's hitbox as the real client has it, since that is the box players aim at.
// Test players have no skin, so every body shows a default one.
public class BodyClientTest implements FabricClientGameTest {
    private static final int BODY_DISTANCE = 3;
    private static final double TOLERANCE = 1.0E-3;

    private record Body(UUID owner, UUID entity) {
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        ClientTests.withServer(ctx, (server, connection) -> {
            boolean enabledWas = server.computeOnServer(s -> Settings.get().bodies.enabled);
            YoltConfig.BodyPose poseWas = server.computeOnServer(s -> Settings.get().bodies.pose);
            YoltConfig.BodyType typeWas = server.computeOnServer(s -> Settings.get().bodies.type);
            // Only touched on the server thread
            List<UUID> owners = new ArrayList<>();
            try {
                server.runOnServer(s -> Settings.get().bodies.enabled = true);
                // Prone and standing share a width, so only the height tells them apart
                showMannequin(ctx, server, connection, owners, YoltConfig.BodyPose.PRONE, 0.6, 0.6);
                showMannequin(ctx, server, connection, owners, YoltConfig.BodyPose.SLEEPING, 0.2, 0.2);
                showMannequin(ctx, server, connection, owners, YoltConfig.BodyPose.STANDING, 0.6, 1.8);
                showPlayerBody(ctx, server, connection, owners);
            } finally {
                server.runOnServer(s -> {
                    // Switched back first, so a test player still online leaves no body
                    Settings.get().bodies.enabled = enabledWas;
                    Settings.get().bodies.pose = poseWas;
                    Settings.get().bodies.type = typeWas;
                    for (UUID owner : owners) {
                        ServerPlayer online = s.getPlayerList().getPlayer(owner);
                        if (online != null) {
                            TestPlayers.leave(online);
                        }
                        removeBody(s, owner);
                    }
                });
            }
        });
    }

    private static void showMannequin(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                      TestDedicatedServerConnection connection, List<UUID> owners,
                                      YoltConfig.BodyPose pose, double width, double height) {
        String name = pose.name().toLowerCase(Locale.ROOT);
        Body body = leaveBody(server, connection, owners, pose);
        waitForBody(ctx, connection, EntityTypes.MANNEQUIN);
        if (pose == YoltConfig.BodyPose.PRONE) {
            ClientTests.waitUntilProne(ctx, body.entity());
        }
        checkHitbox(ctx, body.entity(), name, width, height);
        screenshot(ctx, "04-body-" + name);
        // So the client never has two bodies in view
        server.runOnServer(s -> removeBody(s, body.owner()));
        ctx.waitFor(mc -> mc.level.getEntity(body.entity()) == null);
    }

    private static void showPlayerBody(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                       TestDedicatedServerConnection connection, List<UUID> owners) {
        server.runOnServer(s -> Settings.get().bodies.type = YoltConfig.BodyType.PLAYER);
        Body body = leaveBody(server, connection, owners, YoltConfig.BodyPose.PRONE);
        waitForBody(ctx, connection, EntityTypes.PLAYER);
        ClientTests.waitUntilProne(ctx, body.entity());

        if (!ctx.computeOnClient(mc -> mc.level.getEntity(body.entity()) instanceof Player)) {
            throw new AssertionError("the client has no player entity for the player body");
        }
        // The client only spawns a player it has an entry for
        if (!ctx.computeOnClient(mc -> mc.getConnection().getPlayerInfo(body.entity()) != null)) {
            throw new AssertionError("the client has no entry for the player body");
        }
        boolean listed = ctx.computeOnClient(mc -> mc.getConnection().getListedOnlinePlayers().stream()
                .anyMatch(info -> info.getProfile().id().equals(body.entity())));
        if (listed) {
            throw new AssertionError("the client lists the player body in the tab list");
        }
        // The body has its own id, so the owner stays offline to the client
        if (!ctx.computeOnClient(mc -> mc.getConnection().getPlayerInfo(body.owner()) == null)) {
            throw new AssertionError("the client has an entry for the body's offline owner");
        }
        checkHitbox(ctx, body.entity(), "player", 0.6, 0.6);
        screenshot(ctx, "04-body-player");
    }

    // A test player in an iron chestplate logs out a few blocks in front of the client, and the client looks at the body
    private static Body leaveBody(TestDedicatedServerContext server, TestDedicatedServerConnection connection,
                                  List<UUID> owners, YoltConfig.BodyPose pose) {
        return server.computeOnServer(s -> {
            Settings.get().bodies.pose = pose;
            ServerPlayer self = connection.getServerPlayer();
            Direction facing = self.getDirection();
            ServerPlayer sleeper = TestPlayers.join(s, "Sleeper", GameType.SURVIVAL,
                    self.blockPosition().relative(facing, BODY_DISTANCE));
            owners.add(sleeper.getUUID());
            sleeper.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            // Lying bodies side-on and a standing one from the front. Vanilla lays a sleeper a quarter turn from its facing.
            float turn = switch (pose) {
                case PRONE -> 90.0F;
                case SLEEPING -> 0.0F;
                case STANDING -> 180.0F;
            };
            float yaw = facing.toYRot() + turn;
            sleeper.setYRot(yaw);
            sleeper.setYHeadRot(yaw);
            sleeper.setYBodyRot(yaw);
            TestPlayers.leave(sleeper);

            BodyRecord record = BodyStore.get(s).byOwner(sleeper.getUUID())
                    .orElseThrow(() -> new AssertionError("the logout left no body record"));
            Entity entity = s.overworld().getEntity(record.bodyEntity());
            if (entity == null) {
                throw new AssertionError("the logout's body is not in the world");
            }
            self.lookAt(EntityAnchorArgument.Anchor.EYES, entity.getBoundingBox().getCenter());
            return new Body(sleeper.getUUID(), record.bodyEntity());
        });
    }

    private static void waitForBody(ClientGameTestContext ctx, TestDedicatedServerConnection connection, EntityType<?> type) {
        connection.waitForClientboundEntityUpdates(type);
        // The camera turned, so sections that came into view may still be building
        connection.waitForChunksRender();
    }

    private static void checkHitbox(ClientGameTestContext ctx, UUID bodyId, String name, double width, double height) {
        AABB box = ctx.computeOnClient(mc -> Optional.ofNullable(mc.level.getEntity(bodyId)).map(Entity::getBoundingBox))
                .orElseThrow(() -> new AssertionError("the client has no entity for the " + name + " body"));
        YouOnlyLiveTwice.LOGGER.info("The client's hitbox for the {} body is {} wide, {} deep and {} tall",
                name, box.getXsize(), box.getZsize(), box.getYsize());
        if (!near(box.getXsize(), width) || !near(box.getZsize(), width)) {
            throw new AssertionError("the client's " + name + " body is " + box.getXsize() + " by " + box.getZsize()
                    + " wide, not " + width);
        }
        if (!near(box.getYsize(), height)) {
            throw new AssertionError("the client's " + name + " body is " + box.getYsize() + " tall, not " + height);
        }
    }

    private static boolean near(double actual, double expected) {
        return Math.abs(actual - expected) < TOLERANCE;
    }

    private static void removeBody(MinecraftServer server, UUID owner) {
        BodyStore store = BodyStore.get(server);
        store.byOwner(owner).ifPresent(record -> {
            store.remove(owner);
            Entity entity = server.overworld().getEntityInAnyDimension(record.bodyEntity());
            if (entity != null) {
                entity.discard();
            }
        });
    }

    private static void screenshot(ClientGameTestContext ctx, String name) {
        ctx.runOnClient(mc -> mc.gui.toastManager().clear());
        // Without the HUD the crosshair doesn't cover the body
        ctx.getInput().pressKey(options -> options.keyToggleGui);
        ctx.waitFor(mc -> mc.gui.hud.isHidden());
        // No counter prefix, so the CI artifact keeps the plain file names
        ctx.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix());
        ctx.getInput().pressKey(options -> options.keyToggleGui);
        ctx.waitFor(mc -> !mc.gui.hud.isHidden());
    }
}
