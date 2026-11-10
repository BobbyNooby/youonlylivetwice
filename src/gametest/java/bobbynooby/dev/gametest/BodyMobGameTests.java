package bobbynooby.dev.gametest;

import bobbynooby.dev.bodies.Bodies;
import bobbynooby.dev.bodies.BodyMobs;
import bobbynooby.dev.bodies.BodyRecord;
import bobbynooby.dev.bodies.BodyRules;
import bobbynooby.dev.bodies.BodyStore;
import bobbynooby.dev.bodies.BodyTargetGoal;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.mixin.MobAccessor;
import bobbynooby.dev.mixin.TargetGoalAccessor;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.TagValueOutput;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

// The scenes stand on the structure's barrier floor. Bodies are only switched on while the body spawns, and
// mobsAttack is on by default. A test that holds a switch or the world clock across ticks runs in a batch of its own.
public class BodyMobGameTests {
    private static final String SOLO = "you-only-live-twice-gametest:solo";
    private static final String DAYLIGHT = "you-only-live-twice-gametest:daylight";
    private static final String BODY_DIFFICULTY = "you-only-live-twice-gametest:body_difficulty";
    private static final BlockPos BODY_POS = new BlockPos(1, 0, 1);
    private static final BlockPos NEXT_TO_BODY = new BlockPos(3, 0, 1);
    private static final BlockPos FOUR_AWAY = new BlockPos(5, 0, 1);
    private static final BlockPos ABOVE_BODY = new BlockPos(3, 3, 1);
    private static final int WAIT_TICKS = 160;
    private static final BiConsumer<Mob, Mannequin> NO_EXTRA_CHECK = (mob, body) -> {
    };
    private static final Runnable NOTHING_TO_RESTORE = () -> {
    };

    // Spec 11.2 warns a mob may need re-aiming every tick, so this shows a zombie walk up and hit
    @GameTest(maxTicks = 300)
    public void zombieAttacksBody(GameTestHelper helper) {
        hurtsBody(helper, "Hunted", spawned -> spawned.add(helper.spawn(EntityTypes.ZOMBIE, FOUR_AWAY)));
    }

    @GameTest(maxTicks = 200, environment = SOLO)
    public void zombieIgnoresBodyWhenSwitchOff(GameTestHelper helper) {
        YoltConfig.BodiesSection bodies = Settings.get().bodies;
        boolean before = bodies.mobsAttack;
        bodies.mobsAttack = false;
        ignoresBody(helper, "SwitchedOff", EntityTypes.ZOMBIE, FOUR_AWAY,
                (zombie, body) -> helper.assertValueEqual(body.getHealth(), body.getMaxHealth(), "body health"),
                () -> bodies.mobsAttack = before);
    }

    // The test above shares this batch and holds the switch off for its wait. So this waits for the hunt to start,
    // and only reads the switch when it turns it off.
    @GameTest(maxTicks = 400, environment = SOLO)
    public void huntStopsWhenSwitchTurnedOff(GameTestHelper helper) {
        YoltConfig.BodiesSection bodies = Settings.get().bodies;
        List<Entity> spawned = new ArrayList<>();
        UUID owner = null;
        boolean waiting = false;
        try {
            ServerPlayer player = join(helper, "Spared");
            owner = player.getUUID();
            Mannequin body = leaveBody(helper, player);
            Mob zombie = helper.spawn(EntityTypes.ZOMBIE, FOUR_AWAY);
            spawned.add(zombie);
            UUID bodyOwner = owner;
            // Empty until the switch is turned off
            AtomicReference<Boolean> before = new AtomicReference<>();
            Runnable finish = () -> {
                Boolean was = before.getAndSet(null);
                if (was != null) {
                    bodies.mobsAttack = was;
                }
                cleanUp(helper, bodyOwner, spawned);
            };
            helper.runBeforeTestEnd(finish);
            helper.startSequence()
                    .thenWaitUntil(() -> helper.assertTrue(zombie.getTarget() == body, "the zombie has not gone for the body"))
                    .thenExecute(() -> {
                        before.set(bodies.mobsAttack);
                        bodies.mobsAttack = false;
                    })
                    // A mob rechecks its running target goals every other tick
                    .thenExecuteAfter(4, () -> {
                        try {
                            helper.assertFalse(zombie.getTarget() == body, "the zombie still goes for the body");
                        } finally {
                            finish.run();
                        }
                    })
                    .thenSucceed();
            waiting = true;
        } finally {
            if (!waiting) {
                cleanUp(helper, owner, spawned);
            }
        }
    }

    @GameTest(maxTicks = 200)
    public void endermanIgnoresBody(GameTestHelper helper) {
        ignoresBody(helper, "Stared", EntityTypes.ENDERMAN, NEXT_TO_BODY,
                (enderman, body) -> helper.assertTrue(enderman.getTarget() == null, "the enderman has a target"),
                NOTHING_TO_RESTORE);
    }

    @GameTest(maxTicks = 200)
    public void zombifiedPiglinIgnoresBody(GameTestHelper helper) {
        ignoresBody(helper, "Calm", EntityTypes.ZOMBIFIED_PIGLIN, NEXT_TO_BODY, NO_EXTRA_CHECK, NOTHING_TO_RESTORE);
    }

    // Its own batch, which sets the clock to noon and puts it back afterwards. Noon in a shared batch would set undead
    // in other tests on fire, since barriers let skylight through. Not solo: a solo test holds mobsAttack off meanwhile.
    @GameTest(maxTicks = 200, skyAccess = true, environment = DAYLIGHT)
    public void spiderIgnoresBodyInDaylight(GameTestHelper helper) {
        ignoresBody(helper, "Daylight", EntityTypes.SPIDER, NEXT_TO_BODY,
                // Proves the scene: spiders hunt below this
                (spider, body) -> helper.assertTrue(spider.getLightLevelDependentMagicValue() >= 0.5F, "no daylight at the spider"),
                NOTHING_TO_RESTORE);
    }

    // Four blocks off and without its goals, so it stays put: a blast that killed the body would kill its owner
    @GameTest(maxTicks = 100)
    public void creeperExplosionHurtsBody(GameTestHelper helper) {
        hurtsBody(helper, "Blasted", spawned -> {
            Creeper creeper = helper.spawnWithNoFreeWill(EntityTypes.CREEPER, FOUR_AWAY);
            spawned.add(creeper);
            creeper.ignite();
        });
    }

    // What its chunk saving and loading again does to it
    @GameTest
    public void bodySurvivesSaveAndLoad(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Saved");
        UUID owner = player.getUUID();
        try {
            player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            Mannequin body = leaveBody(helper, player);
            TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
            helper.assertTrue(body.save(output), "body not saved");

            // Never added to the world
            Entity loaded = EntityType.loadEntityRecursive(output.buildResult(), helper.getLevel(),
                    new EntitySpawnRequest(EntitySpawnReason.LOAD, false), EntityProcessor.NOP);
            helper.assertTrue(loaded instanceof Mannequin, "not loaded as a mannequin");
            Mannequin copy = (Mannequin) loaded;
            helper.assertTrue(copy.entityTags().contains(Bodies.TAG), "body tag lost");
            helper.assertTrue(BodyRules.isBody(copy), "loaded body no longer counts as a body");
            helper.assertValueEqual(copy.getPose(), Pose.SWIMMING, "pose");
            helper.assertTrue(copy.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE), "chestplate lost");
            helper.assertTrue(copy.getItemBySlot(EquipmentSlot.MAINHAND).is(Items.IRON_SWORD), "sword lost");
            helper.assertValueEqual(copy.getProfile().partialProfile().id(), owner, "profile id");
        } finally {
            cleanUp(helper, owner, List.of());
        }
        helper.succeed();
    }

    // A mob loads again each time its chunk starts ticking again. Priorities are those of each mob's player goal in 26.3.
    @GameTest
    public void huntersGetOneBodyGoal(GameTestHelper helper) {
        List<Entity> spawned = new ArrayList<>();
        try {
            Mob zombie = spawnMob(helper, spawned, EntityTypes.ZOMBIE);
            BodyMobs.addBodyGoal(zombie);
            BodyMobs.addBodyGoal(zombie);
            helper.assertValueEqual(bodyGoalPriorities(zombie), List.of(2), "zombie's body goals");
            assertBodyGoals(helper, spawned, EntityTypes.ZOMBIE_VILLAGER, List.of(2));
            assertBodyGoals(helper, spawned, EntityTypes.SPIDER, List.of(2));
            assertBodyGoals(helper, spawned, EntityTypes.CREEPER, List.of(1));
            assertBodyGoals(helper, spawned, EntityTypes.ILLUSIONER, List.of(2));
            assertBodyGoals(helper, spawned, EntityTypes.GHAST, List.of(1));
            assertBodyGoals(helper, spawned, EntityTypes.SHULKER, List.of(2));
            // No player goal of the usual kind
            assertBodyGoals(helper, spawned, EntityTypes.PHANTOM, List.of(1));
            assertBodyGoals(helper, spawned, EntityTypes.ENDERMAN, List.of());
            assertBodyGoals(helper, spawned, EntityTypes.ZOMBIFIED_PIGLIN, List.of());
            assertBodyGoals(helper, spawned, EntityTypes.IRON_GOLEM, List.of());
        } finally {
            spawned.forEach(Entity::discard);
        }
        helper.succeed();
    }

    // A mob's hit of 4 scaled as for a player (26.3 Player.hurtServer): Easy min(4 / 2 + 1, 4), Normal 4, Hard
    // 4 * 3 / 2, Peaceful none. Its own batch, since it changes the difficulty; it puts it back within the tick.
    @GameTest(environment = BODY_DIFFICULTY)
    public void bodyMobDamageFollowsDifficulty(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Difficulty before = level.getDifficulty();
        List<Entity> spawned = new ArrayList<>();
        ServerPlayer player = join(helper, "Scaled");
        UUID owner = player.getUUID();
        try {
            Mannequin body = leaveBody(helper, player);
            Zombie zombie = helper.spawnWithNoFreeWill(EntityTypes.ZOMBIE, FOUR_AWAY);
            spawned.add(zombie);
            DamageSource attack = level.damageSources().mobAttack(zombie);
            assertHit(helper, body, Difficulty.EASY, attack, 3.0F);
            assertHit(helper, body, Difficulty.NORMAL, attack, 4.0F);
            assertHit(helper, body, Difficulty.HARD, attack, 6.0F);
            assertHit(helper, body, Difficulty.PEACEFUL, attack, 0.0F);
            // As for a player, only a hit a mob caused scales
            assertHit(helper, body, Difficulty.HARD, level.damageSources().generic(), 4.0F);
            // A mannequin that isn't a body stays vanilla
            Mannequin plain = helper.spawn(EntityTypes.MANNEQUIN, NEXT_TO_BODY);
            spawned.add(plain);
            assertHit(helper, plain, Difficulty.HARD, attack, 4.0F);
        } finally {
            level.getServer().setDifficulty(before, true);
            cleanUp(helper, owner, spawned);
        }
        helper.succeed();
    }

    private static void assertHit(GameTestHelper helper, Mannequin target, Difficulty difficulty, DamageSource source,
                                  float expected) {
        helper.getLevel().getServer().setDifficulty(difficulty, true);
        target.setHealth(target.getMaxHealth());
        // Past the hurt cooldown, so the hit lands in full
        target.damageCooldownTime = 0;
        boolean landed = target.hurtServer(helper.getLevel(), source, 4.0F);
        helper.assertValueEqual(landed, expected > 0.0F, difficulty.getSerializedName() + " hit landed");
        helper.assertValueEqual(target.getMaxHealth() - target.getHealth(), expected,
                difficulty.getSerializedName() + " damage");
    }

    // An evoker keeps after a player out of sight for 300 ticks (26.3 Evoker.registerGoals); most mobs for 60
    @GameTest
    public void evokerBodyGoalKeepsItsMemory(GameTestHelper helper) {
        List<Entity> spawned = new ArrayList<>();
        try {
            helper.assertValueEqual(unseenMemoryTicks(spawnMob(helper, spawned, EntityTypes.EVOKER)), 300,
                    "evoker's body goal memory");
            helper.assertValueEqual(unseenMemoryTicks(spawnMob(helper, spawned, EntityTypes.ZOMBIE)), 60,
                    "zombie's body goal memory");
        } finally {
            spawned.forEach(Entity::discard);
        }
        helper.succeed();
    }

    // BodyTargetGoal is final, so the compiler only allows the cast to the mixed-in interface through Object
    private static int unseenMemoryTicks(Mob mob) {
        return ((TargetGoalAccessor) (Object) bodyGoal(mob)).yoltUnseenMemoryTicks();
    }

    // A phantom's own player goal sets its target without claiming it, which is what this does by hand
    @GameTest
    public void phantomDropsBodyGoalForPlayer(GameTestHelper helper) {
        List<Entity> spawned = new ArrayList<>();
        ServerPlayer owner = join(helper, "Dreamer");
        ServerPlayer awake = null;
        try {
            Mannequin body = leaveBody(helper, owner);
            awake = join(helper, "Awake");
            Phantom phantom = helper.spawnWithNoFreeWill(EntityTypes.PHANTOM, ABOVE_BODY);
            spawned.add(phantom);
            BodyTargetGoal goal = bodyGoal(phantom);
            phantom.setTarget(body);
            helper.assertTrue(goal.canContinueToUse(), "the body goal let go of the body");
            phantom.setTarget(awake);
            helper.assertFalse(goal.canContinueToUse(), "the body goal kept going after the phantom picked a player");
            goal.stop();
            helper.assertTrue(phantom.getTarget() == awake, "stopping the body goal took the player off the phantom");
        } finally {
            if (awake != null) {
                TestPlayers.leave(awake);
            }
            cleanUp(helper, owner.getUUID(), spawned);
        }
        helper.succeed();
    }

    @GameTest
    public void chasingPhantomIgnoresBody(GameTestHelper helper) {
        List<Entity> spawned = new ArrayList<>();
        ServerPlayer owner = join(helper, "Sleepless");
        ServerPlayer awake = null;
        try {
            leaveBody(helper, owner);
            awake = join(helper, "Chased");
            Phantom phantom = helper.spawnWithNoFreeWill(EntityTypes.PHANTOM, ABOVE_BODY);
            spawned.add(phantom);
            BodyTargetGoal goal = bodyGoal(phantom);
            phantom.setTarget(awake);
            helper.assertFalse(canUseWithin(goal), "the body goal would take the phantom off the player it chases");
            helper.assertTrue(phantom.getTarget() == awake, "the phantom no longer chases the player");
            // Proves the scene: with nobody to chase, the phantom does go for the body
            phantom.setTarget(null);
            helper.assertTrue(canUseWithin(goal), "a phantom with no target never went for the body");
        } finally {
            if (awake != null) {
                TestPlayers.leave(awake);
            }
            cleanUp(helper, owner.getUUID(), spawned);
        }
        helper.succeed();
    }

    // canUse only looks for a target one try in five, so a check gets plenty of tries
    private static boolean canUseWithin(BodyTargetGoal goal) {
        for (int i = 0; i < 100; i++) {
            if (goal.canUse()) {
                return true;
            }
        }
        return false;
    }

    private static BodyTargetGoal bodyGoal(Mob mob) {
        return ((MobAccessor) mob).yoltTargetSelector().getAvailableGoals().stream()
                .map(WrappedGoal::getGoal)
                .filter(BodyTargetGoal.class::isInstance)
                .map(BodyTargetGoal.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no body goal on " + mob.getType()));
    }

    private static void assertBodyGoals(GameTestHelper helper, List<Entity> spawned, EntityType<? extends Mob> type,
                                        List<Integer> priorities) {
        helper.assertValueEqual(bodyGoalPriorities(spawnMob(helper, spawned, type)), priorities,
                EntityType.getKey(type) + " body goal priorities");
    }

    // All in one spot: they are gone again before they ever tick
    private static Mob spawnMob(GameTestHelper helper, List<Entity> spawned, EntityType<? extends Mob> type) {
        Mob mob = helper.spawn(type, new BlockPos(3, 0, 3));
        spawned.add(mob);
        return mob;
    }

    private static List<Integer> bodyGoalPriorities(Mob mob) {
        return ((MobAccessor) mob).yoltTargetSelector().getAvailableGoals().stream()
                .filter(goal -> goal.getGoal() instanceof BodyTargetGoal)
                .map(WrappedGoal::getPriority)
                .toList();
    }

    // Succeeds once the body has lost health. Cleans up then, or just before a timeout.
    private static void hurtsBody(GameTestHelper helper, String name, Consumer<List<Entity>> spawnMobs) {
        List<Entity> spawned = new ArrayList<>();
        UUID owner = null;
        boolean waiting = false;
        try {
            ServerPlayer player = join(helper, name);
            owner = player.getUUID();
            Mannequin body = leaveBody(helper, player);
            spawnMobs.accept(spawned);
            UUID bodyOwner = owner;
            helper.runBeforeTestEnd(() -> cleanUp(helper, bodyOwner, spawned));
            helper.succeedWhen(() -> {
                helper.assertTrue(body.getHealth() < body.getMaxHealth(), "the body has not been hurt");
                cleanUp(helper, bodyOwner, spawned);
            });
            waiting = true;
        } finally {
            if (!waiting) {
                cleanUp(helper, owner, spawned);
            }
        }
    }

    // The mob stands near a fresh body for the wait. Its target is checked every tick, then atEnd runs.
    private static void ignoresBody(GameTestHelper helper, String name, EntityType<? extends Mob> type, BlockPos at,
                                    BiConsumer<Mob, Mannequin> atEnd, Runnable restore) {
        List<Entity> spawned = new ArrayList<>();
        UUID owner = null;
        boolean waiting = false;
        try {
            ServerPlayer player = join(helper, name);
            owner = player.getUUID();
            Mannequin body = leaveBody(helper, player);
            Mob mob = helper.spawn(type, at);
            spawned.add(mob);
            AtomicBoolean targeted = new AtomicBoolean();
            helper.onEachTick(() -> {
                if (mob.getTarget() == body) {
                    targeted.set(true);
                }
            });
            UUID bodyOwner = owner;
            helper.runAfterDelay(WAIT_TICKS, () -> {
                try {
                    helper.assertFalse(targeted.get(), "the " + name + " mob went for the body");
                    atEnd.accept(mob, body);
                } finally {
                    restore.run();
                    cleanUp(helper, bodyOwner, spawned);
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                restore.run();
                cleanUp(helper, owner, spawned);
            }
        }
    }

    private static ServerPlayer join(GameTestHelper helper, String name) {
        return TestPlayers.join(helper, name, GameType.SURVIVAL, BODY_POS);
    }

    // A real logout. The test server keeps bodies off, so the default switches go on just for it.
    private static Mannequin leaveBody(GameTestHelper helper, ServerPlayer player) {
        YoltConfig config = Settings.get();
        YoltConfig.BodiesSection before = config.bodies;
        config.bodies = new YoltConfig.BodiesSection();
        try {
            TestPlayers.leave(player);
        } finally {
            config.bodies = before;
        }
        Optional<BodyRecord> record = store(helper).byOwner(player.getUUID());
        helper.assertTrue(record.isPresent(), "no body record");
        Entity entity = helper.getLevel().getEntity(record.get().bodyEntity());
        helper.assertTrue(entity instanceof Mannequin, "no mannequin for the record");
        return (Mannequin) entity;
    }

    private static BodyStore store(GameTestHelper helper) {
        return BodyStore.get(helper.getLevel().getServer());
    }

    // Anything left would sit in another test's way. Bodies are off again by now, so a player still online
    // leaves without making another one.
    private static void cleanUp(GameTestHelper helper, UUID owner, List<Entity> spawned) {
        spawned.forEach(Entity::discard);
        if (owner == null) {
            return;
        }
        ServerPlayer online = helper.getLevel().getServer().getPlayerList().getPlayer(owner);
        if (online != null) {
            TestPlayers.leave(online);
        }
        helper.getLevel().getEntities(EntityTypes.MANNEQUIN, body -> !body.isRemoved() && body.entityTags().contains(Bodies.TAG)
                && owner.equals(body.getProfile().partialProfile().id())).forEach(Entity::discard);
        store(helper).remove(owner);
    }
}
