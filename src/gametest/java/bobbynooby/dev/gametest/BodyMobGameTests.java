package bobbynooby.dev.gametest;

import bobbynooby.dev.bodies.Bodies;
import bobbynooby.dev.bodies.BodyMobs;
import bobbynooby.dev.bodies.BodyRules;
import bobbynooby.dev.bodies.BodyTargetGoal;
import bobbynooby.dev.bodies.PlayerBody;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.mixin.MobAccessor;
import bobbynooby.dev.mixin.TargetGoalAccessor;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
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
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.TagValueOutput;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static bobbynooby.dev.gametest.BodyTestKit.leaveBodyWithDefaults;

// The scenes stand on the structure's barrier floor. Bodies are only switched on while the body spawns, and
// mobsAttack is on by default. A test that holds a switch or the world clock across ticks runs in a batch of its own.
public class BodyMobGameTests {
    // Each of these holds mobsAttack off across ticks, so each has a batch of its own
    private static final String MOBS_IGNORE = "you-only-live-twice-gametest:mobs_ignore";
    private static final String MOBS_STOP = "you-only-live-twice-gametest:mobs_stop";
    private static final SharedHold IGNORE_HOLD = new SharedHold();
    private static final SharedHold STOP_HOLD = new SharedHold();
    // The clock stopped at midnight, so daylight can't decide a hunt: a zombie burns in it, and a spider won't start one
    private static final String MIDNIGHT = "you-only-live-twice-gametest:midnight";
    private static final String DAYLIGHT = "you-only-live-twice-gametest:daylight";
    private static final String BODY_DIFFICULTY = "you-only-live-twice-gametest:body_difficulty";
    // A player body that stays across ticks is a player to every mob, and the piglin is a mob to every player body near
    // it, so this has a batch of its own rather than player_body's
    private static final String PIGLIN_BODY = "you-only-live-twice-gametest:piglin_body";
    private static final BlockPos BODY_POS = new BlockPos(1, 0, 1);
    private static final BlockPos BESIDE_BODY = new BlockPos(2, 0, 1);
    private static final BlockPos NEXT_TO_BODY = new BlockPos(3, 0, 1);
    private static final BlockPos FOUR_AWAY = new BlockPos(5, 0, 1);
    private static final BlockPos ABOVE_BODY = new BlockPos(3, 3, 1);
    private static final int WAIT_TICKS = 160;
    private static final BiConsumer<Mob, Mannequin> NO_EXTRA_CHECK = (mob, body) -> {
    };
    private static final Runnable NOTHING_TO_RESTORE = () -> {
    };

    // Spec 11.2 warns a mob may need re-aiming every tick, so this shows a zombie walk up and hit
    @GameTest(maxTicks = 300, environment = MIDNIGHT)
    public void zombieAttacksBody(GameTestHelper helper) {
        hurtsBody(helper, "Hunted", spawned -> {
            Mob zombie = helper.spawn(EntityTypes.ZOMBIE, FOUR_AWAY);
            spawned.add(zombie);
            return zombie;
        }, (zombie, body) -> helper.assertTrue(body.getLastHurtByMob() == zombie,
                "the body was last hurt by " + body.getLastHurtByMob() + ", not the zombie"));
    }

    @GameTest(maxTicks = 200, environment = MOBS_IGNORE)
    public void zombieIgnoresBodyWhenSwitchOff(GameTestHelper helper) {
        holdMobsAttackOff(IGNORE_HOLD);
        ignoresBody(helper, "SwitchedOff", EntityTypes.ZOMBIE, FOUR_AWAY,
                (zombie, body) -> helper.assertValueEqual(body.getHealth(), body.getMaxHealth(), "body health"),
                BodyTestKit.once(IGNORE_HOLD::release));
    }

    // Waits for the hunt to start, then turns the switch off and keeps it off until the end. A copy in a flake hunt
    // needs the switch on until its own hunt starts, so it waits for any copy holding it off to finish.
    @GameTest(maxTicks = 400, environment = MOBS_STOP)
    public void huntStopsWhenSwitchTurnedOff(GameTestHelper helper) {
        List<Entity> spawned = new ArrayList<>();
        UUID owner = null;
        boolean waiting = false;
        try {
            ServerPlayer player = join(helper, "Spared");
            owner = player.getUUID();
            Mannequin body = leaveBodyWithDefaults(helper, player);
            Mob zombie = helper.spawn(EntityTypes.ZOMBIE, FOUR_AWAY);
            spawned.add(zombie);
            UUID bodyOwner = owner;
            // Set once the switch is turned off
            AtomicBoolean holding = new AtomicBoolean();
            Runnable finish = BodyTestKit.once(() -> {
                if (holding.get()) {
                    STOP_HOLD.release();
                }
                cleanUp(helper, bodyOwner, spawned);
            });
            helper.runBeforeTestEnd(finish);
            helper.startSequence()
                    .thenWaitUntil(() -> {
                        helper.assertFalse(STOP_HOLD.held(), "another copy holds the switch off");
                        helper.assertTrue(zombie.getTarget() == body, "the zombie has not gone for the body");
                    })
                    .thenExecute(() -> {
                        holdMobsAttackOff(STOP_HOLD);
                        holding.set(true);
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

    // Its own batch, which stops the clock at noon, keeps the weather clear, and puts both back afterwards. Noon in a
    // shared batch would set undead in other tests on fire, since barriers let skylight through. Stopped, since a test
    // only starts once its chunks are loaded, and the idle test server can run thousands of ticks before that, into
    // the night or a thunderstorm, where a spider hunts. A storm from the batch just before, such as night_thunder's,
    // still darkens the sky while its rain and thunder fade, up to 100 ticks, so the spider comes once they have.
    // The light call is deprecated in 26.3 with nothing to replace it; it is the one the spider's rule reads
    @SuppressWarnings("deprecation")
    @GameTest(maxTicks = 320, skyAccess = true, environment = DAYLIGHT)
    public void spiderIgnoresBodyInDaylight(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(level.getRainLevel(1.0F) == 0 && level.getThunderLevel(1.0F) == 0,
                        "the last storm's rain and thunder haven't faded"))
                .thenExecute(() -> ignoresBody(helper, "Daylight", EntityTypes.SPIDER, NEXT_TO_BODY,
                        // Proves the scene: spiders hunt below this
                        (spider, body) -> helper.assertTrue(spider.getLightLevelDependentMagicValue() >= 0.5F, "no daylight at the spider"),
                        NOTHING_TO_RESTORE));
    }

    // The drowned's own player rule (26.3 Drowned.okTarget): while it is bright outside it only goes after someone in
    // water, so a body on land is left alone. In the daylight batch, waiting for the last storm to fade, as the spider
    // test does, since a storm darkens the sky.
    @GameTest(maxTicks = 320, environment = DAYLIGHT)
    public void drownedIgnoresBodyOnLand(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(level.getRainLevel(1.0F) == 0 && level.getThunderLevel(1.0F) == 0,
                        "the last storm's rain and thunder haven't faded"))
                .thenExecute(() -> ignoresBody(helper, "Dry", EntityTypes.DROWNED, NEXT_TO_BODY,
                        // Proves the scene: the rule lets a drowned hunt at night, or a body in water
                        (drowned, body) -> {
                            helper.assertTrue(level.isBrightOutside(), "it is not bright outside");
                            helper.assertFalse(body.isInWater(), "the body is in water");
                        },
                        NOTHING_TO_RESTORE));
    }

    // The slime's own player rule (26.3 Slime.addTargetingGoals): nothing more than 4 blocks above or below it. A big
    // slime's search reaches higher than that, so a body 6 blocks over its feet is in the search but left alone, and
    // found once the slime is 3 blocks below it.
    @GameTest
    public void slimeKeepsItsPlayerRule(GameTestHelper helper) {
        List<Entity> spawned = new ArrayList<>();
        ServerPlayer owner = TestPlayers.join(helper, "Bounced", GameType.SURVIVAL, BODY_POS.above(6));
        try {
            leaveBodyWithDefaults(helper, owner);
            Slime slime = helper.spawnWithNoFreeWill(EntityTypes.SLIME, BODY_POS);
            spawned.add(slime);
            slime.setSize(8, true);
            BodyTargetGoal goal = bodyGoal(slime);
            helper.assertFalse(canUseWithin(goal), "a slime 6 blocks below the body went for it");
            slime.setPos(slime.getX(), slime.getY() + 3, slime.getZ());
            helper.assertTrue(canUseWithin(goal), "a slime 3 blocks below the body never went for it");
        } finally {
            cleanUp(helper, owner.getUUID(), spawned);
        }
        helper.succeed();
    }

    // The guardian's own rule (26.3 Guardian.GuardianAttackSelector): nothing within 3 blocks, where its beam would
    // stop. One 2 blocks from the body leaves it alone, and one 4 blocks away finds it.
    @GameTest
    public void guardianIgnoresBodyWithinThreeBlocks(GameTestHelper helper) {
        List<Entity> spawned = new ArrayList<>();
        ServerPlayer owner = join(helper, "Beamed");
        try {
            leaveBodyWithDefaults(helper, owner);
            Mob near = helper.spawnWithNoFreeWill(EntityTypes.GUARDIAN, NEXT_TO_BODY);
            spawned.add(near);
            Mob far = helper.spawnWithNoFreeWill(EntityTypes.GUARDIAN, FOUR_AWAY);
            spawned.add(far);
            helper.assertFalse(canUseWithin(bodyGoal(near)), "a guardian 2 blocks from the body went for it");
            helper.assertTrue(canUseWithin(bodyGoal(far)), "a guardian 4 blocks from the body never went for it");
        } finally {
            cleanUp(helper, owner.getUUID(), spawned);
        }
        helper.succeed();
    }

    // A phantom circles high above whoever it is after, so its search reaches 64 blocks up and down, as its own player
    // search does (26.3 Phantom.PhantomAttackPlayerTargetGoal). A body 40 blocks below is found, where a mob's usual
    // search reaches only 4 blocks up and down. Open to the sky, so nothing is between them.
    @GameTest(skyAccess = true)
    public void phantomFindsBodyFarBelow(GameTestHelper helper) {
        List<Entity> spawned = new ArrayList<>();
        ServerPlayer owner = join(helper, "Deep");
        try {
            Mannequin body = leaveBodyWithDefaults(helper, owner);
            Phantom phantom = helper.spawnWithNoFreeWill(EntityTypes.PHANTOM, BODY_POS.above(40));
            spawned.add(phantom);
            BodyTargetGoal goal = bodyGoal(phantom);
            helper.assertTrue(canUseWithin(goal), "a phantom 40 blocks above the body never went for it");
            goal.start();
            helper.assertTrue(phantom.getTarget() == body, "the phantom went for " + phantom.getTarget() + ", not the body");
        } finally {
            cleanUp(helper, owner.getUUID(), spawned);
        }
        helper.succeed();
    }

    // The dark control for the daylight test: at midnight a spider does go after a body and hit it. It starts a block
    // off, inside the 2 blocks where a spider never leaps (26.3 LeapAtTargetGoal), since each leap holds off its next
    // bite for a second and can land it past the body. The test waits for the hunt first, then for the hit.
    @GameTest(maxTicks = 300, environment = MIDNIGHT)
    public void spiderHuntsBodyAtMidnight(GameTestHelper helper) {
        List<Entity> spawned = new ArrayList<>();
        UUID owner = null;
        boolean waiting = false;
        try {
            ServerPlayer player = join(helper, "Night");
            owner = player.getUUID();
            Mannequin body = leaveBodyWithDefaults(helper, player);
            Mob spider = helper.spawn(EntityTypes.SPIDER, BESIDE_BODY);
            spawned.add(spider);
            UUID bodyOwner = owner;
            Runnable finish = BodyTestKit.once(() -> cleanUp(helper, bodyOwner, spawned));
            helper.runBeforeTestEnd(finish);
            helper.startSequence()
                    .thenWaitUntil(() -> helper.assertTrue(spider.getTarget() == body,
                            "the spider has not gone for the body: " + whereIs(spider)))
                    .thenWaitUntil(() -> {
                        helper.assertTrue(body.getHealth() < body.getMaxHealth(), "the body has not been hurt: " + whereIs(spider));
                        helper.assertTrue(body.getLastHurtByMob() == spider,
                                "the body was last hurt by " + body.getLastHurtByMob() + ", not the spider");
                    })
                    .thenExecute(finish)
                    .thenSucceed();
            waiting = true;
        } finally {
            if (!waiting) {
                cleanUp(helper, owner, spawned);
            }
        }
    }

    // A brain mob finds players through its own senses, not goals, so it never gets the body goal, and a player body is
    // a player to it: a piglin goes after one, as it would after its owner without gold. The mannequin body, nearer to
    // it, is the control and is never its target.
    @GameTest(maxTicks = 200, environment = PIGLIN_BODY)
    public void piglinHuntsPlayerBody(GameTestHelper helper) {
        List<Entity> spawned = new ArrayList<>();
        ServerPlayer playerOwner = join(helper, "Hunted");
        ServerPlayer mannequinOwner = TestPlayers.join(helper, "Overlooked", GameType.SURVIVAL, NEXT_TO_BODY);
        UUID playerId = playerOwner.getUUID();
        UUID mannequinId = mannequinOwner.getUUID();
        Runnable finish = BodyTestKit.once(() -> {
            try {
                cleanUp(helper, playerId, spawned);
            } finally {
                BodyTestKit.cleanUp(helper, mannequinId);
            }
        });
        YoltConfig.BodiesSection before = BodyTestKit.usePlayerBodies();
        boolean waiting = false;
        try {
            PlayerBody playerBody = BodyTestKit.leavePlayerBody(helper, playerOwner);
            Settings.get().bodies.type = YoltConfig.BodyType.MANNEQUIN;
            Mannequin mannequin = BodyTestKit.leaveBody(helper, mannequinOwner);
            Settings.get().bodies = before;
            Piglin piglin = helper.spawn(EntityTypes.PIGLIN, FOUR_AWAY);
            spawned.add(piglin);
            // It would turn into a zombified piglin in the overworld after 300 ticks
            piglin.setImmuneToZombification(true);
            AtomicBoolean mannequinTargeted = new AtomicBoolean();
            helper.onEachTick(() -> {
                if (piglin.getTarget() == mannequin) {
                    mannequinTargeted.set(true);
                }
            });
            helper.runBeforeTestEnd(finish);
            helper.succeedWhen(() -> {
                helper.assertFalse(mannequinTargeted.get(), "the piglin went for the mannequin body");
                helper.assertTrue(piglin.getTarget() == playerBody, "the piglin has not gone for the player body");
                finish.run();
            });
            waiting = true;
        } finally {
            Settings.get().bodies = before;
            if (!waiting) {
                finish.run();
            }
        }
    }

    // Four blocks off and without its goals, so it stays put: a blast that killed the body would kill its owner
    @GameTest(maxTicks = 100, environment = MIDNIGHT)
    public void creeperExplosionHurtsBody(GameTestHelper helper) {
        hurtsBody(helper, "Blasted", spawned -> {
            Creeper creeper = helper.spawnWithNoFreeWill(EntityTypes.CREEPER, FOUR_AWAY);
            spawned.add(creeper);
            creeper.ignite();
            return creeper;
        }, (creeper, body) -> {
            // The creeper is gone once it blows up, so it is the blast's own source that names it
            DamageSource hurtBy = body.getLastDamageSource();
            helper.assertTrue(hurtBy != null && hurtBy.is(DamageTypeTags.IS_EXPLOSION) && hurtBy.getEntity() == creeper,
                    "the body was last hurt by " + hurtBy + ", not the creeper's blast");
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
            Mannequin body = leaveBodyWithDefaults(helper, player);
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

    // A hit of 4 scaled as for a player (26.3 Player.hurtServer): Easy min(4 / 2 + 1, 4), Normal 4, Hard 4 * 3 / 2,
    // Peaceful none. Its own batch, since it changes the difficulty; it puts it back within the tick.
    @GameTest(environment = BODY_DIFFICULTY)
    public void bodyMobDamageFollowsDifficulty(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Difficulty before = level.getDifficulty();
        List<Entity> spawned = new ArrayList<>();
        ServerPlayer player = join(helper, "Scaled");
        UUID owner = player.getUUID();
        try {
            Mannequin body = leaveBodyWithDefaults(helper, player);
            Zombie zombie = helper.spawnWithNoFreeWill(EntityTypes.ZOMBIE, FOUR_AWAY);
            spawned.add(zombie);
            DamageSource attack = level.damageSources().mobAttack(zombie);
            assertHit(helper, body, Difficulty.EASY, attack, 3.0F);
            assertHit(helper, body, Difficulty.NORMAL, attack, 4.0F);
            assertHit(helper, body, Difficulty.HARD, attack, 6.0F);
            assertHit(helper, body, Difficulty.PEACEFUL, attack, 0.0F);
            // As for a player, only a hit a mob caused scales: not one with no attacker, nor a player's
            assertHit(helper, body, Difficulty.HARD, level.damageSources().generic(), 4.0F);
            assertHit(helper, body, Difficulty.HARD, level.damageSources().playerAttack(player), 4.0F);
            // An explosion scales whatever set it off (26.3 DamageTypes: DamageScaling.ALWAYS)
            DamageSource blast = level.damageSources().explosion(null, null);
            assertHit(helper, body, Difficulty.EASY, blast, 3.0F);
            assertHit(helper, body, Difficulty.HARD, blast, 6.0F);
            assertHit(helper, body, Difficulty.PEACEFUL, blast, 0.0F);
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
            Mannequin body = leaveBodyWithDefaults(helper, owner);
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
            leaveBodyWithDefaults(helper, owner);
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

    // What a hunter is after, and where it stands, for a failure message
    private static String whereIs(Mob mob) {
        return "it is after " + mob.getTarget() + " and stands at " + mob.position();
    }

    // Succeeds once the body has lost health and hurtBy finds the spawned mob did it. Cleans up then, or just before a
    // timeout.
    private static void hurtsBody(GameTestHelper helper, String name, Function<List<Entity>, Mob> spawnMob,
                                  BiConsumer<Mob, Mannequin> hurtBy) {
        List<Entity> spawned = new ArrayList<>();
        UUID owner = null;
        boolean waiting = false;
        try {
            ServerPlayer player = join(helper, name);
            owner = player.getUUID();
            Mannequin body = leaveBodyWithDefaults(helper, player);
            Mob mob = spawnMob.apply(spawned);
            UUID bodyOwner = owner;
            helper.runBeforeTestEnd(() -> cleanUp(helper, bodyOwner, spawned));
            helper.succeedWhen(() -> {
                helper.assertTrue(body.getHealth() < body.getMaxHealth(), "the body has not been hurt: " + whereIs(mob));
                hurtBy.accept(mob, body);
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
            Mannequin body = leaveBodyWithDefaults(helper, player);
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

    // Copies of one test in a flake hunt share the hold, so the last to finish turns the switch back on
    private static void holdMobsAttackOff(SharedHold hold) {
        YoltConfig.BodiesSection bodies = Settings.get().bodies;
        hold.take(() -> {
            boolean mobsAttack = bodies.mobsAttack;
            return () -> bodies.mobsAttack = mobsAttack;
        });
        bodies.mobsAttack = false;
    }

    private static ServerPlayer join(GameTestHelper helper, String name) {
        return TestPlayers.join(helper, name, GameType.SURVIVAL, BODY_POS);
    }

    // The mobs, then the owner and what they left. Bodies are off again by now, so an owner still online leaves without
    // making another body.
    private static void cleanUp(GameTestHelper helper, UUID owner, List<Entity> spawned) {
        spawned.forEach(Entity::discard);
        if (owner != null) {
            BodyTestKit.cleanUp(helper, owner);
        }
    }
}
