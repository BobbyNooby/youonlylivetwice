package bobbynooby.dev.gametest;

import bobbynooby.dev.bodies.BodyChunks;
import bobbynooby.dev.bodies.BodyExpiry;
import bobbynooby.dev.bodies.BodyRecord;
import bobbynooby.dev.bodies.BodyRules;
import bobbynooby.dev.bodies.BodyStore;
import bobbynooby.dev.config.BodyDuration;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.core.Stacks;
import bobbynooby.dev.offline.OfflinePlayerData;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.core.LogEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static bobbynooby.dev.gametest.BodyTestKit.cleanUp;
import static bobbynooby.dev.gametest.BodyTestKit.leaveBody;
import static bobbynooby.dev.gametest.BodyTestKit.removeBodies;
import static bobbynooby.dev.gametest.BodyTestKit.store;
import static bobbynooby.dev.gametest.BodyTestKit.useDefaultBodies;

// Like the other body tests: each one runs with the default body switches (bodies on) and puts the server's own
// back in the same tick. The ones that wait only need the switches while the body spawns, or until its item drops.
public class BodyBehaviourGameTests {
    // Where a test player joins inside its block: the middle of the bottom
    private static final Vec3 BLOCK_CENTRE = new Vec3(0.5, 0.0, 0.5);
    // A batch of its own: the test runs the expiry at clock times days ahead, when every other body would be due
    private static final String BODY_RETRY = "you-only-live-twice-gametest:body_retry";

    // While set, an item entity of this item throws as it loads, as another mod's load listener might. Fabric's events
    // can't take a listener off again, so this one stays, and does nothing while it is null.
    private static volatile Item failingDrop;

    static {
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            Item failing = failingDrop;
            if (failing != null && entity instanceof ItemEntity drop && drop.getItem().is(failing)) {
                throw new IllegalStateException("A test made this drop fail");
            }
        });
    }

    @GameTest
    public void armourWearsWhenHit(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Armour", new BlockPos(1, 1, 1));
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            Mannequin body = leaveBody(helper, player);
            body.hurtServer(helper.getLevel(), helper.getLevel().damageSources().mobAttack(attacker(helper)), 6.0F);
            helper.assertTrue(body.getItemBySlot(EquipmentSlot.CHEST).getDamageValue() > 0, "chestplate did not wear");
        } finally {
            cleanUp(helper, player.getUUID(), before);
        }
        helper.succeed();
    }

    @GameTest
    public void armourDoesNotWearWhenOff(GameTestHelper helper) {
        ServerPlayer player = join(helper, "NoWear", new BlockPos(1, 1, 1));
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            Settings.get().bodies.armourWears = false;
            player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            Mannequin body = leaveBody(helper, player);
            body.hurtServer(helper.getLevel(), helper.getLevel().damageSources().mobAttack(attacker(helper)), 6.0F);
            helper.assertValueEqual(body.getItemBySlot(EquipmentSlot.CHEST).getDamageValue(), 0, "chestplate damage");
        } finally {
            cleanUp(helper, player.getUUID(), before);
        }
        helper.succeed();
    }

    @GameTest
    public void totemSavesBodyOnce(GameTestHelper helper) {
        ServerPlayer player = join(helper, "BodyTotem", new BlockPos(1, 1, 1));
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            player.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
            Mannequin body = leaveBody(helper, player);
            body.setHealth(2.0F);
            body.hurtServer(helper.getLevel(), helper.getLevel().damageSources().generic(), 10.0F);
            helper.assertFalse(body.isDeadOrDying(), "totem did not save the body");
            helper.assertTrue(body.getItemBySlot(EquipmentSlot.OFFHAND).isEmpty(), "totem not used up");
        } finally {
            cleanUp(helper, player.getUUID(), before);
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 60)
    public void bodyFallsWhenMovable(GameTestHelper helper) {
        fallTest(helper, "Falls", true);
    }

    @GameTest(maxTicks = 60)
    public void bodyStaysWhenImmovable(GameTestHelper helper) {
        fallTest(helper, "Stays", false);
    }

    @GameTest
    public void bodyCannotUsePortal(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Portal", new BlockPos(1, 1, 1));
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            Mannequin body = leaveBody(helper, player);
            helper.assertTrue(BodyRules.isBody(body), "not recognised as a body");
            helper.assertFalse(body.canUsePortal(false), "body can use a portal");
            // Never added to the world; only checks the hook leaves other mannequins alone
            Mannequin plain = new Mannequin(EntityTypes.MANNEQUIN, helper.getLevel());
            helper.assertTrue(plain.canUsePortal(false), "a plain mannequin can't use a portal");
        } finally {
            cleanUp(helper, player.getUUID(), before);
        }
        helper.succeed();
    }

    // Minecarts pick up mobs, and vanilla takes a vehicle's passengers through a portal without asking them
    @GameTest
    public void vehicleWithBodyCannotUsePortal(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Rider", new BlockPos(1, 1, 1));
        YoltConfig.BodiesSection before = useDefaultBodies();
        Minecart minecart = null;
        try {
            Mannequin body = leaveBody(helper, player);
            minecart = EntityTypes.MINECART.create(helper.getLevel(), EntitySpawnReason.COMMAND);
            helper.assertTrue(minecart != null, "no minecart");
            minecart.snapTo(body.getX(), body.getY(), body.getZ(), 0.0F, 0.0F);
            helper.assertTrue(helper.getLevel().addFreshEntity(minecart), "minecart not added");
            helper.assertTrue(body.startRiding(minecart, true, false), "body did not get in the minecart");
            helper.assertFalse(minecart.canUsePortal(false), "a minecart carrying a body can use a portal");
            body.stopRiding();
            helper.assertTrue(minecart.canUsePortal(false), "an empty minecart can't use a portal");
        } finally {
            if (minecart != null) {
                minecart.discard();
            }
            cleanUp(helper, player.getUUID(), before);
        }
        helper.succeed();
    }

    // The expiry pass runs over every body, so this has the expiry batch
    @GameTest(environment = PlayerBodyGameTests.BODY_EXPIRY)
    public void timedBodyExpiresAndSavesState(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Expires", new BlockPos(1, 1, 1));
        UUID owner = player.getUUID();
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            Settings.get().bodies.duration = new BodyDuration(1);
            player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            player.addEffect(new MobEffectInstance(MobEffects.SPEED, 12000));
            Mannequin body = leaveBody(helper, player);
            helper.assertFalse(speedModifiers(helper, OfflinePlayerData.load(server, player.nameAndId()).orElseThrow()).isEmpty(),
                    "logout save has no speed modifier");
            // Wore off while the body stood
            body.removeEffect(MobEffects.SPEED);
            body.setHealth(9.0F);
            body.setRemainingFireTicks(40);
            body.setAirSupply(120);
            body.getItemBySlot(EquipmentSlot.CHEST).setDamageValue(30);
            body.teleportTo(body.getX() + 2.0, body.getY(), body.getZ());
            Vec3 at = body.position();
            BodyStore store = store(helper);
            expiredLogout(helper, owner);

            BodyExpiry.tick(server);
            helper.assertTrue(body.isRemoved(), "body still in the world");
            helper.assertTrue(store.byOwner(owner).isEmpty(), "body record kept");
            CompoundTag saved = OfflinePlayerData.load(server, player.nameAndId()).orElseThrow();
            helper.assertValueEqual(saved.getFloatOr("Health", -1.0F), 9.0F, "saved health");
            helper.assertValueEqual(saved.getShortOr("Fire", (short) -1), (short) 40, "saved fire ticks");
            helper.assertValueEqual(saved.getShortOr("Air", (short) -1), (short) 120, "saved air");
            helper.assertValueEqual(saved.read(ServerPlayer.SavedPosition.MAP_CODEC).flatMap(ServerPlayer.SavedPosition::position),
                    Optional.of(at), "saved position");
            ItemStack chest = OfflinePlayerData.readGear(saved, server.registryAccess()).items()
                    .getOrDefault(EquipmentSlot.CHEST, ItemStack.EMPTY);
            helper.assertTrue(chest.is(Items.IRON_CHESTPLATE), "chestplate lost from the save");
            helper.assertValueEqual(chest.getDamageValue(), 30, "saved chestplate damage");
            helper.assertFalse(saved.contains("active_effects"), "worn-off effect still saved");
            // Loading doesn't take an effect's modifier off again, so a stale one would stay for good
            helper.assertTrue(speedModifiers(helper, saved).isEmpty(), "worn-off speed's modifier still saved");
        } finally {
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    // The same switch turned on after the logout: the body took none of the owner's effects, so the expiry leaves the
    // save's own, and their modifier with them. The pass runs over every body, so this has the expiry batch.
    @GameTest(environment = PlayerBodyGameTests.BODY_EXPIRY)
    public void copyEffectsTurnedOnBeforeExpiryKeepsSavedEffects(GameTestHelper helper) {
        ServerPlayer player = join(helper, "LateExpiry", new BlockPos(1, 1, 1));
        UUID owner = player.getUUID();
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            Settings.get().bodies.duration = new BodyDuration(1);
            Settings.get().bodies.copyEffects = false;
            player.addEffect(new MobEffectInstance(MobEffects.SPEED, 12000));
            Mannequin body = leaveBody(helper, player);
            helper.assertTrue(body.getActiveEffects().isEmpty(), "the body got effects: " + body.getActiveEffects());
            Settings.get().bodies.copyEffects = true;
            expiredLogout(helper, owner);

            BodyExpiry.tick(server);
            helper.assertTrue(body.isRemoved(), "body still in the world");
            CompoundTag saved = OfflinePlayerData.load(server, player.nameAndId()).orElseThrow();
            helper.assertTrue(saved.contains("active_effects"), "the save's speed is gone after the expiry");
            helper.assertFalse(speedModifiers(helper, saved).isEmpty(), "the save's speed modifier is gone after the expiry");
        } finally {
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    // A body set alight burns on its own ticks until its time is up. The expiry is no death: the burn and the health it
    // cost go into the save, the owner logs in still burning, and there is no grave or lockout. The pass runs over every
    // body, so this has the expiry batch; the switches are only on in the ticks that need them.
    @GameTest(environment = PlayerBodyGameTests.BODY_EXPIRY, maxTicks = 80)
    public void bodyExpiresWhileBurning(GameTestHelper helper) {
        helper.setBlock(new BlockPos(1, 0, 1), Blocks.STONE);
        ServerPlayer player = join(helper, "Smoulder", new BlockPos(1, 1, 1));
        UUID owner = player.getUUID();
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.BodiesSection before = useDefaultBodies();
        boolean waiting = false;
        try {
            Mannequin body = leaveBody(helper, player);
            Settings.get().bodies = before;
            body.igniteForSeconds(10.0F);
            helper.runAfterDelay(45, () -> {
                YoltConfig.BodiesSection during = useDefaultBodies();
                try {
                    float health = body.getHealth();
                    int fire = body.getRemainingFireTicks();
                    helper.assertTrue(health < 20.0F, "the fire did not hurt the body");
                    helper.assertTrue(fire > 0, "the body stopped burning");
                    Settings.get().bodies.duration = new BodyDuration(1);
                    expiredLogout(helper, owner);
                    BodyExpiry.tick(server);
                    Settings.get().bodies = during;
                    helper.assertTrue(body.isRemoved(), "the body is still in the world");
                    CompoundTag saved = OfflinePlayerData.load(server, player.nameAndId()).orElseThrow();
                    helper.assertValueEqual(saved.getShortOr("Fire", (short) -1), (short) fire, "saved fire ticks");
                    helper.assertValueEqual(saved.getFloatOr("Health", -1.0F), health, "saved health");
                    helper.assertTrue(BodyTestKit.gravesOf(helper, owner).isEmpty(), "the expiry made a grave");
                    helper.assertTrue(LockoutGameTests.refusalText(owner).isEmpty(), "the expiry started the lockout");

                    ServerPlayer back = TestPlayers.rejoin(helper, player.getGameProfile(), GameType.SURVIVAL, new BlockPos(1, 1, 1));
                    helper.assertFalse(back.isDeadOrDying(), "the owner logged in dead");
                    helper.assertValueEqual(back.getRemainingFireTicks(), fire, "fire ticks after login");
                    helper.assertValueEqual(back.getHealth(), health, "health after login");
                } finally {
                    cleanUp(helper, owner, during);
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

    // As after the mod or datapack that added them went: the save's chest and main-hand entries don't read back, while
    // the body wears a chestplate and holds a sword. Those can't be written over the entries, so they go to free
    // inventory slots. The pass runs over every body, so this has the expiry batch.
    @GameTest(environment = PlayerBodyGameTests.BODY_EXPIRY)
    public void unreadableGearSlotIsNotOverwritten(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Unreadable", new BlockPos(1, 1, 1));
        UUID owner = player.getUUID();
        String name = player.nameAndId().name();
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            Settings.get().bodies.duration = new BodyDuration(1);
            player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            Mannequin body = leaveBody(helper, player);
            body.getItemBySlot(EquipmentSlot.HEAD).setDamageValue(30);
            CompoundTag tag = OfflinePlayerData.load(server, player.nameAndId()).orElseThrow();
            CompoundTag chest = OfflineGameTests.unreadableItem();
            tag.getCompound("equipment").orElseThrow().put("chest", chest.copy());
            CompoundTag held = OfflineGameTests.unreadableItem();
            held.putByte("Slot", (byte) tag.getIntOr("SelectedItemSlot", 0));
            ListTag inventory = new ListTag();
            inventory.add(held.copy());
            tag.put("Inventory", inventory);
            OfflinePlayerData.save(server, owner, tag);
            expiredLogout(helper, owner);

            List<String> log = OfflineGameTests.yoltLog(() -> BodyExpiry.tick(server));
            helper.assertTrue(store(helper).byOwner(owner).isEmpty(), "body record kept");
            CompoundTag saved = OfflinePlayerData.load(server, player.nameAndId()).orElseThrow();
            helper.assertValueEqual(saved.getCompoundOrEmpty("equipment").get("chest"), chest, "saved chest entry");
            helper.assertTrue(saved.getListOrEmpty("Inventory").contains(held), "saved main-hand entry gone: " + saved.get("Inventory"));
            Map<Integer, ItemStack> stored = OfflineGameTests.savedInventory(helper, saved);
            helper.assertValueEqual(stored.size(), 2, "inventory items that read back: " + stored);
            helper.assertTrue(stored.values().stream().anyMatch(item -> item.is(Items.IRON_SWORD)), "sword not in the inventory: " + stored);
            helper.assertTrue(stored.values().stream().anyMatch(item -> item.is(Items.IRON_CHESTPLATE)),
                    "chestplate not in the inventory: " + stored);
            // The rest of the body's gear still carries over
            ItemStack helmet = OfflinePlayerData.readGear(saved, server.registryAccess()).items()
                    .getOrDefault(EquipmentSlot.HEAD, ItemStack.EMPTY);
            helper.assertTrue(helmet.is(Items.IRON_HELMET) && helmet.getDamageValue() == 30, "saved helmet: " + helmet);
            for (String slot : List.of("chest", "mainhand")) {
                String kept = "Keeping " + name + "'s unreadable item in slot " + slot + " ";
                helper.assertValueEqual(log.stream().filter(line -> line.startsWith(kept)).count(), 1L,
                        "warnings for the " + slot + " entry: " + log);
            }
        } finally {
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    // The save's chest entry doesn't read back, and a dispenser put a chestplate into the body's empty chest slot. It
    // can't go over the entry, so it goes into the first free inventory slot.
    @GameTest(environment = PlayerBodyGameTests.BODY_EXPIRY)
    public void bodyItemOverUnreadableSlotGoesToFreeSlot(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Dispensed", new BlockPos(1, 1, 1));
        UUID owner = player.getUUID();
        String name = player.nameAndId().name();
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            Settings.get().bodies.duration = new BodyDuration(1);
            Mannequin body = leaveBody(helper, player);
            body.setItemSlot(EquipmentSlot.CHEST, damaged(Items.IRON_CHESTPLATE, 7));
            CompoundTag chest = unreadableChest(server, player);
            expiredLogout(helper, owner);

            List<String> log = OfflineGameTests.yoltLog(() -> BodyExpiry.tick(server));
            helper.assertTrue(body.isRemoved(), "body still in the world");
            CompoundTag saved = OfflinePlayerData.load(server, player.nameAndId()).orElseThrow();
            helper.assertValueEqual(saved.getCompoundOrEmpty("equipment").get("chest"), chest, "saved chest entry");
            Map<Integer, ItemStack> stored = OfflineGameTests.savedInventory(helper, saved);
            helper.assertValueEqual(stored.size(), 1, "inventory items: " + stored);
            ItemStack kept = stored.values().iterator().next();
            helper.assertTrue(kept.is(Items.IRON_CHESTPLATE) && kept.getDamageValue() == 7, "inventory item: " + kept);
            String line = "Keeping " + name + "'s unreadable item in slot chest ";
            helper.assertValueEqual(log.stream().filter(entry -> entry.startsWith(line) && entry.contains("goes into inventory slot"))
                    .count(), 1L, "warnings for the chest entry: " + log);
        } finally {
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    // The same, with every inventory slot of the save taken: the chestplate drops where the body stood, for good
    @GameTest(environment = PlayerBodyGameTests.BODY_EXPIRY)
    public void bodyItemWithNoFreeSlotDropsAtBody(GameTestHelper helper) {
        ServerPlayer player = join(helper, "NoRoom", new BlockPos(1, 1, 1));
        UUID owner = player.getUUID();
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.BodiesSection before = useDefaultBodies();
        AABB around = null;
        try {
            Settings.get().bodies.duration = new BodyDuration(1);
            OfflineGameTests.fillInventory(player);
            Mannequin body = leaveBody(helper, player);
            body.setItemSlot(EquipmentSlot.CHEST, damaged(Items.IRON_CHESTPLATE, 7));
            CompoundTag chest = unreadableChest(server, player);
            expiredLogout(helper, owner);
            around = new AABB(body.blockPosition()).inflate(2.0);

            BodyExpiry.tick(server);
            helper.assertTrue(body.isRemoved(), "body still in the world");
            CompoundTag saved = OfflinePlayerData.load(server, player.nameAndId()).orElseThrow();
            helper.assertValueEqual(saved.getCompoundOrEmpty("equipment").get("chest"), chest, "saved chest entry");
            Map<Integer, ItemStack> stored = OfflineGameTests.savedInventory(helper, saved);
            helper.assertTrue(stored.size() == Inventory.INVENTORY_SIZE && stored.values().stream().allMatch(item -> item.is(Items.DIRT)),
                    "saved inventory: " + stored);
            List<ItemEntity> drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class, around,
                    drop -> drop.getItem().is(Items.IRON_CHESTPLATE));
            helper.assertValueEqual(drops.size(), 1, "chestplates dropped at the body");
            helper.assertValueEqual(drops.get(0).getItem().getDamageValue(), 7, "dropped chestplate's damage");
            // ItemEntity.setUnlimitedLifetime sets this age, which the item's tick never counts up from
            helper.assertValueEqual(drops.get(0).getAge(), (int) Short.MIN_VALUE, "dropped chestplate's age");
            // No random push, which could carry it off the block the body stood on and into lava or the void
            helper.assertValueEqual(drops.get(0).getDeltaMovement(), Vec3.ZERO, "dropped chestplate's movement");
        } finally {
            if (around != null) {
                helper.getLevel().getEntitiesOfClass(ItemEntity.class, around, drop -> drop.getItem().is(Items.IRON_CHESTPLATE))
                        .forEach(Entity::discard);
            }
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    // The same, with the save failing after the chestplate dropped: a non-empty folder where the old save gets moved
    // makes the swap fail. The drop is taken back, so the chestplate is only on the body, and the retry drops it once.
    @GameTest(environment = PlayerBodyGameTests.BODY_EXPIRY)
    public void failedExpirySaveTakesBackTheDrop(GameTestHelper helper) throws IOException {
        ServerPlayer player = join(helper, "SaveFails", new BlockPos(1, 1, 1));
        UUID owner = player.getUUID();
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.BodiesSection before = useDefaultBodies();
        AABB around = null;
        try {
            Settings.get().bodies.duration = new BodyDuration(1);
            OfflineGameTests.fillInventory(player);
            Mannequin body = leaveBody(helper, player);
            body.setItemSlot(EquipmentSlot.CHEST, damaged(Items.IRON_CHESTPLATE, 7));
            CompoundTag chest = unreadableChest(server, player);
            expiredLogout(helper, owner);
            around = new AABB(body.blockPosition()).inflate(2.0);
            Path file = saveFile(helper, owner);
            byte[] saveBefore = Files.readAllBytes(file);
            Path blocker = file.resolveSibling(owner + ".dat_old");
            Path blockerContent = blocker.resolve("blocker");
            Files.deleteIfExists(blocker);
            Files.createDirectories(blocker);
            Files.writeString(blockerContent, "");
            try {
                BodyExpiry.tick(server);
            } finally {
                Files.delete(blockerContent);
                Files.delete(blocker);
            }

            helper.assertFalse(body.isRemoved(), "body removed although its save failed");
            helper.assertTrue(store(helper).byOwner(owner).isPresent(), "record dropped although the save failed");
            helper.assertTrue(Arrays.equals(Files.readAllBytes(file), saveBefore), "owner's save changed");
            ItemStack worn = body.getItemBySlot(EquipmentSlot.CHEST);
            helper.assertTrue(worn.is(Items.IRON_CHESTPLATE) && worn.getDamageValue() == 7, "body's chestplate: " + worn);
            helper.assertValueEqual(chestplatesIn(helper, around).size(), 0, "chestplates dropped by the failed expiry");

            // A record out of the running for one pass loses its wait, so the retry doesn't wait out the minutes
            BodyStore store = store(helper);
            BodyRecord record = store.byOwner(owner).orElseThrow();
            store.put(record.withPendingRemoval(true));
            BodyExpiry.tick(server);
            store.put(record);
            BodyExpiry.tick(server);

            helper.assertTrue(body.isRemoved(), "body still in the world after the retry");
            helper.assertTrue(store.byOwner(owner).isEmpty(), "body record kept after the retry");
            List<ItemEntity> drops = chestplatesIn(helper, around);
            helper.assertValueEqual(drops.size(), 1, "chestplates dropped by the retry");
            helper.assertValueEqual(drops.get(0).getItem().getDamageValue(), 7, "dropped chestplate's damage");
            CompoundTag saved = OfflinePlayerData.load(server, player.nameAndId()).orElseThrow();
            helper.assertValueEqual(saved.getCompoundOrEmpty("equipment").get("chest"), chest, "saved chest entry");
            Map<Integer, ItemStack> stored = OfflineGameTests.savedInventory(helper, saved);
            helper.assertTrue(stored.size() == Inventory.INVENTORY_SIZE && stored.values().stream().allMatch(item -> item.is(Items.DIRT)),
                    "saved inventory: " + stored);
        } finally {
            if (around != null) {
                chestplatesIn(helper, around).forEach(Entity::discard);
            }
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    // An immovable body floats where its owner logged out, here with two blocks of air under it. The chestplate it
    // drops stays at its feet rather than falling, since lava or the void could be under it.
    @GameTest(environment = PlayerBodyGameTests.BODY_EXPIRY, maxTicks = 60)
    public void bodyItemOverAirStaysPut(GameTestHelper helper) {
        assertStaysPut(helper, dropAtExpiry(helper, "Floating", new BlockPos(1, 3, 1), BLOCK_CENTRE, false));
    }

    // A body stands on a fence top 0.1 from the post's side, so the body (0.6 wide) still overlaps the post while
    // the item (0.25 wide) misses it. The block under the body is the fence all the same, so only a check on the
    // item's own box keeps it from falling past the post.
    @GameTest(environment = PlayerBodyGameTests.BODY_EXPIRY, maxTicks = 60)
    public void bodyItemBesideFencePostStaysPut(GameTestHelper helper) {
        // A lone fence is only its post, which spans 0.375 to 0.625 of the block and stands 1.5 high
        helper.setBlock(new BlockPos(1, 2, 1), Blocks.OAK_FENCE);
        assertStaysPut(helper, dropAtExpiry(helper, "Railing", new BlockPos(1, 3, 1), new Vec3(0.1, 0.5, 0.5), true));
    }

    // On solid ground the item falls as vanilla's drops do, which only puts it on the floor
    @GameTest(environment = PlayerBodyGameTests.BODY_EXPIRY)
    public void bodyItemOverStoneKeepsGravity(GameTestHelper helper) {
        helper.setBlock(new BlockPos(1, 0, 1), Blocks.STONE);
        ItemEntity drop = dropAtExpiry(helper, "Grounded", new BlockPos(1, 1, 1), BLOCK_CENTRE, true);
        try {
            helper.assertFalse(drop.isNoGravity(), "the chestplate dropped on stone has no gravity");
        } finally {
            drop.discard();
        }
        helper.succeed();
    }

    // The dropped item has no gravity and is still where it dropped 50 ticks on. It never despawns, so it goes
    // either way.
    private static void assertStaysPut(GameTestHelper helper, ItemEntity drop) {
        Vec3 at = drop.position();
        boolean waiting = false;
        try {
            helper.assertTrue(drop.isNoGravity(), "the dropped chestplate has gravity at " + at);
            helper.runAfterDelay(50, () -> {
                try {
                    helper.assertTrue(drop.isAlive(), "the dropped chestplate is gone");
                    helper.assertTrue(drop.position().distanceTo(at) < 0.01, "the dropped chestplate moved from " + at
                            + " to " + drop.position());
                } finally {
                    drop.discard();
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                drop.discard();
            }
        }
    }

    // The no-free-slot expiry above, for a body left at inBlock, an offset from the corner of the block at. Returns
    // the one chestplate it dropped; the switches, the body and its record are gone again by then.
    private static ItemEntity dropAtExpiry(GameTestHelper helper, String name, BlockPos at, Vec3 inBlock, boolean movable) {
        ServerPlayer player = join(helper, name, at);
        // From the absolute corner: vanilla's relativePos would turn an unrotated test's position half round
        player.snapTo(Vec3.atLowerCornerOf(helper.absolutePos(at)).add(inBlock));
        UUID owner = player.getUUID();
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            Settings.get().bodies.duration = new BodyDuration(1);
            Settings.get().bodies.movable = movable;
            OfflineGameTests.fillInventory(player);
            Mannequin body = leaveBody(helper, player);
            body.setItemSlot(EquipmentSlot.CHEST, damaged(Items.IRON_CHESTPLATE, 7));
            unreadableChest(server, player);
            expiredLogout(helper, owner);
            AABB around = new AABB(body.blockPosition()).inflate(2.0);

            BodyExpiry.tick(server);
            helper.assertTrue(body.isRemoved(), "body still in the world");
            List<ItemEntity> drops = chestplatesIn(helper, around);
            helper.assertValueEqual(drops.size(), 1, "chestplates dropped at the body");
            return drops.get(0);
        } finally {
            cleanUp(helper, owner, before);
        }
    }

    private static List<ItemEntity> chestplatesIn(GameTestHelper helper, AABB area) {
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, area,
                drop -> drop.isAlive() && drop.getItem().is(Items.IRON_CHESTPLATE));
    }

    // Puts an entry that doesn't read back into the chest slot of the player's save, and returns it
    private static CompoundTag unreadableChest(MinecraftServer server, ServerPlayer player) {
        CompoundTag tag = OfflinePlayerData.load(server, player.nameAndId()).orElseThrow();
        CompoundTag chest = OfflineGameTests.unreadableItem();
        CompoundTag equipment = tag.getCompoundOrEmpty("equipment");
        equipment.put("chest", chest.copy());
        tag.put("equipment", equipment);
        OfflinePlayerData.save(server, player.getUUID(), tag);
        return chest;
    }

    private static ItemStack damaged(Item item, int damage) {
        ItemStack stack = new ItemStack(item);
        stack.setDamageValue(damage);
        return stack;
    }

    // The save only lists the attributes the player has used, so an effect gained on the body may need one it lacks
    @GameTest(environment = PlayerBodyGameTests.BODY_EXPIRY)
    public void effectGainedOnBodyKeepsItsModifier(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Weakened", new BlockPos(1, 1, 1));
        UUID owner = player.getUUID();
        MinecraftServer server = helper.getLevel().getServer();
        RegistryOps<Tag> ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            Settings.get().bodies.duration = new BodyDuration(1);
            Mannequin body = leaveBody(helper, player);
            CompoundTag lacking = OfflinePlayerData.load(server, player.nameAndId()).orElseThrow();
            lacking.store("attributes", AttributeInstance.Packed.LIST_CODEC, ops, attributes(helper, lacking).stream()
                    .filter(packed -> packed.attribute().value() != Attributes.ATTACK_DAMAGE.value())
                    .toList());
            OfflinePlayerData.save(server, owner, lacking);
            body.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 12000));
            expiredLogout(helper, owner);

            BodyExpiry.tick(server);
            helper.assertTrue(store(helper).byOwner(owner).isEmpty(), "body record kept");
            CompoundTag saved = OfflinePlayerData.load(server, player.nameAndId()).orElseThrow();
            Optional<AttributeInstance.Packed> attack = attributes(helper, saved).stream()
                    .filter(packed -> packed.attribute().value() == Attributes.ATTACK_DAMAGE.value())
                    .findFirst();
            helper.assertTrue(attack.isPresent(), "attack damage not saved");
            helper.assertValueEqual(attack.get().baseValue(), 1.0, "attack damage base");
            List<Identifier> weakness = new ArrayList<>();
            MobEffects.WEAKNESS.value().createModifiers(0, (attribute, modifier) -> weakness.add(modifier.id()));
            helper.assertTrue(attack.get().modifiers().stream().anyMatch(modifier -> weakness.contains(modifier.id())),
                    "weakness's modifier not saved");
        } finally {
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    // Only the offline-death routine edits a dead player's save, so the expiry leaves it exactly as it is
    @GameTest(environment = PlayerBodyGameTests.BODY_EXPIRY)
    public void expiryOfDeadSaveOnlyDropsRecord(GameTestHelper helper) throws IOException {
        ServerPlayer player = join(helper, "DeadSave", new BlockPos(1, 1, 1));
        UUID owner = player.getUUID();
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            Settings.get().bodies.duration = new BodyDuration(1);
            Mannequin body = leaveBody(helper, player);
            CompoundTag dead = OfflinePlayerData.load(server, player.nameAndId()).orElseThrow();
            dead.putFloat("Health", 0.0F);
            OfflinePlayerData.save(server, owner, dead);
            byte[] saveBefore = Files.readAllBytes(saveFile(helper, owner));
            // A snapshot written by mistake would bring this back
            body.setHealth(9.0F);
            expiredLogout(helper, owner);

            BodyExpiry.tick(server);
            helper.assertTrue(body.isRemoved(), "body still in the world");
            helper.assertTrue(store(helper).byOwner(owner).isEmpty(), "body record kept");
            helper.assertTrue(Arrays.equals(Files.readAllBytes(saveFile(helper, owner)), saveBefore), "dead owner's save changed");
        } finally {
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    // The same dead save, with a chestplate a dispenser put in the body's empty chest slot. That one is real and the
    // save isn't written, so it drops where the body stood, for good.
    @GameTest(environment = PlayerBodyGameTests.BODY_EXPIRY)
    public void expiryOfDeadSaveDropsDispensedGear(GameTestHelper helper) throws IOException {
        ServerPlayer player = join(helper, "DeadGear", new BlockPos(1, 1, 1));
        UUID owner = player.getUUID();
        String name = player.nameAndId().name();
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.BodiesSection before = useDefaultBodies();
        AABB around = null;
        try {
            Settings.get().bodies.duration = new BodyDuration(1);
            Mannequin body = leaveBody(helper, player);
            CompoundTag dead = OfflinePlayerData.load(server, player.nameAndId()).orElseThrow();
            dead.putFloat("Health", 0.0F);
            OfflinePlayerData.save(server, owner, dead);
            byte[] saveBefore = Files.readAllBytes(saveFile(helper, owner));
            body.setItemSlot(EquipmentSlot.CHEST, damaged(Items.IRON_CHESTPLATE, 7));
            expiredLogout(helper, owner);
            around = new AABB(body.blockPosition()).inflate(2.0);

            List<String> log = OfflineGameTests.yoltLog(() -> BodyExpiry.tick(server));
            helper.assertTrue(body.isRemoved(), "body still in the world");
            helper.assertTrue(store(helper).byOwner(owner).isEmpty(), "body record kept");
            helper.assertTrue(Arrays.equals(Files.readAllBytes(saveFile(helper, owner)), saveBefore), "dead owner's save changed");
            List<ItemEntity> drops = chestplatesIn(helper, around);
            helper.assertValueEqual(drops.size(), 1, "chestplates dropped at the body");
            helper.assertValueEqual(drops.get(0).getItem().getDamageValue(), 7, "dropped chestplate's damage");
            helper.assertValueEqual(drops.get(0).getAge(), (int) Short.MIN_VALUE, "dropped chestplate's age");
            String line = name + "'s body got an item in slot chest after they logged out, ";
            helper.assertValueEqual(log.stream().filter(entry -> entry.startsWith(line)).count(), 1L,
                    "warnings for the chestplate: " + log);
        } finally {
            if (around != null) {
                chestplatesIn(helper, around).forEach(Entity::discard);
            }
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    // The same, with the drop failing, here through a load listener that throws. The drop takes its item entity back,
    // so the warning, which holds the whole item, is all that is left of it for an admin.
    @GameTest(environment = PlayerBodyGameTests.BODY_EXPIRY)
    public void failedDropOfDispensedGearIsLogged(GameTestHelper helper) throws IOException {
        ServerPlayer player = join(helper, "NoDrop", new BlockPos(1, 1, 1));
        UUID owner = player.getUUID();
        String name = player.nameAndId().name();
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.BodiesSection before = useDefaultBodies();
        AABB around = null;
        try {
            Settings.get().bodies.duration = new BodyDuration(1);
            Mannequin body = leaveBody(helper, player);
            CompoundTag dead = OfflinePlayerData.load(server, player.nameAndId()).orElseThrow();
            dead.putFloat("Health", 0.0F);
            OfflinePlayerData.save(server, owner, dead);
            body.setItemSlot(EquipmentSlot.CHEST, damaged(Items.IRON_CHESTPLATE, 7));
            expiredLogout(helper, owner);
            around = new AABB(body.blockPosition()).inflate(2.0);

            List<String> log;
            failingDrop = Items.IRON_CHESTPLATE;
            try {
                log = OfflineGameTests.yoltLog(() -> BodyExpiry.tick(server));
            } finally {
                failingDrop = null;
            }
            helper.assertTrue(body.isRemoved(), "body still in the world");
            helper.assertTrue(store(helper).byOwner(owner).isEmpty(), "body record kept");
            helper.assertValueEqual(chestplatesIn(helper, around).size(), 0, "chestplates left by the failed drop");
            String start = name + "'s body got an item in slot chest after they logged out, and the body goes without them "
                    + "taking it over, ";
            helper.assertValueEqual(log.stream().filter(line -> line.startsWith(start)).toList(),
                    List.of(start + "but the item could not be dropped: "
                            + Stacks.saved(damaged(Items.IRON_CHESTPLATE, 7), server.registryAccess())),
                    "warnings for the chestplate");
        } finally {
            if (around != null) {
                chestplatesIn(helper, around).forEach(Entity::discard);
            }
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    // A body in a chunk that isn't loaded is only its record, so the save gets the record's state. Standing in for the
    // unload: the hook saves the body's state, then the body leaves the world.
    @GameTest(environment = PlayerBodyGameTests.BODY_EXPIRY)
    public void expiryOfUnloadedBodyUsesRecordSnapshot(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Unloaded", new BlockPos(1, 1, 1));
        UUID owner = player.getUUID();
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            Settings.get().bodies.duration = new BodyDuration(1);
            Mannequin body = leaveBody(helper, player);
            body.setHealth(7.0F);
            BodyChunks.onBodyUnload(body);
            body.discard();
            expiredLogout(helper, owner);

            BodyExpiry.tick(server);
            helper.assertTrue(store(helper).byOwner(owner).isEmpty(), "body record kept");
            CompoundTag saved = OfflinePlayerData.load(server, player.nameAndId()).orElseThrow();
            helper.assertValueEqual(saved.getFloatOr("Health", -1.0F), 7.0F, "saved health");
        } finally {
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    // An owner who is online isn't away, and their save is theirs to write. As after a login that couldn't remove the
    // record: it is put back, due.
    @GameTest(environment = PlayerBodyGameTests.BODY_EXPIRY)
    public void expirySkipsOnlineOwner(GameTestHelper helper) throws IOException {
        ServerPlayer player = join(helper, "Online", new BlockPos(1, 1, 1));
        UUID owner = player.getUUID();
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            Settings.get().bodies.duration = new BodyDuration(1);
            leaveBody(helper, player);
            BodyStore store = store(helper);
            BodyRecord record = store.byOwner(owner).orElseThrow();
            TestPlayers.rejoin(helper, player.getGameProfile(), GameType.SURVIVAL, new BlockPos(1, 1, 1));
            store.put(record);
            expiredLogout(helper, owner);
            byte[] saveBefore = Files.readAllBytes(saveFile(helper, owner));

            BodyExpiry.tick(server);
            helper.assertTrue(store.byOwner(owner).isPresent(), "an online owner's record was removed");
            helper.assertTrue(Arrays.equals(Files.readAllBytes(saveFile(helper, owner)), saveBefore), "an online owner's save changed");
        } finally {
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    // The death path owns a dying body, so the expiry leaves its record and the save alone. At 0 health, as a body is
    // while its death plays out.
    @GameTest(environment = PlayerBodyGameTests.BODY_EXPIRY)
    public void expirySkipsDyingBody(GameTestHelper helper) throws IOException {
        ServerPlayer player = join(helper, "Dying", new BlockPos(1, 1, 1));
        UUID owner = player.getUUID();
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            Settings.get().bodies.duration = new BodyDuration(1);
            Mannequin body = leaveBody(helper, player);
            expiredLogout(helper, owner);
            byte[] saveBefore = Files.readAllBytes(saveFile(helper, owner));
            body.setHealth(0.0F);
            helper.assertTrue(body.isDeadOrDying(), "the body is not dying");

            BodyExpiry.tick(server);
            helper.assertTrue(store(helper).byOwner(owner).isPresent(), "a dying body's record was removed");
            helper.assertFalse(body.isRemoved(), "a dying body was removed");
            helper.assertTrue(Arrays.equals(Files.readAllBytes(saveFile(helper, owner)), saveBefore), "the owner's save changed");
        } finally {
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    // Each failed try reloads the save, and vanilla warns and copies an unreadable one every time, so the next try waits
    @GameTest(environment = PlayerBodyGameTests.BODY_EXPIRY)
    public void failedExpiryWaitsBeforeRetrying(GameTestHelper helper) throws IOException {
        ServerPlayer player = join(helper, "Retry", new BlockPos(1, 1, 1));
        UUID owner = player.getUUID();
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            Settings.get().bodies.duration = new BodyDuration(1);
            Mannequin body = leaveBody(helper, player);
            expiredLogout(helper, owner);
            // With no save to load, the expiry fails
            Path file = saveFile(helper, owner);
            byte[] save = Files.readAllBytes(file);
            Files.delete(file);
            Files.deleteIfExists(file.resolveSibling(owner + ".dat_old"));
            try {
                BodyExpiry.tick(server);
            } finally {
                Files.write(file, save);
            }
            helper.assertFalse(body.isRemoved(), "body removed although the expiry failed");
            helper.assertTrue(store(helper).byOwner(owner).isPresent(), "record dropped although the expiry failed");

            // The save is back, but the next try isn't due yet
            BodyExpiry.tick(server);
            helper.assertFalse(body.isRemoved(), "failed body was tried again straight away");
            helper.assertTrue(store(helper).byOwner(owner).isPresent(), "failed body's record was tried again straight away");
        } finally {
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    // With no save to load, every try fails. Each failure names the wait before the next try, which doubles from 5
    // minutes up to a day, so vanilla doesn't copy an unreadable save every few minutes for good. Only the first carries
    // the stack trace. The clock is stepped by hand, a millisecond short of each try first.
    @GameTest(environment = BODY_RETRY)
    public void failedExpiryWaitDoublesUpToADay(GameTestHelper helper) throws IOException {
        ServerPlayer player = join(helper, "Backoff", new BlockPos(1, 1, 1));
        UUID owner = player.getUUID();
        String failed = "Could not remove the expired body of " + player.nameAndId().name() + "; ";
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.BodiesSection before = useDefaultBodies();
        Path file = saveFile(helper, owner);
        byte[] save = null;
        try {
            Settings.get().bodies.duration = new BodyDuration(1);
            Mannequin body = leaveBody(helper, player);
            expiredLogout(helper, owner);
            save = Files.readAllBytes(file);
            Files.delete(file);
            Files.deleteIfExists(file.resolveSibling(owner + ".dat_old"));

            long at = System.currentTimeMillis();
            boolean first = true;
            for (long wait : List.of(5L, 10L, 20L, 40L, 80L, 160L, 320L, 640L, 1280L, 1440L, 1440L)) {
                long tryAt = at;
                List<LogEvent> errors = OfflineGameTests.yoltEvents(() -> BodyExpiry.tick(server, tryAt)).stream()
                        .filter(event -> event.getMessage().getFormattedMessage().startsWith(failed))
                        .toList();
                helper.assertValueEqual(errors.stream().map(event -> event.getMessage().getFormattedMessage()).toList(),
                        List.of(failed + "it stays, and this is tried again in " + wait + " minutes"), "errors of the failed try");
                helper.assertValueEqual(errors.get(0).getThrown() != null, first,
                        "a stack trace on the error naming " + wait + " minutes");
                first = false;
                at += wait * 60_000L;
                long early = at - 1;
                List<String> waiting = OfflineGameTests.yoltLog(() -> BodyExpiry.tick(server, early));
                helper.assertTrue(waiting.stream().noneMatch(line -> line.startsWith(failed)),
                        "tried again before a wait of " + wait + " minutes was up");
            }

            Files.write(file, save);
            BodyExpiry.tick(server, at);
            helper.assertTrue(body.isRemoved(), "body still in the world once its save was back");
            helper.assertTrue(store(helper).byOwner(owner).isEmpty(), "body record kept once its save was back");
        } finally {
            if (save != null && !Files.exists(file)) {
                Files.write(file, save);
            }
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    private static List<AttributeInstance.Packed> attributes(GameTestHelper helper, CompoundTag saved) {
        RegistryOps<Tag> ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
        return saved.read("attributes", AttributeInstance.Packed.LIST_CODEC, ops).orElse(List.of());
    }

    private static List<AttributeModifier> speedModifiers(GameTestHelper helper, CompoundTag saved) {
        return attributes(helper, saved).stream()
                .filter(packed -> packed.attribute().value() == Attributes.MOVEMENT_SPEED.value())
                .flatMap(packed -> packed.modifiers().stream())
                .toList();
    }

    // Five blocks above a stone floor. The switches only matter while the body spawns, so they go back before the wait.
    private static void fallTest(GameTestHelper helper, String name, boolean movable) {
        helper.setBlock(new BlockPos(1, 0, 1), Blocks.STONE);
        ServerPlayer player = join(helper, name, new BlockPos(1, 6, 1));
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = useDefaultBodies();
        boolean waiting = false;
        try {
            Settings.get().bodies.movable = movable;
            Mannequin body = leaveBody(helper, player);
            double startY = body.getY();
            Settings.get().bodies = before;
            helper.runAfterDelay(40, () -> {
                try {
                    if (movable) {
                        helper.assertTrue(body.getY() < startY, "movable body did not fall");
                        helper.assertTrue(body.getHealth() < 20.0F, "body took no fall damage");
                        DamageSource hurtBy = body.getLastDamageSource();
                        helper.assertTrue(hurtBy != null && hurtBy.is(DamageTypes.FALL),
                                "body was last hurt by " + hurtBy + ", not a fall");
                    } else {
                        helper.assertValueEqual(body.getY(), startY, "immovable body's height");
                    }
                } finally {
                    removeBodies(helper, owner);
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

    private static ServerPlayer join(GameTestHelper helper, String name, BlockPos at) {
        return TestPlayers.join(helper, name, GameType.SURVIVAL, at);
    }

    // Never added to the world: a mob attack only needs someone to come from
    private static Zombie attacker(GameTestHelper helper) {
        return new Zombie(helper.getLevel());
    }

    // As if the owner logged out 61 seconds ago, so a one-minute body is due
    static void expiredLogout(GameTestHelper helper, UUID owner) {
        BodyStore store = store(helper);
        BodyRecord record = store.byOwner(owner).orElseThrow();
        store.put(new BodyRecord(record.owner(), record.ownerName(), record.bodyEntity(), record.type(),
                System.currentTimeMillis() - 61_000L, false, record.snapshot(), record.skin(), record.emptyAtLogout(),
                record.copiedEffects()));
    }

    private static Path saveFile(GameTestHelper helper, UUID owner) {
        return helper.getLevel().getServer().getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(owner + ".dat");
    }
}
