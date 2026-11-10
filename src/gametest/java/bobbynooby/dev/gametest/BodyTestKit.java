package bobbynooby.dev.gametest;

import bobbynooby.dev.bodies.Bodies;
import bobbynooby.dev.bodies.BodyRecord;
import bobbynooby.dev.bodies.BodyStore;
import bobbynooby.dev.bodies.PlayerBody;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.graves.Grave;
import bobbynooby.dev.graves.GraveStore;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// What every body test does to make a body and get rid of it. The test server keeps bodies off, so a test turns the
// default switches on, and cleanUp puts the server's own back in the same tick.
final class BodyTestKit {
    private BodyTestKit() {
    }

    // A fresh section has the default switches, with bodies on; the caller puts the returned one back
    static YoltConfig.BodiesSection useDefaultBodies() {
        YoltConfig config = Settings.get();
        YoltConfig.BodiesSection before = config.bodies;
        config.bodies = new YoltConfig.BodiesSection();
        return before;
    }

    // The same, with the player type
    static YoltConfig.BodiesSection usePlayerBodies() {
        YoltConfig.BodiesSection before = useDefaultBodies();
        Settings.get().bodies.type = YoltConfig.BodyType.PLAYER;
        return before;
    }

    // A real logout: leaving fires the hook and saves the player, so joining again reads that save
    static Mannequin leaveBody(GameTestHelper helper, ServerPlayer player) {
        TestPlayers.leave(player);
        return bodyOf(helper, player, Mannequin.class, "mannequin");
    }

    // The same, for a test that keeps the server's own switches: the defaults are on just for the logout
    static Mannequin leaveBodyWithDefaults(GameTestHelper helper, ServerPlayer player) {
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            TestPlayers.leave(player);
        } finally {
            Settings.get().bodies = before;
        }
        return bodyOf(helper, player, Mannequin.class, "mannequin");
    }

    static PlayerBody leavePlayerBody(GameTestHelper helper, ServerPlayer player) {
        TestPlayers.leave(player);
        return bodyOf(helper, player, PlayerBody.class, "player body");
    }

    static BodyStore store(GameTestHelper helper) {
        return BodyStore.get(helper.getLevel().getServer());
    }

    // The owner's mannequin bodies in the test's level
    static List<? extends Mannequin> bodiesOf(GameTestHelper helper, UUID owner) {
        return mannequinsOf(helper.getLevel(), owner);
    }

    static List<Grave> gravesOf(GameTestHelper helper, UUID owner) {
        return GraveStore.get(helper.getLevel().getServer()).all().stream()
                .filter(grave -> grave.owner().equals(owner))
                .toList();
    }

    // Bodies, records and graves left behind would sit in another test's way
    static void cleanUp(GameTestHelper helper, UUID owner, YoltConfig.BodiesSection before) {
        restore(helper, owner, before);
        removeBodies(helper, owner);
        removeGraves(helper, owner);
    }

    // For a test whose switches are the server's own already
    static void cleanUp(GameTestHelper helper, UUID owner) {
        cleanUp(helper, owner, Settings.get().bodies);
    }

    // The switches go back first, so a player still online leaves without making another body. By uuid: after a
    // relog the player online is a new object.
    static void restore(GameTestHelper helper, UUID owner, YoltConfig.BodiesSection before) {
        Settings.get().bodies = before;
        ServerPlayer online = helper.getLevel().getServer().getPlayerList().getPlayer(owner);
        if (online != null) {
            TestPlayers.leave(online);
        }
    }

    // Both kinds, in every dimension, since a test can move a body to another
    static void removeBodies(GameTestHelper helper, UUID owner) {
        MinecraftServer server = helper.getLevel().getServer();
        for (ServerLevel level : server.getAllLevels()) {
            mannequinsOf(level, owner).forEach(Entity::discard);
            // A copy: discarding a body takes it out of the level's players
            for (ServerPlayer player : new ArrayList<>(level.players())) {
                if (player instanceof PlayerBody body && body.owner().equals(owner)) {
                    body.discard();
                }
            }
        }
        store(helper).remove(owner);
    }

    // Out of the store first, so the grave no longer protects its block. In the grave's own dimension, since a body can
    // die in another.
    static void removeGraves(GameTestHelper helper, UUID owner) {
        MinecraftServer server = helper.getLevel().getServer();
        GraveStore graves = GraveStore.get(server);
        for (Grave grave : gravesOf(helper, owner)) {
            graves.remove(grave.id());
            ServerLevel level = server.getLevel(grave.dimension());
            if (level != null) {
                level.setBlock(grave.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    private static <T extends Entity> T bodyOf(GameTestHelper helper, ServerPlayer player, Class<T> type, String what) {
        Optional<BodyRecord> record = store(helper).byOwner(player.getUUID());
        helper.assertTrue(record.isPresent(), "no body record");
        Entity entity = helper.getLevel().getEntity(record.get().bodyEntity());
        helper.assertTrue(type.isInstance(entity), "no " + what + " for the record");
        return type.cast(entity);
    }

    private static List<? extends Mannequin> mannequinsOf(ServerLevel level, UUID owner) {
        return level.getEntities(EntityTypes.MANNEQUIN, body -> !body.isRemoved() && body.entityTags().contains(Bodies.TAG)
                && owner.equals(body.getProfile().partialProfile().id()));
    }
}
