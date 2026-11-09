package bobbynooby.dev.gametest;

import bobbynooby.dev.bodies.Bodies;
import bobbynooby.dev.bodies.BodyRecord;
import bobbynooby.dev.bodies.BodyStore;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.lockout.DeathLockout;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

// The test server keeps bodies off. Each test runs with the default body switches (bodies on) and puts the
// server's own back before any other test runs, since none of them waits a tick.
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
            helper.assertTrue(Bodies.isBody(body), "not recognised as a body");
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
            helper.assertTrue(Bodies.onLogout(player).isEmpty(), "body made with bodies off");
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
            helper.assertTrue(DeathLockout.refusal(player.getUUID()) != null, "death not recorded");
            helper.assertTrue(Bodies.onLogout(player).isEmpty(), "locked-out player got a body");

            // With the lockout off, being dead is all that stops it; graves off leaves nothing behind
            config.deathLockout.enabled = false;
            config.graves.enabled = false;
            TestPlayers.markLoaded(player);
            player.kill(player.level());
            helper.assertTrue(player.isDeadOrDying(), "player did not die");
            helper.assertTrue(Bodies.onLogout(player).isEmpty(), "dead player got a body");
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
            helper.assertTrue(Bodies.onLogout(player).isEmpty(), "spectator got a body");
            assertNoBody(helper, player);
        } finally {
            cleanUp(helper, player, before);
        }
        helper.succeed();
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
        Optional<Entity> body = Bodies.onLogout(player);
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

    private static CompoundTag save(GameTestHelper helper, Entity entity) {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
        entity.saveWithoutId(output);
        return output.buildResult();
    }

    // The switches go back first, so a player still online leaves without making another body
    private static void cleanUp(GameTestHelper helper, ServerPlayer player, YoltConfig.BodiesSection before) {
        Settings.get().bodies = before;
        MinecraftServer server = helper.getLevel().getServer();
        // By uuid: after a relog the player online is a new object
        ServerPlayer online = server.getPlayerList().getPlayer(player.getUUID());
        if (online != null) {
            TestPlayers.leave(online);
        }
        bodiesOf(helper, player.getUUID()).forEach(Entity::discard);
        store(helper).remove(player.getUUID());
    }
}
