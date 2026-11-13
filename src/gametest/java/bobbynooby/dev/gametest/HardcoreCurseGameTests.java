package bobbynooby.dev.gametest;

import bobbynooby.dev.bodies.Kills;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.hardcore.Curse;
import bobbynooby.dev.hardcore.HardcoreStates;
import bobbynooby.dev.lockout.DeathLockout;
import bobbynooby.dev.revive.Revive;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.GameType;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

// The curse stays on through each test's environment, which puts it back when the batch ends. Each test cleans up the
// stored curse of its own players.
public class HardcoreCurseGameTests {
    // The lockout and graves off, so a dead player is neither kicked nor leaves a grave
    private static final String CURSE = "you-only-live-twice-gametest:hardcore_curse";
    // The lockout on, counting creative and spectator deaths; each test swaps in a death store of its own for one tick
    private static final String CURSE_LOCKOUT = "you-only-live-twice-gametest:hardcore_curse_lockout";
    // A heal after 200 ticks of play
    private static final String CURSE_HEAL = "you-only-live-twice-gametest:hardcore_curse_heal";
    private static final BlockPos AT = new BlockPos(1, 1, 1);
    // The hub's tick may come just before or just after a test's own in the same server tick
    private static final int SLACK = 5;

    @GameTest(environment = CURSE)
    public void curse2OnlineDeathOfflineDeathAndTotem(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer online = join(helper, "CurseDier");
        ServerPlayer away = join(helper, "CurseAway");
        ServerPlayer saved = join(helper, "CurseSaved");
        GameProfile awayProfile = away.getGameProfile();
        List<UUID> players = List.of(online.getUUID(), away.getUUID(), saved.getUUID());
        try {
            GraveDeathGameTests.respawnBesideTest(helper, online);
            online.kill(online.level());
            helper.assertTrue(online.isDeadOrDying(), "the player did not die");
            ServerPlayer back = TestPlayers.respawn(online, false, Entity.RemovalReason.KILLED);
            helper.assertValueEqual(HardcoreStates.read(online.getUUID()).curseHeartsLost, 1, "hearts lost to the online death");
            assertMaxHealth(helper, back, 18.0F, "after an online death and respawn");

            // What /yolt kill runs, by id, so no name lookup stands between the test and the save
            TestPlayers.leave(away);
            helper.assertValueEqual(Kills.kill(server, away.nameAndId()), Kills.Result.KILLED_OFFLINE, "the offline kill");
            helper.assertValueEqual(HardcoreStates.read(awayProfile.id()).curseHeartsLost, 1, "hearts lost to the offline kill");
            ServerPlayer rejoined = TestPlayers.rejoin(helper, awayProfile, GameType.SURVIVAL, AT);
            helper.assertTrue(rejoined.isDeadOrDying(), "the player killed offline came back alive");
            GraveDeathGameTests.respawnBesideTest(helper, rejoined);
            ServerPlayer respawned = TestPlayers.respawn(rejoined, false, Entity.RemovalReason.KILLED);
            assertMaxHealth(helper, respawned, 18.0F, "after an offline kill and a rejoin");

            saved.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
            saved.setHealth(2.0F);
            saved.hurtServer(helper.getLevel(), helper.getLevel().damageSources().generic(), 10.0F);
            helper.assertFalse(saved.isDeadOrDying(), "the totem did not save the player");
            assertMaxHealth(helper, saved, 18.0F, "straight after the totem");
            helper.assertValueEqual(saved.getHealth(), 1.0F, "health after the totem");
            helper.assertTrue(actionBars(saved).contains("Curse of the Undead: −1 max heart."), "action bar: " + actionBars(saved));
        } finally {
            for (UUID uuid : players) {
                Curse.set(uuid, 0);
                leave(server, uuid);
            }
        }
        helper.succeed();
    }

    @GameTest(environment = CURSE_LOCKOUT)
    public void curse3CreativeAndSpectatorDeathsTakeNothing(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer creative = TestPlayers.join(helper, "CurseCreative", GameType.CREATIVE, AT);
        ServerPlayer spectator = TestPlayers.join(helper, "CurseSpectator", GameType.SPECTATOR, AT.offset(1, 0, 0));
        try (LockoutGameTests.OwnStore own = new LockoutGameTests.OwnStore()) {
            for (ServerPlayer player : List.of(creative, spectator)) {
                TestPlayers.markLoaded(player);
                player.kill(player.level());
                helper.assertTrue(player.isDeadOrDying(), player.gameMode() + " player did not die");
                helper.assertTrue(LockoutGameTests.refusedForLockout(DeathLockout.refusal(player.getUUID(), "Test")),
                        "the counted " + player.gameMode() + " death did not lock the player out");
                helper.assertValueEqual(HardcoreStates.read(player.getUUID()).curseHeartsLost, 0,
                        "hearts lost to a " + player.gameMode() + " death");
                assertMaxHealth(helper, player, 20.0F, "after a " + player.gameMode() + " death");
            }
        } finally {
            // Gone before the death kick comes
            for (ServerPlayer player : List.of(creative, spectator)) {
                Curse.set(player.getUUID(), 0);
                leave(server, player.getUUID());
            }
        }
        helper.succeed();
    }

    @GameTest(environment = CURSE_LOCKOUT)
    public void curse4UnkillRefundsARevivePaperDoesNot(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = join(helper, "CurseUnkilled");
        ServerPlayer user = join(helper, "CursePaper");
        UUID uuid = player.getUUID();
        String name = player.getGameProfile().name();
        try (LockoutGameTests.OwnStore own = new LockoutGameTests.OwnStore()) {
            GraveDeathGameTests.respawnBesideTest(helper, player);
            player.kill(player.level());
            helper.assertValueEqual(HardcoreStates.read(uuid).curseHeartsLost, 1, "hearts lost to the first death");
            helper.assertValueEqual(CommandGameTests.run(server, "yolt unkill " + name),
                    List.of("Revived " + name + ". Gave back 1 max heart."), "the unkill's reply");
            helper.assertValueEqual(HardcoreStates.read(uuid).curseHeartsLost, 0, "hearts lost after the unkill");

            ServerPlayer back = respawnByButton(player);
            TestPlayers.markLoaded(back);
            back.kill(back.level());
            helper.assertTrue(back.isDeadOrDying(), "the player did not die again");
            helper.assertValueEqual(HardcoreStates.read(uuid).curseHeartsLost, 1, "hearts lost to the second death");
            ItemStack head = ReviveGameTests.reviveHead(ResolvableProfile.createResolved(back.getGameProfile()), 1);
            helper.assertValueEqual(Revive.tryUse(user, head), Revive.Outcome.REVIVED, "the revive paper");
            helper.assertValueEqual(HardcoreStates.read(uuid).curseHeartsLost, 1, "hearts lost after the revive paper");
        } finally {
            Curse.set(uuid, 0);
            leave(server, uuid);
            leave(server, user.getUUID());
        }
        helper.succeed();
    }

    // A second owner one heart short of the cap holds a totem as they leave, which their body keeps in its hand
    @GameTest(environment = CURSE)
    public void curse5ABodyCopiesTheCurse(GameTestHelper helper) {
        ServerPlayer cursed = join(helper, "CurseBody");
        ServerPlayer holder = join(helper, "CurseTotemBody", AT.offset(2, 0, 0));
        UUID owner = cursed.getUUID();
        UUID holderId = holder.getUUID();
        YoltConfig.BodiesSection before = BodyTestKit.useDefaultBodies();
        try {
            Curse.set(owner, 2);
            Mannequin body = BodyTestKit.leaveBody(helper, cursed);
            assertMaxHealth(helper, body, 16.0F, "on the body of a player 2 hearts down");

            Curse.set(holderId, 1);
            holder.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
            Mannequin holding = BodyTestKit.leaveBody(helper, holder);
            assertMaxHealth(helper, holding, 18.0F, "on the body of a player 1 heart down");
            holding.setHealth(2.0F);
            holding.hurtServer(helper.getLevel(), helper.getLevel().damageSources().generic(), 10.0F);
            helper.assertFalse(holding.isDeadOrDying(), "the totem did not save the body");
            helper.assertValueEqual(HardcoreStates.read(holderId).curseHeartsLost, 2, "the owner's hearts lost to the body's totem");
            assertMaxHealth(helper, holding, 16.0F, "on the body after its totem");
        } finally {
            Curse.set(owner, 0);
            Curse.set(holderId, 0);
            BodyTestKit.cleanUp(helper, owner, before);
            BodyTestKit.cleanUp(helper, holderId, before);
        }
        helper.succeed();
    }

    // 2 down at the start; a heal at 200; a totem at 300, with 100 ticks counted, which doesn't start the count over;
    // so the next heal at 400
    @GameTest(environment = CURSE_HEAL, maxTicks = 420)
    public void curse6AHeartComesBackAfterTheSetOnlineTime(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = join(helper, "CurseHealer");
        UUID uuid = player.getUUID();
        Runnable cleanUp = BodyTestKit.once(() -> {
            Curse.set(uuid, 0);
            leave(server, uuid);
        });
        boolean waiting = false;
        try {
            Curse.set(uuid, 2);
            helper.runAfterDelay(200 - SLACK, () -> step(cleanUp, () -> assertLost(helper, uuid, 2, "just before 200 ticks")));
            helper.runAfterDelay(200 + SLACK, () -> step(cleanUp, () -> {
                assertLost(helper, uuid, 1, "just after 200 ticks");
                assertMaxHealth(helper, player, 18.0F, "after the heal");
            }));
            helper.runAfterDelay(300, () -> step(cleanUp, () -> {
                long counted = HardcoreStates.read(uuid).curseOnlineTicks;
                helper.assertTrue(Math.abs(counted - 100) <= SLACK, counted + " ticks counted at 300, not about 100");
                player.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
                player.setHealth(2.0F);
                player.hurtServer(helper.getLevel(), helper.getLevel().damageSources().generic(), 10.0F);
                helper.assertFalse(player.isDeadOrDying(), "the totem did not save the player");
                assertLost(helper, uuid, 2, "after the totem");
            }));
            helper.runAfterDelay(400 - SLACK, () -> step(cleanUp, () -> assertLost(helper, uuid, 2, "just before 400 ticks")));
            helper.runAfterDelay(400 + SLACK, () -> {
                try {
                    assertLost(helper, uuid, 1, "just after 400 ticks");
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

    @GameTest(environment = CURSE)
    public void curseCommandSetsAndClears(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = join(helper, "CurseCommanded");
        UUID uuid = player.getUUID();
        String name = player.getGameProfile().name();
        try {
            helper.assertValueEqual(CommandGameTests.run(server, "yolt curse " + name + " set 2"),
                    List.of(name + " has lost 2 max hearts to the Curse of the Undead."), "the set's reply");
            assertMaxHealth(helper, player, 16.0F, "after the set");
            helper.assertValueEqual(CommandGameTests.run(server, "yolt curse " + name + " clear"),
                    List.of("Cleared " + name + "'s Curse of the Undead."), "the clear's reply");
            assertMaxHealth(helper, player, 20.0F, "after the clear");
            helper.assertValueEqual(CommandGameTests.run(server, "yolt curse " + name + " set 3"),
                    List.of("The curse takes at most 2 hearts."), "the reply past the cap");
            helper.assertValueEqual(HardcoreStates.read(uuid).curseHeartsLost, 0, "hearts lost after the refused set");
        } finally {
            Curse.set(uuid, 0);
            leave(server, uuid);
        }
        helper.succeed();
    }

    // Loaded, so the player can be hurt, which vanilla holds off until a client says the world is in
    private static ServerPlayer join(GameTestHelper helper, String name) {
        return join(helper, name, AT);
    }

    private static ServerPlayer join(GameTestHelper helper, String name, BlockPos at) {
        ServerPlayer player = TestPlayers.join(helper, name, GameType.SURVIVAL, at);
        TestPlayers.markLoaded(player);
        return player;
    }

    // By uuid: a player who left already, or respawned as another object, is not left twice
    private static void leave(MinecraftServer server, UUID uuid) {
        ServerPlayer online = server.getPlayerList().getPlayer(uuid);
        if (online != null) {
            TestPlayers.leave(online);
        }
    }

    // As the death screen's button does it, so the player that comes back can be hurt
    private static ServerPlayer respawnByButton(ServerPlayer player) {
        player.connection.handleClientCommand(
                new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
        return player.connection.player;
    }

    // A failed check ends the test at once, so the player isn't left online for the rest of the batch
    private static void step(Runnable cleanUp, Runnable check) {
        try {
            check.run();
        } catch (RuntimeException e) {
            cleanUp.run();
            throw e;
        }
    }

    private static void assertLost(GameTestHelper helper, UUID uuid, int expected, String when) {
        helper.assertValueEqual(HardcoreStates.read(uuid).curseHeartsLost, expected, "hearts lost " + when);
    }

    private static void assertMaxHealth(GameTestHelper helper, LivingEntity entity, float expected, String when) {
        helper.assertValueEqual(entity.getMaxHealth(), expected, "max health " + when);
    }

    private static List<String> actionBars(ServerPlayer player) {
        return TestPlayers.sentPackets(player).stream()
                .filter(packet -> packet instanceof ClientboundSystemChatPacket chat && chat.overlay())
                .map(packet -> ((ClientboundSystemChatPacket) packet).content().getString())
                .toList();
    }
}
