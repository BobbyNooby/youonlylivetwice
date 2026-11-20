package bobbynooby.dev.gametest;

import bobbynooby.dev.bodies.Bodies;
import bobbynooby.dev.bodies.BodyChunks;
import bobbynooby.dev.bodies.BodyLogin;
import bobbynooby.dev.bodies.BodyLogout;
import bobbynooby.dev.bodies.BodyRecord;
import bobbynooby.dev.bodies.BodyRestart;
import bobbynooby.dev.bodies.BodyRules;
import bobbynooby.dev.bodies.BodySnapshot;
import bobbynooby.dev.bodies.GearSync;
import bobbynooby.dev.bodies.PlayerBody;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.core.Profiles;
import bobbynooby.dev.lockout.DeathLockout;
import bobbynooby.dev.offline.OfflineDeath;
import bobbynooby.dev.offline.OfflinePlayerData;
import bobbynooby.dev.skins.Skins;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static bobbynooby.dev.gametest.BodyTestKit.bodiesOf;
import static bobbynooby.dev.gametest.BodyTestKit.cleanUp;
import static bobbynooby.dev.gametest.BodyTestKit.gravesOf;
import static bobbynooby.dev.gametest.BodyTestKit.leaveBody;
import static bobbynooby.dev.gametest.BodyTestKit.leavePlayerBody;
import static bobbynooby.dev.gametest.BodyTestKit.once;
import static bobbynooby.dev.gametest.BodyTestKit.removeBodies;
import static bobbynooby.dev.gametest.BodyTestKit.restore;
import static bobbynooby.dev.gametest.BodyTestKit.store;
import static bobbynooby.dev.gametest.BodyTestKit.useDefaultBodies;
import static bobbynooby.dev.gametest.BodyTestKit.usePlayerBodies;

// The test server keeps bodies off. Each test runs with the default body switches (bodies on) and puts the
// server's own back in the same tick, before any other test runs. The tests that wait only wait for the
// check a body load queues, which doesn't read them.
public class BodyLifecycleGameTests {
    // A batch of its own: the pose test holds the server's own pose across ticks
    private static final String BODY_POSE = "you-only-live-twice-gametest:body_pose";
    private static final SharedHold POSE_HOLD = new SharedHold();

    @GameTest
    public void logoutSpawnsProneBodyWithGear(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Body", GameType.SURVIVAL);
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            player.setHealth(14.0F);
            // A real logout: leaving fires the hook at the start of PlayerList.remove
            TestPlayers.leave(player);

            Optional<BodyRecord> record = store(helper).byOwner(owner);
            helper.assertTrue(record.isPresent(), "no body record");
            helper.assertValueEqual(record.get().type(), YoltConfig.BodyType.MANNEQUIN, "body type");
            helper.assertFalse(record.get().pendingRemoval(), "new body marked for removal");
            Entity entity = helper.getLevel().getEntity(record.get().bodyEntity());
            helper.assertTrue(entity instanceof Mannequin, "no mannequin for the record");
            Mannequin body = (Mannequin) entity;

            helper.assertValueEqual(body.getPose(), Pose.SWIMMING, "pose");
            helper.assertValueEqual(body.position(), player.position(), "position");
            helper.assertTrue(body.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE), "chestplate not copied");
            helper.assertTrue(body.getItemBySlot(EquipmentSlot.MAINHAND).is(Items.IRON_SWORD), "sword not copied");
            helper.assertValueEqual(body.getHealth(), 14.0F, "health");
            helper.assertTrue(BodyRules.isBody(body), "not recognised as a body");
            helper.assertValueEqual(body.getProfile().partialProfile().id(), owner, "profile id");
            helper.assertTrue(body.getCustomName() == null, "body has a custom name");
            // The fields without a public getter, read the way they are saved
            CompoundTag saved = save(helper, body);
            helper.assertTrue(saved.getBooleanOr("hide_description", false), "NPC label not hidden");
            helper.assertFalse(saved.getBooleanOr("immovable", true), "body immovable with movable on");
        } finally {
            cleanUp(helper, player.getUUID(), before);
        }
        helper.succeed();
    }

    @GameTest
    public void noBodyWhenDisabled(GameTestHelper helper) {
        ServerPlayer player = join(helper, "NoBody", GameType.SURVIVAL);
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            Settings.get().bodies.enabled = false;
            helper.assertTrue(BodyLogout.onLogout(player).isEmpty(), "body made with bodies off");
            assertNoBody(helper, player);
        } finally {
            cleanUp(helper, player.getUUID(), before);
        }
        helper.succeed();
    }

    @GameTest
    public void noBodyForDeadPlayer(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Dead", GameType.SURVIVAL);
        YoltConfig config = Settings.get();
        YoltConfig.BodiesSection before = useDefaultBodies();
        boolean lockoutBefore = config.deathLockout.enabled;
        boolean gravesBefore = config.graves.enabled;
        try {
            // Still alive, so only the lockout check can stop this one
            DeathLockout.recordDeath(player.getUUID(), player.nameAndId().name());
            helper.assertTrue(DeathLockout.refusal(player.getUUID(), "Test") != null, "death not recorded");
            helper.assertTrue(BodyLogout.onLogout(player).isEmpty(), "locked-out player got a body");

            // With the lockout off, being dead is all that stops it; graves off leaves nothing behind
            config.deathLockout.enabled = false;
            config.graves.enabled = false;
            TestPlayers.markLoaded(player);
            player.kill(player.level());
            helper.assertTrue(player.isDeadOrDying(), "player did not die");
            helper.assertTrue(BodyLogout.onLogout(player).isEmpty(), "dead player got a body");
            assertNoBody(helper, player);
        } finally {
            config.deathLockout.enabled = lockoutBefore;
            config.graves.enabled = gravesBefore;
            cleanUp(helper, player.getUUID(), before);
        }
        helper.succeed();
    }

    @GameTest
    public void noBodyForSpectator(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Watcher", GameType.SPECTATOR);
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            helper.assertTrue(BodyLogout.onLogout(player).isEmpty(), "spectator got a body");
            assertNoBody(helper, player);
        } finally {
            cleanUp(helper, player.getUUID(), before);
        }
        helper.succeed();
    }

    // The old body stands in for a logout from before, as if the login had failed to take it over. The player has
    // been online since, so it goes too, as it would for a new body.
    @GameTest
    public void creativeLogoutLeavesNoBody(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Creative", GameType.SURVIVAL);
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            Mannequin old = logOut(helper, player);
            player.setGameMode(GameType.CREATIVE);
            helper.assertTrue(BodyLogout.onLogout(player).isEmpty(), "creative player got a body");
            helper.assertTrue(old.isRemoved(), "the old body still stands");
            assertNoBody(helper, player);
        } finally {
            cleanUp(helper, player.getUUID(), before);
        }
        helper.succeed();
    }

    // The same stale body as above, for a spectator
    @GameTest
    public void spectatorLogoutClearsStaleBody(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Ghostly", GameType.SURVIVAL);
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            Mannequin old = logOut(helper, player);
            player.setGameMode(GameType.SPECTATOR);
            helper.assertTrue(BodyLogout.onLogout(player).isEmpty(), "spectator got a body");
            helper.assertTrue(old.isRemoved(), "the old body still stands");
            assertNoBody(helper, player);
        } finally {
            cleanUp(helper, player.getUUID(), before);
        }
        helper.succeed();
    }

    // The same stale body, at a real logout with bodies switched off since. It goes all the same: its head was empty,
    // so taking it over at the next login would clear the helmet put on since.
    @GameTest
    public void bodiesOffLogoutClearsStaleBody(GameTestHelper helper) {
        ServerPlayer player = join(helper, "SwitchedOff", GameType.SURVIVAL);
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            Mannequin old = logOut(helper, player);
            player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            Settings.get().bodies.enabled = false;
            TestPlayers.leave(player);
            helper.assertTrue(old.isRemoved(), "the old body still stands");
            helper.assertTrue(store(helper).byOwner(owner).isEmpty(), "the old body's record kept");

            ServerPlayer back = rejoin(helper, player);
            helper.assertTrue(back.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET), "the helmet is gone after login");
        } finally {
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    // A stale body as above, whose empty chest slot a dispenser filled: that chestplate is real, so clearing the body
    // must not take it along. It goes into the owner's inventory, never the hand, or with no room drops where the body
    // stood, for good.
    @GameTest
    public void staleBodyGearAddedSinceIsKept(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Restocked", GameType.SURVIVAL);
        ServerPlayer packed = join(helper, "Packed", GameType.SURVIVAL);
        YoltConfig.BodiesSection before = useDefaultBodies();
        AABB around = null;
        try {
            Mannequin old = logOut(helper, player);
            old.setItemSlot(EquipmentSlot.CHEST, dispensedChestplate());
            AABB aroundOld = new AABB(old.blockPosition()).inflate(2.0);
            Mannequin fresh = logOut(helper, player);
            helper.assertTrue(old.isRemoved(), "the old body still stands");
            helper.assertTrue(fresh.getItemBySlot(EquipmentSlot.CHEST).isEmpty(), "the new body wears the chestplate");
            helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class, aroundOld,
                    drop -> drop.getItem().is(Items.IRON_CHESTPLATE)).isEmpty(), "a chestplate dropped although there was room");
            Inventory inventory = player.getInventory();
            helper.assertValueEqual(inventory.countItem(Items.IRON_CHESTPLATE), 1, "chestplates in the owner's inventory");
            int found = -1;
            for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
                if (inventory.getItem(slot).is(Items.IRON_CHESTPLATE)) {
                    found = slot;
                }
            }
            helper.assertTrue(found != inventory.getSelectedSlot(), "the chestplate went into the owner's hand");
            helper.assertValueEqual(inventory.getItem(found).getDamageValue(), 7, "chestplate damage");

            for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
                packed.getInventory().setItem(slot, new ItemStack(Items.DIRT));
            }
            Mannequin oldPacked = logOut(helper, packed);
            oldPacked.setItemSlot(EquipmentSlot.CHEST, dispensedChestplate());
            around = new AABB(oldPacked.blockPosition()).inflate(2.0);
            Mannequin freshPacked = logOut(helper, packed);
            helper.assertTrue(oldPacked.isRemoved(), "the packed player's old body still stands");
            helper.assertTrue(freshPacked.getItemBySlot(EquipmentSlot.CHEST).isEmpty(),
                    "the packed player's new body wears the chestplate");
            helper.assertValueEqual(packed.getInventory().countItem(Items.IRON_CHESTPLATE), 0,
                    "chestplates in the packed player's inventory");
            List<ItemEntity> drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class, around,
                    drop -> drop.getItem().is(Items.IRON_CHESTPLATE));
            helper.assertValueEqual(drops.size(), 1, "chestplates dropped at the old body");
            helper.assertValueEqual(drops.get(0).getItem().getDamageValue(), 7, "dropped chestplate's damage");
            helper.assertValueEqual(drops.get(0).getAge(), (int) Short.MIN_VALUE, "dropped chestplate's age");
            helper.assertValueEqual(drops.get(0).getDeltaMovement(), Vec3.ZERO, "dropped chestplate's movement");
        } finally {
            if (around != null) {
                helper.getLevel().getEntitiesOfClass(ItemEntity.class, around, drop -> drop.getItem().is(Items.IRON_CHESTPLATE))
                        .forEach(Entity::discard);
            }
            cleanUp(helper, player.getUUID(), before);
            cleanUp(helper, packed.getUUID(), before);
        }
        helper.succeed();
    }

    // What a dispenser puts on a body: a real chestplate, with wear its owner never had
    private static ItemStack dispensedChestplate() {
        ItemStack chestplate = new ItemStack(Items.IRON_CHESTPLATE);
        chestplate.setDamageValue(7);
        return chestplate;
    }

    @GameTest
    public void onlyOneBodyPerPlayer(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Twice", GameType.SURVIVAL);
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            Mannequin first = logOut(helper, player);
            Mannequin second = logOut(helper, player);

            helper.assertTrue(first.isRemoved(), "first body still in the world");
            List<? extends Mannequin> bodies = bodiesOf(helper, player.getUUID());
            helper.assertValueEqual(bodies.size(), 1, "bodies for the player");
            helper.assertTrue(bodies.get(0) == second, "the body left is not the newest");
            helper.assertValueEqual(store(helper).byOwner(player.getUUID()).map(BodyRecord::bodyEntity),
                    Optional.of(second.getUUID()), "record's body");
        } finally {
            cleanUp(helper, player.getUUID(), before);
        }
        helper.succeed();
    }

    // The stop makes the body itself, since its kick most likely never reaches LEAVE. When LEAVE does come after, the
    // player still has one body and one record, and gets their gear back once.
    @GameTest
    public void stopThenLeaveLeavesOneBody(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Stopped", GameType.SURVIVAL);
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            Bodies.leaveBody(player);
            helper.assertTrue(store(helper).byOwner(owner).isPresent(), "no body record after the stop");
            helper.assertValueEqual(bodiesOf(helper, owner).size(), 1, "bodies after the stop");

            TestPlayers.leave(player);
            List<? extends Mannequin> bodies = bodiesOf(helper, owner);
            helper.assertValueEqual(bodies.size(), 1, "bodies after the stop and LEAVE");
            Mannequin body = bodies.get(0);
            helper.assertValueEqual(store(helper).byOwner(owner).map(BodyRecord::bodyEntity), Optional.of(body.getUUID()),
                    "record's body");
            helper.assertTrue(ItemStack.matches(body.getItemBySlot(EquipmentSlot.CHEST), new ItemStack(Items.IRON_CHESTPLATE)),
                    "chest slot of the body: " + body.getItemBySlot(EquipmentSlot.CHEST));
            helper.assertTrue(ItemStack.matches(body.getItemBySlot(EquipmentSlot.MAINHAND), new ItemStack(Items.IRON_SWORD)),
                    "main hand of the body: " + body.getItemBySlot(EquipmentSlot.MAINHAND));
            AABB around = new AABB(body.position(), body.position()).inflate(2.0);
            helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class, around).isEmpty(), "items dropped by the bodies");

            ServerPlayer back = TestPlayers.rejoin(helper, player.getGameProfile(), GameType.SURVIVAL, new BlockPos(1, 1, 1));
            helper.assertValueEqual(back.getInventory().countItem(Items.IRON_CHESTPLATE), 1, "chestplates after login");
            helper.assertValueEqual(back.getInventory().countItem(Items.IRON_SWORD), 1, "swords after login");
            helper.assertTrue(store(helper).byOwner(owner).isEmpty(), "body record kept after login");
            helper.assertTrue(bodiesOf(helper, owner).isEmpty(), "a body is left after login");
        } finally {
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    @GameTest
    public void healthAboveBaseMaxKeptWithGearModifier(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Hearty", GameType.SURVIVAL);
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            wearHeartyChestplate(helper, player);
            Mannequin body = logOut(helper, player);
            helper.assertValueEqual(body.getMaxHealth(), 30.0F, "body max health");
            helper.assertValueEqual(body.getHealth(), 28.0F, "body health");
        } finally {
            cleanUp(helper, player.getUUID(), before);
        }
        helper.succeed();
    }

    // Loading puts the health back before the gear, so it is clamped to the base maximum on the way in
    @GameTest
    public void reloadedBodyKeepsHealthFromGear(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Reloaded", GameType.SURVIVAL);
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            wearHeartyChestplate(helper, player);
            Mannequin body = leaveBody(helper, player);
            helper.assertValueEqual(body.getHealth(), 28.0F, "body health before its chunk unloads");
            Mannequin loaded = loadAgain(helper, unload(helper, body));
            helper.assertValueEqual(loaded.getMaxHealth(), 30.0F, "max health after the load");
            helper.assertValueEqual(loaded.getHealth(), 28.0F, "health after the load");
        } finally {
            cleanUp(helper, player.getUUID(), before);
        }
        helper.succeed();
    }

    // A chestplate with +10 max health, and the player at 28 of 30. A test player never ticks, so the modifier goes
    // on by hand, as the player's first tick would put it.
    private static void wearHeartyChestplate(GameTestHelper helper, ServerPlayer player) {
        ItemStack chest = new ItemStack(Items.IRON_CHESTPLATE);
        chest.set(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.builder().add(Attributes.MAX_HEALTH,
                new AttributeModifier(Identifier.fromNamespaceAndPath("you-only-live-twice-gametest", "hearty"), 10.0,
                        AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.CHEST).build());
        player.setItemSlot(EquipmentSlot.CHEST, chest);
        chest.forEachModifier(EquipmentSlot.CHEST,
                (attribute, modifier) -> player.getAttribute(attribute).addTransientModifier(modifier));
        player.setHealth(28.0F);
        helper.assertValueEqual(player.getHealth(), 28.0F, "player health");
    }

    @GameTest
    public void totemStrippedWhenTotemsOff(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Totem", GameType.SURVIVAL);
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            Settings.get().bodies.totems = false;
            player.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
            ItemStack offhand = logOut(helper, player).getItemBySlot(EquipmentSlot.OFFHAND);

            helper.assertTrue(offhand.is(Items.TOTEM_OF_UNDYING), "totem not copied");
            helper.assertFalse(offhand.has(DataComponents.DEATH_PROTECTION), "body's totem still protects");
            helper.assertTrue(player.getItemBySlot(EquipmentSlot.OFFHAND).has(DataComponents.DEATH_PROTECTION),
                    "player's own totem was stripped");
        } finally {
            cleanUp(helper, player.getUUID(), before);
        }
        helper.succeed();
    }

    @GameTest
    public void poseSwitchApplies(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Pose", GameType.SURVIVAL);
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            Settings.get().bodies.pose = YoltConfig.BodyPose.STANDING;
            helper.assertValueEqual(logOut(helper, player).getPose(), Pose.STANDING, "standing pose");
            Settings.get().bodies.pose = YoltConfig.BodyPose.SLEEPING;
            helper.assertValueEqual(logOut(helper, player).getPose(), Pose.SLEEPING, "sleeping pose");
        } finally {
            cleanUp(helper, player.getUUID(), before);
        }
        helper.succeed();
    }

    @GameTest
    public void loginTakesBodyStateAndRemovesIt(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Takeover", GameType.SURVIVAL);
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            Mannequin body = leaveBody(helper, player);
            helper.assertValueEqual(body.getHealth(), 20.0F, "body health at logout");
            body.hurtServer(helper.getLevel(), helper.getLevel().damageSources().generic(), 5.0F);
            helper.assertValueEqual(body.getHealth(), 15.0F, "body health after the hit");

            ServerPlayer back = rejoin(helper, player);
            helper.assertValueEqual(back.getHealth(), 15.0F, "health after login");
            helper.assertTrue(body.isRemoved(), "body still in the world");
            helper.assertTrue(bodiesOf(helper, owner).isEmpty(), "a body is left for the player");
            helper.assertTrue(store(helper).byOwner(owner).isEmpty(), "body record kept");
        } finally {
            cleanUp(helper, player.getUUID(), before);
        }
        helper.succeed();
    }

    @GameTest
    public void movedBodyMovesPlayer(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Moved", GameType.SURVIVAL);
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            Mannequin body = leaveBody(helper, player);
            body.teleportTo(body.getX() + 3.0, body.getY(), body.getZ());
            Vec3 moved = body.position();

            // Joins where they logged out, 3 blocks from the body
            ServerPlayer back = rejoin(helper, player);
            helper.assertValueEqual(back.position(), moved, "position after login");
        } finally {
            cleanUp(helper, player.getUUID(), before);
        }
        helper.succeed();
    }

    @GameTest
    public void poppedTotemIsGoneAfterLogin(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Popped", GameType.SURVIVAL);
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            player.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
            Mannequin body = leaveBody(helper, player);
            // What saving the body from a death does to its totem
            body.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);

            ServerPlayer back = rejoin(helper, player);
            helper.assertTrue(back.getItemBySlot(EquipmentSlot.OFFHAND).isEmpty(), "used totem came back");
        } finally {
            cleanUp(helper, player.getUUID(), before);
        }
        helper.succeed();
    }

    // The totem saves the body and is used up, then a real dispenser puts a shield in the empty off hand. The owner
    // gets the shield, and the totem stays used.
    @GameTest(maxTicks = 40)
    public void dispensedShieldReplacesPoppedTotem(GameTestHelper helper) {
        BlockPos at = new BlockPos(1, 1, 1);
        BlockPos dispenser = at.east();
        helper.setBlock(at.below(), Blocks.STONE);
        helper.setBlock(dispenser, Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.WEST));
        ServerPlayer player = join(helper, "Shielded", GameType.SURVIVAL);
        UUID owner = player.getUUID();
        Runnable cleanUp = once(() -> cleanUp(helper, owner));
        YoltConfig.BodiesSection before = useDefaultBodies();
        Mannequin body;
        try {
            player.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
            body = leaveBody(helper, player);
        } catch (RuntimeException e) {
            Settings.get().bodies = before;
            cleanUp.run();
            throw e;
        } finally {
            Settings.get().bodies = before;
        }
        boolean waiting = false;
        try {
            body.hurtServer(helper.getLevel(), helper.getLevel().damageSources().generic(), 100.0F);
            helper.assertTrue(body.isAlive() && body.getItemBySlot(EquipmentSlot.OFFHAND).isEmpty(), "the totem did not save the body");
            helper.getBlockEntity(dispenser, DispenserBlockEntity.class).setItem(0, new ItemStack(Items.SHIELD));
            helper.setBlock(dispenser.east(), Blocks.REDSTONE_BLOCK);
            helper.runBeforeTestEnd(cleanUp);
            helper.startSequence()
                    .thenWaitUntil(() -> helper.assertTrue(body.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.SHIELD),
                            "the dispenser has not equipped the body"))
                    .thenExecute(() -> {
                        try {
                            ServerPlayer back = rejoin(helper, player);
                            helper.assertTrue(back.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.SHIELD),
                                    "off hand after login: " + back.getItemBySlot(EquipmentSlot.OFFHAND));
                            helper.assertValueEqual(back.getInventory().countItem(Items.TOTEM_OF_UNDYING), 0, "totems after login");
                        } finally {
                            cleanUp.run();
                        }
                    })
                    .thenSucceed();
            waiting = true;
        } finally {
            if (!waiting) {
                cleanUp.run();
            }
        }
    }

    @GameTest
    public void wornArmourKeepsDamageAfterLogin(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Worn", GameType.SURVIVAL);
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            Mannequin body = leaveBody(helper, player);
            body.getItemBySlot(EquipmentSlot.CHEST).set(DataComponents.DAMAGE, 40);

            ServerPlayer back = rejoin(helper, player);
            ItemStack chest = back.getItemBySlot(EquipmentSlot.CHEST);
            helper.assertTrue(chest.is(Items.IRON_CHESTPLATE), "chestplate lost");
            helper.assertValueEqual(chest.getOrDefault(DataComponents.DAMAGE, 0), 40, "chestplate damage");
        } finally {
            cleanUp(helper, player.getUUID(), before);
        }
        helper.succeed();
    }

    // Logging out doesn't put out a fire or refill the air: the body has them, and the player gets the body's back
    @GameTest
    public void fallFireAndAirCarryOver(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Burning", GameType.SURVIVAL);
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            player.fallDistance = 4.0;
            player.setRemainingFireTicks(100);
            player.setAirSupply(150);
            Mannequin body = leaveBody(helper, player);
            helper.assertValueEqual(body.fallDistance, 4.0, "body fall distance");
            helper.assertValueEqual(body.getRemainingFireTicks(), 100, "body fire ticks");
            helper.assertValueEqual(body.getAirSupply(), 150, "body air");

            // Different from the logout save, so the values can only come from the body
            body.fallDistance = 6.0;
            body.setRemainingFireTicks(60);
            body.setAirSupply(90);
            ServerPlayer back = rejoin(helper, player);
            helper.assertValueEqual(back.fallDistance, 6.0, "fall distance after login");
            helper.assertValueEqual(back.getRemainingFireTicks(), 60, "fire ticks after login");
            helper.assertValueEqual(back.getAirSupply(), 90, "air after login");
        } finally {
            cleanUp(helper, player.getUUID(), before);
        }
        helper.succeed();
    }

    // With copyEffects off the body gets none of its owner's effects, and the login leaves the save's own: one the body
    // gained doesn't come over either. The switch is off only within this tick.
    @GameTest
    public void copyEffectsOffLeavesOwnerEffects(GameTestHelper helper) {
        ServerPlayer player = join(helper, "OwnEffects", GameType.SURVIVAL);
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            Settings.get().bodies.copyEffects = false;
            player.addEffect(new MobEffectInstance(MobEffects.SPEED, 12000));
            Mannequin body = leaveBody(helper, player);
            helper.assertTrue(body.getActiveEffects().isEmpty(), "the body got effects: " + body.getActiveEffects());
            body.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 12000));

            ServerPlayer back = rejoin(helper, player);
            helper.assertTrue(back.hasEffect(MobEffects.SPEED), "the save's speed is gone after login");
            helper.assertFalse(back.hasEffect(MobEffects.REGENERATION), "the body's regeneration came over");
            helper.assertTrue(body.isRemoved(), "body still in the world");
        } finally {
            cleanUp(helper, player.getUUID(), before);
        }
        helper.succeed();
    }

    // Whether the body takes the effects is settled as it is made. Turning copyEffects on between the logout and the
    // login leaves the player the save's own: the body took none of theirs to hand back. Within this tick.
    @GameTest
    public void copyEffectsTurnedOnAfterLogoutKeepsOwnerEffects(GameTestHelper helper) {
        ServerPlayer player = join(helper, "LateEffects", GameType.SURVIVAL);
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            Settings.get().bodies.copyEffects = false;
            player.addEffect(new MobEffectInstance(MobEffects.SPEED, 12000));
            player.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 12000));
            Mannequin body = leaveBody(helper, player);
            helper.assertTrue(body.getActiveEffects().isEmpty(), "the body got effects: " + body.getActiveEffects());
            Settings.get().bodies.copyEffects = true;

            ServerPlayer back = rejoin(helper, player);
            helper.assertTrue(back.hasEffect(MobEffects.SPEED), "the save's speed is gone after login");
            helper.assertTrue(back.hasEffect(MobEffects.FIRE_RESISTANCE), "the save's fire resistance is gone after login");
            helper.assertTrue(body.isRemoved(), "body still in the world");
        } finally {
            cleanUp(helper, player.getUUID(), before);
        }
        helper.succeed();
    }

    // The owner takes over the body's effects as they are now: one it gained comes over, one that went is gone
    @GameTest
    public void takeoverReplacesEffectsWithBodys(GameTestHelper helper) {
        ServerPlayer player = join(helper, "BodyEffects", GameType.SURVIVAL);
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            player.addEffect(new MobEffectInstance(MobEffects.SPEED, 12000));
            Mannequin body = leaveBody(helper, player);
            helper.assertTrue(body.hasEffect(MobEffects.SPEED), "the body did not get the speed");
            body.removeEffect(MobEffects.SPEED);
            body.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 12000));

            ServerPlayer back = rejoin(helper, player);
            helper.assertTrue(back.hasEffect(MobEffects.REGENERATION), "the body's regeneration did not come over");
            helper.assertFalse(back.hasEffect(MobEffects.SPEED), "the speed the body lost came back");
        } finally {
            cleanUp(helper, player.getUUID(), before);
        }
        helper.succeed();
    }

    // An admin moved the body to the Nether, so the owner logs in there, not where their save left them. A player
    // body, which a teleport keeps the same entity and in the world wherever it goes.
    @GameTest
    public void takeoverAcrossDimensions(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Netherward", GameType.SURVIVAL);
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = usePlayerBodies();
        try {
            PlayerBody body = leavePlayerBody(helper, player);
            ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
            helper.assertTrue(nether != null, "no nether to go to");
            // What a /tp into another dimension runs
            body.teleportTo(nether, 0.5, 64.0, 0.5, Set.of(), 0.0F, 0.0F, true);
            helper.assertTrue(body.level() == nether, "the body did not change dimension");
            Vec3 at = body.position();

            ServerPlayer back = rejoin(helper, player);
            helper.assertValueEqual(back.level().dimension(), Level.NETHER, "dimension after login");
            helper.assertTrue(back.position().distanceTo(at) <= 1.0, "position after login " + back.position()
                    + ", the body's " + at);
            helper.assertTrue(body.isRemoved(), "body still in the world");
            helper.assertTrue(store(helper).byOwner(owner).isEmpty(), "body record kept");
        } finally {
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    // The same with a mannequin, the default type. Vanilla moves an entity that isn't a player to another dimension by
    // making a new one there with the same id, which only counts once its chunk is loaded. So the Nether chunk is kept
    // loaded, and the login waits for the body to be there. Immovable, so it can't fall in the Nether; the switches are
    // only on for the logout.
    @GameTest(maxTicks = 100)
    public void mannequinTakeoverAcrossDimensions(GameTestHelper helper) {
        ServerPlayer player = join(helper, "NetherDoll", GameType.SURVIVAL);
        UUID owner = player.getUUID();
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "no nether to go to");
        // Away from the spot other tests send players to
        Vec3 to = new Vec3(40.5, 64.0, 40.5);
        ChunkPos chunk = ChunkPos.containing(BlockPos.containing(to));
        YoltConfig.BodiesSection before = useDefaultBodies();
        Mannequin body;
        try {
            Settings.get().bodies.movable = false;
            body = leaveBody(helper, player);
        } finally {
            Settings.get().bodies = before;
        }
        // Only a chunk this test forced is let go again
        boolean forced = nether.setChunkForced(chunk.x(), chunk.z(), true);
        Runnable cleanUp = once(() -> {
            try {
                cleanUp(helper, owner);
            } finally {
                if (forced) {
                    nether.setChunkForced(chunk.x(), chunk.z(), false);
                }
            }
        });
        boolean waiting = false;
        try {
            // What a /tp into another dimension runs
            helper.assertTrue(body.teleportTo(nether, to.x, to.y, to.z, Set.of(), 0.0F, 0.0F, true), "the body did not move");
            helper.assertTrue(body.isRemoved(), "the body is still in the overworld");
            helper.runBeforeTestEnd(cleanUp);
            helper.startSequence()
                    .thenWaitUntil(() -> helper.assertTrue(nether.getEntity(body.getUUID()) instanceof Mannequin,
                            "the body is not in the Nether yet"))
                    .thenExecute(() -> {
                        try {
                            Vec3 at = nether.getEntity(body.getUUID()).position();
                            ServerPlayer back = rejoin(helper, player);
                            helper.assertValueEqual(back.level().dimension(), Level.NETHER, "dimension after login");
                            helper.assertTrue(back.position().distanceTo(at) <= 1.0, "position after login " + back.position()
                                    + ", the body's " + at);
                            helper.assertTrue(nether.getEntity(body.getUUID()) == null, "body still in the Nether");
                            helper.assertTrue(store(helper).byOwner(owner).isEmpty(), "body record kept");
                        } finally {
                            cleanUp.run();
                        }
                    })
                    .thenSucceed();
            waiting = true;
        } finally {
            if (!waiting) {
                cleanUp.run();
            }
        }
    }

    // Absorption is held to a cap only an effect raises, so the body has Absorption II, a cap of 8, with 4 left. The
    // effect alone would start the owner at 8.
    @GameTest
    public void absorptionCarriesOver(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Absorbed", GameType.SURVIVAL);
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            Mannequin body = leaveBody(helper, player);
            body.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 12000, 1));
            body.setAbsorptionAmount(4.0F);
            helper.assertValueEqual(body.getAbsorptionAmount(), 4.0F, "body absorption");

            ServerPlayer back = rejoin(helper, player);
            helper.assertValueEqual(back.getAbsorptionAmount(), 4.0F, "absorption after login");
        } finally {
            cleanUp(helper, player.getUUID(), before);
        }
        helper.succeed();
    }

    // A body whose death could not kill its owner: a non-empty folder where the old save gets moved makes the
    // save swap fail, so the death is taken back and the save stays as it was
    @GameTest
    public void deadBodyIsNotTakenOver(GameTestHelper helper) throws IOException {
        ServerPlayer player = join(helper, "DeadBody", GameType.SURVIVAL);
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            player.getInventory().add(new ItemStack(Items.DIAMOND));
            Mannequin body = leaveBody(helper, player);
            Path blocker = helper.getLevel().getServer().getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(owner + ".dat_old");
            Path blockerContent = blocker.resolve("blocker");
            Files.createDirectories(blocker);
            Files.writeString(blockerContent, "");
            try {
                body.kill(helper.getLevel());
            } finally {
                Files.delete(blockerContent);
                Files.delete(blocker);
            }
            helper.assertTrue(body.isDeadOrDying(), "body did not die");
            helper.assertTrue(gravesOf(helper, owner).isEmpty(), "failed death left a grave");
            helper.assertTrue(DeathLockout.refusal(owner, "Test") == null, "failed death started the lockout");
            // What the removal at the end of the death animation runs
            BodyChunks.onBodyUnload(body);
            helper.assertValueEqual(store(helper).byOwner(owner).map(record -> record.snapshot().health()), Optional.of(20.0F),
                    "saved health after the body died");

            // Still in its death animation, so the login finds it
            ServerPlayer back = rejoin(helper, player);
            helper.assertFalse(back.isDeadOrDying(), "player joined dead");
            helper.assertValueEqual(back.getHealth(), 20.0F, "health after login");
            helper.assertTrue(back.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE), "chestplate lost");
            helper.assertValueEqual(back.getInventory().countItem(Items.DIAMOND), 1, "diamonds after login");
            helper.assertTrue(body.isRemoved(), "dead body still in the world");
            helper.assertTrue(store(helper).byOwner(owner).isEmpty(), "body record kept");
        } finally {
            cleanUp(helper, player.getUUID(), before);
        }
        helper.succeed();
    }

    // Killed offline while the body stood, without going through it (as after a crash before the record's removal
    // was saved): the grave has the real gear, the body only copies of it
    @GameTest
    public void deadJoinerGetsNoBodyGear(GameTestHelper helper) {
        ServerPlayer player = join(helper, "DeadJoin", GameType.SURVIVAL);
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            Mannequin body = leaveBody(helper, player);
            MinecraftServer server = helper.getLevel().getServer();
            CompoundTag saved = OfflinePlayerData.load(server, player.nameAndId()).orElseThrow();
            OfflineDeath.applyOfflineDeath(server, player.nameAndId(), saved, OfflinePlayerData.lastPosition(saved),
                    Profiles.of(Skins.known(server, player.nameAndId())), null);
            helper.assertValueEqual(GraveAsserts.countInGraves(server, owner, Items.IRON_CHESTPLATE), 1, "chestplates in the graves");
            helper.assertValueEqual(GraveAsserts.countInGraves(server, owner, Items.IRON_SWORD), 1, "swords in the graves");

            ServerPlayer back = rejoin(helper, player);
            helper.assertTrue(back.isDeadOrDying(), "dead player came back alive");
            for (EquipmentSlot slot : GearSync.SLOTS) {
                helper.assertTrue(back.getItemBySlot(slot).isEmpty(), "got the body's " + slot.getName());
            }
            helper.assertTrue(body.isRemoved(), "body still in the world");
            helper.assertTrue(store(helper).byOwner(owner).isEmpty(), "body record kept");
        } finally {
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    // The same dead joiner, whose body's empty head a dispenser filled since. That helmet is real and no takeover hands
    // it over, so it drops where the body stood, for good.
    @GameTest
    public void deadJoinerGetsDispensedGearDropped(GameTestHelper helper) {
        ServerPlayer player = join(helper, "DeadDrop", GameType.SURVIVAL);
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = useDefaultBodies();
        AABB around = null;
        try {
            Mannequin body = leaveBody(helper, player);
            body.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            around = new AABB(body.blockPosition()).inflate(2.0);
            MinecraftServer server = helper.getLevel().getServer();
            CompoundTag saved = OfflinePlayerData.load(server, player.nameAndId()).orElseThrow();
            OfflineDeath.applyOfflineDeath(server, player.nameAndId(), saved, OfflinePlayerData.lastPosition(saved),
                    Profiles.of(Skins.known(server, player.nameAndId())), null);

            ServerPlayer back = rejoin(helper, player);
            helper.assertTrue(back.isDeadOrDying(), "dead player came back alive");
            helper.assertTrue(back.getItemBySlot(EquipmentSlot.HEAD).isEmpty(), "the dead player got the helmet");
            helper.assertTrue(body.isRemoved(), "body still in the world");
            List<ItemEntity> drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class, around,
                    drop -> drop.getItem().is(Items.IRON_HELMET));
            helper.assertValueEqual(drops.size(), 1, "helmets dropped at the body");
            helper.assertValueEqual(drops.get(0).getAge(), (int) Short.MIN_VALUE, "dropped helmet's age");
        } finally {
            if (around != null) {
                helper.getLevel().getEntitiesOfClass(ItemEntity.class, around, drop -> drop.getItem().is(Items.IRON_HELMET))
                        .forEach(Entity::discard);
            }
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    // The record is what a login uses while the chunk stays unloaded, so the stop brings it up to date
    @GameTest(environment = PlayerBodyGameTests.BODY_RESTART)
    public void stopSavesMannequinState(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Stopping", GameType.SURVIVAL);
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            Mannequin body = leaveBody(helper, player);
            body.teleportTo(body.getX() + 2.0, body.getY(), body.getZ());
            body.setHealth(11.0F);
            BodyRestart.snapshotBodies(helper.getLevel().getServer());

            Optional<BodyRecord> record = store(helper).byOwner(owner);
            helper.assertTrue(record.isPresent(), "no body record");
            helper.assertValueEqual(record.get().snapshot().pos(), body.position(), "saved position");
            helper.assertValueEqual(record.get().snapshot().health(), 11.0F, "saved health");
        } finally {
            cleanUp(helper, player.getUUID(), before);
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 20)
    public void loginWithUnloadedBodyUsesSnapshot(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Unloaded", GameType.SURVIVAL);
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = useDefaultBodies();
        boolean waiting = false;
        try {
            Mannequin body = leaveBody(helper, player);
            body.setHealth(12.0F);
            CompoundTag saved = unload(helper, body);

            ServerPlayer back = rejoin(helper, player);
            helper.assertValueEqual(back.getHealth(), 12.0F, "health after login");
            Optional<BodyRecord> record = store(helper).byOwner(owner);
            helper.assertTrue(record.isPresent() && record.get().pendingRemoval(), "record not waiting for the body to load");
            // A record waiting for its body was taken over already, so a later login leaves the player alone
            back.setHealth(18.0F);
            BodyLogin.onLogin(back);
            helper.assertValueEqual(back.getHealth(), 18.0F, "health after a second login");

            Mannequin loaded = loadAgain(helper, saved);
            // A server running behind still runs queued tasks within 4 ticks
            helper.runAfterDelay(5, () -> {
                try {
                    helper.assertTrue(loaded.isRemoved(), "body kept after its chunk loaded");
                    helper.assertTrue(store(helper).byOwner(owner).isEmpty(), "record kept after the body loaded");
                } finally {
                    removeBodies(helper, owner);
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            restore(helper, owner, before);
            if (!waiting) {
                removeBodies(helper, owner);
            }
        }
    }

    @GameTest(maxTicks = 20)
    public void relogThenLogoutBeforeOldBodyLoads(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Relog", GameType.SURVIVAL);
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = useDefaultBodies();
        boolean waiting = false;
        try {
            CompoundTag first = unload(helper, leaveBody(helper, player));
            ServerPlayer back = rejoin(helper, player);
            helper.assertTrue(store(helper).byOwner(owner).map(BodyRecord::pendingRemoval).orElse(false),
                    "record not waiting for the first body to load");
            Mannequin second = leaveBody(helper, back);

            Mannequin firstLoaded = loadAgain(helper, first);
            helper.runAfterDelay(5, () -> {
                try {
                    helper.assertTrue(firstLoaded.isRemoved(), "first body kept after its chunk loaded");
                    helper.assertFalse(second.isRemoved(), "second body removed");
                    helper.assertValueEqual(store(helper).byOwner(owner).map(BodyRecord::bodyEntity),
                            Optional.of(second.getUUID()), "record's body");
                } finally {
                    removeBodies(helper, owner);
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            restore(helper, owner, before);
            if (!waiting) {
                removeBodies(helper, owner);
            }
        }
    }

    // The race at login: the body is alive when the login check lets the owner in, and dies before they are placed in
    // the world. The owner must never come back alive holding what the grave holds too: they land dead, with nothing,
    // and are still locked out, so the join's kick comes. In the login check's batch, so no other test's player fills
    // the one slot the test server has. All in one tick, and they leave before the kick.
    @GameTest(environment = LockoutGameTests.LOGIN_CHECK)
    public void bodyKilledBetweenLoginCheckAndJoinNeverRevivesTheOwner(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = join(helper, "Raced", GameType.SURVIVAL);
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            player.getInventory().add(new ItemStack(Items.DIAMOND, 5));
            player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            Mannequin body = leaveBody(helper, player);
            helper.assertTrue(LockoutGameTests.loginRefusal(server, player.nameAndId()) == null,
                    "the login check refused the owner while the body was alive");

            body.kill(helper.getLevel());
            helper.assertTrue(body.isDeadOrDying(), "the body did not die");
            helper.assertValueEqual(GraveAsserts.countInGraves(server, owner, Items.DIAMOND), 5, "diamonds in the graves");

            ServerPlayer back = rejoin(helper, player);
            helper.assertTrue(back.isDeadOrDying(), "the owner landed alive after their body died");
            helper.assertValueEqual(back.getInventory().countItem(Items.DIAMOND), 0, "diamonds the owner landed with");
            helper.assertTrue(back.getItemBySlot(EquipmentSlot.CHEST).isEmpty(), "the owner landed wearing the chestplate");
            helper.assertTrue(LockoutGameTests.refusedForLockout(DeathLockout.refusal(owner, "Test")),
                    "the owner is not locked out, so the join's kick won't come");
            helper.assertTrue(store(helper).byOwner(owner).isEmpty(), "the dead body's record is still there");
        } finally {
            cleanUp(helper, owner, before);
            DeathLockout.revive(owner, "Test", "console");
        }
        helper.succeed();
    }

    // A second login of the same account: vanilla kicks the first session, whose logout leaves a body holding copies of
    // the hand, and only then places the second, which takes the body over. The diamonds exist once, on the player,
    // with no body, record or drop left. Three in the hand, which the body copies, and two in the bag, which it doesn't.
    @GameTest
    public void duplicateLoginOfSameUuidMakesNoSecondCopy(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer first = join(helper, "Twin", GameType.SURVIVAL);
        UUID owner = first.getUUID();
        AABB around = new AABB(first.blockPosition()).inflate(4.0);
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            first.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND, 3));
            first.getInventory().setItem(9, new ItemStack(Items.DIAMOND, 2));
            helper.assertTrue(server.getPlayerList().disconnectAllPlayersWithProfile(owner), "no session was kicked");
            helper.assertTrue(server.getPlayerList().getPlayer(owner) == null, "the first session is still online");
            helper.assertValueEqual(LockoutGameTests.disconnectReason(first), PlayerList.DUPLICATE_LOGIN_DISCONNECT_MESSAGE.getString(),
                    "the first session's kick");
            List<? extends Mannequin> bodies = bodiesOf(helper, owner);
            helper.assertValueEqual(bodies.size(), 1, "bodies the kick left");
            helper.assertValueEqual(bodies.get(0).getMainHandItem().getCount(), 3, "diamonds in the body's hand");

            ServerPlayer second = rejoin(helper, first);
            helper.assertValueEqual(second.getInventory().countItem(Items.DIAMOND), 5, "diamonds the second session has");
            helper.assertTrue(bodiesOf(helper, owner).isEmpty(), "a body is left");
            helper.assertTrue(store(helper).byOwner(owner).isEmpty(), "a body record is left for the second session");
            helper.assertValueEqual(GraveDeathGameTests.itemsNear(helper, Items.DIAMOND, first.blockPosition(), 4.0), 0, "diamonds dropped");
        } finally {
            helper.getLevel().getEntitiesOfClass(ItemEntity.class, around).forEach(Entity::discard);
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    // Only what a body stands for comes from it: health from the body, while hunger and XP stay as the save has them
    @GameTest
    public void loginKeepsOwnHungerAndXp(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Peckish", GameType.SURVIVAL);
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            player.getFoodData().setFoodLevel(6);
            player.giveExperienceLevels(12);
            Mannequin body = leaveBody(helper, player);
            body.setHealth(10.0F);

            ServerPlayer back = rejoin(helper, player);
            helper.assertValueEqual(back.getHealth(), 10.0F, "health after login");
            helper.assertValueEqual(back.getFoodData().getFoodLevel(), 6, "food after login");
            helper.assertValueEqual(back.experienceLevel, 12, "XP level after login");
        } finally {
            cleanUp(helper, player.getUUID(), before);
        }
        helper.succeed();
    }

    // Switching bodies off stops new ones, but a body already standing is still its owner: the login hands it over
    // and takes it away, as with the switch on
    @GameTest
    public void bodiesTurnedOffWhileAwayStillHandsTheBodyBack(GameTestHelper helper) {
        ServerPlayer player = join(helper, "SwitchedAway", GameType.SURVIVAL);
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            Mannequin body = leaveBody(helper, player);
            body.setHealth(8.0F);
            body.teleportTo(body.getX() + 3.0, body.getY(), body.getZ());
            Vec3 moved = body.position();
            Settings.get().bodies.enabled = false;

            ServerPlayer back = rejoin(helper, player);
            helper.assertValueEqual(back.position(), moved, "position after login");
            helper.assertValueEqual(back.getHealth(), 8.0F, "health after login");
            helper.assertTrue(body.isRemoved(), "the body is still in the world");
            helper.assertTrue(store(helper).byOwner(owner).isEmpty(), "the body's record is still there");
        } finally {
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    // A mod that removes a body without killing it, as a lag clear can: nothing dies, so there is no grave or lockout,
    // and the owner takes over the state the record last saved. The record waits for a body that will never load.
    @GameTest
    public void discardedBodyHandsOverItsLastSavedState(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Cleared", GameType.SURVIVAL);
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            Mannequin body = leaveBody(helper, player);
            body.setHealth(12.0F);
            // What the autosave and the stop run, so the record has the body's state
            BodyRestart.snapshotBodies(helper.getLevel().getServer());
            body.setHealth(5.0F);
            body.discard();
            helper.assertTrue(gravesOf(helper, owner).isEmpty(), "the discard made a grave");
            helper.assertTrue(DeathLockout.refusal(owner, "Test") == null, "the discard locked the owner out");
            helper.assertValueEqual(store(helper).byOwner(owner).map(record -> record.snapshot().health()), Optional.of(12.0F),
                    "saved health after the discard");

            ServerPlayer back = rejoin(helper, player);
            helper.assertFalse(back.isDeadOrDying(), "the owner logged in dead");
            helper.assertValueEqual(back.getHealth(), 12.0F, "health after login");
            Optional<BodyRecord> record = store(helper).byOwner(owner);
            helper.assertTrue(record.isPresent() && record.get().pendingRemoval(), "the record is not waiting for its body: " + record);
        } finally {
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    // bodies.pose is read as a body is made: a prone mannequin and a prone player body stay prone through 40 ticks
    // with the switch at standing, and only the next logout stands. A player stays near, so the bodies' area ticks. A
    // batch of its own, since the server's own section holds the pose across ticks: bodies stay off in it, so no other
    // test's logout makes a body, and copies in a flake hunt share the hold.
    @GameTest(maxTicks = 80, environment = BODY_POSE)
    public void poseChangeLeavesStandingBodiesAlone(GameTestHelper helper) {
        ServerPlayer mannequinOwner = join(helper, "ProneMannequin", GameType.SURVIVAL);
        ServerPlayer playerOwner = TestPlayers.join(helper, "PronePlayer", GameType.SURVIVAL, new BlockPos(3, 1, 1));
        ServerPlayer later = TestPlayers.join(helper, "StandsLater", GameType.SURVIVAL, new BlockPos(1, 1, 3));
        ServerPlayer watcher = TestPlayers.join(helper, "PoseWatcher", GameType.SURVIVAL, new BlockPos(3, 1, 3));
        Runnable cleanUp = once(() -> {
            POSE_HOLD.release();
            TestPlayers.leave(watcher);
            for (ServerPlayer owner : List.of(mannequinOwner, playerOwner, later)) {
                cleanUp(helper, owner.getUUID());
            }
        });
        POSE_HOLD.take(() -> {
            YoltConfig.BodiesSection bodies = Settings.get().bodies;
            YoltConfig.BodyPose pose = bodies.pose;
            return () -> bodies.pose = pose;
        });
        boolean waiting = false;
        try {
            YoltConfig.BodiesSection before = useDefaultBodies();
            Mannequin mannequin;
            PlayerBody playerBody;
            try {
                mannequin = leaveBody(helper, mannequinOwner);
                Settings.get().bodies.type = YoltConfig.BodyType.PLAYER;
                playerBody = leavePlayerBody(helper, playerOwner);
            } finally {
                Settings.get().bodies = before;
            }
            helper.assertValueEqual(mannequin.getPose(), Pose.SWIMMING, "the mannequin's pose at its logout");
            helper.assertValueEqual(playerBody.getPose(), Pose.SWIMMING, "the player body's pose at its logout");
            Settings.get().bodies.pose = YoltConfig.BodyPose.STANDING;
            helper.runAfterDelay(40, () -> {
                try {
                    helper.assertValueEqual(mannequin.getPose(), Pose.SWIMMING, "the mannequin's pose 40 ticks after the switch");
                    helper.assertValueEqual(playerBody.getPose(), Pose.SWIMMING, "the player body's pose 40 ticks after the switch");
                    YoltConfig.BodiesSection held = Settings.get().bodies;
                    held.enabled = true;
                    try {
                        helper.assertValueEqual(leaveBody(helper, later).getPose(), Pose.STANDING, "the next logout's pose");
                    } finally {
                        held.enabled = false;
                    }
                } finally {
                    cleanUp.run();
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                cleanUp.run();
            }
        }
    }

    // bodies.type is read as a body is made too: a mannequin made before the switch to player stays a mannequin and
    // stays its owner's body, so killing it is still their death
    @GameTest
    public void typeSwitchLeavesStandingBodiesAsTheyWere(GameTestHelper helper) {
        ServerPlayer player = join(helper, "TypeSwitched", GameType.SURVIVAL);
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            Mannequin body = leaveBody(helper, player);
            Settings.get().bodies.type = YoltConfig.BodyType.PLAYER;
            Entity entity = helper.getLevel().getEntity(body.getUUID());
            helper.assertTrue(entity instanceof Mannequin && !entity.isRemoved(), "the mannequin went with the switch: " + entity);
            helper.assertTrue(BodyRules.isBody(body), "the mannequin is no longer a body");
            helper.assertValueEqual(store(helper).byOwner(owner).map(BodyRecord::type), Optional.of(YoltConfig.BodyType.MANNEQUIN),
                    "the record's type");

            body.kill(helper.getLevel());
            helper.assertValueEqual(gravesOf(helper, owner).size(), 1, "graves after the mannequin's death");
            helper.assertTrue(LockoutGameTests.refusedForLockout(DeathLockout.refusal(owner, "Test")),
                    "the mannequin's death did not lock the owner out");
        } finally {
            cleanUp(helper, owner, before);
            DeathLockout.revive(owner, "Test", "console");
        }
        helper.succeed();
    }

    // Adventure is a survival mode, so its logout leaves a body like survival's; only creative and spectator get none
    @GameTest
    public void adventureLogoutLeavesABody(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Wanderer", GameType.ADVENTURE);
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            Mannequin body = leaveBody(helper, player);
            helper.assertTrue(BodyRules.isBody(body), "the adventure logout's mannequin is not a body");
        } finally {
            cleanUp(helper, player.getUUID(), before);
        }
        helper.succeed();
    }

    // A mannequin with the body tag that no record points at, as after a record was dropped, goes once it loads. A
    // plain mannequin beside it stays: only the tag and a missing record make one go. The check waits for the tick's
    // end, so a server running behind still runs it within 4 ticks.
    @GameTest(maxTicks = 20)
    public void taggedMannequinWithNoRecordIsRemovedWhenItLoads(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Mannequin tagged = new Mannequin(EntityTypes.MANNEQUIN, level);
        tagged.addTag(Bodies.TAG);
        Mannequin plain = new Mannequin(EntityTypes.MANNEQUIN, level);
        BlockPos at = helper.absolutePos(new BlockPos(2, 1, 2));
        tagged.snapTo(at, 0.0F, 0.0F);
        plain.snapTo(at.east(), 0.0F, 0.0F);
        Runnable cleanUp = once(() -> {
            tagged.discard();
            plain.discard();
        });
        boolean waiting = false;
        try {
            helper.assertTrue(level.addFreshEntity(tagged), "the tagged mannequin could not load");
            helper.assertTrue(level.addFreshEntity(plain), "the plain mannequin could not load");
            helper.runAfterDelay(5, () -> {
                try {
                    helper.assertTrue(tagged.isRemoved(), "the tagged mannequin with no record stayed");
                    helper.assertFalse(plain.isRemoved(), "the plain mannequin went");
                } finally {
                    cleanUp.run();
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                cleanUp.run();
            }
        }
    }

    // A body moved to a dimension that is gone since, as after a datapack's removal: the login can't put the owner
    // there, so they stay where their save has them, and still take over the body's health
    @GameTest
    public void takeoverOfABodyInAGoneDimensionKeepsTheSavePosition(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Stranded", GameType.SURVIVAL);
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            Mannequin body = leaveBody(helper, player);
            Vec3 savedAt = body.position();
            unload(helper, body);
            BodyRecord record = store(helper).byOwner(owner).orElseThrow();
            BodySnapshot was = record.snapshot();
            ResourceKey<Level> gone = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("yolt", "gone"));
            store(helper).put(record.withSnapshot(new BodySnapshot(gone, was.pos().add(100.0, 0.0, 100.0), was.yRot(), 9.0F,
                    was.absorption(), was.fallDistance(), was.fireTicks(), was.air(), was.effects(), was.gear())));

            ServerPlayer back = rejoin(helper, player);
            helper.assertTrue(back.level() == helper.getLevel(), "the owner logged in in " + back.level().dimension().identifier());
            helper.assertValueEqual(back.position(), savedAt, "position after login");
            helper.assertValueEqual(back.getHealth(), 9.0F, "health after login");
        } finally {
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    private static ServerPlayer join(GameTestHelper helper, String name, GameType mode) {
        return TestPlayers.join(helper, name, mode, new BlockPos(1, 1, 1));
    }

    private static Mannequin logOut(GameTestHelper helper, ServerPlayer player) {
        Optional<Entity> body = BodyLogout.onLogout(player);
        helper.assertTrue(body.isPresent() && body.get() instanceof Mannequin, "no mannequin body");
        return (Mannequin) body.get();
    }

    private static ServerPlayer rejoin(GameTestHelper helper, ServerPlayer player) {
        return TestPlayers.rejoin(helper, player.getGameProfile(), GameType.SURVIVAL, new BlockPos(1, 1, 1));
    }

    // Stands in for the body's chunk unloading: the hook saves its state, then it leaves the world.
    // Returns its save data, so loadAgain can bring it back the way a chunk load would.
    private static CompoundTag unload(GameTestHelper helper, Mannequin body) {
        BodyChunks.onBodyUnload(body);
        CompoundTag saved = save(helper, body);
        body.discard();
        return saved;
    }

    private static Mannequin loadAgain(GameTestHelper helper, CompoundTag saved) {
        Mannequin body = new Mannequin(EntityTypes.MANNEQUIN, helper.getLevel());
        body.load(TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), saved));
        helper.assertTrue(helper.getLevel().addFreshEntity(body), "body could not be loaded again");
        return body;
    }

    private static void assertNoBody(GameTestHelper helper, ServerPlayer player) {
        helper.assertTrue(store(helper).byOwner(player.getUUID()).isEmpty(), "body record made");
        helper.assertTrue(bodiesOf(helper, player.getUUID()).isEmpty(), "body in the world");
    }

    private static CompoundTag save(GameTestHelper helper, Entity entity) {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
        entity.saveWithoutId(output);
        return output.buildResult();
    }
}
