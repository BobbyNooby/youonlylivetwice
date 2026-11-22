package bobbynooby.dev.gametest;

import bobbynooby.dev.bodies.BodyTargetGoal;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.hardcore.HardcoreConfig;
import bobbynooby.dev.hardcore.MobBoosts;
import bobbynooby.dev.hardcore.Relentless;
import bobbynooby.dev.mixin.MobAccessor;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.ConversionParams;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

import static bobbynooby.dev.gametest.BodyTestKit.leaveBodyWithDefaults;

// A mob is judged as it loads, so each feature stays on through the test's environment rather than being switched on
// after the mob is there. Mobs are made the way their spawn reason makes them in 26.3: EntityType.create with that
// reason, which is where Fabric records it, then added to the world, which runs the load hook. None of them gets
// vanilla's finalizeSpawn, so no random leader or follow-range bonus muddies the numbers, and only the zombie that has
// a door to break keeps its free will.
public class HardcoreMobsGameTests {
    private static final String MOBS = "you-only-live-twice-gametest:hardcore_mobs";
    private static final String MOBS_ALL = "you-only-live-twice-gametest:hardcore_mobs_all";
    private static final String MOBS_TRIAL = "you-only-live-twice-gametest:hardcore_mobs_trial";
    private static final String MOBS_OFF = "you-only-live-twice-gametest:hardcore_mobs_off";
    // Easy as well, held for the whole batch, which this test has to itself
    private static final String MOBS_DOORS = "you-only-live-twice-gametest:hardcore_mobs_doors";
    private static final String MOBS_DOORS_NO_DROP = "you-only-live-twice-gametest:hardcore_mobs_doors_nodrop";
    private static final String MOBS_NO_DOORS = "you-only-live-twice-gametest:hardcore_mobs_no_doors";
    // Easy, with doorsAnyDifficulty off, so doors stay Hard-only
    private static final String MOBS_DOORS_HARD_ONLY = "you-only-live-twice-gametest:hardcore_mobs_doors_hard_only";
    // As MOBS_DOORS, for a test that changes its switch within a tick, apart from the door break that has its own batch
    private static final String MOBS_DOORS_KEPT = "you-only-live-twice-gametest:hardcore_mobs_doors_kept";
    private static final BlockPos PLAYER_AT = new BlockPos(1, 0, 1);
    private static final BlockPos MOB_AT = new BlockPos(4, 0, 4);
    private static final BlockPos VILLAGER_AT = new BlockPos(6, 0, 1);
    private static final double CLOSE = 1e-9;
    // A corridor along x at z 3: the zombie, an oak door, then the villager in a closed cell
    private static final int LANE = 3;
    private static final BlockPos ZOMBIE_AT = new BlockPos(1, 0, LANE);
    private static final BlockPos DOOR_AT = new BlockPos(3, 0, LANE);
    private static final BlockPos BEHIND_DOOR = new BlockPos(5, 0, LANE);

    // A zombie's 20 health times 1.5
    @GameTest(environment = MOBS)
    public void mobs2HealthSurvivesReloadsWithoutStacking(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        HardcoreConfig.TougherMobsSection config = Settings.get().hardcore.tougherMobs;
        boolean enabledBefore = config.enabled;
        List<Entity> spawned = new ArrayList<>();
        try {
            Mob zombie = create(helper, spawned, EntityTypes.ZOMBIE, EntitySpawnReason.NATURAL, MOB_AT);
            assertHealth(helper, zombie, 30.0F, 30.0F, "on first sight");
            for (int i = 0; i < 3; i++) {
                MobBoosts.onLoad(zombie, level);
            }
            assertHealth(helper, zombie, 30.0F, 30.0F, "after three more loads");
            helper.assertValueEqual(attribute(zombie, Attributes.MAX_HEALTH).getModifiers().size(), 1, "max health modifiers");

            // Set and put back within this tick
            config.enabled = false;
            MobBoosts.onLoad(zombie, level);
            assertHealth(helper, zombie, 20.0F, 20.0F, "loaded with the feature off");
            helper.assertTrue(attribute(zombie, Attributes.MAX_HEALTH).getModifier(MobBoosts.TOUGHER) == null,
                    "the max health modifier stayed with the feature off");
            config.enabled = true;
            MobBoosts.onLoad(zombie, level);
            // Its health was scaled once already, so it only gets its room back
            assertHealth(helper, zombie, 30.0F, 20.0F, "loaded with the feature on again");
        } finally {
            config.enabled = enabledBefore;
            spawned.forEach(Entity::discard);
        }
        helper.succeed();
    }

    // A converted mob gets the zombie's tags but neither its health nor its modifiers (26.3 ConversionType), so it is
    // scaled once as it loads
    @GameTest(environment = MOBS)
    public void mobs2ConvertedMobKeepsItsExtraHealth(GameTestHelper helper) {
        List<Entity> spawned = new ArrayList<>();
        try {
            Mob zombie = create(helper, spawned, EntityTypes.ZOMBIE, EntitySpawnReason.NATURAL, MOB_AT);
            assertHealth(helper, zombie, 30.0F, 30.0F, "of the zombie");
            Mob drowned = zombie.convertTo(EntityTypes.DROWNED, ConversionParams.single(zombie, true, true), converted -> {
            });
            helper.assertTrue(drowned != null, "the zombie did not convert");
            spawned.add(drowned);
            helper.assertTrue(drowned.entityTags().contains(MobBoosts.TOUGH), "the drowned is not tagged " + MobBoosts.TOUGH);
            assertHealth(helper, drowned, 30.0F, 30.0F, "of the drowned");
            // Loading again doesn't scale it a second time
            MobBoosts.onLoad(drowned, helper.getLevel());
            assertHealth(helper, drowned, 30.0F, 30.0F, "of the drowned loaded again");
        } finally {
            spawned.forEach(Entity::discard);
        }
        helper.succeed();
    }

    // Hits of 4 on Normal, the test server's difficulty, which leaves a player's damage as dealt
    @GameTest(environment = MOBS)
    public void mobs3ArrowIsBoostedCreeperBlastIsNot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<Entity> spawned = new ArrayList<>();
        List<ServerPlayer> players = new ArrayList<>();
        try {
            Mob skeleton = create(helper, spawned, EntityTypes.SKELETON, EntitySpawnReason.NATURAL, MOB_AT);
            // Never added to the world: only the damage source needs it
            var arrow = EntityTypes.ARROW.create(level, EntitySpawnReason.COMMAND);
            helper.assertTrue(arrow != null, "no arrow made");
            assertPlayerTakes(helper, players, "Arrowed", level.damageSources().arrow(arrow, skeleton), 4.0F, 5.0F);

            // The creeper is the blast's cause, and its override keeps its damage at 1
            Mob creeper = create(helper, spawned, EntityTypes.CREEPER, EntitySpawnReason.NATURAL, MOB_AT);
            helper.assertTrue(creeper.entityTags().contains(MobBoosts.TOUGH), "the creeper was not boosted");
            assertPlayerTakes(helper, players, "Blasted", level.damageSources().explosion(creeper, creeper), 4.0F, 4.0F);

            Mob wither = create(helper, spawned, EntityTypes.WITHER, EntitySpawnReason.NATURAL, MOB_AT);
            helper.assertTrue(attribute(wither, Attributes.MAX_HEALTH).getModifier(MobBoosts.TOUGHER) == null,
                    "the wither has a max health modifier");
            helper.assertTrue(attribute(wither, Attributes.MOVEMENT_SPEED).getModifier(MobBoosts.TOUGHER) == null,
                    "the wither has a speed modifier");
            helper.assertFalse(wither.entityTags().contains(MobBoosts.TOUGH), "the wither was boosted");
            var skull = EntityTypes.WITHER_SKULL.create(level, EntitySpawnReason.COMMAND);
            helper.assertTrue(skull != null, "no wither skull made");
            assertPlayerTakes(helper, players, "Skulled", level.damageSources().witherSkull(skull, wither), 4.0F, 4.0F);
        } finally {
            players.forEach(TestPlayers::leave);
            spawned.forEach(Entity::discard);
        }
        helper.succeed();
    }

    @GameTest(environment = MOBS)
    public void mobs6PlayersHarderVillagersVanilla(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<Entity> spawned = new ArrayList<>();
        List<ServerPlayer> players = new ArrayList<>();
        ServerPlayer owner = null;
        try {
            Mob zombie = create(helper, spawned, EntityTypes.ZOMBIE, EntitySpawnReason.NATURAL, MOB_AT);
            DamageSource bite = level.damageSources().mobAttack(zombie);
            assertPlayerTakes(helper, players, "Bitten", bite, 3.0F, 3.75F);

            owner = TestPlayers.join(helper, "BittenBody", GameType.SURVIVAL, PLAYER_AT);
            Mannequin body = leaveBodyWithDefaults(helper, owner);
            assertTakes(helper, body, bite, 3.0F, 3.75F, "the logout body");

            Mob villager = helper.spawnWithNoFreeWill(EntityTypes.VILLAGER, VILLAGER_AT);
            spawned.add(villager);
            assertTakes(helper, villager, bite, 3.0F, 3.0F, "the villager");
        } finally {
            players.forEach(TestPlayers::leave);
            spawned.forEach(Entity::discard);
            if (owner != null) {
                BodyTestKit.cleanUp(helper, owner.getUUID());
            }
        }
        helper.succeed();
    }

    // damageAppliesTo all, held by the environment
    @GameTest(environment = MOBS_ALL)
    public void mobs6AllMakesVillagersTakeItToo(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<Entity> spawned = new ArrayList<>();
        try {
            Mob zombie = create(helper, spawned, EntityTypes.ZOMBIE, EntitySpawnReason.NATURAL, MOB_AT);
            Mob villager = helper.spawnWithNoFreeWill(EntityTypes.VILLAGER, VILLAGER_AT);
            spawned.add(villager);
            assertTakes(helper, villager, level.damageSources().mobAttack(zombie), 3.0F, 3.75F, "the villager");
        } finally {
            spawned.forEach(Entity::discard);
        }
        helper.succeed();
    }

    @GameTest(environment = MOBS)
    public void mobs7SpawnerYesTrialEggAndCommandNo(GameTestHelper helper) {
        List<Entity> spawned = new ArrayList<>();
        try {
            assertBoosted(helper, create(helper, spawned, EntityTypes.ZOMBIE, EntitySpawnReason.SPAWNER, MOB_AT), "a spawner zombie");
            for (EntitySpawnReason reason : List.of(EntitySpawnReason.TRIAL_SPAWNER, EntitySpawnReason.SPAWN_ITEM_USE,
                    EntitySpawnReason.COMMAND)) {
                Mob zombie = create(helper, spawned, EntityTypes.ZOMBIE, reason, MOB_AT);
                String name = "a zombie from " + reason;
                helper.assertTrue(zombie.entityTags().contains(MobBoosts.VANILLA), name + " is not tagged " + MobBoosts.VANILLA);
                helper.assertFalse(zombie.entityTags().contains(MobBoosts.TOUGH), name + " is tagged " + MobBoosts.TOUGH);
                assertHealth(helper, zombie, 20.0F, 20.0F, name);
                helper.assertTrue(attribute(zombie, Attributes.MOVEMENT_SPEED).getModifier(MobBoosts.TOUGHER) == null,
                        name + " has a speed modifier");
            }
        } finally {
            spawned.forEach(Entity::discard);
        }
        helper.succeed();
    }

    // includeTrialSpawners on, held by the environment
    @GameTest(environment = MOBS_TRIAL)
    public void mobs7TrialSpawnerMobsWhenIncluded(GameTestHelper helper) {
        List<Entity> spawned = new ArrayList<>();
        try {
            assertBoosted(helper, create(helper, spawned, EntityTypes.ZOMBIE, EntitySpawnReason.TRIAL_SPAWNER, MOB_AT),
                    "a trial-spawner zombie");
        } finally {
            spawned.forEach(Entity::discard);
        }
        helper.succeed();
    }

    @GameTest(environment = MOBS_OFF)
    public void mobsOffLeavesMobsAlone(GameTestHelper helper) {
        List<Entity> spawned = new ArrayList<>();
        try {
            Mob zombie = create(helper, spawned, EntityTypes.ZOMBIE, EntitySpawnReason.NATURAL, MOB_AT);
            helper.assertTrue(zombie.entityTags().stream().noneMatch(tag -> tag.startsWith("yolt.")),
                    "the zombie has YOLT tags: " + zombie.entityTags());
            helper.assertTrue(attribute(zombie, Attributes.MAX_HEALTH).getModifier(MobBoosts.TOUGHER) == null,
                    "the zombie has a max health modifier");
            helper.assertTrue(attribute(zombie, Attributes.MOVEMENT_SPEED).getModifier(MobBoosts.TOUGHER) == null,
                    "the zombie has a speed modifier");
            helper.assertTrue(attribute(zombie, Attributes.FOLLOW_RANGE).getModifier(Relentless.RELENTLESS) == null,
                    "the zombie has a follow range modifier");
            assertHealth(helper, zombie, 20.0F, 20.0F, "the zombie");
        } finally {
            spawned.forEach(Entity::discard);
        }
        helper.succeed();
    }

    // Vanilla needs 240 ticks of hitting once the zombie stands at the door (26.3 BreakDoorGoal), after it finds the
    // villager and walks the two blocks to it, so this allows more than twice that
    @GameTest(environment = MOBS_DOORS, maxTicks = 600)
    public void mobs4ZombieBreaksADoorOnEasyAndItDrops(GameTestHelper helper) {
        breaksDoor(helper, 1);
    }

    // dropBrokenDoor off: the same break, and the door is gone with nothing dropped
    @GameTest(environment = MOBS_DOORS_NO_DROP, maxTicks = 600)
    public void mobs4BrokenDoorDropsNothingWhenTheDropIsOff(GameTestHelper helper) {
        breaksDoor(helper, 0);
    }

    // A zombie with a villager behind an oak door breaks it on Easy; then this many door items lie by it
    private static void breaksDoor(GameTestHelper helper, int doorItems) {
        helper.assertValueEqual(helper.getLevel().getDifficulty(), Difficulty.EASY, "difficulty");
        buildCorridor(helper);
        List<Entity> spawned = new ArrayList<>();
        Runnable cleanUp = BodyTestKit.once(() -> {
            spawned.forEach(Entity::discard);
            helper.getEntities(EntityTypes.ITEM, DOOR_AT, 3.0).forEach(Entity::discard);
        });
        boolean waiting = false;
        try {
            Zombie zombie = createFree(helper, spawned, EntityTypes.ZOMBIE, EntitySpawnReason.NATURAL, ZOMBIE_AT);
            helper.assertTrue(zombie.canBreakDoors(), "the zombie did not win its door roll at a chance of 1");
            // Only the villager loses its will, so it stays in its cell. The closed door blocks the zombie's sight, and its
            // target goals only pick what they can see, so it is pointed at the villager by hand.
            Mob villager = helper.spawnWithNoFreeWill(EntityTypes.VILLAGER, BEHIND_DOOR);
            spawned.add(villager);
            zombie.setTarget(villager);
            helper.runBeforeTestEnd(cleanUp);
            helper.startSequence()
                    .thenWaitUntil(() -> helper.assertTrue(helper.getBlockState(DOOR_AT).isAir()
                            && helper.getBlockState(DOOR_AT.above()).isAir(), "the door still stands"))
                    .thenExecute(() -> {
                        try {
                            helper.assertItemEntityCountIs(Items.OAK_DOOR, DOOR_AT, 3.0, doorItems);
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

    @GameTest(environment = MOBS_NO_DOORS)
    public void mobs4ChanceZeroBreaksNoDoors(GameTestHelper helper) {
        List<Entity> spawned = new ArrayList<>();
        try {
            List<Zombie> zombies = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                zombies.add(create(helper, spawned, EntityTypes.ZOMBIE, EntitySpawnReason.NATURAL, MOB_AT));
            }
            zombies.add(create(helper, spawned, EntityTypes.HUSK, EntitySpawnReason.NATURAL, MOB_AT));
            for (Zombie zombie : zombies) {
                helper.assertFalse(zombie.canBreakDoors(), "a " + zombie.getType() + " can break doors");
                helper.assertTrue(zombie.entityTags().contains(Relentless.DOOR_ROLLED),
                        "a " + zombie.getType() + " is not tagged " + Relentless.DOOR_ROLLED);
            }
        } finally {
            spawned.forEach(Entity::discard);
        }
        helper.succeed();
    }

    // A zombie's base follow range is 35; the warden is excluded, and the body search keeps vanilla's reach
    @GameTest(environment = MOBS)
    public void mobs5FollowRangeIsOneAndAHalfTimes(GameTestHelper helper) {
        List<Entity> spawned = new ArrayList<>();
        try {
            Mob zombie = create(helper, spawned, EntityTypes.ZOMBIE, EntitySpawnReason.NATURAL, MOB_AT);
            AttributeInstance range = attribute(zombie, Attributes.FOLLOW_RANGE);
            assertClose(helper, range.getValue(), range.getBaseValue() * 1.5, "the zombie's follow range");
            Mob warden = create(helper, spawned, EntityTypes.WARDEN, EntitySpawnReason.NATURAL, MOB_AT);
            AttributeInstance wardenRange = attribute(warden, Attributes.FOLLOW_RANGE);
            assertClose(helper, wardenRange.getValue(), wardenRange.getBaseValue(), "the warden's follow range");
            assertClose(helper, bodyGoal(zombie).getFollowDistance(), range.getBaseValue(), "the body goal's search range");
        } finally {
            spawned.forEach(Entity::discard);
        }
        helper.succeed();
    }

    // Turning both features off takes their boosts off a mob the next time it loads, here after a save and a load as a
    // chunk does it. The tag stays, so a mob is never judged again.
    @GameTest(environment = MOBS)
    public void mobs2OffTakesTheBoostOffAtTheNextLoad(GameTestHelper helper) {
        HardcoreConfig.TougherMobsSection tougher = Settings.get().hardcore.tougherMobs;
        HardcoreConfig.RelentlessMobsSection relentless = Settings.get().hardcore.relentlessMobs;
        boolean tougherBefore = tougher.enabled;
        boolean relentlessBefore = relentless.enabled;
        List<Entity> spawned = new ArrayList<>();
        try {
            Mob zombie = create(helper, spawned, EntityTypes.ZOMBIE, EntitySpawnReason.NATURAL, MOB_AT);
            assertHealth(helper, zombie, 30.0F, 30.0F, "on first sight");
            AttributeInstance range = attribute(zombie, Attributes.FOLLOW_RANGE);
            assertClose(helper, range.getValue(), range.getBaseValue() * 1.5, "the follow range on first sight");

            // Set and put back within this tick
            tougher.enabled = false;
            relentless.enabled = false;
            Mob loaded = reload(helper, spawned, zombie);
            assertHealth(helper, loaded, 20.0F, 20.0F, "loaded with both features off");
            AttributeInstance loadedRange = attribute(loaded, Attributes.FOLLOW_RANGE);
            assertClose(helper, loadedRange.getValue(), loadedRange.getBaseValue(), "the follow range loaded with both off");
            helper.assertTrue(loaded.entityTags().contains(MobBoosts.TOUGH), "the tag went: " + loaded.entityTags());
        } finally {
            tougher.enabled = tougherBefore;
            relentless.enabled = relentlessBefore;
            spawned.forEach(Entity::discard);
        }
        helper.succeed();
    }

    // damage is read as each hit lands, so a change counts from the next hit on the same zombie
    @GameTest(environment = MOBS)
    public void mobs3ADamageChangeAppliesAtOnce(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        HardcoreConfig.TougherMobsSection tougher = Settings.get().hardcore.tougherMobs;
        double damageBefore = tougher.damage;
        List<Entity> spawned = new ArrayList<>();
        List<ServerPlayer> players = new ArrayList<>();
        try {
            Mob zombie = create(helper, spawned, EntityTypes.ZOMBIE, EntitySpawnReason.NATURAL, MOB_AT);
            DamageSource bite = level.damageSources().mobAttack(zombie);
            assertPlayerTakes(helper, players, "BittenBefore", bite, 3.0F, 3.75F);
            // Set and put back within this tick
            tougher.damage = 2.0;
            assertPlayerTakes(helper, players, "BittenAfter", bite, 3.0F, 6.0F);
        } finally {
            tougher.damage = damageBefore;
            players.forEach(TestPlayers::leave);
            spawned.forEach(Entity::discard);
        }
        helper.succeed();
    }

    // includeCommandsAndEggs on boosts mobs from commands, eggs and dispensed eggs; includeSpawners off leaves spawner
    // mobs vanilla for good. Both are read as a mob is first seen, and set within this tick.
    @GameTest(environment = MOBS)
    public void mobs7CommandsAndEggsWhenIncluded(GameTestHelper helper) {
        HardcoreConfig.TougherMobsSection tougher = Settings.get().hardcore.tougherMobs;
        boolean eggsBefore = tougher.includeCommandsAndEggs;
        boolean spawnersBefore = tougher.includeSpawners;
        List<Entity> spawned = new ArrayList<>();
        try {
            tougher.includeCommandsAndEggs = true;
            tougher.includeSpawners = false;
            for (EntitySpawnReason reason : List.of(EntitySpawnReason.COMMAND, EntitySpawnReason.SPAWN_ITEM_USE,
                    EntitySpawnReason.DISPENSER)) {
                assertBoosted(helper, create(helper, spawned, EntityTypes.ZOMBIE, reason, MOB_AT), "a zombie from " + reason);
            }
            Mob fromSpawner = create(helper, spawned, EntityTypes.ZOMBIE, EntitySpawnReason.SPAWNER, MOB_AT);
            helper.assertTrue(fromSpawner.entityTags().contains(MobBoosts.VANILLA), "a spawner zombie isn't tagged " + MobBoosts.VANILLA);
            assertHealth(helper, fromSpawner, 20.0F, 20.0F, "a spawner zombie");
        } finally {
            tougher.includeCommandsAndEggs = eggsBefore;
            tougher.includeSpawners = spawnersBefore;
            spawned.forEach(Entity::discard);
        }
        helper.succeed();
    }

    // With doorsAnyDifficulty off, a zombie that won its roll still breaks doors on Hard only, as in vanilla: on Easy it
    // reaches the door and the door stands
    @GameTest(environment = MOBS_DOORS_HARD_ONLY, maxTicks = 340)
    public void mobs4DoorsStayHardOnlyWhenSwitchedOff(GameTestHelper helper) {
        helper.assertValueEqual(helper.getLevel().getDifficulty(), Difficulty.EASY, "difficulty");
        buildCorridor(helper);
        List<Entity> spawned = new ArrayList<>();
        Runnable cleanUp = BodyTestKit.once(() -> spawned.forEach(Entity::discard));
        boolean waiting = false;
        try {
            Zombie zombie = createFree(helper, spawned, EntityTypes.ZOMBIE, EntitySpawnReason.NATURAL, ZOMBIE_AT);
            helper.assertTrue(zombie.canBreakDoors(), "the zombie did not win its door roll at a chance of 1");
            Mob villager = helper.spawnWithNoFreeWill(EntityTypes.VILLAGER, BEHIND_DOOR);
            spawned.add(villager);
            zombie.setTarget(villager);
            helper.runBeforeTestEnd(cleanUp);
            helper.runAfterDelay(300, () -> {
                try {
                    helper.assertTrue(helper.getBlockState(DOOR_AT).getBlock() instanceof DoorBlock
                            && helper.getBlockState(DOOR_AT.above()).getBlock() instanceof DoorBlock, "the door was broken on Easy");
                    double toDoor = zombie.position().distanceTo(helper.absoluteVec(Vec3.atBottomCenterOf(DOOR_AT)));
                    helper.assertTrue(toDoor < 2.0, "the zombie never reached the door: " + toDoor + " blocks off");
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

    // A zombie that won its roll keeps it with the feature off, loaded again, and is never rolled again once it is back
    // on, even at a chance of 0
    @GameTest(environment = MOBS_DOORS_KEPT)
    public void mobs4ADoorWinnerKeepsItAfterTheSwitchGoesOff(GameTestHelper helper) {
        HardcoreConfig.RelentlessMobsSection relentless = Settings.get().hardcore.relentlessMobs;
        boolean enabledBefore = relentless.enabled;
        double chanceBefore = relentless.doorBreakerChance;
        List<Entity> spawned = new ArrayList<>();
        try {
            Zombie zombie = create(helper, spawned, EntityTypes.ZOMBIE, EntitySpawnReason.NATURAL, MOB_AT);
            helper.assertTrue(zombie.canBreakDoors(), "the zombie did not win its door roll at a chance of 1");
            // Set and put back within this tick
            relentless.enabled = false;
            Mob offLoad = reload(helper, spawned, zombie);
            helper.assertTrue(((Zombie) offLoad).canBreakDoors(), "the zombie lost its doors loaded with relentless off");
            relentless.enabled = true;
            relentless.doorBreakerChance = 0.0;
            Mob onLoad = reload(helper, spawned, offLoad);
            helper.assertTrue(((Zombie) onLoad).canBreakDoors(), "the zombie was rolled again at a chance of 0");
            helper.assertTrue(onLoad.entityTags().contains(Relentless.DOOR_ROLLED), "the roll's tag went");
        } finally {
            relentless.enabled = enabledBefore;
            relentless.doorBreakerChance = chanceBefore;
            spawned.forEach(Entity::discard);
        }
        helper.succeed();
    }

    // A mob tougher mobs leaves alone is left alone by relentless mobs too: no longer reach and no door roll
    @GameTest(environment = MOBS)
    public void mobs5TougherExcludeAlsoKeepsRelentlessOff(GameTestHelper helper) {
        HardcoreConfig.TougherMobsSection tougher = Settings.get().hardcore.tougherMobs;
        List<String> excludeBefore = tougher.exclude;
        List<Entity> spawned = new ArrayList<>();
        try {
            // Set and put back within this tick
            List<String> exclude = new ArrayList<>(excludeBefore);
            exclude.add("minecraft:zombie");
            tougher.exclude = exclude;
            Zombie zombie = create(helper, spawned, EntityTypes.ZOMBIE, EntitySpawnReason.NATURAL, MOB_AT);
            AttributeInstance range = attribute(zombie, Attributes.FOLLOW_RANGE);
            assertClose(helper, range.getValue(), range.getBaseValue(), "an excluded zombie's follow range");
            helper.assertFalse(zombie.entityTags().contains(Relentless.DOOR_ROLLED), "an excluded zombie rolled for doors");
            assertHealth(helper, zombie, 20.0F, 20.0F, "an excluded zombie");
        } finally {
            tougher.exclude = excludeBefore;
            spawned.forEach(Entity::discard);
        }
        helper.succeed();
    }

    // Endermen, zombified piglins, piglins and spiders count as hostile, so each gets 1.5 times its own health
    @GameTest(environment = MOBS)
    public void mobs6EndermenPiglinsSpidersAreBoosted(GameTestHelper helper) {
        List<Entity> spawned = new ArrayList<>();
        try {
            for (EntityType<? extends Mob> type : List.of(EntityTypes.ENDERMAN, EntityTypes.ZOMBIFIED_PIGLIN, EntityTypes.PIGLIN,
                    EntityTypes.SPIDER)) {
                Mob mob = create(helper, spawned, type, EntitySpawnReason.NATURAL, MOB_AT);
                double base = attribute(mob, Attributes.MAX_HEALTH).getBaseValue();
                assertClose(helper, mob.getMaxHealth(), base * 1.5, "a " + type + "'s max health");
                helper.assertTrue(mob.entityTags().contains(MobBoosts.TOUGH), "a " + type + " isn't tagged " + MobBoosts.TOUGH);
            }
        } finally {
            spawned.forEach(Entity::discard);
        }
        helper.succeed();
    }

    // A mob saved before tougher mobs was on carries no verdict, so when it next loads it counts as natural and is
    // boosted, whatever made it (docs/known-limits.md)
    @GameTest(environment = MOBS)
    public void mobs2SavedBeforeTheFeatureCountsAsNatural(GameTestHelper helper) {
        HardcoreConfig.TougherMobsSection tougher = Settings.get().hardcore.tougherMobs;
        boolean before = tougher.enabled;
        List<Entity> spawned = new ArrayList<>();
        try {
            // Set and put back within this tick
            tougher.enabled = false;
            Mob commanded = create(helper, spawned, EntityTypes.ZOMBIE, EntitySpawnReason.COMMAND, MOB_AT);
            helper.assertFalse(commanded.entityTags().contains(MobBoosts.TOUGH) || commanded.entityTags().contains(MobBoosts.VANILLA),
                    "the zombie was judged with tougher mobs off: " + commanded.entityTags());
            tougher.enabled = true;
            Mob loaded = reload(helper, spawned, commanded);
            assertBoosted(helper, loaded, "a command zombie saved before tougher mobs");
        } finally {
            tougher.enabled = before;
            spawned.forEach(Entity::discard);
        }
        helper.succeed();
    }

    // Saved and taken out of the world, then loaded and added back as a chunk would bring it, which runs the load hook
    private static Mob reload(GameTestHelper helper, List<Entity> spawned, Mob mob) {
        ServerLevel level = helper.getLevel();
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        helper.assertTrue(mob.save(output), "the " + mob.getType() + " was not saved");
        mob.discard();
        Entity loaded = EntityType.loadEntityRecursive(output.buildResult(), level,
                new EntitySpawnRequest(EntitySpawnReason.LOAD, false), EntityProcessor.NOP);
        helper.assertTrue(loaded instanceof Mob, "the " + mob.getType() + " didn't load as a mob");
        helper.assertTrue(level.addFreshEntity(loaded), "the loaded " + mob.getType() + " was not added");
        spawned.add(loaded);
        return (Mob) loaded;
    }

    // Walls and a roof of stone, so the zombie has one way to go, and the cell behind the door closed at the far end
    private static void buildCorridor(GameTestHelper helper) {
        for (int x = 0; x <= 7; x++) {
            for (int y = 0; y <= 1; y++) {
                helper.setBlock(new BlockPos(x, y, LANE - 1), Blocks.STONE);
                helper.setBlock(new BlockPos(x, y, LANE + 1), Blocks.STONE);
            }
            helper.setBlock(new BlockPos(x, 2, LANE), Blocks.STONE);
        }
        for (int y = 0; y <= 1; y++) {
            helper.setBlock(new BlockPos(0, y, LANE), Blocks.STONE);
            helper.setBlock(new BlockPos(6, y, LANE), Blocks.STONE);
        }
        // Facing along the lane, so the closed door stands across it; the bottom half first, then the top
        BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST);
        helper.setBlock(DOOR_AT, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        helper.setBlock(DOOR_AT.above(), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    private static BodyTargetGoal bodyGoal(Mob mob) {
        return ((MobAccessor) mob).yoltTargetSelector().getAvailableGoals().stream()
                .map(WrappedGoal::getGoal)
                .filter(BodyTargetGoal.class::isInstance)
                .map(BodyTargetGoal.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no body goal on " + mob.getType()));
    }

    private static void assertClose(GameTestHelper helper, double actual, double expected, String name) {
        helper.assertTrue(Math.abs(actual - expected) < CLOSE, name + " is " + actual + ", not " + expected);
    }

    // As its spawn reason makes it: EntityType.create records the reason, and adding it to the world loads it
    static <T extends Mob> T create(GameTestHelper helper, List<Entity> spawned, EntityType<T> type, EntitySpawnReason reason,
                                    BlockPos pos) {
        T mob = createFree(helper, spawned, type, reason, pos);
        mob.removeFreeWill();
        return mob;
    }

    private static <T extends Mob> T createFree(GameTestHelper helper, List<Entity> spawned, EntityType<T> type,
                                                EntitySpawnReason reason, BlockPos pos) {
        ServerLevel level = helper.getLevel();
        T mob = type.create(level, reason);
        helper.assertTrue(mob != null, "no " + type + " made");
        Vec3 at = helper.absoluteVec(Vec3.atBottomCenterOf(pos));
        mob.snapTo(at.x, at.y, at.z, 0.0F, 0.0F);
        mob.setPersistenceRequired();
        helper.assertTrue(level.addFreshEntity(mob), "the " + type + " was not added");
        spawned.add(mob);
        return mob;
    }

    static AttributeInstance attribute(LivingEntity entity, Holder<Attribute> attribute) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance == null) {
            throw new IllegalStateException(entity + " has no " + attribute.getRegisteredName());
        }
        return instance;
    }

    private static void assertBoosted(GameTestHelper helper, Mob zombie, String name) {
        helper.assertTrue(zombie.entityTags().contains(MobBoosts.TOUGH), name + " is not tagged " + MobBoosts.TOUGH);
        helper.assertFalse(zombie.entityTags().contains(MobBoosts.VANILLA), name + " is tagged " + MobBoosts.VANILLA);
        assertHealth(helper, zombie, 30.0F, 30.0F, name);
    }

    private static void assertHealth(GameTestHelper helper, LivingEntity mob, float max, float health, String when) {
        helper.assertValueEqual(mob.getMaxHealth(), max, "max health " + when);
        helper.assertValueEqual(mob.getHealth(), health, "health " + when);
    }

    // A fresh player for each hit, so no hurt cooldown cuts it
    private static void assertPlayerTakes(GameTestHelper helper, List<ServerPlayer> players, String name, DamageSource source,
                                          float dealt, float expected) {
        ServerPlayer player = TestPlayers.join(helper, name, GameType.SURVIVAL, PLAYER_AT);
        players.add(player);
        // A test player is invulnerable until its client would say the world loaded
        TestPlayers.markLoaded(player);
        assertTakes(helper, player, source, dealt, expected, name);
    }

    private static void assertTakes(GameTestHelper helper, LivingEntity target, DamageSource source, float dealt, float expected,
                                    String name) {
        target.setHealth(target.getMaxHealth());
        target.damageCooldownTime = 0;
        helper.assertTrue(target.hurtServer(helper.getLevel(), source, dealt), "the hit on " + name + " did not land");
        helper.assertValueEqual(target.getMaxHealth() - target.getHealth(), expected, "damage to " + name);
    }
}
