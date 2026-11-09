package bobbynooby.dev.gametest;

import bobbynooby.dev.bodies.Bodies;
import bobbynooby.dev.bodies.BodyExpiry;
import bobbynooby.dev.bodies.BodyRecord;
import bobbynooby.dev.bodies.BodyRules;
import bobbynooby.dev.bodies.BodyStore;
import bobbynooby.dev.config.BodyDuration;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.offline.OfflinePlayerData;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
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
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Like the other body tests: each one runs with the default body switches (bodies on) and puts the server's own
// back in the same tick. The two that wait only need the switches while the body spawns.
public class BodyBehaviourGameTests {
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

    @GameTest
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
            ItemStack chest = OfflinePlayerData.readGear(saved, server.registryAccess()).getOrDefault(EquipmentSlot.CHEST, ItemStack.EMPTY);
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

    // The save only lists the attributes the player has used, so an effect gained on the body may need one it lacks
    @GameTest
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
    @GameTest
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

    // Each failed try reloads the save, and vanilla warns and copies an unreadable one every time, so the next try waits
    @GameTest
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
                    } else {
                        helper.assertValueEqual(body.getY(), startY, "immovable body's height");
                    }
                } finally {
                    removeBody(helper, owner);
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

    // A fresh section has the default switches, with bodies on; the caller puts the returned one back
    private static YoltConfig.BodiesSection useDefaultBodies() {
        YoltConfig config = Settings.get();
        YoltConfig.BodiesSection before = config.bodies;
        config.bodies = new YoltConfig.BodiesSection();
        return before;
    }

    // A real logout: leaving fires the hook and saves the player
    private static Mannequin leaveBody(GameTestHelper helper, ServerPlayer player) {
        TestPlayers.leave(player);
        Optional<BodyRecord> record = store(helper).byOwner(player.getUUID());
        helper.assertTrue(record.isPresent(), "no body record");
        Entity entity = helper.getLevel().getEntity(record.get().bodyEntity());
        helper.assertTrue(entity instanceof Mannequin, "no mannequin for the record");
        return (Mannequin) entity;
    }

    // Never added to the world: a mob attack only needs someone to come from
    private static Zombie attacker(GameTestHelper helper) {
        return new Zombie(helper.getLevel());
    }

    // As if the owner logged out 61 seconds ago, so a one-minute body is due
    private static void expiredLogout(GameTestHelper helper, UUID owner) {
        BodyStore store = store(helper);
        BodyRecord record = store.byOwner(owner).orElseThrow();
        store.put(new BodyRecord(record.owner(), record.ownerName(), record.bodyEntity(), record.type(),
                System.currentTimeMillis() - 61_000L, false, record.snapshot()));
    }

    private static Path saveFile(GameTestHelper helper, UUID owner) {
        return helper.getLevel().getServer().getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(owner + ".dat");
    }

    private static BodyStore store(GameTestHelper helper) {
        return BodyStore.get(helper.getLevel().getServer());
    }

    // The switches go back first, so a player still online leaves without making another body
    private static void cleanUp(GameTestHelper helper, UUID owner, YoltConfig.BodiesSection before) {
        Settings.get().bodies = before;
        ServerPlayer online = helper.getLevel().getServer().getPlayerList().getPlayer(owner);
        if (online != null) {
            TestPlayers.leave(online);
        }
        removeBody(helper, owner);
    }

    // A body or record left behind would sit in another test's way
    private static void removeBody(GameTestHelper helper, UUID owner) {
        helper.getLevel().getEntities(EntityTypes.MANNEQUIN, body -> !body.isRemoved() && body.entityTags().contains(Bodies.TAG)
                && owner.equals(body.getProfile().partialProfile().id())).forEach(Entity::discard);
        store(helper).remove(owner);
    }
}
