package bobbynooby.dev.hardcore;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static bobbynooby.dev.hardcore.HardcoreTestKit.assertSlow;

/**
 * The Curse of the Undead, the players left out and the store, with no YOLT on the server. With no lockout a dead
 * player stays online and comes straight back, so the heart a death takes shows at the respawn. The curse ships on, so
 * the tests in one tick run on the defaults; the two that wait hold their settings in a batch of their own.
 */
public class HardcoreDeathAloneGameTests {
    // A heal after 200 ticks of play
    private static final String CURSE_HEALS = "yolt-hardcore-gametest:curse_heals_alone";
    // Broken legs on as well, beside the curse
    private static final String CREATIVE = "yolt-hardcore-gametest:creative_alone";
    private static final BlockPos AT = new BlockPos(1, 1, 1);
    private static final String LOST_ONE = "Curse of the Undead: −1 max heart.";
    // The hub's tick may come just before or just after a test's own in the same server tick
    private static final int SLACK = 5;

    // A death in survival takes a heart; one in creative takes none
    @GameTest
    public void aDeathTakesAMaxHeartAtTheRespawn(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        HardcoreFile.Loaded before = YoltHardcore.swapForTesting(HardcoreFile.Loaded.defaults());
        UUID dier = null;
        UUID creative = null;
        try {
            ServerPlayer player = loaded(helper, "CurseDier", GameType.SURVIVAL);
            dier = player.getUUID();
            ServerPlayer inCreative = loaded(helper, "CreativeDier", GameType.CREATIVE);
            creative = inCreative.getUUID();
            helper.assertValueEqual(player.getMaxHealth(), 20.0F, "max health before the death");
            assertLost(helper, dier, 0, "before the death");
            respawnBesideTest(helper, player);
            int from = TestPlayers.mark(player);
            player.kill(helper.getLevel());
            helper.assertTrue(player.isDeadOrDying(), "the player did not die");
            helper.assertTrue(server.getPlayerList().getPlayer(dier) == player, "the dead player is no longer online");
            assertLost(helper, dier, 1, "after the death");
            helper.assertFalse(TestPlayers.actionBars(player, from).contains(LOST_ONE), "told before coming back");

            ServerPlayer back = TestPlayers.respawn(player);
            helper.assertValueEqual(back.getMaxHealth(), 18.0F, "max health after the respawn");
            helper.assertValueEqual(TestPlayers.actionBars(back, from), List.of(LOST_ONE), "the hotbar after the respawn");
            List<String> status = TestPlayers.status(helper, back);
            helper.assertTrue(status.contains("Curse −1 max heart") && status.contains("  next heart back in 1h of play"),
                    "/status: " + status);

            respawnBesideTest(helper, inCreative);
            inCreative.kill(helper.getLevel());
            helper.assertTrue(inCreative.isDeadOrDying(), "the creative player did not die");
            assertLost(helper, creative, 0, "after a death in creative");
            helper.assertValueEqual(TestPlayers.respawn(inCreative).getMaxHealth(), 20.0F, "max health after a creative death");
        } finally {
            try {
                for (UUID id : new UUID[]{dier, creative}) {
                    if (id != null) {
                        Curse.set(id, 0);
                        TestPlayers.leave(server, id);
                    }
                }
            } finally {
                YoltHardcore.swapForTesting(before);
            }
        }
        helper.succeed();
    }

    @GameTest
    public void aTotemTakesAMaxHeart(GameTestHelper helper) {
        HardcoreFile.Loaded before = YoltHardcore.swapForTesting(HardcoreFile.Loaded.defaults());
        ServerPlayer player = null;
        try {
            player = loaded(helper, "CurseSaved", GameType.SURVIVAL);
            // The join's refresh saw a creative player, so the max health alone can't show a heart already lost
            assertLost(helper, player.getUUID(), 0, "before the totem");
            helper.assertValueEqual(player.getMaxHealth(), 20.0F, "max health before the totem");
            int from = TestPlayers.mark(player);
            player.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
            player.setHealth(2.0F);
            player.hurtServer(helper.getLevel(), helper.getLevel().damageSources().generic(), 10.0F);
            helper.assertFalse(player.isDeadOrDying(), "the totem did not save the player");
            assertLost(helper, player.getUUID(), 1, "after the totem");
            helper.assertValueEqual(player.getMaxHealth(), 18.0F, "max health straight after the totem");
            helper.assertValueEqual(player.getHealth(), 1.0F, "health after the totem");
            helper.assertValueEqual(TestPlayers.actionBars(player, from), List.of(LOST_ONE), "the hotbar");
        } finally {
            try {
                if (player != null) {
                    Curse.set(player.getUUID(), 0);
                }
                TestPlayers.leave(player);
            } finally {
                YoltHardcore.swapForTesting(before);
            }
        }
        helper.succeed();
    }

    // The heal comes from the player's own ticks online, so it is still owed just before 200 and back just after
    @GameTest(environment = CURSE_HEALS, maxTicks = 240)
    public void playTimeGivesTheHeartBack(GameTestHelper helper) {
        Held held = new Held(helper, c -> c.curse.healEveryDays = 200.0 / 24000.0);
        try {
            ServerPlayer player = held.join("CurseHealer", GameType.SURVIVAL, AT);
            UUID id = player.getUUID();
            held.onCleanUp(() -> Curse.set(id, 0));
            int from = TestPlayers.mark(player);
            Curse.set(id, 1);
            helper.runAfterDelay(SLACK, held.step(() -> {
                helper.assertValueEqual(player.getMaxHealth(), 18.0F, "max health a heart down");
                assertLost(helper, id, 1, "at the start");
            }));
            helper.runAfterDelay(200 - SLACK, held.step(() -> assertLost(helper, id, 1, "just before 200 ticks")));
            helper.runAfterDelay(200 + SLACK, held.last(() -> {
                assertLost(helper, id, 0, "just after 200 ticks");
                helper.assertValueEqual(player.getMaxHealth(), 20.0F, "max health after the heal");
                helper.assertValueEqual(TestPlayers.actionBars(player, from), List.of("Curse of the Undead: +1 max heart."),
                        "the hotbar");
            }));
        } catch (RuntimeException | Error e) {
            held.cleanUpAfter(e);
            throw e;
        }
    }

    // Creative takes the curse's hearts and the legs' slowdown off at the next tick and leaves them stored; survival
    // puts them back at the next
    @GameTest(environment = CREATIVE, maxTicks = 40)
    public void aCreativePlayerIsLeftOut(GameTestHelper helper) {
        Held held = new Held(helper, c -> c.legs.enabled = true);
        try {
            ServerPlayer player = held.join("Switcher", GameType.SURVIVAL, AT);
            UUID id = player.getUUID();
            held.onCleanUp(() -> {
                Curse.set(id, 0);
                Legs.set(id, false);
            });
            Curse.set(id, 2);
            Legs.set(id, true);
            helper.runAfterDelay(2, held.step(() -> {
                assertInTheRules(helper, player, "in survival");
                List<String> status = TestPlayers.status(helper, player);
                helper.assertTrue(status.contains("Curse −2 max hearts")
                        && status.contains("Legs broken · 20% slower · healing 0/30"), "/status in survival: " + status);
                player.setGameMode(GameType.CREATIVE);
            }));
            helper.runAfterDelay(4, held.step(() -> {
                helper.assertValueEqual(player.getMaxHealth(), 20.0F, "max health in creative");
                assertSlow(helper, player, 0.0, "in creative");
                HardcoreState kept = HardcoreStates.read(id);
                helper.assertTrue(kept.curseHeartsLost == 2 && kept.legsBroken, "creative changed the stored state");
                List<String> status = TestPlayers.status(helper, player);
                helper.assertTrue(status.stream().noneMatch(line -> line.startsWith("Curse") || line.startsWith("Legs")
                        || line.startsWith("Total")), "/status in creative: " + status);
                player.setGameMode(GameType.SURVIVAL);
            }));
            helper.runAfterDelay(6, held.last(() -> assertInTheRules(helper, player, "back in survival")));
        } catch (RuntimeException | Error e) {
            held.cleanUpAfter(e);
            throw e;
        }
    }

    // The leave writes the state to the world's store, under the name it has under YOLT too, and the player's next join
    // reads it back from there and puts the curse on at once: the store's own copy, read through a connection of the
    // test's, shows what was written
    @GameTest
    public void theStateIsSavedInTheWorldsStoreAndReadBack(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        HardcoreFile.Loaded before = YoltHardcore.swapForTesting(HardcoreFile.Loaded.defaults());
        Path file = server.getWorldPath(LevelResource.ROOT).resolve("database/you-only-live-twice-hardcore.db");
        GameProfile profile = TestPlayers.profile("Saver");
        UUID id = profile.id();
        try {
            ServerPlayer player = TestPlayers.join(helper, profile, GameType.SURVIVAL, AT);
            HardcoreStates.of(player);
            Curse.set(id, 1);
            Legs.set(id, true);
            HardcoreStates.update(id, state -> state.chill = 0.25);
            // A read never makes the file, so none yet means nothing is stored
            helper.assertFalse(Files.exists(file) && onDisk(file, id).legsBroken, "the state is on disk before the leave");
            TestPlayers.leave(player);

            helper.assertTrue(Files.exists(file), "no " + file + " after the leave");
            assertState(helper, onDisk(file, id), "in the file after the leave");
            ServerPlayer back = TestPlayers.rejoin(helper, profile, AT);
            helper.assertValueEqual(back.gameMode(), GameType.SURVIVAL, "the game mode the player joined again in");
            helper.assertValueEqual(back.getMaxHealth(), 18.0F, "max health as the player joins again");
            assertState(helper, HardcoreStates.read(id), "after the next join");
        } finally {
            try {
                Curse.set(id, 0);
                Legs.set(id, false);
                HardcoreStates.update(id, state -> state.chill = 0.0);
                TestPlayers.leave(server, id);
            } finally {
                YoltHardcore.swapForTesting(before);
            }
        }
        helper.succeed();
    }

    private static ServerPlayer loaded(GameTestHelper helper, String base, GameType mode) {
        ServerPlayer player = TestPlayers.join(helper, base, mode, AT);
        TestPlayers.markLoaded(player);
        return player;
    }

    // A respawn beside the test, rather than at the world spawn far from it
    private static void respawnBesideTest(GameTestHelper helper, ServerPlayer player) {
        player.setRespawnPosition(new ServerPlayer.RespawnConfig(LevelData.RespawnData.of(helper.getLevel().dimension(),
                helper.absolutePos(new BlockPos(7, 1, 7)), 0.0F, 0.0F), true), false);
    }

    private static HardcoreState onDisk(Path file, UUID id) {
        try (HardcoreStore store = HardcoreStore.open(file)) {
            return store.player(id).map(HardcoreState::decode).orElseGet(HardcoreState::new);
        }
    }

    private static void assertState(GameTestHelper helper, HardcoreState state, String where) {
        helper.assertTrue(state.curseHeartsLost == 1 && state.legsBroken && state.chill == 0.25,
                "the state " + where + ": " + state.encode());
    }

    // Two hearts lost, and the legs' slowdown
    private static void assertInTheRules(GameTestHelper helper, ServerPlayer player, String when) {
        helper.assertValueEqual(player.getMaxHealth(), 16.0F, "max health " + when);
        assertSlow(helper, player, 0.2, when);
    }

    private static void assertLost(GameTestHelper helper, UUID id, int expected, String when) {
        helper.assertValueEqual(HardcoreStates.read(id).curseHeartsLost, expected, "hearts lost " + when);
    }
}
