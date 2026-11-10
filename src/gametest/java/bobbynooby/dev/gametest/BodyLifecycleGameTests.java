package bobbynooby.dev.gametest;

import bobbynooby.dev.bodies.Bodies;
import bobbynooby.dev.bodies.BodyChunks;
import bobbynooby.dev.bodies.BodyLogin;
import bobbynooby.dev.bodies.BodyLogout;
import bobbynooby.dev.bodies.BodyRecord;
import bobbynooby.dev.bodies.BodyRestart;
import bobbynooby.dev.bodies.BodyRules;
import bobbynooby.dev.bodies.BodyStore;
import bobbynooby.dev.bodies.GearSync;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.core.Profiles;
import bobbynooby.dev.graves.Grave;
import bobbynooby.dev.graves.GraveStore;
import bobbynooby.dev.lockout.DeathLockout;
import bobbynooby.dev.offline.OfflineDeath;
import bobbynooby.dev.offline.OfflinePlayerData;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
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
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
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
import java.util.UUID;

// The test server keeps bodies off. Each test runs with the default body switches (bodies on) and puts the
// server's own back in the same tick, before any other test runs. The tests that wait only wait for the
// check a body load queues, which doesn't read them.
public class BodyLifecycleGameTests {
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
            cleanUp(helper, player, before);
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
            cleanUp(helper, player, before);
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
            cleanUp(helper, player, before);
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
            cleanUp(helper, player, before);
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
            cleanUp(helper, player, before);
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
            cleanUp(helper, player, before);
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
            Mannequin fresh = logOut(helper, player);
            helper.assertTrue(old.isRemoved(), "the old body still stands");
            helper.assertTrue(fresh.getItemBySlot(EquipmentSlot.CHEST).isEmpty(), "the new body wears the chestplate");
            Inventory inventory = player.getInventory();
            int found = -1;
            for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
                if (inventory.getItem(slot).is(Items.IRON_CHESTPLATE)) {
                    found = slot;
                }
            }
            helper.assertTrue(found >= 0, "the chestplate is not in the owner's inventory");
            helper.assertTrue(found != inventory.getSelectedSlot(), "the chestplate went into the owner's hand");
            helper.assertValueEqual(inventory.getItem(found).getDamageValue(), 7, "chestplate damage");

            for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
                packed.getInventory().setItem(slot, new ItemStack(Items.DIRT));
            }
            Mannequin oldPacked = logOut(helper, packed);
            oldPacked.setItemSlot(EquipmentSlot.CHEST, dispensedChestplate());
            around = new AABB(oldPacked.blockPosition()).inflate(2.0);
            logOut(helper, packed);
            helper.assertTrue(oldPacked.isRemoved(), "the packed player's old body still stands");
            List<ItemEntity> drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class, around,
                    drop -> drop.getItem().is(Items.IRON_CHESTPLATE));
            helper.assertValueEqual(drops.size(), 1, "chestplates dropped at the old body");
            helper.assertValueEqual(drops.get(0).getItem().getDamageValue(), 7, "dropped chestplate's damage");
            helper.assertValueEqual(drops.get(0).getAge(), (int) Short.MIN_VALUE, "dropped chestplate's age");
        } finally {
            if (around != null) {
                helper.getLevel().getEntitiesOfClass(ItemEntity.class, around, drop -> drop.getItem().is(Items.IRON_CHESTPLATE))
                        .forEach(Entity::discard);
            }
            cleanUp(helper, player, before);
            cleanUp(helper, packed, before);
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
            cleanUp(helper, player, before);
        }
        helper.succeed();
    }

    // A test player never ticks, so its chestplate's modifier goes on by hand, as the player's first tick would put it
    @GameTest
    public void healthAboveBaseMaxKeptWithGearModifier(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Hearty", GameType.SURVIVAL);
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            ItemStack chest = new ItemStack(Items.IRON_CHESTPLATE);
            chest.set(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.builder().add(Attributes.MAX_HEALTH,
                    new AttributeModifier(Identifier.fromNamespaceAndPath("you-only-live-twice-gametest", "hearty"), 10.0,
                            AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.CHEST).build());
            player.setItemSlot(EquipmentSlot.CHEST, chest);
            chest.forEachModifier(EquipmentSlot.CHEST,
                    (attribute, modifier) -> player.getAttribute(attribute).addTransientModifier(modifier));
            player.setHealth(28.0F);
            helper.assertValueEqual(player.getHealth(), 28.0F, "player health");

            Mannequin body = logOut(helper, player);
            helper.assertValueEqual(body.getMaxHealth(), 30.0F, "body max health");
            helper.assertValueEqual(body.getHealth(), 28.0F, "body health");
        } finally {
            cleanUp(helper, player, before);
        }
        helper.succeed();
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
            cleanUp(helper, player, before);
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
            cleanUp(helper, player, before);
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
            cleanUp(helper, player, before);
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
            cleanUp(helper, player, before);
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
            cleanUp(helper, player, before);
        }
        helper.succeed();
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
            cleanUp(helper, player, before);
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
            cleanUp(helper, player, before);
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
            cleanUp(helper, player, before);
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
                    Profiles.of(player.nameAndId()), null);
            helper.assertTrue(gravesOf(helper, owner).stream().anyMatch(grave -> grave.items().stream()
                    .anyMatch(item -> item.is(Items.IRON_CHESTPLATE))), "no grave holds the chestplate");

            ServerPlayer back = rejoin(helper, player);
            helper.assertTrue(back.isDeadOrDying(), "dead player came back alive");
            for (EquipmentSlot slot : GearSync.SLOTS) {
                helper.assertTrue(back.getItemBySlot(slot).isEmpty(), "got the body's " + slot.getName());
            }
            helper.assertTrue(body.isRemoved(), "body still in the world");
            helper.assertTrue(store(helper).byOwner(owner).isEmpty(), "body record kept");
        } finally {
            cleanUp(helper, player, before);
            removeGraves(helper, owner);
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
            cleanUp(helper, player, before);
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

    private static ServerPlayer join(GameTestHelper helper, String name, GameType mode) {
        return TestPlayers.join(helper, name, mode, new BlockPos(1, 1, 1));
    }

    // A fresh section has the default switches, with bodies on; the caller puts the returned one back
    private static YoltConfig.BodiesSection useDefaultBodies() {
        YoltConfig config = Settings.get();
        YoltConfig.BodiesSection before = config.bodies;
        config.bodies = new YoltConfig.BodiesSection();
        return before;
    }

    private static Mannequin logOut(GameTestHelper helper, ServerPlayer player) {
        Optional<Entity> body = BodyLogout.onLogout(player);
        helper.assertTrue(body.isPresent() && body.get() instanceof Mannequin, "no mannequin body");
        return (Mannequin) body.get();
    }

    // A real logout: leaving fires the hook and saves the player, so joining again reads that save
    private static Mannequin leaveBody(GameTestHelper helper, ServerPlayer player) {
        TestPlayers.leave(player);
        Optional<BodyRecord> record = store(helper).byOwner(player.getUUID());
        helper.assertTrue(record.isPresent(), "no body record");
        Entity entity = helper.getLevel().getEntity(record.get().bodyEntity());
        helper.assertTrue(entity instanceof Mannequin, "no mannequin for the record");
        return (Mannequin) entity;
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

    private static BodyStore store(GameTestHelper helper) {
        return BodyStore.get(helper.getLevel().getServer());
    }

    private static List<? extends Mannequin> bodiesOf(GameTestHelper helper, UUID owner) {
        return helper.getLevel().getEntities(EntityTypes.MANNEQUIN, body -> !body.isRemoved()
                && body.entityTags().contains(Bodies.TAG) && owner.equals(body.getProfile().partialProfile().id()));
    }

    private static void assertNoBody(GameTestHelper helper, ServerPlayer player) {
        helper.assertTrue(store(helper).byOwner(player.getUUID()).isEmpty(), "body record made");
        helper.assertTrue(bodiesOf(helper, player.getUUID()).isEmpty(), "body in the world");
    }

    private static List<Grave> gravesOf(GameTestHelper helper, UUID owner) {
        return GraveStore.get(helper.getLevel().getServer()).all().stream()
                .filter(grave -> grave.owner().equals(owner))
                .toList();
    }

    // A grave left behind would sit in another test's way
    private static void removeGraves(GameTestHelper helper, UUID owner) {
        GraveStore store = GraveStore.get(helper.getLevel().getServer());
        for (Grave grave : gravesOf(helper, owner)) {
            store.remove(grave.id());
            helper.getLevel().setBlock(grave.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    private static CompoundTag save(GameTestHelper helper, Entity entity) {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
        entity.saveWithoutId(output);
        return output.buildResult();
    }

    private static void cleanUp(GameTestHelper helper, ServerPlayer player, YoltConfig.BodiesSection before) {
        restore(helper, player.getUUID(), before);
        removeBodies(helper, player.getUUID());
    }

    // The switches go back first, so a player still online leaves without making another body
    private static void restore(GameTestHelper helper, UUID owner, YoltConfig.BodiesSection before) {
        Settings.get().bodies = before;
        // By uuid: after a relog the player online is a new object
        ServerPlayer online = helper.getLevel().getServer().getPlayerList().getPlayer(owner);
        if (online != null) {
            TestPlayers.leave(online);
        }
    }

    private static void removeBodies(GameTestHelper helper, UUID owner) {
        bodiesOf(helper, owner).forEach(Entity::discard);
        store(helper).remove(owner);
    }
}
