package bobbynooby.dev.gametest;

import bobbynooby.dev.config.HardcoreConfig;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.hardcore.MobBoosts;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

import static bobbynooby.dev.gametest.BodyTestKit.leaveBodyWithDefaults;

// A mob is judged as it loads, so each feature stays on through the test's environment rather than being switched on
// after the mob is there. Mobs are made the way their spawn reason makes them in 26.3: EntityType.create with that
// reason, which is where Fabric records it, then added to the world, which runs the load hook. None of them gets
// vanilla's finalizeSpawn, so no random leader or follow-range bonus muddies the numbers, and none has free will.
public class HardcoreMobsGameTests {
    private static final String MOBS = "you-only-live-twice-gametest:hardcore_mobs";
    private static final String MOBS_ALL = "you-only-live-twice-gametest:hardcore_mobs_all";
    private static final String MOBS_TRIAL = "you-only-live-twice-gametest:hardcore_mobs_trial";
    private static final String MOBS_OFF = "you-only-live-twice-gametest:hardcore_mobs_off";
    private static final BlockPos PLAYER_AT = new BlockPos(1, 0, 1);
    private static final BlockPos MOB_AT = new BlockPos(4, 0, 4);
    private static final BlockPos VILLAGER_AT = new BlockPos(6, 0, 1);

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
            assertHealth(helper, zombie, 20.0F, 20.0F, "the zombie");
        } finally {
            spawned.forEach(Entity::discard);
        }
        helper.succeed();
    }

    // As its spawn reason makes it: EntityType.create records the reason, and adding it to the world loads it
    static <T extends Mob> T create(GameTestHelper helper, List<Entity> spawned, EntityType<T> type, EntitySpawnReason reason,
                                    BlockPos pos) {
        ServerLevel level = helper.getLevel();
        T mob = type.create(level, reason);
        helper.assertTrue(mob != null, "no " + type + " made");
        Vec3 at = helper.absoluteVec(Vec3.atBottomCenterOf(pos));
        mob.snapTo(at.x, at.y, at.z, 0.0F, 0.0F);
        mob.setPersistenceRequired();
        helper.assertTrue(level.addFreshEntity(mob), "the " + type + " was not added");
        spawned.add(mob);
        mob.removeFreeWill();
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
