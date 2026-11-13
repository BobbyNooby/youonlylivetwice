package bobbynooby.dev.gametest;

import bobbynooby.dev.bodies.BodyRecord;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.core.Yolt;
import bobbynooby.dev.graves.Grave;
import bobbynooby.dev.graves.GraveItems;
import bobbynooby.dev.graves.GraveMenu;
import bobbynooby.dev.graves.GraveMenus;
import bobbynooby.dev.graves.GraveStore;
import bobbynooby.dev.graves.Graves;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.equine.Horse;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static bobbynooby.dev.gametest.BodyTestKit.cleanUp;
import static bobbynooby.dev.gametest.BodyTestKit.disconnectBody;
import static bobbynooby.dev.gametest.BodyTestKit.gravesOf;
import static bobbynooby.dev.gametest.BodyTestKit.once;
import static bobbynooby.dev.gametest.BodyTestKit.store;
import static bobbynooby.dev.gametest.BodyTestKit.useDefaultBodies;

// Logging out in the middle of something. Every logout runs a client's disconnect steps, which wake a sleeper and let
// riders off before the player list lets the player go. Each test turns the default body switches on and puts the
// server's own back in the same tick; the ones that wait only need the body, which ticks on its own.
public class LogoutStateGameTests {
    private static final BlockPos ON_FLOOR = new BlockPos(1, 1, 1);
    private static final BlockPos ABOVE_POOL = new BlockPos(3, 5, 3);

    // Vanilla saves the horse with its rider and takes it out of the world. At login the body's state comes first,
    // then vanilla puts the owner back on the horse where it was saved, not where the body ended up.
    @GameTest
    public void logoutRidingHorse(GameTestHelper helper) {
        Horse horse = EntityTypes.HORSE.create(helper.getLevel(), EntitySpawnReason.COMMAND);
        helper.assertTrue(horse != null, "no horse");
        horse.setTamed(true);
        rideOutAndBack(helper, "HorseRider", horse);
    }

    // The README's riding case
    @GameTest
    public void logoutInMinecart(GameTestHelper helper) {
        Minecart minecart = EntityTypes.MINECART.create(helper.getLevel(), EntitySpawnReason.COMMAND);
        helper.assertTrue(minecart != null, "no minecart");
        rideOutAndBack(helper, "CartRider", minecart);
    }

    // A rider's feet hang below the seat, under the water line, so the body lies on top of the boat instead. The boat
    // goes with its only rider, and the body is held there, so it neither sinks nor runs out of air.
    @GameTest(maxTicks = 800)
    public void boatLogoutHoldsTheBodyOnTheWater(GameTestHelper helper) {
        boatLogout(helper, false);
    }

    // One of two riders leaves: the boat stays with the other, and the body is held on top of it the same way
    @GameTest(maxTicks = 800)
    public void sharedBoatLogoutHoldsTheBodyToo(GameTestHelper helper) {
        boatLogout(helper, true);
    }

    // On a mount that stands on dry ground, the body drops from its top and moves like any other
    @GameTest(maxTicks = 120)
    public void logoutOnAMountOnDryGroundLeavesTheBodyMovable(GameTestHelper helper) {
        floor(helper);
        Horse horse = EntityTypes.HORSE.create(helper.getLevel(), EntitySpawnReason.COMMAND);
        helper.assertTrue(horse != null, "no horse");
        horse.setTamed(true);
        Vec3 spot = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(2, 2, 2)));
        horse.snapTo(spot.x, spot.y, spot.z, 0.0F, 0.0F);
        helper.assertTrue(helper.getLevel().addFreshEntity(horse), "the horse was not added");
        double floorY = helper.absolutePos(new BlockPos(2, 1, 2)).getY();
        Logout logout = new Logout(helper, horse);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(horse.onGround(), "the horse has not landed"))
                .thenExecute(() -> {
                    logout.leave(TestPlayers.join(helper, "HorseGround", GameType.SURVIVAL, new BlockPos(2, 1, 2)), null);
                    helper.assertFalse(logout.record().held(), "the body on dry ground is held");
                    helper.assertTrue(logout.body.getY() > floorY + 0.5, "the body did not start on the horse's top: "
                            + logout.body.getY());
                })
                .thenWaitUntil(() -> helper.assertTrue(logout.body.onGround() && Math.abs(logout.body.getY() - floorY) < 1.0E-6,
                        "the body is not on the floor: " + logout.body.position()))
                .thenExecute(() -> {
                    try {
                        helper.assertTrue(logout.body.isAlive(), "the body died");
                    } finally {
                        logout.cleanUp.run();
                    }
                })
                .thenSucceed();
    }

    // The cart's top is above the rail. Wherever the body ends up, no part of it may be inside a block.
    @GameTest(maxTicks = 160)
    public void minecartLogoutLeavesTheBodyOnTheFloor(GameTestHelper helper) {
        floor(helper);
        BlockPos railAt = new BlockPos(2, 1, 2);
        helper.setBlock(railAt, Blocks.RAIL);
        Minecart minecart = helper.spawn(EntityTypes.MINECART, railAt);
        double floorY = helper.absolutePos(railAt).getY();
        Logout logout = new Logout(helper, minecart);
        double[] expected = new double[1];
        helper.startSequence()
                .thenWaitUntil(settled(helper, minecart))
                .thenExecute(() -> {
                    double cartTop = minecart.getBoundingBox().maxY;
                    logout.leave(TestPlayers.join(helper, "CartFloor", GameType.SURVIVAL, railAt), null);
                    boolean held = logout.record().held();
                    Yolt.LOGGER.info("minecartLogoutLeavesTheBodyOnTheFloor: the cart was {} ground, so the body is {}",
                            held ? "off" : "on", held ? "held above the rail" : "movable");
                    expected[0] = held ? cartTop : floorY;
                })
                .thenExecuteAfter(100, () -> {
                    try {
                        Mannequin body = logout.body;
                        helper.assertTrue(body.isAlive(), "the body died");
                        helper.assertTrue(helper.getLevel().noBlockCollision(body, body.getBoundingBox().deflate(1.0E-7)),
                                "the body is inside a block at " + body.position());
                        helper.assertTrue(Math.abs(body.getY() - expected[0]) < 1.0E-6,
                                "the body is at y " + body.getY() + ", not " + expected[0]);
                    } finally {
                        logout.cleanUp.run();
                    }
                })
                .thenSucceed();
    }

    // The boat settles on the pool first: once a player is in it, the server no longer moves it. Then 600 ticks after
    // the logout the body is still where it was made, on the boat's top, alive and with full air.
    private static void boatLogout(GameTestHelper helper, boolean shared) {
        Boat boat = poolWithBoat(helper);
        Logout logout = new Logout(helper, boat);
        double[] boatTop = new double[1];
        helper.startSequence()
                .thenWaitUntil(settled(helper, boat))
                .thenExecute(() -> {
                    ServerPlayer stays = null;
                    if (shared) {
                        stays = TestPlayers.join(helper, "BoatStays", GameType.SURVIVAL, ABOVE_POOL);
                        helper.assertTrue(stays.startRiding(boat, true, false), "the first player did not get in");
                    }
                    boatTop[0] = boat.getBoundingBox().maxY;
                    logout.leave(TestPlayers.join(helper, "BoatLeaves", GameType.SURVIVAL, ABOVE_POOL), stays);
                    helper.assertTrue(logout.record().held(), "the body on the water is not held");
                    helper.assertTrue(Math.abs(logout.body.getY() - boatTop[0]) < 1.0E-6,
                            "the body is at y " + logout.body.getY() + ", not the boat's top " + boatTop[0]);
                    if (shared) {
                        helper.assertFalse(boat.isRemoved(), "the boat left with a rider still on it");
                        helper.assertTrue(stays.getVehicle() == boat, "the other player is not in the boat");
                    } else {
                        helper.assertTrue(boat.isRemoved(), "the boat stayed without its only rider");
                    }
                    logout.at = logout.body.position();
                })
                .thenExecuteAfter(600, () -> {
                    try {
                        Mannequin body = logout.body;
                        helper.assertTrue(body.isAlive(), "the body died: " + body.getLastDamageSource());
                        helper.assertValueEqual(body.getAirSupply(), body.getMaxAirSupply(), "the body's air");
                        helper.assertValueEqual(body.position(), logout.at, "the body's position");
                        if (shared) {
                            helper.assertFalse(boat.isRemoved(), "the boat is gone");
                        }
                    } finally {
                        logout.cleanUp.run();
                    }
                })
                .thenSucceed();
    }

    // One rider's logout off a vehicle, with the clean-up that runs at the end or before the timeout
    private static final class Logout {
        private final GameTestHelper helper;
        private final Entity vehicle;
        private UUID owner;
        private ServerPlayer stays;
        Mannequin body;
        Vec3 at;
        final Runnable cleanUp;

        Logout(GameTestHelper helper, Entity vehicle) {
            this.helper = helper;
            this.vehicle = vehicle;
            cleanUp = once(() -> {
                try {
                    if (owner != null) {
                        cleanUp(helper, owner);
                    }
                    if (stays != null) {
                        TestPlayers.leave(stays);
                    }
                } finally {
                    vehicle.discard();
                }
            });
            helper.runBeforeTestEnd(cleanUp);
        }

        // With the default body switches on just for the logout
        void leave(ServerPlayer player, ServerPlayer stays) {
            owner = player.getUUID();
            this.stays = stays;
            YoltConfig.BodiesSection before = useDefaultBodies();
            try {
                helper.assertTrue(player.startRiding(vehicle, true, false), "the player did not get on");
                body = disconnectBody(helper, player);
            } finally {
                Settings.get().bodies = before;
            }
        }

        BodyRecord record() {
            return store(helper).byOwner(owner).orElseThrow();
        }
    }

    // A pool of still water three blocks deep, walled in glass, with a boat on it
    private static Boat poolWithBoat(GameTestHelper helper) {
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                boolean edge = x == 0 || x == 6 || z == 0 || z == 6;
                for (int y = 1; y <= 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z), edge ? Blocks.GLASS : y <= 3 ? Blocks.WATER : Blocks.AIR);
                }
            }
        }
        return helper.spawn(EntityTypes.OAK_BOAT, new BlockPos(3, 3, 3));
    }

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x <= 4; x++) {
            for (int z = 0; z <= 4; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
    }

    // Passes once the vehicle's height has stayed the same for five ticks in a row
    private static Runnable settled(GameTestHelper helper, Entity vehicle) {
        double[] last = {Double.NaN};
        int[] still = {0};
        return () -> {
            double y = vehicle.getY();
            still[0] = Math.abs(y - last[0]) < 1.0E-6 ? still[0] + 1 : 0;
            last[0] = y;
            helper.assertTrue(still[0] >= 5, "the vehicle has not settled at " + y);
        };
    }

    // Gliding is the client's doing, so the body just drops, and lands with the fall it logged out with on top of its
    // own. The test player's living tick doesn't matter here: the body ticks itself down.
    @GameTest(maxTicks = 60)
    public void logoutWhileGliding(GameTestHelper helper) {
        helper.setBlock(new BlockPos(1, 0, 1), Blocks.STONE);
        BlockPos high = new BlockPos(1, 7, 1);
        ServerPlayer player = TestPlayers.join(helper, "Glider", GameType.SURVIVAL, high);
        UUID owner = player.getUUID();
        double floorY = helper.absolutePos(ON_FLOOR).getY();
        YoltConfig.BodiesSection before = useDefaultBodies();
        boolean waiting = false;
        try {
            player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
            helper.assertTrue(player.tryToStartFallFlying(), "the player could not start gliding");
            // A dive: what the glide had fallen so far
            player.fallDistance = 8.0;
            Mannequin body = disconnectBody(helper, player);
            Settings.get().bodies = before;
            helper.assertFalse(body.isFallFlying(), "the body glides");
            helper.assertValueEqual(body.fallDistance, 8.0, "the body's fall distance at logout");
            double drop = body.getY() - floorY;
            helper.runAfterDelay(40, () -> {
                try {
                    helper.assertTrue(body.onGround() && Math.abs(body.getY() - floorY) < 1.0E-6,
                            "the body did not land on the floor: " + body.position());
                    DamageSource hurtBy = body.getLastDamageSource();
                    helper.assertTrue(hurtBy != null && hurtBy.is(DamageTypes.FALL), "the body was last hurt by " + hurtBy + ", not a fall");
                    // The drop alone costs at most ceil(6 - 3) = 3, so anything from 9 up carried the dive over
                    float damage = 20.0F - body.getHealth();
                    helper.assertValueInBetween(9.0F, damage, (float) Math.ceil(8.0 + drop - 3.0) + 1.0F, "fall damage");

                    ServerPlayer back = TestPlayers.rejoin(helper, player.getGameProfile(), GameType.SURVIVAL, high);
                    helper.assertValueEqual(back.position(), body.position(), "the owner's spot after login");
                    helper.assertValueEqual(back.getHealth(), body.getHealth(), "health after login");
                    helper.assertValueEqual(back.fallDistance, 0.0, "fall distance after login");
                    helper.assertTrue(ItemStack.matches(back.getItemBySlot(EquipmentSlot.CHEST), new ItemStack(Items.ELYTRA)),
                            "chest slot after login: " + back.getItemBySlot(EquipmentSlot.CHEST));
                } finally {
                    cleanUp(helper, owner, before);
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            Settings.get().bodies = before;
            if (!waiting) {
                cleanUp(helper, owner, before);
            }
        }
    }

    // The body burns on in the lava and dies of it, a normal death: the grave goes on the rim, never in the lava, and
    // the owner is locked out. A 3x3 pool in a stone rim, so the lava has nowhere to flow; no ceiling, which the grave
    // search would take as a floor.
    @GameTest(maxTicks = 120, skyAccess = true)
    public void logoutStandingInLava(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> pool = new ArrayList<>();
        List<BlockPos> built = new ArrayList<>();
        for (int x = 1; x <= 5; x++) {
            for (int z = 1; z <= 5; z++) {
                BlockPos pos = new BlockPos(x, 0, z);
                boolean rim = x == 1 || x == 5 || z == 1 || z == 5;
                helper.setBlock(pos, rim ? Blocks.STONE : Blocks.LAVA);
                built.add(pos);
                if (!rim) {
                    pool.add(helper.absolutePos(pos));
                }
            }
        }
        ServerPlayer player = TestPlayers.join(helper, "Bather", GameType.SURVIVAL, new BlockPos(3, 0, 3));
        UUID owner = player.getUUID();
        String name = player.getGameProfile().name();
        YoltConfig.BodiesSection before = useDefaultBodies();
        Runnable cleanUp = once(() -> {
            cleanUp(helper, owner, before);
            built.forEach(pos -> helper.setBlock(pos, Blocks.AIR));
        });
        boolean waiting = false;
        try {
            player.getInventory().setItem(0, new ItemStack(Items.IRON_SWORD));
            player.getInventory().setItem(9, new ItemStack(Items.DIAMOND, 5));
            Mannequin body = disconnectBody(helper, player);
            Settings.get().bodies = before;
            helper.assertTrue(level.getFluidState(body.blockPosition()).is(FluidTags.LAVA), "the body is not in the lava");
            AABB around = new AABB(body.blockPosition()).inflate(4.0);
            helper.runBeforeTestEnd(cleanUp);
            helper.succeedWhen(() -> {
                helper.assertTrue(body.isDeadOrDying(), "the body is still alive in the lava");
                DamageSource hurtBy = body.getLastDamageSource();
                helper.assertTrue(hurtBy != null && hurtBy.is(DamageTypes.LAVA), "the body was last hurt by " + hurtBy + ", not lava");
                List<Grave> graves = gravesOf(helper, owner);
                helper.assertValueEqual(graves.size(), 1, "graves for the owner");
                Grave grave = graves.get(0);
                BlockPos below = grave.pos().below();
                helper.assertFalse(pool.contains(grave.pos()), "grave placed in the lava pool at " + grave.pos());
                helper.assertFalse(level.getFluidState(grave.pos()).is(FluidTags.LAVA) || level.getFluidState(below).is(FluidTags.LAVA),
                        "grave in or over lava at " + grave.pos());
                helper.assertFalse(level.getBlockState(below).getCollisionShape(level, below).isEmpty(),
                        "nothing solid under the grave at " + grave.pos());
                List<ItemStack> items = grave.items();
                helper.assertValueEqual(items.size(), 3, "grave stacks");
                helper.assertTrue(GraveItems.isGraveHead(items.get(0)), "the first grave item is not the grave head");
                helper.assertValueEqual(GraveAsserts.count(items, Items.IRON_SWORD), 1, "swords in the grave");
                helper.assertValueEqual(GraveAsserts.count(items, Items.DIAMOND), 5, "diamonds in the grave");
                helper.assertTrue(level.getEntitiesOfClass(ItemEntity.class, around, Entity::isAlive).isEmpty(), "items dropped");
                String refusal = LockoutGameTests.refusalText(owner);
                helper.assertTrue(refusal.startsWith("You died: Killed while away (" + name + " tried to swim in lava)\n"),
                        "the owner's lockout: " + refusal);
                helper.assertTrue(store(helper).byOwner(owner).isEmpty(), "body record kept");
                cleanUp.run();
            });
            waiting = true;
        } finally {
            Settings.get().bodies = before;
            if (!waiting) {
                cleanUp.run();
            }
        }
    }

    // A body never uses a portal: one left standing in a lit nether portal stays in the overworld, and so does its
    // owner at login. A plain mobile entity would go through on its first tick in there.
    @GameTest(maxTicks = 40)
    public void logoutInNetherPortal(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> built = new ArrayList<>();
        // An obsidian frame two wide and three high along x, lit; the frame's bottom is the floor
        for (int x = 0; x <= 3; x++) {
            for (int y = 0; y <= 4; y++) {
                if (x == 0 || x == 3 || y == 0 || y == 4) {
                    BlockPos pos = new BlockPos(x, y, 1);
                    helper.setBlock(pos, Blocks.OBSIDIAN);
                    built.add(pos);
                }
            }
        }
        BlockState portal = Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS, Direction.Axis.X);
        for (int x = 1; x <= 2; x++) {
            for (int y = 1; y <= 3; y++) {
                BlockPos pos = new BlockPos(x, y, 1);
                helper.setBlock(pos, portal);
                built.add(pos);
            }
        }
        ServerPlayer player = TestPlayers.join(helper, "Portal", GameType.SURVIVAL, ON_FLOOR);
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = useDefaultBodies();
        Runnable cleanUp = once(() -> {
            cleanUp(helper, owner, before);
            built.forEach(pos -> helper.setBlock(pos, Blocks.AIR));
        });
        boolean waiting = false;
        try {
            Mannequin body = disconnectBody(helper, player);
            Settings.get().bodies = before;
            BlockPos inPortal = body.blockPosition();
            helper.assertTrue(level.getBlockState(inPortal).is(Blocks.NETHER_PORTAL), "the body is not in the portal");
            AABB portalShape = level.getBlockState(inPortal).getShape(level, inPortal).bounds().move(Vec3.atLowerCornerOf(inPortal));
            helper.assertTrue(body.getBoundingBox().intersects(portalShape), "the body does not touch the portal");
            helper.runAfterDelay(20, () -> {
                try {
                    helper.assertFalse(body.isRemoved(), "the body left the overworld: " + body.getRemovalReason());
                    helper.assertTrue(body.level() == level && body.blockPosition().equals(inPortal),
                            "the body moved to " + body.blockPosition() + " in " + body.level().dimension().identifier());
                    helper.assertTrue(body.portalProcess == null, "the portal started taking the body");
                    helper.assertTrue(level.getBlockState(inPortal).is(Blocks.NETHER_PORTAL), "the portal went out");
                    helper.assertValueEqual(store(helper).byOwner(owner).map(BodyRecord::bodyEntity), Optional.of(body.getUUID()),
                            "the record's body");

                    ServerPlayer back = TestPlayers.rejoin(helper, player.getGameProfile(), GameType.SURVIVAL, ON_FLOOR);
                    helper.assertTrue(back.level() == level, "the owner is in " + back.level().dimension().identifier());
                    helper.assertValueEqual(back.position(), body.position(), "the owner's spot after login");
                    helper.assertTrue(body.isRemoved(), "the body is still in the world");
                } finally {
                    cleanUp.run();
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            Settings.get().bodies = before;
            if (!waiting) {
                cleanUp.run();
            }
        }
    }

    // A looter logs out with someone's grave open: the menu shuts, and each stack is in one place after. The ones
    // taken are in the looter's save and the rest in the grave. The stack held on the cursor goes back into the grave
    // too, where vanilla would drop it to despawn. The body only holds copies, so the login adds nothing.
    @GameTest
    public void logoutWithGraveMenuOpen(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ResolvableProfile profile = ResolvableProfile.createUnresolved("Ghost");
        List<ItemStack> stacks = List.of(GraveItems.graveHead(profile), new ItemStack(Items.DIAMOND, 4),
                new ItemStack(Items.EMERALD, 6), new ItemStack(Items.GOLD_INGOT, 2));
        List<Grave> made = Graves.create(level, helper.absolutePos(new BlockPos(2, 1, 2)), profile, UUID.randomUUID(), "Ghost", stacks);
        helper.assertValueEqual(made.size(), 1, "graves made");
        Grave grave = made.get(0);
        ServerPlayer looter = TestPlayers.join(helper, "Looter", GameType.SURVIVAL, ON_FLOOR);
        UUID owner = looter.getUUID();
        AABB around = new AABB(looter.blockPosition()).inflate(4.0);
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            // In the hand, so the body holds a copy of it
            looter.getInventory().setItem(0, new ItemStack(Items.IRON_SWORD));
            GraveMenus.open(looter, grave);
            AbstractContainerMenu menu = looter.containerMenu;
            helper.assertTrue(menu instanceof GraveMenu, "the grave menu did not open");
            menu.clicked(1, 0, ContainerInput.QUICK_MOVE, looter);
            menu.clicked(2, 0, ContainerInput.PICKUP, looter);
            helper.assertTrue(menu.getCarried().is(Items.EMERALD), "the emeralds are not on the cursor");

            Mannequin body = disconnectBody(helper, looter);
            helper.assertTrue(looter.containerMenu == looter.inventoryMenu, "the grave menu is still open");
            helper.assertTrue(GraveMenus.containerFor(grave) == null, "the grave is still shared after the logout");
            helper.assertTrue(ItemStack.matches(body.getMainHandItem(), new ItemStack(Items.IRON_SWORD)), "the body's hand");

            ServerPlayer back = TestPlayers.rejoin(helper, looter.getGameProfile(), GameType.SURVIVAL, ON_FLOOR);
            helper.assertTrue(back.containerMenu == back.inventoryMenu, "a menu is open after login");
            helper.assertTrue(body.isRemoved(), "the body is still in the world");
            List<ItemStack> inGrave = GraveStore.get(server).byId(grave.id()).orElseThrow().items();
            List<ItemStack> onGround = level.getEntitiesOfClass(ItemEntity.class, around, Entity::isAlive).stream()
                    .map(ItemEntity::getItem).toList();
            assertOnce(helper, back, inGrave, onGround, Items.DIAMOND, 4, 0, 0);
            assertOnce(helper, back, inGrave, onGround, Items.EMERALD, 0, 6, 0);
            assertOnce(helper, back, inGrave, onGround, Items.GOLD_INGOT, 0, 2, 0);
            assertOnce(helper, back, inGrave, onGround, Items.IRON_SWORD, 1, 0, 0);
            helper.assertValueEqual(GraveAsserts.count(inGrave, Items.PLAYER_HEAD), 1, "grave heads in the grave");
            helper.assertTrue(onGround.isEmpty(), "stacks on the ground: " + onGround);
        } finally {
            cleanUp(helper, owner, before);
            level.getEntitiesOfClass(ItemEntity.class, around).forEach(Entity::discard);
            GraveStore.get(server).remove(grave.id());
            level.setBlock(grave.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        helper.succeed();
    }

    // Two bodies on one block, each its owner's: each login takes over its own body only, with its own state
    @GameTest
    public void twoLogoutsOnOneBlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer first = TestPlayers.join(helper, "SharedA", GameType.SURVIVAL, ON_FLOOR);
        ServerPlayer second = TestPlayers.join(helper, "SharedB", GameType.SURVIVAL, ON_FLOOR);
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            first.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            second.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
            Mannequin firstBody = disconnectBody(helper, first);
            Mannequin secondBody = disconnectBody(helper, second);
            helper.assertTrue(firstBody != secondBody, "both records point at one body");
            helper.assertValueEqual(firstBody.position(), secondBody.position(), "the bodies' spots");
            // Hit differently, so each owner's health can only come from their own body
            firstBody.hurtServer(level, level.damageSources().generic(), 3.0F);
            secondBody.hurtServer(level, level.damageSources().generic(), 7.0F);

            ServerPlayer firstBack = TestPlayers.rejoin(helper, first.getGameProfile(), GameType.SURVIVAL, ON_FLOOR);
            helper.assertTrue(firstBody.isRemoved(), "the first body is still in the world");
            helper.assertFalse(secondBody.isRemoved(), "the first login took the second body");
            helper.assertValueEqual(firstBack.getHealth(), 17.0F, "the first owner's health");
            helper.assertTrue(ItemStack.matches(firstBack.getMainHandItem(), new ItemStack(Items.IRON_SWORD)), "the first owner's hand");

            ServerPlayer secondBack = TestPlayers.rejoin(helper, second.getGameProfile(), GameType.SURVIVAL, ON_FLOOR);
            helper.assertTrue(secondBody.isRemoved(), "the second body is still in the world");
            helper.assertValueEqual(secondBack.getHealth(), 13.0F, "the second owner's health");
            helper.assertTrue(ItemStack.matches(secondBack.getMainHandItem(), new ItemStack(Items.IRON_AXE)), "the second owner's hand");
            helper.assertValueEqual(firstBack.getInventory().countItem(Items.IRON_AXE) + secondBack.getInventory().countItem(Items.IRON_SWORD),
                    0, "items with the wrong owner");
            helper.assertTrue(store(helper).byOwner(first.getUUID()).isEmpty() && store(helper).byOwner(second.getUUID()).isEmpty(),
                    "body records kept");
        } finally {
            cleanUp(helper, first.getUUID(), before);
            cleanUp(helper, second.getUUID(), before);
        }
        helper.succeed();
    }

    // Rides the vehicle out from a stone floor and logs back in. The body is hit and moved while its owner is away,
    // so what comes from the body and what comes from the vehicle can be told apart.
    private static void rideOutAndBack(GameTestHelper helper, String name, Entity vehicle) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(1, 0, 1), Blocks.STONE);
        Vec3 spot = Vec3.atBottomCenterOf(helper.absolutePos(ON_FLOOR));
        vehicle.snapTo(spot.x, spot.y, spot.z, 0.0F, 0.0F);
        helper.assertTrue(level.addFreshEntity(vehicle), "the vehicle was not added");
        UUID vehicleId = vehicle.getUUID();
        EntityType<?> type = vehicle.getType();
        AABB around = new AABB(spot, spot).inflate(8.0);
        ServerPlayer player = TestPlayers.join(helper, name, GameType.SURVIVAL, ON_FLOOR);
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            helper.assertTrue(player.startRiding(vehicle, true, false), "the player did not get on");
            Mannequin body = disconnectBody(helper, player);
            helper.assertTrue(vehicle.isRemoved(), "the vehicle stayed in the world without its rider");
            helper.assertFalse(body.isPassenger(), "the body rides " + body.getVehicle());
            body.hurtServer(level, level.damageSources().generic(), 5.0F);
            body.teleportTo(body.getX() + 3.0, spot.y, body.getZ());

            ServerPlayer back = TestPlayers.rejoin(helper, player.getGameProfile(), GameType.SURVIVAL, ON_FLOOR);
            Entity riding = back.getVehicle();
            helper.assertTrue(riding != null && riding.getUUID().equals(vehicleId), "the owner is not back on the vehicle: " + riding);
            helper.assertTrue(riding.position().distanceTo(spot) < 1.0E-6, "the vehicle came back at " + riding.position());
            helper.assertValueEqual(level.getEntities((Entity) null, around, entity -> entity.getType() == type && entity.isAlive()).size(),
                    1, "vehicles");
            helper.assertValueEqual(back.getHealth(), 15.0F, "health after login");
            helper.assertTrue(ItemStack.matches(back.getItemBySlot(EquipmentSlot.CHEST), new ItemStack(Items.IRON_CHESTPLATE)),
                    "chest slot after login: " + back.getItemBySlot(EquipmentSlot.CHEST));
            helper.assertTrue(body.isRemoved(), "the body is still in the world");
            helper.assertTrue(store(helper).byOwner(owner).isEmpty(), "body record kept");
        } finally {
            // The owner leaves on the vehicle, which goes with them
            cleanUp(helper, owner, before);
            level.getEntities((Entity) null, around, entity -> entity.getType() == type).forEach(Entity::discard);
        }
        helper.succeed();
    }

    // How many of the item the looter has, the grave holds and lie on the ground, each against what it should be
    private static void assertOnce(GameTestHelper helper, ServerPlayer looter, List<ItemStack> inGrave, List<ItemStack> onGround,
                                   Item item, int held, int kept, int dropped) {
        String what = item.toString();
        helper.assertValueEqual(looter.getInventory().countItem(item), held, what + " the looter has");
        helper.assertValueEqual(GraveAsserts.count(inGrave, item), kept, what + " in the grave");
        helper.assertValueEqual(GraveAsserts.count(onGround, item), dropped, what + " on the ground");
    }
}
