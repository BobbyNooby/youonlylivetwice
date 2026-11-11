package bobbynooby.dev.gametest.client;

import bobbynooby.dev.bodies.BodyRecord;
import bobbynooby.dev.bodies.BodyStore;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.gametest.TestPlayers;
import bobbynooby.dev.graves.Grave;
import bobbynooby.dev.graves.GraveStore;
import bobbynooby.dev.lockout.DeathLockout;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// One body's life on a real client: it lies in its owner's diamond gear, a zombie goes for it, a player knocks it
// back, its armour wears down and it dies into a grave. Then a player-type body is hunted too.
// Test players have no skin, so every body shows a default one.
public class BodyStoryClientTest implements FabricClientGameTest {
    static final Map<EquipmentSlot, Item> DIAMOND_GEAR = Map.of(
            EquipmentSlot.HEAD, Items.DIAMOND_HELMET,
            EquipmentSlot.CHEST, Items.DIAMOND_CHESTPLATE,
            EquipmentSlot.LEGS, Items.DIAMOND_LEGGINGS,
            EquipmentSlot.FEET, Items.DIAMOND_BOOTS,
            EquipmentSlot.MAINHAND, Items.DIAMOND_SWORD);
    private static final List<EquipmentSlot> ARMOUR = List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET);
    private static final int BODY_DISTANCE = 3;
    private static final int MOB_SIDEWAYS = 3;
    private static final int BRAWLER_SIDEWAYS = 2;
    private static final float PUNCH = 6.0F;
    private static final double MIN_KNOCKBACK = 0.5;
    private static final int HUNT_TIMEOUT = 400;
    private static final int SETTLE_TIMEOUT = 100;
    private static final double GRAVE_VIEW_DISTANCE = 2.0;
    private static final int DEATH_SMOKE_TICKS = 60;

    // Where the scenes play: in front of where the client joined, on the flat world's ground
    private record Stage(BlockPos origin, Direction facing, double groundY) {
        // To the right of the client's view
        Direction side() {
            return facing.getClockWise();
        }
    }

    private record Body(UUID owner, UUID entity) {
    }

    private record Punch(UUID brawler, Vec3 from, float health) {
    }

    // Everything the scenes add, for the cleanup. Only touched on the server thread.
    private static final class Made {
        final List<UUID> players = new ArrayList<>();
        final List<UUID> mobs = new ArrayList<>();
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        ClientTests.withServer(ctx, (server, connection) -> {
            boolean enabledWas = server.computeOnServer(s -> Settings.get().bodies.enabled);
            YoltConfig.BodyType typeWas = server.computeOnServer(s -> Settings.get().bodies.type);
            YoltConfig.BodyPose poseWas = server.computeOnServer(s -> Settings.get().bodies.pose);
            Difficulty difficultyWas = server.computeOnServer(s -> s.getWorldData().getDifficulty());
            UUID self = server.computeOnServer(s -> connection.getServerPlayer().getUUID());
            GameType modeWas = server.computeOnServer(s -> connection.getServerPlayer().gameMode());
            Made made = new Made();
            try {
                Stage stage = server.computeOnServer(s -> {
                    YoltConfig.BodiesSection bodies = Settings.get().bodies;
                    bodies.enabled = true;
                    bodies.type = YoltConfig.BodyType.MANNEQUIN;
                    bodies.pose = YoltConfig.BodyPose.PRONE;
                    // Peaceful would take the zombies away
                    s.setDifficulty(Difficulty.NORMAL, true);
                    ServerPlayer player = connection.getServerPlayer();
                    // Zombies leave creative players alone, so the bodies are all they go for
                    player.setGameMode(GameType.CREATIVE);
                    return new Stage(player.blockPosition(), player.getDirection(), player.getY());
                });
                Body knight = showFullArmour(ctx, server, connection, stage, made);
                showZombieAttack(ctx, server, connection, stage, made, knight);
                showKnockback(ctx, server, connection, stage, made, knight);
                showArmourWorn(ctx, server, connection, stage, knight);
                showGrave(ctx, server, connection, knight);
                showPlayerBodyHunted(ctx, server, connection, stage, made);
            } finally {
                server.runOnServer(s -> {
                    // The switches first: they are static, so later client tests in this JVM would see them
                    YoltConfig.BodiesSection bodies = Settings.get().bodies;
                    bodies.enabled = enabledWas;
                    bodies.type = typeWas;
                    bodies.pose = poseWas;
                    List<Runnable> steps = new ArrayList<>();
                    steps.add(() -> s.setDifficulty(difficultyWas, true));
                    steps.add(() -> {
                        ServerPlayer player = s.getPlayerList().getPlayer(self);
                        if (player != null) {
                            player.setGameMode(modeWas);
                        }
                    });
                    for (UUID mob : made.mobs) {
                        steps.add(() -> discard(s, mob));
                    }
                    for (UUID owner : made.players) {
                        steps.add(() -> ClientTests.leaveWithoutBody(s, owner));
                        steps.add(() -> removeBody(s, owner));
                        steps.add(() -> removeGraves(s, owner));
                        // A body's death locks its owner out
                        steps.add(() -> DeathLockout.revive(owner, "Test", "console"));
                    }
                    ClientTests.runAll(steps);
                });
            }
        });
    }

    private static Body showFullArmour(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                       TestDedicatedServerConnection connection, Stage stage, Made made) {
        Body body = leaveBody(server, stage, made, "Knight", DIAMOND_GEAR);
        connection.waitForClientboundEntityUpdates(EntityTypes.MANNEQUIN);
        ClientTests.waitUntilProne(ctx, body.entity());
        ctx.runOnClient(mc -> {
            LivingEntity seen = clientLiving(mc, body.entity(), "armoured body");
            DIAMOND_GEAR.forEach((slot, item) -> {
                ItemStack worn = seen.getItemBySlot(slot);
                if (!worn.is(item)) {
                    throw new AssertionError("the client's body has " + worn + " in its " + slot + " slot, not " + item);
                }
            });
        });
        frame(server, connection, stage, BODY_DISTANCE, body.entity());
        ClientTests.assertInView(ctx, body.entity(), "armoured body");
        ClientTests.screenshotWithoutHud(ctx, "05-body-full-armour");
        return body;
    }

    private static void showZombieAttack(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                         TestDedicatedServerConnection connection, Stage stage, Made made, Body body) {
        float before = server.computeOnServer(s -> living(s, body.entity()).getHealth());
        UUID zombie = server.computeOnServer(s -> spawnZombie(s, made, living(s, body.entity()).position(), stage.side()));
        ClientTests.waitOnServer(server, HUNT_TIMEOUT, s -> living(s, body.entity()).getHealth() < before,
                s -> "the zombie never hurt the body, which still has " + living(s, body.entity()).getHealth() + " health");
        connection.waitForClientboundEntityUpdates(EntityTypes.ZOMBIE, EntityTypes.MANNEQUIN);
        float seen = ctx.computeOnClient(mc -> clientLiving(mc, body.entity(), "body").getHealth());
        if (seen >= before) {
            throw new AssertionError("the client's body still has " + seen + " health after the zombie's hit");
        }
        frame(server, connection, stage, BODY_DISTANCE + 0.5, body.entity(), zombie);
        ClientTests.assertInView(ctx, body.entity(), "body");
        ClientTests.assertInView(ctx, zombie, "zombie");
        ClientTests.screenshotWithoutHud(ctx, "05-zombie-attacks-body");
        removeMob(ctx, server, made, zombie);
    }

    // A test player punches the body from its left, which knocks it to the right
    private static void showKnockback(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                      TestDedicatedServerConnection connection, Stage stage, Made made, Body body) {
        // Still sliding from the zombie, or too soon after its hit for a full one
        ClientTests.waitOnServer(server, SETTLE_TIMEOUT, s -> resting(living(s, body.entity())),
                s -> "the body never came to rest after the zombie: " + living(s, body.entity()).getDeltaMovement());
        Punch punch = server.computeOnServer(s -> {
            LivingEntity target = living(s, body.entity());
            BlockPos stand = BlockPos.containing(target.position()).relative(stage.side().getOpposite(), BRAWLER_SIDEWAYS);
            ServerPlayer brawler = TestPlayers.join(s, "Brawler", GameType.SURVIVAL, stand);
            made.players.add(brawler.getUUID());
            face(brawler, stage.side().toYRot());
            Punch landed = new Punch(brawler.getUUID(), target.position(), target.getHealth());
            if (!target.hurtServer(s.overworld(), s.overworld().damageSources().playerAttack(brawler), PUNCH)) {
                throw new AssertionError("the punch did not land on the body");
            }
            return landed;
        });
        ClientTests.waitOnServer(server, SETTLE_TIMEOUT,
                s -> resting(living(s, body.entity())) && flatDistance(living(s, body.entity()).position(), punch.from()) > MIN_KNOCKBACK,
                s -> "the punch moved the body " + flatDistance(living(s, body.entity()).position(), punch.from()) + " blocks");
        float health = server.computeOnServer(s -> living(s, body.entity()).getHealth());
        if (health >= punch.health()) {
            throw new AssertionError("the body still has " + health + " health after the punch");
        }
        connection.waitForClientboundEntityUpdates(EntityTypes.MANNEQUIN, EntityTypes.PLAYER);
        ctx.runOnClient(mc -> {
            LivingEntity seen = clientLiving(mc, body.entity(), "body");
            double moved = flatDistance(seen.position(), punch.from());
            if (moved <= MIN_KNOCKBACK) {
                throw new AssertionError("the client sees the body only " + moved + " blocks from where it was punched");
            }
            if (seen.getHealth() >= punch.health()) {
                throw new AssertionError("the client's body still has " + seen.getHealth() + " health after the punch");
            }
        });
        frame(server, connection, stage, 5.0, body.entity(), punch.brawler());
        ClientTests.assertInView(ctx, body.entity(), "body");
        ClientTests.assertInView(ctx, punch.brawler(), "brawler");
        ClientTests.screenshotWithoutHud(ctx, "05-body-knocked-back");
        server.runOnServer(s -> ClientTests.leaveWithoutBody(s, punch.brawler()));
    }

    private static void showArmourWorn(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                       TestDedicatedServerConnection connection, Stage stage, Body body) {
        Map<EquipmentSlot, Integer> wear = server.computeOnServer(s -> armourWear(living(s, body.entity())));
        wear.forEach((slot, damage) -> {
            if (damage <= 0) {
                throw new AssertionError("the body's " + slot + " armour did not wear down from the hits");
            }
        });
        Map<EquipmentSlot, Integer> seen = ctx.computeOnClient(mc -> armourWear(clientLiving(mc, body.entity(), "body")));
        if (!seen.equals(wear)) {
            throw new AssertionError("the client's body has armour wear " + seen + ", the server's " + wear);
        }
        // Worn armour looks like new on a body, so this is only a close look at it after its hits
        frame(server, connection, stage, 2.0, body.entity());
        ClientTests.assertInView(ctx, body.entity(), "body");
        ClientTests.screenshotWithoutHud(ctx, "05-body-armour-worn");
    }

    private static void showGrave(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                  TestDedicatedServerConnection connection, Body body) {
        BlockPos at = server.computeOnServer(s -> {
            LivingEntity target = living(s, body.entity());
            BlockPos where = target.blockPosition();
            target.kill(s.overworld());
            if (BodyStore.get(s).byOwner(body.owner()).isPresent()) {
                throw new AssertionError("the dead body kept its record");
            }
            Grave grave = gravesOf(s, body.owner()).stream().findFirst()
                    .orElseThrow(() -> new AssertionError("the dead body left no grave"));
            if (!grave.pos().equals(where)) {
                throw new AssertionError("the grave is at " + grave.pos() + ", not at " + where + " where the body died");
            }
            faceHead(s, connection, grave.pos());
            return grave.pos();
        });
        connection.waitForClientboundPackets();
        connection.waitForChunksRender();
        // The body plays its death animation before it goes
        ClientTests.waitOnClient(ctx, ClientGameTestContext.DEFAULT_TIMEOUT, mc -> mc.level.getEntity(body.entity()) == null,
                mc -> "the client still has the dead body");
        if (!ctx.computeOnClient(mc -> mc.level.getBlockState(at).is(Blocks.PLAYER_HEAD))) {
            throw new AssertionError("the client has no player head where the body died");
        }
        // The smoke it leaves in would cover the head. A fixed wait, since the particle engine only gives its count as F3
        // text, and it only clears the picture: the checks are done.
        ctx.waitTicks(DEATH_SMOKE_TICKS);
        ClientTests.screenshotWithoutHud(ctx, "05-body-killed-grave");
        // So the next body has the spot to itself
        server.runOnServer(s -> removeGraves(s, body.owner()));
    }

    private static void showPlayerBodyHunted(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                             TestDedicatedServerConnection connection, Stage stage, Made made) {
        server.runOnServer(s -> Settings.get().bodies.type = YoltConfig.BodyType.PLAYER);
        Body body = leaveBody(server, stage, made, "Hunted", Map.of());
        connection.waitForClientboundEntityUpdates(EntityTypes.PLAYER);
        if (!ctx.computeOnClient(mc -> mc.level.getEntity(body.entity()) instanceof Player)) {
            throw new AssertionError("the client has no player entity for the player body");
        }
        float before = server.computeOnServer(s -> living(s, body.entity()).getHealth());
        UUID zombie = server.computeOnServer(s -> spawnZombie(s, made, living(s, body.entity()).position(), stage.side()));
        ClientTests.waitOnServer(server, HUNT_TIMEOUT, s -> living(s, body.entity()).getHealth() < before,
                s -> "the zombie never hurt the player body, which still has " + living(s, body.entity()).getHealth() + " health");
        connection.waitForClientboundEntityUpdates(EntityTypes.ZOMBIE, EntityTypes.PLAYER);
        float seen = ctx.computeOnClient(mc -> clientLiving(mc, body.entity(), "player body").getHealth());
        if (seen >= before) {
            throw new AssertionError("the client's player body still has " + seen + " health after the zombie's hit");
        }
        frame(server, connection, stage, BODY_DISTANCE + 0.5, body.entity(), zombie);
        ClientTests.assertInView(ctx, body.entity(), "player body");
        ClientTests.assertInView(ctx, zombie, "zombie");
        ClientTests.screenshotWithoutHud(ctx, "05-player-body-hunted");
        removeMob(ctx, server, made, zombie);
        server.runOnServer(s -> removeBody(s, body.owner()));
    }

    // A test player in the given gear logs out in front of the client and lies across its view, head to the right
    private static Body leaveBody(TestDedicatedServerContext server, Stage stage, Made made, String name,
                                  Map<EquipmentSlot, Item> gear) {
        return server.computeOnServer(s -> {
            ServerPlayer player = TestPlayers.join(s, name, GameType.SURVIVAL, stage.origin().relative(stage.facing(), BODY_DISTANCE));
            made.players.add(player.getUUID());
            gear.forEach((slot, item) -> player.setItemSlot(slot, new ItemStack(item)));
            // A prone body lies along its facing
            face(player, stage.side().toYRot());
            TestPlayers.leave(player);
            BodyRecord record = BodyStore.get(s).byOwner(player.getUUID())
                    .orElseThrow(() -> new AssertionError("the logout of " + name + " left no body record"));
            if (s.overworld().getEntity(record.bodyEntity()) == null) {
                throw new AssertionError("the body of " + name + " is not in the world");
            }
            return new Body(player.getUUID(), record.bodyEntity());
        });
    }

    // Beside the body and facing it. The helmet keeps the sun from setting it alight.
    private static UUID spawnZombie(MinecraftServer s, Made made, Vec3 target, Direction side) {
        ServerLevel level = s.overworld();
        Zombie zombie = new Zombie(level);
        Vec3 at = target.add(side.getStepX() * MOB_SIDEWAYS, 0.0, side.getStepZ() * MOB_SIDEWAYS);
        zombie.snapTo(at.x, at.y, at.z, side.getOpposite().toYRot(), 0.0F);
        zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        zombie.setPersistenceRequired();
        if (!level.addFreshEntity(zombie)) {
            throw new AssertionError("the world refused the zombie");
        }
        made.mobs.add(zombie.getUUID());
        return zombie.getUUID();
    }

    private static void removeMob(ClientGameTestContext ctx, TestDedicatedServerContext server, Made made, UUID mob) {
        server.runOnServer(s -> {
            discard(s, mob);
            made.mobs.remove(mob);
        });
        ClientTests.waitOnClient(ctx, ClientGameTestContext.DEFAULT_TIMEOUT, mc -> mc.level.getEntity(mob) == null,
                mc -> "the client still has a mob the server removed");
    }

    // Stands the client back from the middle of the entities, looking along the stage at that middle
    private static void frame(TestDedicatedServerContext server, TestDedicatedServerConnection connection, Stage stage,
                              double distance, UUID... ids) {
        server.runOnServer(s -> {
            Vec3 middle = Vec3.ZERO;
            for (UUID id : ids) {
                middle = middle.add(entity(s, id).getBoundingBox().getCenter());
            }
            middle = middle.scale(1.0 / ids.length);
            Direction facing = stage.facing();
            ServerPlayer player = connection.getServerPlayer();
            player.teleportTo(s.overworld(), middle.x - facing.getStepX() * distance, stage.groundY(),
                    middle.z - facing.getStepZ() * distance, Set.of(), facing.toYRot(), 0.0F, true);
            player.lookAt(EntityAnchorArgument.Anchor.EYES, middle);
        });
        connection.waitForClientboundPackets();
        // The camera moved, so sections that came into view may still be building
        connection.waitForChunksRender();
    }

    // A head shows its face to someone looking at it with the yaw it was placed at, so the client stands there
    private static void faceHead(MinecraftServer s, TestDedicatedServerConnection connection, BlockPos head) {
        ServerLevel level = s.overworld();
        float yaw = RotationSegment.convertToDegrees(level.getBlockState(head).getValue(SkullBlock.ROTATION));
        double radians = Math.toRadians(yaw);
        Vec3 base = Vec3.atBottomCenterOf(head);
        ServerPlayer player = connection.getServerPlayer();
        player.teleportTo(level, base.x + Math.sin(radians) * GRAVE_VIEW_DISTANCE, base.y,
                base.z - Math.cos(radians) * GRAVE_VIEW_DISTANCE, Set.of(), yaw, 0.0F, true);
        // The middle of the head, which is half a block tall
        player.lookAt(EntityAnchorArgument.Anchor.EYES, base.add(0.0, 0.25, 0.0));
    }

    private static void face(ServerPlayer player, float yaw) {
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setYBodyRot(yaw);
    }

    // On the ground, not moving, and past the time a new hit only does the difference
    static boolean resting(LivingEntity entity) {
        return entity.onGround() && entity.getDeltaMovement().horizontalDistanceSqr() < 1.0E-4 && entity.hurtTime == 0;
    }

    static double flatDistance(Vec3 a, Vec3 b) {
        return Math.hypot(a.x - b.x, a.z - b.z);
    }

    private static Map<EquipmentSlot, Integer> armourWear(LivingEntity entity) {
        Map<EquipmentSlot, Integer> wear = new EnumMap<>(EquipmentSlot.class);
        for (EquipmentSlot slot : ARMOUR) {
            wear.put(slot, entity.getItemBySlot(slot).getDamageValue());
        }
        return wear;
    }

    private static Entity entity(MinecraftServer s, UUID id) {
        Entity entity = s.overworld().getEntity(id);
        if (entity == null) {
            throw new AssertionError("no entity " + id + " on the server");
        }
        return entity;
    }

    static LivingEntity living(MinecraftServer s, UUID id) {
        if (entity(s, id) instanceof LivingEntity living) {
            return living;
        }
        throw new AssertionError("the entity " + id + " on the server is not living");
    }

    private static LivingEntity clientLiving(Minecraft mc, UUID id, String what) {
        if (mc.level.getEntity(id) instanceof LivingEntity living) {
            return living;
        }
        throw new AssertionError("the client has no " + what);
    }

    private static void discard(MinecraftServer s, UUID id) {
        Entity entity = s.overworld().getEntity(id);
        if (entity != null) {
            entity.discard();
        }
    }

    static void removeBody(MinecraftServer s, UUID owner) {
        BodyStore store = BodyStore.get(s);
        store.byOwner(owner).ifPresent(record -> {
            store.remove(owner);
            Entity entity = s.overworld().getEntityInAnyDimension(record.bodyEntity());
            if (entity != null) {
                entity.discard();
            }
        });
    }

    private static List<Grave> gravesOf(MinecraftServer s, UUID owner) {
        return GraveStore.get(s).all().stream().filter(grave -> grave.owner().equals(owner)).toList();
    }

    // Out of the store first, so the grave no longer protects its block
    private static void removeGraves(MinecraftServer s, UUID owner) {
        GraveStore store = GraveStore.get(s);
        for (Grave grave : gravesOf(s, owner)) {
            store.remove(grave.id());
            ServerLevel level = s.getLevel(grave.dimension());
            if (level != null) {
                level.setBlock(grave.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }
}
