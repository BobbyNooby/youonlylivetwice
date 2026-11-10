package bobbynooby.dev.gametest;

import bobbynooby.dev.bodies.Bodies;
import bobbynooby.dev.bodies.BodyChunks;
import bobbynooby.dev.bodies.BodyLogout;
import bobbynooby.dev.bodies.BodyStore;
import bobbynooby.dev.bodies.Kills;
import bobbynooby.dev.bodies.PlayerBody;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.graves.Grave;
import bobbynooby.dev.graves.GraveItems;
import bobbynooby.dev.lockout.DeathLockout;
import bobbynooby.dev.offline.OfflinePlayerData;
import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static bobbynooby.dev.gametest.BodyTestKit.cleanUp;
import static bobbynooby.dev.gametest.BodyTestKit.gravesOf;
import static bobbynooby.dev.gametest.BodyTestKit.leaveBody;
import static bobbynooby.dev.gametest.BodyTestKit.store;
import static bobbynooby.dev.gametest.BodyTestKit.useDefaultBodies;

// Like BodyLifecycleGameTests: each test turns the default body switches on and puts the server's own back in
// the same tick. Every logout is real, so the owner's save holds what they had.
public class BodyDeathGameTests {
    // A batch of its own: the test turns show_death_messages off, which every death in the batch would read
    private static final String DEATH_MESSAGES = "you-only-live-twice-gametest:death_messages";
    // In the test datapack: adds 5 XP to any kill made with it in the main hand
    private static final ResourceKey<Enchantment> MOB_EXPERIENCE = ResourceKey.create(Registries.ENCHANTMENT,
            Identifier.fromNamespaceAndPath("you-only-live-twice-gametest", "mob_experience"));

    // With death messages hidden the death screen is empty, so the lockout names no killer either: not for a player,
    // and not for a body. The first pair, with the rule on, shows the killer does come through otherwise.
    @GameTest(environment = DEATH_MESSAGES)
    public void hiddenDeathMessagesNameNoKiller(GameTestHelper helper) {
        GameRules rules = helper.getLevel().getGameRules();
        MinecraftServer server = helper.getLevel().getServer();
        boolean ruleBefore = rules.get(GameRules.SHOW_DEATH_MESSAGES);
        Zombie killer = helper.spawnWithNoFreeWill(EntityTypes.ZOMBIE, new BlockPos(3, 1, 3));
        killer.setCustomName(Component.literal("Slayer"));
        try {
            rules.set(GameRules.SHOW_DEATH_MESSAGES, true, server);
            String player = killedOnline(helper, "Shown", killer);
            helper.assertTrue(player.startsWith("You died: Shown") && player.contains(" was slain by Slayer\nTry again in "),
                    "player's lockout with the rule on: " + player);
            String body = bodyKilled(helper, "ShownBody", killer);
            helper.assertTrue(body.startsWith("You died: Killed while away (ShownBody")
                    && body.contains(" was slain by Slayer)\nTry again in "), "body's lockout with the rule on: " + body);

            rules.set(GameRules.SHOW_DEATH_MESSAGES, false, server);
            player = killedOnline(helper, "Hidden", killer);
            helper.assertTrue(player.startsWith("You died.\nTry again in ") && !player.contains("Slayer")
                    && !player.contains("was slain"), "player's lockout with the rule off: " + player);
            body = bodyKilled(helper, "HiddenBody", killer);
            helper.assertTrue(body.startsWith("You died: Killed while away\nTry again in ") && !body.contains("Slayer")
                    && !body.contains("was slain"), "body's lockout with the rule off: " + body);
        } finally {
            rules.set(GameRules.SHOW_DEATH_MESSAGES, ruleBefore, server);
            killer.discard();
        }
        helper.succeed();
    }

    // Killed by the killer while online; returns what the lockout tells them. The dead player leaves before the kick.
    private static String killedOnline(GameTestHelper helper, String name, Zombie killer) {
        ServerPlayer player = join(helper, name);
        UUID owner = player.getUUID();
        try {
            TestPlayers.markLoaded(player);
            player.hurtServer(helper.getLevel(), helper.getLevel().damageSources().mobAttack(killer), Float.MAX_VALUE);
            helper.assertTrue(player.isDeadOrDying(), name + " did not die");
            return LockoutGameTests.refusalText(owner);
        } finally {
            cleanUp(helper, owner);
        }
    }

    // The player leaves a mannequin body, which the killer kills; returns what the lockout tells its owner
    private static String bodyKilled(GameTestHelper helper, String name, Zombie killer) {
        ServerPlayer player = join(helper, name);
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            Mannequin body = leaveBody(helper, player);
            body.hurtServer(helper.getLevel(), helper.getLevel().damageSources().mobAttack(killer), Float.MAX_VALUE);
            helper.assertTrue(body.isDeadOrDying(), name + "'s body did not die");
            return LockoutGameTests.refusalText(owner);
        } finally {
            cleanUp(helper, owner, before);
        }
    }

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
            helper.assertTrue(ItemStack.matches(grave.items().get(1), new ItemStack(Items.DIAMOND)),
                    "second grave item: " + grave.items().get(1));
            helper.assertTrue(hasSkin(head.get(DataComponents.PROFILE)), "grave head lost the body's skin");
            helper.assertTrue(hasSkin(grave.profile()), "grave lost the body's skin");

            CompoundTag saved = save(helper, player);
            helper.assertTrue(saved.getListOrEmpty("Inventory").isEmpty(), "saved inventory still has items");
            helper.assertValueEqual(saved.getFloatOr("Health", -1.0F), 0.0F, "saved health");
            // A mannequin's own death message would name the mannequin, so it is told as the owner's
            String refusal = LockoutGameTests.refusalText(owner);
            helper.assertTrue(refusal.startsWith("You died: Killed while away (" + player.getGameProfile().name() + " was killed)\n"),
                    "body death's lockout: " + refusal);
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
            helper.assertValueEqual(GraveAsserts.countInGraves(server, owner, Items.DIAMOND), 1, "diamonds in the graves");
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

    // The body wears copies of the saved gear, so anything it dropped would exist twice. Vanilla's mannequin drops
    // nothing and has no XP, so the test datapack gives mannequins a diamond, and the killer's sword an enchantment that
    // gives XP for any kill. A plain mannequin dies first to show both are there.
    @GameTest
    public void bodyDeathDropsNothing(GameTestHelper helper) {
        ServerPlayer player = join(helper, "NoDrops");
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = useDefaultBodies();
        Mannequin plain = null;
        ServerPlayer killer = null;
        try {
            killer = TestPlayers.join(helper, "XpSlayer", GameType.SURVIVAL, new BlockPos(3, 1, 3));
            ItemStack sword = new ItemStack(Items.IRON_SWORD);
            sword.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(MOB_EXPERIENCE),
                    1);
            killer.setItemSlot(EquipmentSlot.MAINHAND, sword);
            // Made by a player, so the XP counts, and past any armour, so the body's chestplate doesn't wear
            DamageSource kill = new DamageSource(helper.getLevel().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE)
                    .getOrThrow(DamageTypes.GENERIC_KILL), killer);

            plain = helper.spawn(EntityTypes.MANNEQUIN, new BlockPos(5, 1, 5));
            AABB aroundPlain = new AABB(plain.blockPosition()).inflate(2.0);
            plain.hurtServer(helper.getLevel(), kill, Float.MAX_VALUE);
            helper.assertTrue(plain.isDeadOrDying(), "the plain mannequin did not die");
            List<ItemEntity> loot = helper.getLevel().getEntities(EntityTypes.ITEM, aroundPlain, Entity::isAlive);
            helper.assertValueEqual(loot.size(), 1, "item entities the plain mannequin dropped");
            helper.assertTrue(ItemStack.matches(loot.get(0).getItem(), new ItemStack(Items.DIAMOND)),
                    "the plain mannequin's drop: " + loot.get(0).getItem());
            List<ExperienceOrb> orbs = helper.getLevel().getEntities(EntityTypes.EXPERIENCE_ORB, aroundPlain, Entity::isAlive);
            helper.assertFalse(orbs.isEmpty(), "the plain mannequin dropped no XP");
            // Gone before the body dies, so the checks below see only the body's drops
            loot.forEach(Entity::discard);
            orbs.forEach(Entity::discard);

            player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            // The main hand is the selected hotbar slot, so the diamond goes in after it
            player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            player.getInventory().add(new ItemStack(Items.DIAMOND));
            Mannequin body = leaveBody(helper, player);
            AABB around = new AABB(body.blockPosition()).inflate(4.0);
            body.hurtServer(helper.getLevel(), kill, Float.MAX_VALUE);
            helper.assertTrue(body.isDeadOrDying(), "body did not die");

            helper.assertTrue(helper.getLevel().getEntities(EntityTypes.ITEM, around, Entity::isAlive).isEmpty(), "items dropped");
            // The owner had no XP, so any orb would be the body's own
            helper.assertTrue(helper.getLevel().getEntities(EntityTypes.EXPERIENCE_ORB, around, Entity::isAlive).isEmpty(),
                    "XP dropped");
            List<Grave> graves = gravesOf(helper, owner);
            helper.assertValueEqual(GraveAsserts.countInGraves(graves, Items.DIAMOND), 1, "diamonds in the graves");
            helper.assertValueEqual(GraveAsserts.countInGraves(graves, Items.IRON_CHESTPLATE), 1, "chestplates in the graves");
            helper.assertValueEqual(GraveAsserts.countInGraves(graves, Items.IRON_SWORD), 1, "swords in the graves");
            List<ItemStack> saved = savedItems(helper, player);
            helper.assertValueEqual(GraveAsserts.count(saved, Items.DIAMOND), 0, "diamonds left in the save");
            helper.assertValueEqual(GraveAsserts.count(saved, Items.IRON_CHESTPLATE), 0, "chestplates left in the save");
        } finally {
            if (plain != null) {
                AABB aroundPlain = new AABB(plain.blockPosition()).inflate(2.0);
                helper.getLevel().getEntities(EntityTypes.ITEM, aroundPlain, Entity::isAlive).forEach(Entity::discard);
                helper.getLevel().getEntities(EntityTypes.EXPERIENCE_ORB, aroundPlain, Entity::isAlive).forEach(Entity::discard);
                plain.discard();
            }
            try {
                cleanUp(helper, owner, before);
            } finally {
                // After the switches went back, so this logout leaves no body
                if (killer != null) {
                    TestPlayers.leave(killer);
                }
            }
        }
        helper.succeed();
    }

    // Vanilla /kill leaves both kinds of body standing and says how many it left, in one line; what else it hit dies,
    // and only that is counted. The command runs from a spot under 2 blocks from all three, and far from any other
    // test's entities. Every switch goes back in the same tick.
    @GameTest
    public void vanillaKillSkipsBodiesWithHint(GameTestHelper helper) {
        ServerPlayer first = join(helper, "KillSkipM");
        helper.setBlock(new BlockPos(3, 0, 1), Blocks.STONE);
        ServerPlayer second = TestPlayers.join(helper, "KillSkipP", GameType.SURVIVAL, new BlockPos(3, 1, 1));
        UUID mannequinOwner = first.getUUID();
        UUID playerBodyOwner = second.getUUID();
        YoltConfig.BodiesSection before = useDefaultBodies();
        Zombie zombie = helper.spawnWithNoFreeWill(EntityTypes.ZOMBIE, new BlockPos(1, 1, 3));
        try {
            helper.assertTrue(Settings.get().bodies.ignoreVanillaKill, "vanilla /kill kills bodies by default");
            Mannequin mannequin = leaveBody(helper, first);
            Settings.get().bodies.type = YoltConfig.BodyType.PLAYER;
            PlayerBody playerBody = BodyTestKit.leavePlayerBody(helper, second);

            helper.assertValueEqual(killFrom(helper, new BlockPos(2, 1, 2), "kill @e[distance=..2.5]"),
                    List.of("Killed " + zombie.getDisplayName().getString(),
                            "Skipped 2 offline player bodies. Use /yolt kill <player> to kill one."), "output of /kill");
            helper.assertTrue(zombie.isDeadOrDying(), "the zombie /kill hit is alive");
            helper.assertFalse(mannequin.isDeadOrDying() || mannequin.isRemoved(), "the mannequin body died");
            helper.assertFalse(playerBody.isDeadOrDying() || playerBody.isRemoved(), "the player body died");

            // A body alone: nothing dies, and the line says "body"
            helper.assertValueEqual(killFrom(helper, new BlockPos(1, 1, 1), "kill @e[distance=..0.5]"),
                    List.of("Killed 0 entities", "Skipped 1 offline player body. Use /yolt kill <player> to kill one."),
                    "output of /kill for one body");
            helper.assertFalse(mannequin.isDeadOrDying() || mannequin.isRemoved(), "the mannequin body died to the second /kill");
            for (UUID owner : List.of(mannequinOwner, playerBodyOwner)) {
                helper.assertTrue(LockoutGameTests.refusalText(owner).isEmpty(), "an owner was locked out");
                helper.assertTrue(gravesOf(helper, owner).isEmpty(), "an owner got a grave");
                helper.assertTrue(store(helper).byOwner(owner).isPresent(), "a body record went");
            }
        } finally {
            zombie.discard();
            cleanUp(helper, mannequinOwner, before);
            BodyTestKit.removeBodies(helper, playerBodyOwner);
            BodyTestKit.removeGraves(helper, playerBodyOwner);
        }
        helper.succeed();
    }

    // With the switch off, vanilla /kill kills a body like anything else, and its owner dies with it
    @GameTest
    public void vanillaKillSwitchOffKillsBodies(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = join(helper, "KillOff");
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            player.getInventory().add(new ItemStack(Items.DIAMOND));
            Mannequin body = leaveBody(helper, player);
            Settings.get().bodies.ignoreVanillaKill = false;
            helper.assertValueEqual(killFrom(helper, new BlockPos(1, 1, 1), "kill @e[distance=..0.5]"),
                    List.of("Killed " + body.getDisplayName().getString()), "output of /kill");
            helper.assertTrue(body.isDeadOrDying(), "the body /kill hit is alive");
            helper.assertValueEqual(GraveAsserts.countInGraves(server, owner, Items.DIAMOND), 1, "diamonds in the graves");
            helper.assertTrue(LockoutGameTests.refusalText(owner).startsWith("You died"), "the owner was not locked out");
        } finally {
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    // Only the command leaves bodies alone: a body below the world still dies of the void, and its owner with it. The
    // switches go back before the wait, since a body's death counts with bodies off too. At 1 health the first tick of
    // void damage kills it, whatever the damage cooldown.
    @GameTest(maxTicks = 20)
    public void voidStillKillsBody(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = join(helper, "VoidBody");
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = useDefaultBodies();
        Mannequin body;
        try {
            player.getInventory().add(new ItemStack(Items.DIAMOND));
            body = leaveBody(helper, player);
        } finally {
            Settings.get().bodies = before;
        }
        boolean waiting = false;
        try {
            helper.assertTrue(Settings.get().bodies.ignoreVanillaKill, "vanilla /kill kills bodies");
            body.setHealth(1.0F);
            body.teleportTo(body.getX(), helper.getLevel().getMinY() - 70, body.getZ());
            helper.runAfterDelay(10, () -> {
                try {
                    helper.assertTrue(body.isDeadOrDying(), "the body is still alive below the world");
                    helper.assertValueEqual(GraveAsserts.countInGraves(server, owner, Items.DIAMOND), 1, "diamonds in the graves");
                    helper.assertTrue(LockoutGameTests.refusalText(owner).startsWith("You died"), "the owner was not locked out");
                } finally {
                    cleanUp(helper, owner);
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                cleanUp(helper, owner);
            }
        }
    }

    // Runs a command as the console from the bottom middle of a block of the test, and returns what it said back
    private static List<String> killFrom(GameTestHelper helper, BlockPos relativePos, String command) {
        return SkinGameTests.console(helper.getLevel().getServer().createCommandSourceStack().withLevel(helper.getLevel())
                .withPosition(Vec3.atBottomCenterOf(helper.absolutePos(relativePos))), command);
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
            helper.assertTrue(DeathLockout.refusal(owner, "Test") == null, "stale body started the lockout");
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

            helper.assertValueEqual(GraveAsserts.countInGraves(helper.getLevel().getServer(), owner, Items.IRON_HELMET), 1,
                    "helmets in the graves");
            helper.assertTrue(helper.getLevel().getEntities(EntityTypes.ITEM, around, item -> item.getItem().is(Items.IRON_HELMET))
                    .isEmpty(), "helmet dropped");
            helper.assertValueEqual(GraveAsserts.count(savedItems(helper, player), Items.IRON_HELMET), 0,
                    "helmets left in the save");
        } finally {
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    // The save's chest entry doesn't read back and every inventory slot is taken, while a dispenser put a chestplate
    // into the body's empty chest slot. The chestplate can't go over the entry or into the save, so it joins the grave.
    @GameTest
    public void bodyItemWithNoFreeSlotGoesToGrave(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Crowded");
        UUID owner = player.getUUID();
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            OfflineGameTests.fillInventory(player);
            Mannequin body = leaveBody(helper, player);
            ItemStack dispensed = new ItemStack(Items.IRON_CHESTPLATE);
            dispensed.setDamageValue(7);
            body.setItemSlot(EquipmentSlot.CHEST, dispensed);
            CompoundTag tag = save(helper, player);
            CompoundTag chest = OfflineGameTests.unreadableItem();
            CompoundTag equipment = tag.getCompoundOrEmpty("equipment");
            equipment.put("chest", chest.copy());
            tag.put("equipment", equipment);
            OfflinePlayerData.save(server, owner, tag);
            body.kill(helper.getLevel());

            List<Grave> graves = gravesOf(helper, owner);
            helper.assertValueEqual(GraveAsserts.countInGraves(graves, Items.IRON_CHESTPLATE), 1, "chestplates in the graves");
            helper.assertTrue(graves.stream().flatMap(grave -> grave.items().stream())
                    .anyMatch(item -> item.is(Items.IRON_CHESTPLATE) && item.getDamageValue() == 7), "no grave holds the chestplate");
            helper.assertValueEqual(GraveAsserts.countInGraves(graves, Items.DIRT), Inventory.INVENTORY_SIZE, "dirt in the graves");
            helper.assertValueEqual(save(helper, player).getCompoundOrEmpty("equipment").get("chest"), chest, "saved chest entry");
            helper.assertValueEqual(GraveAsserts.count(savedItems(helper, player), Items.IRON_CHESTPLATE), 0,
                    "chestplates left in the save");
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
            BodyChunks.onBodyUnload(body);
            BlockPos at = body.blockPosition();
            body.discard();

            helper.assertValueEqual(Kills.kill(server, player.nameAndId()), Kills.Result.KILLED_OFFLINE,
                    "kill result");
            List<Grave> graves = gravesOf(helper, owner);
            helper.assertValueEqual(GraveAsserts.countInGraves(graves, Items.DIAMOND), 1, "diamonds in the graves");
            helper.assertValueEqual(GraveAsserts.countInGraves(graves, Items.IRON_HELMET), 1, "helmets in the graves");
            helper.assertTrue(graves.stream().allMatch(grave -> grave.pos().closerThan(at, 2.0)), "grave not at the body");
            helper.assertTrue(DeathLockout.refusal(owner, "Test") != null, "kill did not start the lockout");
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
            helper.assertValueEqual(GraveAsserts.countInGraves(graves, Items.TOTEM_OF_UNDYING), 0, "totems in the graves");
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
            Optional<Entity> made = BodyLogout.onLogout(player);
            helper.assertTrue(made.isPresent() && made.get() instanceof Mannequin, "no mannequin body");
            helper.assertTrue(store(helper).byOwner(owner).isPresent(), "no body record");
            Mannequin body = (Mannequin) made.get();
            body.kill(helper.getLevel());

            helper.assertTrue(body.isDeadOrDying(), "body did not die");
            helper.assertTrue(gravesOf(helper, owner).isEmpty(), "body death made a grave");
            helper.assertTrue(DeathLockout.refusal(owner, "Test") == null, "body death started the lockout");
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
            helper.assertValueEqual(GraveAsserts.countInGraves(helper.getLevel().getServer(), owner, Items.DIAMOND), 1,
                    "diamonds in the graves");
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
        return OfflinePlayerData.takeInventory(save(helper, player), helper.getLevel().registryAccess(), player.nameAndId().name());
    }

    private static Path saveFile(GameTestHelper helper, UUID owner) {
        return helper.getLevel().getServer().getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(owner + ".dat");
    }
}
