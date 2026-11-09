package bobbynooby.dev.gametest;

import bobbynooby.dev.YouOnlyLiveTwice;
import bobbynooby.dev.bodies.Bodies;
import bobbynooby.dev.bodies.BodyExpiry;
import bobbynooby.dev.bodies.BodyRecord;
import bobbynooby.dev.bodies.BodyStore;
import bobbynooby.dev.bodies.PlayerBody;
import bobbynooby.dev.config.BodyDuration;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.graves.Grave;
import bobbynooby.dev.graves.GraveItems;
import bobbynooby.dev.graves.GraveStore;
import bobbynooby.dev.lockout.DeathLockout;
import bobbynooby.dev.offline.OfflinePlayerData;
import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.SleepStatus;
import net.minecraft.server.waypoints.ServerWaypointManager;
import net.minecraft.stats.Stats;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

// Like the other body tests: each one turns on the default body switches with the player type and puts the server's
// own back in the same tick. A player body is a player to every mob, so a test that keeps one in the world across
// ticks runs in a batch of its own, away from the mob tests. So does a test that saves or restores every body.
public class PlayerBodyGameTests {
    private static final String PLAYER_BODY = "you-only-live-twice-gametest:player_body";
    // For tests that save or bring back every body on the server, which would rewrite the records of other tests
    static final String BODY_RESTART = "you-only-live-twice-gametest:body_restart";
    private static final BlockPos ON_FLOOR = new BlockPos(1, 1, 1);

    @GameTest
    public void playerBodySpawnsWhenTypeIsPlayer(GameTestHelper helper) {
        ServerPlayer player = join(helper, "PlayerBody", ON_FLOOR);
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = usePlayerBodies();
        try {
            player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            player.setHealth(14.0F);
            // The logout hook on its own, so what it returns can be checked
            Optional<Entity> made = Bodies.onLogout(player);
            helper.assertTrue(made.isPresent() && made.get() instanceof PlayerBody, "no player body");
            PlayerBody body = (PlayerBody) made.get();

            Optional<BodyRecord> record = store(helper).byOwner(owner);
            helper.assertTrue(record.isPresent(), "no body record");
            helper.assertValueEqual(record.get().type(), YoltConfig.BodyType.PLAYER, "body type");
            helper.assertValueEqual(record.get().bodyEntity(), body.getUUID(), "record's body");
            helper.assertFalse(body.getUUID().equals(owner), "body has its owner's id");
            helper.assertValueEqual(body.owner(), owner, "body's owner");
            helper.assertValueEqual(body.getGameProfile().name(), player.getGameProfile().name(), "body's name");
            helper.assertTrue(Bodies.isBody(body), "not recognised as a body");
            helper.assertValueEqual(body.getPose(), Pose.SWIMMING, "pose");
            helper.assertValueEqual(body.position(), player.position(), "position");
            helper.assertTrue(body.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE), "chestplate not copied");
            helper.assertTrue(body.getItemBySlot(EquipmentSlot.MAINHAND).is(Items.IRON_SWORD), "sword not copied");
            helper.assertValueEqual(body.getHealth(), 14.0F, "health");
            helper.assertValueEqual(body.gameMode(), GameType.SURVIVAL, "game mode");
            PlayerTeam team = helper.getLevel().getScoreboard().getPlayerTeam(YouOnlyLiveTwice.TEAM_NAME);
            helper.assertTrue(team != null && body.getTeam() == team, "body not in the nametag team");
        } finally {
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    // Spec 11.2 only inferred this; if it fails, the player type doesn't work without the player list
    @GameTest
    public void playerBodyCountsForSpawningButNotOnline(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Spawner", ON_FLOOR);
        UUID owner = player.getUUID();
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.BodiesSection before = usePlayerBodies();
        try {
            PlayerBody body = leaveBody(helper, player);
            ServerLevel level = helper.getLevel();
            helper.assertTrue(level.getChunkSource().chunkMap.getPlayersCloseForSpawning(body.chunkPosition()).contains(body),
                    "body doesn't count for spawning");
            helper.assertTrue(level.players().contains(body), "body not among the level's players");
            helper.assertFalse(server.getPlayerList().getPlayers().contains(body), "body in the player list");
            helper.assertTrue(server.getPlayerList().getPlayer(body.getUUID()) == null, "body online by its id");
            helper.assertTrue(server.getPlayerList().getPlayer(owner) == null, "owner still online");
        } finally {
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 60, environment = PLAYER_BODY)
    public void playerBodyFalls(GameTestHelper helper) {
        fallTest(helper, "PlayerFalls", true);
    }

    @GameTest(maxTicks = 60, environment = PLAYER_BODY)
    public void playerBodyStaysWhenImmovable(GameTestHelper helper) {
        fallTest(helper, "PlayerStays", false);
    }

    @GameTest
    public void ownerCanLogInWhileBodyExists(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Returns", ON_FLOOR);
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = usePlayerBodies();
        try {
            PlayerBody body = leaveBody(helper, player);
            body.hurtServer(helper.getLevel(), helper.getLevel().damageSources().generic(), 5.0F);

            ServerPlayer back = TestPlayers.rejoin(helper, player.getGameProfile(), GameType.SURVIVAL, ON_FLOOR);
            helper.assertTrue(helper.getLevel().getEntity(owner) == back, "owner not in the level");
            helper.assertTrue(helper.getLevel().players().contains(back), "owner not among the level's players");
            helper.assertValueEqual(back.getHealth(), 15.0F, "health after login");
            helper.assertTrue(body.isRemoved(), "body still in the world");
            helper.assertFalse(helper.getLevel().players().contains(body), "body still among the level's players");
            helper.assertTrue(store(helper).byOwner(owner).isEmpty(), "body record kept");
        } finally {
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    @GameTest
    public void killingPlayerBodyMakesOneGrave(GameTestHelper helper) {
        // Something solid under the body, so its grave goes on the body's own block
        helper.setBlock(new BlockPos(1, 0, 1), Blocks.STONE);
        ServerPlayer player = join(helper, "PlayerGrave", ON_FLOOR);
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = usePlayerBodies();
        try {
            player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            player.getInventory().add(new ItemStack(Items.DIAMOND));
            PlayerBody body = leaveBody(helper, player);
            BlockPos at = body.blockPosition();
            AABB around = new AABB(at).inflate(4.0);
            body.kill(helper.getLevel());

            helper.assertTrue(body.isDeadOrDying(), "body did not die");
            List<Grave> graves = gravesOf(helper, owner);
            helper.assertValueEqual(graves.size(), 1, "graves for the owner");
            helper.assertValueEqual(graves.get(0).pos(), at, "grave position");
            helper.assertValueEqual(countInGraves(graves, Items.DIAMOND), 1, "diamonds in the grave");
            helper.assertValueEqual(countInGraves(graves, Items.IRON_CHESTPLATE), 1, "chestplates in the grave");
            // The revive token has to name the owner, not the body
            helper.assertValueEqual(GraveItems.ownerId(graves.get(0).items().get(0)), Optional.of(owner), "grave head's owner");
            helper.assertTrue(helper.getLevel().getEntities(EntityTypes.ITEM, around, Entity::isAlive).isEmpty(), "items dropped");
            helper.assertTrue(helper.getLevel().getEntities(EntityTypes.EXPERIENCE_ORB, around, Entity::isAlive).isEmpty(),
                    "XP dropped");
            helper.assertTrue(DeathLockout.refusal(owner) != null, "owner not locked out");
            helper.assertTrue(DeathLockout.refusal(body.getUUID()) == null, "a death was recorded for the body itself");
            helper.assertTrue(store(helper).byOwner(owner).isEmpty(), "body record kept");
        } finally {
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    // A restart, done by hand: the stop saves the body's state, the body is gone, and the start brings it back
    @GameTest(environment = BODY_RESTART)
    public void playerBodiesRecreatedFromRecords(GameTestHelper helper) {
        // Test players have no skin, so this one gets one the restored body has to keep
        UUID id = UUID.randomUUID();
        PropertyMap skin = new PropertyMap(ImmutableMultimap.of("textures", new Property("textures", "dGVzdA==")));
        ServerPlayer player = TestPlayers.join(helper, new GameProfile(id, "Skin" + id.toString().substring(0, 8), skin),
                GameType.SURVIVAL, ON_FLOOR);
        UUID owner = player.getUUID();
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.BodiesSection before = usePlayerBodies();
        try {
            player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            PlayerBody body = leaveBody(helper, player);
            // Different from the logout, so the values can only come from the stop's snapshot
            body.teleportTo(body.getX() + 2.0, body.getY(), body.getZ());
            body.setHealth(12.0F);
            Vec3 moved = body.position();
            Bodies.snapshotBodies(server);
            body.discard();
            helper.assertTrue(store(helper).byOwner(owner).isPresent(), "record went with the body");

            Bodies.restorePlayerBodies(server);
            Entity found = helper.getLevel().getEntity(body.getUUID());
            helper.assertTrue(found instanceof PlayerBody && found != body, "no player body brought back");
            PlayerBody restored = (PlayerBody) found;
            helper.assertValueEqual(restored.owner(), owner, "restored body's owner");
            helper.assertValueEqual(restored.position(), moved, "restored body's position");
            helper.assertValueEqual(restored.getHealth(), 12.0F, "restored body's health");
            helper.assertTrue(restored.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE), "chestplate lost");
            helper.assertTrue(hasSkin(restored.getGameProfile().properties()), "restored body lost its skin");
            helper.assertTrue(hasSkin(restored.ownerProfile().partialProfile().properties()), "grave profile lost the skin");
            helper.assertTrue(Bodies.isBody(restored), "restored body not recognised as a body");
            helper.assertTrue(helper.getLevel().players().contains(restored), "restored body not among the level's players");
        } finally {
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    // An autosave keeps the record current, so a crash doesn't bring the body back as it was at logout
    @GameTest(environment = BODY_RESTART)
    public void playerBodyRecordKeptCurrentOnSave(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Autosaved", ON_FLOOR);
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = usePlayerBodies();
        try {
            PlayerBody body = leaveBody(helper, player);
            body.teleportTo(body.getX() + 2.0, body.getY(), body.getZ());
            body.setHealth(13.0F);
            // What every save runs first, without saving the whole world in a test
            ServerLifecycleEvents.BEFORE_SAVE.invoker().onBeforeSave(helper.getLevel().getServer(), false, false);

            Optional<BodyRecord> record = store(helper).byOwner(owner);
            helper.assertTrue(record.isPresent(), "no body record");
            helper.assertValueEqual(record.get().snapshot().pos(), body.position(), "saved position");
            helper.assertValueEqual(record.get().snapshot().health(), 13.0F, "saved health");
        } finally {
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    // A player's own armour wear skips the mixin a mannequin goes through, so the body checks the switch itself
    @GameTest
    public void playerBodyArmourWearsSwitch(GameTestHelper helper) {
        ServerPlayer player = join(helper, "PlayerArmour", ON_FLOOR);
        UUID owner = player.getUUID();
        ServerLevel level = helper.getLevel();
        YoltConfig.BodiesSection before = usePlayerBodies();
        try {
            player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            PlayerBody body = leaveBody(helper, player);
            // Never added to the world: a mob attack only needs someone to come from
            DamageSource attack = level.damageSources().mobAttack(new Zombie(level));

            body.hurtServer(level, attack, 6.0F);
            int chest = body.getItemBySlot(EquipmentSlot.CHEST).getDamageValue();
            helper.assertTrue(chest > 0, "chestplate did not wear with the switch on");
            int helmet = body.getItemBySlot(EquipmentSlot.HEAD).getDamageValue();
            // What a falling block or stalactite does on top of the armour wear
            body.hurtHelmet(attack, 8.0F);
            helper.assertTrue(body.getItemBySlot(EquipmentSlot.HEAD).getDamageValue() > helmet,
                    "helmet did not wear with the switch on");

            Settings.get().bodies.armourWears = false;
            helmet = body.getItemBySlot(EquipmentSlot.HEAD).getDamageValue();
            // Past the hurt cooldown, so the second hit lands in full
            body.damageCooldownTime = 0;
            helper.assertTrue(body.hurtServer(level, attack, 6.0F), "second hit didn't land");
            body.hurtHelmet(attack, 8.0F);
            helper.assertValueEqual(body.getItemBySlot(EquipmentSlot.CHEST).getDamageValue(), chest,
                    "chestplate damage with the switch off");
            helper.assertValueEqual(body.getItemBySlot(EquipmentSlot.HEAD).getDamageValue(), helmet,
                    "helmet damage with the switch off");
        } finally {
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    @GameTest
    public void playerBodyExpires(GameTestHelper helper) {
        ServerPlayer player = join(helper, "PlayerExpires", ON_FLOOR);
        UUID owner = player.getUUID();
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.BodiesSection before = usePlayerBodies();
        try {
            Settings.get().bodies.duration = new BodyDuration(1);
            PlayerBody body = leaveBody(helper, player);
            body.setHealth(9.0F);
            body.teleportTo(body.getX() + 2.0, body.getY(), body.getZ());
            Vec3 at = body.position();
            // As if the owner logged out 61 seconds ago, so a one-minute body is due
            BodyRecord record = store(helper).byOwner(owner).orElseThrow();
            store(helper).put(new BodyRecord(record.owner(), record.ownerName(), record.bodyEntity(), record.type(),
                    System.currentTimeMillis() - 61_000L, false, record.snapshot(), record.skin()));

            BodyExpiry.tick(server);
            helper.assertTrue(body.isRemoved(), "body still in the world");
            helper.assertFalse(helper.getLevel().players().contains(body), "body still among the level's players");
            helper.assertTrue(store(helper).byOwner(owner).isEmpty(), "body record kept");
            CompoundTag saved = OfflinePlayerData.load(server, player.nameAndId()).orElseThrow();
            helper.assertValueEqual(saved.getFloatOr("Health", -1.0F), 9.0F, "saved health");
            helper.assertValueEqual(saved.read(ServerPlayer.SavedPosition.MAP_CODEC).flatMap(ServerPlayer.SavedPosition::position),
                    Optional.of(at), "saved position");
        } finally {
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    // The targeting checks every goal and brain sensor goes through, for a mob that would go for the body
    @GameTest
    public void playerBodyIgnoredWhenMobsAttackOff(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Unhunted", ON_FLOOR);
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = usePlayerBodies();
        try {
            PlayerBody body = leaveBody(helper, player);
            // Never added to the world: it only has to be the one looking
            Zombie zombie = new Zombie(helper.getLevel());
            helper.assertTrue(zombie.canAttack(body), "a zombie can't attack the body with the switch on");
            Settings.get().bodies.mobsAttack = false;
            helper.assertFalse(body.canBeSeenAsEnemy(), "body seen as an enemy with the switch off");
            helper.assertFalse(zombie.canAttack(body), "a zombie can attack the body with the switch off");
        } finally {
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    // Its health goes back to the owner on login, so any healing would be free health for logging out
    @GameTest(maxTicks = 60, environment = PLAYER_BODY)
    public void playerBodyDoesNotRegenerate(GameTestHelper helper) {
        helper.setBlock(new BlockPos(1, 0, 1), Blocks.STONE);
        ServerPlayer player = join(helper, "NoRegen", ON_FLOOR);
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = usePlayerBodies();
        boolean waiting = false;
        try {
            player.setHealth(10.0F);
            PlayerBody body = leaveBody(helper, player);
            int food = body.getFoodData().getFoodLevel();
            float saturation = body.getFoodData().getSaturationLevel();
            // Enough to cost a vanilla player food straight away
            body.causeFoodExhaustion(40.0F);
            Settings.get().bodies = before;
            // A full vanilla player heals every 10 ticks
            helper.runAfterDelay(40, () -> {
                try {
                    helper.assertValueEqual(body.getHealth(), 10.0F, "body health");
                    helper.assertValueEqual(body.getFoodData().getFoodLevel(), food, "body food");
                    helper.assertValueEqual(body.getFoodData().getSaturationLevel(), saturation, "body saturation");
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

    // Only the body's worn gear goes back to its owner, so anything it picked up would be lost with it
    @GameTest(maxTicks = 40, environment = PLAYER_BODY)
    public void playerBodyPicksNothingUp(GameTestHelper helper) {
        helper.setBlock(new BlockPos(1, 0, 1), Blocks.STONE);
        ServerPlayer player = join(helper, "Untouched", ON_FLOOR);
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = usePlayerBodies();
        boolean waiting = false;
        try {
            PlayerBody body = leaveBody(helper, player);
            Settings.get().bodies = before;
            ItemEntity diamond = new ItemEntity(helper.getLevel(), body.getX(), body.getY(), body.getZ(), new ItemStack(Items.DIAMOND));
            diamond.setNoPickUpDelay();
            helper.getLevel().addFreshEntity(diamond);
            // A vanilla player takes it on its next tick
            helper.runAfterDelay(10, () -> {
                try {
                    helper.assertTrue(diamond.isAlive(), "the diamond was picked up");
                    helper.assertValueEqual(body.getInventory().countItem(Items.DIAMOND), 0, "diamonds the body holds");
                } finally {
                    diamond.discard();
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

    // The locator bar would show where an offline player's body is
    @GameTest
    public void playerBodySendsNoWaypoint(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Unmarked", ON_FLOOR);
        UUID owner = player.getUUID();
        ServerWaypointManager waypoints = helper.getLevel().getWaypointManager();
        YoltConfig.BodiesSection before = usePlayerBodies();
        try {
            helper.assertTrue(waypoints.transmitters().contains(player), "the player sends no waypoint to begin with");
            PlayerBody body = leaveBody(helper, player);
            helper.assertFalse(waypoints.transmitters().contains(body), "body sends a waypoint");
            // Invisibility wearing off makes vanilla check the range again
            body.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 200));
            body.removeEffect(MobEffects.INVISIBILITY);
            helper.assertFalse(waypoints.transmitters().contains(body), "body sends a waypoint once invisibility ends");
        } finally {
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    // Vanilla keeps a player changing dimension unhurtable until its client accepts the teleport
    @GameTest
    public void playerBodyHurtableAfterDimensionChange(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Traveller", ON_FLOOR);
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = usePlayerBodies();
        try {
            PlayerBody body = leaveBody(helper, player);
            ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
            helper.assertTrue(nether != null, "no nether to go to");
            // What a /tp into another dimension runs
            body.teleportTo(nether, 0.5, 64.0, 0.5, Set.of(), 0.0F, 0.0F, true);
            helper.assertTrue(body.level() == nether, "body did not change dimension");
            helper.assertFalse(body.isChangingDimension(), "body still changing dimension");
            helper.assertTrue(body.hurtServer(nether, nether.damageSources().generic(), 5.0F), "body refused the damage");
            helper.assertValueEqual(body.getHealth(), 15.0F, "body health after the hit");
        } finally {
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    // Vanilla keeps a player unhurtable until its client has loaded the world; a body has no client
    @GameTest
    public void playerBodyTakesDamageAtOnce(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Hurt", ON_FLOOR);
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = usePlayerBodies();
        try {
            PlayerBody body = leaveBody(helper, player);
            helper.assertTrue(body.hurtServer(helper.getLevel(), helper.getLevel().damageSources().generic(), 5.0F),
                    "body refused the damage");
            helper.assertValueEqual(body.getHealth(), 15.0F, "body health after the hit");
        } finally {
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    // A mannequin's death says nothing, and a player body's message would name its offline owner to everyone. Its
    // death also has to leave the level's players, where a dead body would go on counting for spawning.
    @GameTest(maxTicks = 60, environment = PLAYER_BODY)
    public void playerBodyDeathSaysNothingAndLeaves(GameTestHelper helper) {
        helper.setBlock(new BlockPos(1, 0, 1), Blocks.STONE);
        ServerPlayer player = join(helper, "Silent", ON_FLOOR);
        UUID owner = player.getUUID();
        TestPlayers.Recording listener = TestPlayers.joinRecording(helper, "Listener", GameType.SURVIVAL, new BlockPos(3, 1, 1));
        YoltConfig.HideSection hide = Settings.get().hide;
        boolean messagesHidden = hide.gameMessages;
        YoltConfig.BodiesSection before = usePlayerBodies();
        boolean waiting = false;
        try {
            PlayerBody body = leaveBody(helper, player);
            // Off, so a death message would reach every player rather than only admins
            hide.gameMessages = false;
            body.kill(helper.getLevel());
            hide.gameMessages = messagesHidden;

            helper.assertTrue(body.isDeadOrDying(), "body did not die");
            helper.assertTrue(listener.messages.stream().noneMatch(PlayerBodyGameTests::isDeathMessage), "death message sent");
            helper.assertValueEqual(gravesOf(helper, owner).size(), 1, "graves for the owner");
            Settings.get().bodies = before;
            TestPlayers.leave(listener);
            // Vanilla removes a dead player 20 ticks into its death
            helper.runAfterDelay(30, () -> {
                try {
                    helper.assertTrue(body.isRemoved(), "dead body still in the world");
                    helper.assertFalse(helper.getLevel().players().contains(body), "dead body still among the level's players");
                } finally {
                    cleanUp(helper, owner, before);
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            hide.gameMessages = messagesHidden;
            Settings.get().bodies = before;
            if (helper.getLevel().getServer().getPlayerList().getPlayer(listener.getUUID()) != null) {
                TestPlayers.leave(listener);
            }
            if (!waiting) {
                cleanUp(helper, owner, before);
            }
        }
    }

    // The level counts its awake players for the night skip, and a body is one that never sleeps
    @GameTest
    public void playerBodyDoesNotBlockSleep(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Awake", ON_FLOOR);
        ServerPlayer other = join(helper, "Other", new BlockPos(3, 1, 1));
        UUID owner = player.getUUID();
        YoltConfig.SleepSection sleep = Settings.get().sleep;
        boolean noSkipBefore = sleep.noNightSkip;
        YoltConfig.BodiesSection before = usePlayerBodies();
        try {
            PlayerBody body = leaveBody(helper, player);
            // On, it would always ask for more sleepers than there are
            sleep.noNightSkip = false;
            SleepStatus status = new SleepStatus();
            status.update(List.of(other, body));
            helper.assertValueEqual(status.sleepersNeeded(100), 1, "sleepers needed with a body awake");
        } finally {
            sleep.noNightSkip = noSkipBefore;
            cleanUp(helper, owner, before);
            TestPlayers.leave(other);
        }
        helper.succeed();
    }

    // The player list keeps stats and advancements by id until a player leaves, which a body never does, and saves them
    @GameTest
    public void playerBodyLeavesNoStats(GameTestHelper helper) throws ReflectiveOperationException {
        ServerPlayer player = join(helper, "NoStats", ON_FLOOR);
        UUID owner = player.getUUID();
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.BodiesSection before = usePlayerBodies();
        try {
            PlayerBody body = leaveBody(helper, player);
            UUID id = body.getUUID();
            helper.assertFalse(playerListMap(server, "stats").containsKey(id), "player list keeps the body's stats");
            helper.assertFalse(playerListMap(server, "advancements").containsKey(id), "player list keeps the body's advancements");

            body.awardStat(Stats.JUMP);
            helper.assertValueEqual(body.getStats().getValue(Stats.CUSTOM.get(Stats.JUMP)), 0, "stat counted for the body");
            AdvancementHolder root = server.getAdvancements().get(Identifier.withDefaultNamespace("story/root"));
            helper.assertTrue(root != null, "no story advancement to try");
            helper.assertFalse(body.getAdvancements().award(root, "crafting_table"), "body made an advancement");
            helper.assertTrue(body.getAdvancements().getTriggerMapForType(CriteriaTriggers.INVENTORY_CHANGED) == null,
                    "body's advancements still listen for triggers");

            // What a save of every player writes, and the body's own files if anything saved them
            server.getPlayerList().saveAll();
            body.getStats().save();
            body.getAdvancements().save();
            helper.assertFalse(Files.exists(server.getWorldPath(LevelResource.PLAYER_STATS_DIR).resolve(id + ".json")),
                    "stats file written for the body");
            helper.assertFalse(Files.exists(server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR).resolve(id + ".json")),
                    "advancements file written for the body");
        } finally {
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    // Five blocks above a stone floor. The switches only matter while the body spawns, so they go back before the wait.
    private static void fallTest(GameTestHelper helper, String name, boolean movable) {
        helper.setBlock(new BlockPos(1, 0, 1), Blocks.STONE);
        ServerPlayer player = join(helper, name, new BlockPos(1, 6, 1));
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = usePlayerBodies();
        boolean waiting = false;
        try {
            Settings.get().bodies.movable = movable;
            PlayerBody body = leaveBody(helper, player);
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
                    // Vanilla would have stood it up on its first tick
                    helper.assertValueEqual(body.getPose(), Pose.SWIMMING, "pose after ticking");
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
    private static YoltConfig.BodiesSection usePlayerBodies() {
        YoltConfig config = Settings.get();
        YoltConfig.BodiesSection before = config.bodies;
        config.bodies = new YoltConfig.BodiesSection();
        config.bodies.type = YoltConfig.BodyType.PLAYER;
        return before;
    }

    // A real logout: leaving fires the hook and saves the player
    private static PlayerBody leaveBody(GameTestHelper helper, ServerPlayer player) {
        TestPlayers.leave(player);
        Optional<BodyRecord> record = store(helper).byOwner(player.getUUID());
        helper.assertTrue(record.isPresent(), "no body record");
        Entity entity = helper.getLevel().getEntity(record.get().bodyEntity());
        helper.assertTrue(entity instanceof PlayerBody, "no player body for the record");
        return (PlayerBody) entity;
    }

    private static boolean hasSkin(PropertyMap properties) {
        return properties.values().stream().anyMatch(property -> property.name().equals("textures"));
    }

    private static boolean isDeathMessage(Component message) {
        return message.getContents() instanceof TranslatableContents text && text.getKey().startsWith("death.");
    }

    // Private and only read here; the names are Mojang's, the same at runtime
    private static Map<?, ?> playerListMap(MinecraftServer server, String name) throws ReflectiveOperationException {
        Field field = PlayerList.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Map<?, ?>) field.get(server.getPlayerList());
    }

    private static BodyStore store(GameTestHelper helper) {
        return BodyStore.get(helper.getLevel().getServer());
    }

    private static List<Grave> gravesOf(GameTestHelper helper, UUID owner) {
        return GraveStore.get(helper.getLevel().getServer()).all().stream()
                .filter(grave -> grave.owner().equals(owner))
                .toList();
    }

    private static int countInGraves(List<Grave> graves, Item item) {
        int total = 0;
        for (Grave grave : graves) {
            for (ItemStack stack : grave.items()) {
                if (stack.is(item)) {
                    total += stack.getCount();
                }
            }
        }
        return total;
    }

    // The switches go back first, so a player still online leaves without making another body.
    // Bodies, records and graves left behind would sit in another test's way.
    private static void cleanUp(GameTestHelper helper, UUID owner, YoltConfig.BodiesSection before) {
        Settings.get().bodies = before;
        ServerPlayer online = helper.getLevel().getServer().getPlayerList().getPlayer(owner);
        if (online != null) {
            TestPlayers.leave(online);
        }
        removeBody(helper, owner);
        GraveStore graves = GraveStore.get(helper.getLevel().getServer());
        for (Grave grave : gravesOf(helper, owner)) {
            graves.remove(grave.id());
            helper.getLevel().setBlock(grave.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    // A copy: discarding a body takes it out of the level's players
    // Every dimension, since a test can move a body to another
    private static void removeBody(GameTestHelper helper, UUID owner) {
        for (ServerLevel level : helper.getLevel().getServer().getAllLevels()) {
            for (ServerPlayer player : new ArrayList<>(level.players())) {
                if (player instanceof PlayerBody body && body.owner().equals(owner)) {
                    body.discard();
                }
            }
        }
        store(helper).remove(owner);
    }
}
