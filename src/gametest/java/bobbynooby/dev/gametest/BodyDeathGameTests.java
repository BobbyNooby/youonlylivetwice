package bobbynooby.dev.gametest;

import bobbynooby.dev.bodies.Bodies;
import bobbynooby.dev.bodies.BodyRecord;
import bobbynooby.dev.bodies.BodyStore;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.graves.Grave;
import bobbynooby.dev.graves.GraveItems;
import bobbynooby.dev.graves.GraveStore;
import bobbynooby.dev.lockout.DeathLockout;
import bobbynooby.dev.offline.OfflineKill;
import bobbynooby.dev.offline.OfflinePlayerData;
import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Like BodyLifecycleGameTests: each test turns the default body switches on and puts the server's own back in
// the same tick. Every logout is real, so the owner's save holds what they had.
public class BodyDeathGameTests {
    @GameTest
    public void killingBodyMakesGraveAndLocksOut(GameTestHelper helper) {
        ServerPlayer player = join(helper, "BodyDeath");
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            player.getInventory().add(new ItemStack(Items.DIAMOND));
            Mannequin body = leaveBody(helper, player);
            // Test players have no skin, so the body gets one the grave head has to keep
            body.setComponent(DataComponents.PROFILE, texturedProfile(player));
            BlockPos at = body.blockPosition();
            body.kill(helper.getLevel());

            List<Grave> graves = gravesOf(helper, owner);
            helper.assertValueEqual(graves.size(), 1, "graves for the owner");
            Grave grave = graves.get(0);
            helper.assertValueEqual(grave.pos(), at, "grave position");
            helper.assertValueEqual(grave.items().size(), 2, "grave item count");
            ItemStack head = grave.items().get(0);
            helper.assertTrue(GraveItems.isGraveHead(head), "first grave item is not the grave head");
            helper.assertTrue(grave.items().get(1).is(Items.DIAMOND), "second grave item is not the diamond");
            helper.assertTrue(hasSkin(head.get(DataComponents.PROFILE)), "grave head lost the body's skin");
            helper.assertTrue(hasSkin(grave.profile()), "grave lost the body's skin");

            CompoundTag saved = save(helper, player);
            helper.assertTrue(saved.getListOrEmpty("Inventory").isEmpty(), "saved inventory still has items");
            helper.assertValueEqual(saved.getFloatOr("Health", -1.0F), 0.0F, "saved health");
            helper.assertTrue(DeathLockout.refusal(owner) != null, "body death did not start the lockout");
            helper.assertTrue(store(helper).byOwner(owner).isEmpty(), "body record kept");
        } finally {
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    @GameTest
    public void yoltKillOfflineWithBodyKillsBody(GameTestHelper helper) {
        ServerPlayer player = join(helper, "KillCmd");
        UUID owner = player.getUUID();
        String name = player.getGameProfile().name();
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            player.getInventory().add(new ItemStack(Items.DIAMOND));
            Mannequin body = leaveBody(helper, player);
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "yolt kill " + name);

            helper.assertTrue(body.isDeadOrDying(), "body still alive");
            helper.assertValueEqual(countInGraves(gravesOf(helper, owner), Items.DIAMOND), 1, "diamonds in the graves");
            helper.assertTrue(gravesOf(helper, owner).stream().allMatch(grave -> grave.pos().closerThan(body.blockPosition(), 2.0)),
                    "grave not at the body");
            helper.assertTrue(store(helper).byOwner(owner).isEmpty(), "body record kept");
        } finally {
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    @GameTest(environment = LockoutGameTests.LOGIN_CHECK)
    public void loginAfterBodyDiedIsRefused(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Refused");
        UUID owner = player.getUUID();
        NameAndId who = player.nameAndId();
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            Mannequin body = leaveBody(helper, player);
            body.kill(helper.getLevel());
            helper.assertTrue(LockoutGameTests.refusedForLockout(LockoutGameTests.loginRefusal(helper.getLevel().getServer(), who)),
                    "owner not refused with the lockout message after their body died");
        } finally {
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    // The body wears copies of the saved gear, so anything it dropped would exist twice
    @GameTest
    public void bodyDeathDropsNothing(GameTestHelper helper) {
        ServerPlayer player = join(helper, "NoDrops");
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            // The main hand is the selected hotbar slot, so the diamond goes in after it
            player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            player.getInventory().add(new ItemStack(Items.DIAMOND));
            Mannequin body = leaveBody(helper, player);
            AABB around = new AABB(body.blockPosition()).inflate(4.0);
            body.kill(helper.getLevel());

            helper.assertTrue(helper.getLevel().getEntities(EntityTypes.ITEM, around, Entity::isAlive).isEmpty(), "items dropped");
            // The owner had no XP, so any orb would be the body's own
            helper.assertTrue(helper.getLevel().getEntities(EntityTypes.EXPERIENCE_ORB, around, Entity::isAlive).isEmpty(),
                    "XP dropped");
            List<Grave> graves = gravesOf(helper, owner);
            helper.assertValueEqual(countInGraves(graves, Items.DIAMOND), 1, "diamonds in the graves");
            helper.assertValueEqual(countInGraves(graves, Items.IRON_CHESTPLATE), 1, "chestplates in the graves");
            helper.assertValueEqual(countInGraves(graves, Items.IRON_SWORD), 1, "swords in the graves");
            List<ItemStack> saved = savedItems(helper, player);
            helper.assertValueEqual(count(saved, Items.DIAMOND), 0, "diamonds left in the save");
            helper.assertValueEqual(count(saved, Items.IRON_CHESTPLATE), 0, "chestplates left in the save");
        } finally {
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    // A pending record was taken over already, so its body is a stale copy and its death means nothing
    @GameTest
    public void staleBodyDeathKillsNoOne(GameTestHelper helper) throws IOException {
        ServerPlayer player = join(helper, "Stale");
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            player.getInventory().add(new ItemStack(Items.DIAMOND));
            Mannequin body = leaveBody(helper, player);
            BodyStore store = store(helper);
            store.put(store.byOwner(owner).orElseThrow().withPendingRemoval(true));
            byte[] saveBefore = Files.readAllBytes(saveFile(helper, owner));

            body.kill(helper.getLevel());
            helper.assertTrue(body.isDeadOrDying(), "body did not die");
            helper.assertTrue(gravesOf(helper, owner).isEmpty(), "stale body made a grave");
            helper.assertTrue(DeathLockout.refusal(owner) == null, "stale body started the lockout");
            helper.assertTrue(Arrays.equals(Files.readAllBytes(saveFile(helper, owner)), saveBefore), "owner's save changed");
        } finally {
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    // The real slot was empty, so only the body has the helmet; GearSync gives it to the save before the death
    @GameTest
    public void bodyGearReachesGrave(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Helmet");
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            Mannequin body = leaveBody(helper, player);
            body.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            AABB around = new AABB(body.blockPosition()).inflate(4.0);
            body.kill(helper.getLevel());

            helper.assertValueEqual(countInGraves(gravesOf(helper, owner), Items.IRON_HELMET), 1, "helmets in the graves");
            helper.assertTrue(helper.getLevel().getEntities(EntityTypes.ITEM, around, item -> item.getItem().is(Items.IRON_HELMET))
                    .isEmpty(), "helmet dropped");
            helper.assertValueEqual(count(savedItems(helper, player), Items.IRON_HELMET), 0, "helmets left in the save");
        } finally {
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    // Not loaded: the death uses the record's last saved state, and the record goes
    @GameTest
    public void yoltKillWithUnloadedBodyUsesSnapshot(GameTestHelper helper) {
        ServerPlayer player = join(helper, "KillUnloaded");
        UUID owner = player.getUUID();
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            player.getInventory().add(new ItemStack(Items.DIAMOND));
            Mannequin body = leaveBody(helper, player);
            body.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            // Stands in for the chunk unloading: the hook saves the body's state, then it leaves the world
            Bodies.onBodyUnload(body);
            BlockPos at = body.blockPosition();
            body.discard();

            helper.assertValueEqual(OfflineKill.kill(server, player.nameAndId()), OfflineKill.KillResult.KILLED_OFFLINE,
                    "kill result");
            List<Grave> graves = gravesOf(helper, owner);
            helper.assertValueEqual(countInGraves(graves, Items.DIAMOND), 1, "diamonds in the graves");
            helper.assertValueEqual(countInGraves(graves, Items.IRON_HELMET), 1, "helmets in the graves");
            helper.assertTrue(graves.stream().allMatch(grave -> grave.pos().closerThan(at, 2.0)), "grave not at the body");
            helper.assertTrue(DeathLockout.refusal(owner) != null, "kill did not start the lockout");
            helper.assertTrue(store(helper).byOwner(owner).isEmpty(), "body record kept");
        } finally {
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    // The totem the body used up is gone from the save too, so the grave can't hold it
    @GameTest
    public void poppedTotemNotInGrave(GameTestHelper helper) {
        ServerPlayer player = join(helper, "PoppedGrave");
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            player.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
            Mannequin body = leaveBody(helper, player);
            body.setHealth(2.0F);
            body.hurtServer(helper.getLevel(), helper.getLevel().damageSources().generic(), 10.0F);
            helper.assertFalse(body.isDeadOrDying(), "totem did not save the body");
            helper.assertTrue(body.getItemBySlot(EquipmentSlot.OFFHAND).isEmpty(), "totem not used up");
            body.kill(helper.getLevel());

            List<Grave> graves = gravesOf(helper, owner);
            helper.assertFalse(graves.isEmpty(), "body death made no grave");
            helper.assertValueEqual(countInGraves(graves, Items.TOTEM_OF_UNDYING), 0, "totems in the graves");
        } finally {
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    // An owner who is online isn't away, so a body left standing for them dies alone
    @GameTest
    public void bodyDeathWithOwnerOnlineKillsNoOne(GameTestHelper helper) {
        ServerPlayer player = join(helper, "OnlineOwner");
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            player.getInventory().add(new ItemStack(Items.DIAMOND));
            // The logout hook on its own, so the owner stays online with a body and a record
            Optional<Entity> made = Bodies.onLogout(player);
            helper.assertTrue(made.isPresent() && made.get() instanceof Mannequin, "no mannequin body");
            helper.assertTrue(store(helper).byOwner(owner).isPresent(), "no body record");
            Mannequin body = (Mannequin) made.get();
            body.kill(helper.getLevel());

            helper.assertTrue(body.isDeadOrDying(), "body did not die");
            helper.assertTrue(gravesOf(helper, owner).isEmpty(), "body death made a grave");
            helper.assertTrue(DeathLockout.refusal(owner) == null, "body death started the lockout");
            helper.assertFalse(player.isDeadOrDying(), "owner died");
            helper.assertValueEqual(player.getInventory().countItem(Items.DIAMOND), 1, "owner's diamonds");
        } finally {
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    // A wither's kill plants a rose, which would also take the block the grave goes on
    @GameTest
    public void witherKilledBodyLeavesNoRose(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Withered");
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            // A rose can grow on dirt, so vanilla would plant it on the body's block rather than drop it
            helper.setBlock(new BlockPos(1, 0, 1), Blocks.DIRT);
            Mannequin body = leaveBody(helper, player);
            BlockPos at = body.blockPosition();
            AABB around = new AABB(at).inflate(4.0);
            // Never added to the world: it only has to be who the kill is credited to
            WitherBoss wither = new WitherBoss(EntityTypes.WITHER, helper.getLevel());
            body.hurtServer(helper.getLevel(), helper.getLevel().damageSources().mobAttack(wither), 1000.0F);

            helper.assertTrue(body.isDeadOrDying(), "body did not die");
            helper.assertFalse(helper.getLevel().getBlockState(at).is(Blocks.WITHER_ROSE), "wither rose planted");
            helper.assertTrue(helper.getLevel().getEntities(EntityTypes.ITEM, around, item -> item.getItem().is(Items.WITHER_ROSE))
                    .isEmpty(), "wither rose dropped");
            helper.assertTrue(gravesOf(helper, owner).stream().anyMatch(grave -> grave.pos().equals(at)),
                    "grave not on the body's block");
        } finally {
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    // An admin can tag a real player by hand; their items are real, so they still go to their grave
    @GameTest
    public void taggedPlayerStillDrops(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Tagged");
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            player.addTag(Bodies.TAG);
            player.getInventory().add(new ItemStack(Items.DIAMOND));
            TestPlayers.markLoaded(player);
            player.kill(player.level());

            helper.assertTrue(player.isDeadOrDying(), "player did not die");
            helper.assertValueEqual(countInGraves(gravesOf(helper, owner), Items.DIAMOND), 1, "diamonds in the graves");
        } finally {
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    private static ServerPlayer join(GameTestHelper helper, String name) {
        // Something solid under the body, so its grave goes on the body's own block
        helper.setBlock(new BlockPos(1, 0, 1), Blocks.STONE);
        return TestPlayers.join(helper, name, GameType.SURVIVAL, new BlockPos(1, 1, 1));
    }

    // A fresh section has the default switches, with bodies on; cleanUp puts the returned one back
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

    private static ResolvableProfile texturedProfile(ServerPlayer player) {
        PropertyMap properties = new PropertyMap(ImmutableMultimap.of("textures", new Property("textures", "dGVzdA==")));
        return ResolvableProfile.createResolved(new GameProfile(player.getUUID(), player.getGameProfile().name(), properties));
    }

    private static boolean hasSkin(ResolvableProfile profile) {
        return profile != null && profile.partialProfile().properties().values().stream()
                .anyMatch(property -> property.name().equals("textures"));
    }

    private static CompoundTag save(GameTestHelper helper, ServerPlayer player) {
        return OfflinePlayerData.load(helper.getLevel().getServer(), player.nameAndId()).orElseThrow();
    }

    // Main inventory, armour and offhand as saved
    private static List<ItemStack> savedItems(GameTestHelper helper, ServerPlayer player) {
        return OfflinePlayerData.takeInventory(save(helper, player), helper.getLevel().registryAccess());
    }

    private static Path saveFile(GameTestHelper helper, UUID owner) {
        return helper.getLevel().getServer().getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(owner + ".dat");
    }

    private static int count(List<ItemStack> items, Item item) {
        return items.stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }

    private static int countInGraves(List<Grave> graves, Item item) {
        int total = 0;
        for (Grave grave : graves) {
            total += count(grave.items(), item);
        }
        return total;
    }

    private static BodyStore store(GameTestHelper helper) {
        return BodyStore.get(helper.getLevel().getServer());
    }

    private static List<Grave> gravesOf(GameTestHelper helper, UUID owner) {
        return GraveStore.get(helper.getLevel().getServer()).all().stream()
                .filter(grave -> grave.owner().equals(owner))
                .toList();
    }

    // The switches go back first, so a player still online leaves without making another body.
    // Bodies, records and graves left behind would sit in another test's way.
    private static void cleanUp(GameTestHelper helper, UUID owner, YoltConfig.BodiesSection before) {
        Settings.get().bodies = before;
        ServerPlayer online = helper.getLevel().getServer().getPlayerList().getPlayer(owner);
        if (online != null) {
            TestPlayers.leave(online);
        }
        helper.getLevel().getEntities(EntityTypes.MANNEQUIN, body -> !body.isRemoved() && body.entityTags().contains(Bodies.TAG)
                && owner.equals(body.getProfile().partialProfile().id())).forEach(Entity::discard);
        store(helper).remove(owner);
        GraveStore graves = GraveStore.get(helper.getLevel().getServer());
        for (Grave grave : gravesOf(helper, owner)) {
            graves.remove(grave.id());
            helper.getLevel().setBlock(grave.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }
}
