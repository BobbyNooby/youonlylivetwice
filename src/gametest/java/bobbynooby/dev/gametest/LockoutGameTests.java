package bobbynooby.dev.gametest;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.lockout.DeathLockout;
import bobbynooby.dev.lockout.DeathStore;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.players.NameAndId;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.UUID;

// Uses fresh random uuids, so no test player is needed and tests never collide
public class LockoutGameTests {
    private static final SocketAddress ADDRESS = new InetSocketAddress("127.0.0.1", 25565);

    @GameTest
    public void lockedOutPlayerIsRefusedAtLogin(GameTestHelper helper) {
        UUID uuid = UUID.randomUUID();
        DeathLockout.recordDeath(uuid, "Ghost");
        helper.assertTrue(!canLogin(helper, uuid), "locked-out player was let in");
        helper.succeed();
    }

    @GameTest
    public void revivedPlayerCanLogin(GameTestHelper helper) {
        UUID uuid = UUID.randomUUID();
        DeathLockout.recordDeath(uuid, "Ghost");
        helper.assertTrue(DeathLockout.revive(uuid), "revive found no death");
        helper.assertTrue(canLogin(helper, uuid), "revived player was refused");
        helper.succeed();
    }

    @GameTest
    public void lockoutOffLetsEveryoneIn(GameTestHelper helper) {
        UUID uuid = UUID.randomUUID();
        DeathLockout.recordDeath(uuid, "Ghost");
        boolean before = Settings.get().deathLockout.enabled;
        Settings.get().deathLockout.enabled = false;
        try {
            helper.assertTrue(canLogin(helper, uuid), "player refused with the lockout off");
        } finally {
            Settings.get().deathLockout.enabled = before;
        }
        helper.succeed();
    }

    // A closed store makes every query throw, like a failing database would
    @GameTest
    public void storeFailureLetsPlayerInAndLogs(GameTestHelper helper) throws java.io.IOException {
        UUID uuid = UUID.randomUUID();
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("yolt-broken-store");
        DeathStore broken = DeathStore.open(dir.resolve("broken.db"));
        broken.close();
        DeathStore real = DeathLockout.swapStoreForTesting(broken);
        try {
            helper.assertTrue(DeathLockout.refusal(uuid) == null, "player refused while the store is failing");
            DeathLockout.recordDeath(uuid, "Ghost");
            helper.assertTrue(!DeathLockout.revive(uuid), "revive succeeded on a failing store");
        } finally {
            DeathLockout.swapStoreForTesting(real);
        }
        helper.succeed();
    }

    private static boolean canLogin(GameTestHelper helper, UUID uuid) {
        return helper.getLevel().getServer().getPlayerList().canPlayerLogin(ADDRESS, new NameAndId(uuid, "Ghost")) == null;
    }
}
