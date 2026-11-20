package bobbynooby.dev.gametest;

import bobbynooby.dev.bodies.BodyRecord;
import bobbynooby.dev.bodies.BodySnapshot;
import bobbynooby.dev.bodies.Kills;
import bobbynooby.dev.bodies.PlayerBody;
import bobbynooby.dev.config.HardcoreConfig;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.core.Yolt;
import bobbynooby.dev.gametest.mixin.LastActionAccessor;
import bobbynooby.dev.hardcore.Curse;
import bobbynooby.dev.hardcore.HardcoreState;
import bobbynooby.dev.hardcore.HardcoreStates;
import bobbynooby.dev.hardcore.HardcoreStore;
import bobbynooby.dev.hardcore.Legs;
import bobbynooby.dev.hardcore.Status;
import bobbynooby.dev.lockout.DeathLockout;
import bobbynooby.dev.revive.Revive;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.util.Util;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Path;
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
    // The same, with countAfk off
    private static final String CURSE_AFK = "you-only-live-twice-gametest:hardcore_curse_afk";
    // The same, in a batch of its own, since the test beside it in the other one changes afkMinutes while this one counts
    private static final String CURSE_AFK_LINE = "you-only-live-twice-gametest:hardcore_curse_afk_line";
    // The curse on, each in a batch of its own: its test changes a curse key across ticks
    private static final String CURSE_SWITCH = "you-only-live-twice-gametest:hardcore_curse_switch";
    private static final String CURSE_CAP = "you-only-live-twice-gametest:hardcore_curse_cap";
    private static final String ALL_OFF = "you-only-live-twice-gametest:hardcore_all_off";
    private static final String LEGS = "you-only-live-twice-gametest:hardcore_legs";
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

    // A counted death is in the hardcore store's file at once, with no logout: one that took a heart, and one at the cap,
    // which takes none but leaves nothing for an unkill to give back
    @GameTest(environment = CURSE)
    public void curse2ADeathIsInTheStoreAtOnce(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = join(helper, "CurseOnDisk");
        UUID uuid = player.getUUID();
        HardcoreConfig.CurseSection cfg = Settings.get().hardcore.curse;
        int cap = cfg.maxLostHearts;
        try {
            helper.assertValueEqual(cfg.heartsPerDeath, 1, "heartsPerDeath");
            Curse.set(uuid, cap - 1);
            GraveDeathGameTests.respawnBesideTest(helper, player);
            player.kill(player.level());
            helper.assertTrue(player.isDeadOrDying(), "the player did not die");
            HardcoreState first = onDisk(server, uuid);
            helper.assertValueEqual(first.curseHeartsLost, cap, "hearts lost in the file after a death that took one");
            helper.assertValueEqual(first.curseLastDeathAdded, 1, "the latest death's hearts in the file");

            ServerPlayer back = respawnByButton(player);
            TestPlayers.markLoaded(back);
            back.kill(back.level());
            helper.assertTrue(back.isDeadOrDying(), "the player did not die again");
            HardcoreState second = onDisk(server, uuid);
            helper.assertValueEqual(second.curseHeartsLost, cap, "hearts lost in the file after a death at the cap");
            helper.assertValueEqual(second.curseLastDeathAdded, 0, "the latest death's hearts in the file after a death at the cap");
        } finally {
            Curse.set(uuid, 0);
            leave(server, uuid);
        }
        helper.succeed();
    }

    // The cap is any number the server sets. One heart always stays, and hearts from anything else count.
    @GameTest(environment = CURSE)
    public void curse2NeverTakesTheLastHeart(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = join(helper, "CurseLastHeart");
        UUID uuid = player.getUUID();
        HardcoreConfig.CurseSection cfg = Settings.get().hardcore.curse;
        int cap = cfg.maxLostHearts;
        try {
            cfg.maxLostHearts = 50;
            Curse.set(uuid, 50);
            Curse.apply(player);
            assertMaxHealth(helper, player, 2.0F, "with 50 hearts lost of 10");
            // Health Boost II gives four hearts, and the curse then takes all of those but the last too
            player.addEffect(new MobEffectInstance(MobEffects.HEALTH_BOOST, 12_000, 1));
            Curse.apply(player);
            assertMaxHealth(helper, player, 2.0F, "with 50 hearts lost of 14");
            Curse.set(uuid, 11);
            Curse.apply(player);
            assertMaxHealth(helper, player, 6.0F, "with 11 hearts lost of 14");
            player.removeEffect(MobEffects.HEALTH_BOOST);
            Curse.apply(player);
            assertMaxHealth(helper, player, 2.0F, "with 11 hearts lost of 10");
        } finally {
            cfg.maxLostHearts = cap;
            player.removeEffect(MobEffects.HEALTH_BOOST);
            Curse.set(uuid, 0);
            leave(server, uuid);
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

    // A player body is rebuilt from its record at a restart, through the routine the start runs for each one. The
    // modifier isn't saved with the body, so the rebuilt one has to read its owner's curse again.
    @GameTest(environment = CURSE)
    public void curse5APlayerBodyKeepsTheCurseWhenRebuilt(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = join(helper, "CurseRebuilt");
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = BodyTestKit.usePlayerBodies();
        try {
            Curse.set(owner, 2);
            PlayerBody body = BodyTestKit.leavePlayerBody(helper, player);
            assertMaxHealth(helper, body, 16.0F, "on the player body at the logout");
            BodyRecord saved = BodyTestKit.store(helper).byOwner(owner).orElseThrow().withSnapshot(BodySnapshot.of(body));
            body.discard();
            BodyTestKit.store(helper).put(saved);
            PlayerBody rebuilt = PlayerBody.fromRecord(server, saved);
            helper.assertTrue(rebuilt != null, "no player body brought back");
            assertMaxHealth(helper, rebuilt, 16.0F, "on the rebuilt player body");
        } finally {
            Curse.set(owner, 0);
            BodyTestKit.cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    // /yolt curse set on a player who is away with a body out changes the stored curse only: the body keeps its hearts.
    // At the login the player takes the body's full health, and their next living tick caps it at the lower maximum.
    @GameTest(environment = CURSE)
    public void curse5ASetWhileAwayMeetsTheBodyAtLogin(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = join(helper, "CurseAwayBody");
        UUID owner = player.getUUID();
        GameProfile profile = player.getGameProfile();
        String name = profile.name();
        YoltConfig.BodiesSection before = BodyTestKit.useDefaultBodies();
        try {
            Mannequin body = BodyTestKit.leaveBody(helper, player);
            assertMaxHealth(helper, body, 20.0F, "on the body at the logout");
            helper.assertValueEqual(CommandGameTests.run(server, "yolt curse " + name + " set 2"),
                    List.of(name + " has lost 2 max hearts to the Curse of the Undead."), "the set's reply");
            assertLost(helper, owner, 2, "after the set");
            assertMaxHealth(helper, body, 20.0F, "on the body after the set");

            ServerPlayer back = TestPlayers.rejoin(helper, profile, GameType.SURVIVAL, AT);
            assertMaxHealth(helper, back, 16.0F, "at the login");
            // Vanilla lowers health to a lowered maximum only in the living tick, which a test player doesn't get by
            // itself. Not loaded yet, so nothing in that tick can hurt them.
            back.doTick();
            helper.assertValueEqual(back.getHealth(), 16.0F, "health after the first tick, from the body's 20");
        } finally {
            Curse.set(owner, 0);
            BodyTestKit.cleanUp(helper, owner, before);
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

    // Only online play counts: what the clock had counted at the logout is what it has 60 ticks later and at the login
    @GameTest(environment = CURSE_HEAL, maxTicks = 120)
    public void curse6TheClockStopsWhileOffline(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = join(helper, "CurseAway");
        UUID uuid = player.getUUID();
        GameProfile profile = player.getGameProfile();
        long[] atLogout = new long[1];
        Runnable cleanUp = BodyTestKit.once(() -> {
            Curse.set(uuid, 0);
            leave(server, uuid);
        });
        boolean waiting = false;
        try {
            Curse.set(uuid, 2);
            helper.runAfterDelay(30, () -> step(cleanUp, () -> {
                TestPlayers.leave(player);
                atLogout[0] = HardcoreStates.read(uuid).curseOnlineTicks;
                helper.assertTrue(Math.abs(atLogout[0] - 30) <= SLACK, atLogout[0] + " ticks counted at the logout, not about 30");
            }));
            helper.runAfterDelay(90, () -> {
                try {
                    helper.assertValueEqual(HardcoreStates.read(uuid).curseOnlineTicks, atLogout[0], "ticks counted while away");
                    ServerPlayer back = TestPlayers.rejoin(helper, profile, GameType.SURVIVAL, AT);
                    helper.assertValueEqual(HardcoreStates.of(back).curseOnlineTicks, atLogout[0], "ticks counted at the login");
                    assertLost(helper, uuid, 2, "at the login");
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

    // With countAfk off, a player who hasn't moved, looked or clicked for ten minutes earns no heal time; one who just
    // joined still does, and gets the heart back after 200 ticks
    @GameTest(environment = CURSE_AFK, maxTicks = 240)
    public void curse6AnIdlePlayerHealsNothingWithCountAfkOff(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer idle = join(helper, "CurseIdle");
        ServerPlayer busy = join(helper, "CurseBusy", AT.offset(1, 0, 0));
        UUID idleId = idle.getUUID();
        UUID busyId = busy.getUUID();
        Runnable cleanUp = BodyTestKit.once(() -> {
            for (UUID uuid : List.of(idleId, busyId)) {
                Curse.set(uuid, 0);
                leave(server, uuid);
            }
        });
        boolean waiting = false;
        try {
            helper.assertFalse(Settings.get().hardcore.curse.countAfk, "the environment left countAfk on");
            Curse.set(idleId, 1);
            Curse.set(busyId, 1);
            ((LastActionAccessor) idle).yoltSetLastActionTime(Util.getMillis() - 600_000L);
            helper.runAfterDelay(200 + SLACK, () -> {
                try {
                    assertLost(helper, idleId, 1, "for the idle player after 200 ticks");
                    helper.assertValueEqual(HardcoreStates.read(idleId).curseOnlineTicks, 0L, "ticks counted for the idle player");
                    assertLost(helper, busyId, 0, "for the active player after 200 ticks");
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

    // With countAfk off, a player idle for three minutes still earns heal time under the 5 minutes that ship, and stops
    // as soon as afkMinutes is 2, since each tick reads it
    @GameTest(environment = CURSE_AFK, maxTicks = 60)
    public void curse6AfkMinutesSetsHowLongIdleTimeCounts(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer idle = join(helper, "CurseAfkMinutes", AT.offset(2, 0, 0));
        UUID uuid = idle.getUUID();
        HardcoreConfig.CurseSection cfg = Settings.get().hardcore.curse;
        int minutesBefore = cfg.afkMinutes;
        Runnable cleanUp = BodyTestKit.once(() -> {
            cfg.afkMinutes = minutesBefore;
            Curse.set(uuid, 0);
            leave(server, uuid);
        });
        boolean waiting = false;
        try {
            helper.assertValueEqual(cfg.afkMinutes, 5, "afkMinutes as it ships");
            Curse.set(uuid, 1);
            ((LastActionAccessor) idle).yoltSetLastActionTime(Util.getMillis() - 180_000L);
            long[] counted = new long[1];
            helper.runAfterDelay(10, () -> {
                try {
                    counted[0] = HardcoreStates.read(uuid).curseOnlineTicks;
                    helper.assertTrue(counted[0] > 0, "no time counted for three minutes idle with afkMinutes 5");
                    cfg.afkMinutes = 2;
                } catch (RuntimeException e) {
                    cleanUp.run();
                    throw e;
                }
            });
            helper.runAfterDelay(30, () -> {
                try {
                    helper.assertValueEqual(HardcoreStates.read(uuid).curseOnlineTicks, counted[0],
                            "ticks counted for three minutes idle once afkMinutes was 2");
                    assertLost(helper, uuid, 1, "for the idle player");
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
            helper.assertValueEqual(CommandGameTests.run(server, "yolt curse " + name + " set 5"),
                    List.of("The curse takes at most 4 hearts."), "the reply past the cap");
            helper.assertValueEqual(HardcoreStates.read(uuid).curseHeartsLost, 0, "hearts lost after the refused set");
        } finally {
            Curse.set(uuid, 0);
            leave(server, uuid);
        }
        helper.succeed();
    }

    // The switch is read every tick: off takes the hearts off at once and leaves them in the store, on puts them back.
    // Neither is a heart lost or won, so neither says so over the hotbar.
    @GameTest(environment = CURSE_SWITCH, maxTicks = 40)
    public void curseSwitchedOffLiveGivesHeartsBackAndOnAgainTakesThem(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = join(helper, "CurseSwitched");
        UUID uuid = player.getUUID();
        HardcoreConfig.CurseSection cfg = Settings.get().hardcore.curse;
        boolean before = cfg.enabled;
        Runnable cleanUp = BodyTestKit.once(() -> {
            cfg.enabled = before;
            Curse.set(uuid, 0);
            leave(server, uuid);
        });
        boolean waiting = false;
        try {
            Curse.set(uuid, 2);
            helper.runAfterDelay(2, () -> step(cleanUp, () -> {
                assertMaxHealth(helper, player, 16.0F, "with 2 hearts lost");
                cfg.enabled = false;
            }));
            helper.runAfterDelay(4, () -> step(cleanUp, () -> {
                assertMaxHealth(helper, player, 20.0F, "a tick after the curse went off");
                helper.assertTrue(ourMaxHealthModifiers(player).isEmpty(), "modifiers left on: " + ourMaxHealthModifiers(player));
                assertLost(helper, uuid, 2, "in the store with the curse off");
                cfg.enabled = true;
            }));
            helper.runAfterDelay(6, () -> {
                try {
                    assertMaxHealth(helper, player, 16.0F, "a tick after the curse came back on");
                    List<String> curseLines = actionBars(player).stream().filter(line -> line.startsWith("Curse of the Undead")).toList();
                    helper.assertTrue(curseLines.isEmpty(), "the switch was announced: " + curseLines);
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

    // With unkillRefunds off an unkill only ends the lockout; the switch back on refunds the next death's heart
    @GameTest(environment = CURSE_LOCKOUT)
    public void unkillWithRefundsOffKeepsTheHeart(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = join(helper, "CurseNoRefund");
        UUID uuid = player.getUUID();
        String name = player.getGameProfile().name();
        HardcoreConfig.CurseSection cfg = Settings.get().hardcore.curse;
        boolean before = cfg.unkillRefunds;
        try (LockoutGameTests.OwnStore own = new LockoutGameTests.OwnStore()) {
            cfg.unkillRefunds = false;
            GraveDeathGameTests.respawnBesideTest(helper, player);
            player.kill(player.level());
            assertLost(helper, uuid, 1, "to the first death");
            helper.assertValueEqual(CommandGameTests.run(server, "yolt unkill " + name), List.of("Revived " + name + "."),
                    "the unkill's reply with unkillRefunds off");
            assertLost(helper, uuid, 1, "after the unkill with unkillRefunds off");

            cfg.unkillRefunds = true;
            ServerPlayer back = respawnByButton(player);
            TestPlayers.markLoaded(back);
            back.kill(back.level());
            assertLost(helper, uuid, 2, "to the second death");
            helper.assertValueEqual(CommandGameTests.run(server, "yolt unkill " + name),
                    List.of("Revived " + name + ". Gave back 1 max heart."), "the unkill's reply with unkillRefunds on");
            assertLost(helper, uuid, 1, "after the unkill with unkillRefunds on");
        } finally {
            cfg.unkillRefunds = before;
            Curse.set(uuid, 0);
            leave(server, uuid);
        }
        helper.succeed();
    }

    @GameTest(environment = ALL_OFF)
    public void curseCommandRefusedWhileTheCurseIsOff(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = join(helper, "CurseRefused");
        UUID uuid = player.getUUID();
        String name = player.getGameProfile().name();
        try {
            Curse.set(uuid, 2);
            for (String command : List.of("set 1", "set 3", "clear")) {
                int[] result = {-1};
                helper.assertValueEqual(CommandGameTests.run(server, "yolt curse " + name + " " + command, value -> result[0] = value),
                        List.of("The Curse of the Undead is turned off."), "the reply to '" + command + "'");
                helper.assertValueEqual(result[0], 0, "the result of '" + command + "'");
                assertLost(helper, uuid, 2, "after '" + command + "' with the curse off");
            }
        } finally {
            Curse.set(uuid, 0);
            leave(server, uuid);
        }
        helper.succeed();
    }

    // Nothing is stored under the id the server would give that name. Test players' names all end in a number, so no
    // test ever joins with this one.
    @GameTest(environment = CURSE)
    public void curseCommandNamesSomeoneWhoNeverJoined(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        String name = "NeverHereCurse";
        int[] result = {-1};
        helper.assertValueEqual(CommandGameTests.run(server, "yolt curse " + name + " set 1", value -> result[0] = value),
                List.of("No player named " + name + " has joined this server."), "the reply");
        helper.assertValueEqual(result[0], 0, "the command's result");
        assertLost(helper, UUIDUtil.createOfflinePlayerUUID(name), 0, "under the name's offline id");
        helper.succeed();
    }

    // A set curse is the admin's word: the death before it has nothing left for an unkill to give back
    @GameTest(environment = CURSE_LOCKOUT)
    public void curseCommandLeavesNothingForAnUnkill(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = join(helper, "CurseSetDead");
        UUID uuid = player.getUUID();
        String name = player.getGameProfile().name();
        try (LockoutGameTests.OwnStore own = new LockoutGameTests.OwnStore()) {
            GraveDeathGameTests.respawnBesideTest(helper, player);
            player.kill(player.level());
            helper.assertValueEqual(HardcoreStates.read(uuid).curseLastDeathAdded, 1, "the death's hearts before the set");
            helper.assertValueEqual(CommandGameTests.run(server, "yolt curse " + name + " set 3"),
                    List.of(name + " has lost 3 max hearts to the Curse of the Undead."), "the set's reply");
            helper.assertValueEqual(HardcoreStates.read(uuid).curseLastDeathAdded, 0, "the death's hearts after the set");
            helper.assertValueEqual(CommandGameTests.run(server, "yolt unkill " + name), List.of("Revived " + name + "."),
                    "the unkill's reply");
            assertLost(helper, uuid, 3, "after the unkill");
        } finally {
            Curse.set(uuid, 0);
            leave(server, uuid);
        }
        helper.succeed();
    }

    // The cap is read every tick: lowering it below what is stored shows the cap at once, as "the most", and raising it
    // back shows the stored hearts again
    @GameTest(environment = CURSE_CAP, maxTicks = 40)
    public void curse4LoweringTheCapShowsAtOnce(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = join(helper, "CurseCapped");
        UUID uuid = player.getUUID();
        HardcoreConfig.CurseSection cfg = Settings.get().hardcore.curse;
        int capBefore = cfg.maxLostHearts;
        Runnable cleanUp = BodyTestKit.once(() -> {
            cfg.maxLostHearts = capBefore;
            Curse.set(uuid, 0);
            leave(server, uuid);
        });
        boolean waiting = false;
        try {
            helper.assertValueEqual(capBefore, 4, "maxLostHearts as it ships");
            Curse.set(uuid, 4);
            helper.runAfterDelay(2, () -> step(cleanUp, () -> {
                assertMaxHealth(helper, player, 12.0F, "with 4 hearts lost");
                cfg.maxLostHearts = 2;
            }));
            helper.runAfterDelay(4, () -> step(cleanUp, () -> {
                assertMaxHealth(helper, player, 16.0F, "a tick after the cap went down to 2");
                assertLost(helper, uuid, 4, "in the store under the lower cap");
                List<String> report = Status.report(player).stream().map(Component::getString).toList();
                helper.assertTrue(report.contains("Curse −2 max hearts (the most)"), "the report under the lower cap: " + report);
                cfg.maxLostHearts = 4;
            }));
            helper.runAfterDelay(6, () -> {
                try {
                    assertMaxHealth(helper, player, 12.0F, "a tick after the cap went back to 4");
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

    // A dead or kicked player can't see the hotbar, so the heart a death took is told as they come back, and only then
    @GameTest(environment = CURSE)
    public void curse2TheReturnNoticeComesOnRespawn(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = join(helper, "CurseNotice");
        UUID uuid = player.getUUID();
        String notice = "Curse of the Undead: −1 max heart.";
        try {
            GraveDeathGameTests.respawnBesideTest(helper, player);
            player.kill(player.level());
            helper.assertTrue(player.isDeadOrDying(), "the player did not die");
            helper.assertFalse(actionBars(player).contains(notice), "told before coming back: " + actionBars(player));
            TestPlayers.respawn(player, false, Entity.RemovalReason.KILLED);
            helper.assertValueEqual(actionBars(player).stream().filter(notice::equals).count(), 1L,
                    "notices after the respawn: " + actionBars(player));
        } finally {
            Curse.set(uuid, 0);
            leave(server, uuid);
        }
        helper.succeed();
    }

    // The heal says so over the hotbar, once
    @GameTest(environment = CURSE_HEAL, maxTicks = 220)
    public void curse6AHealSaysPlusOneHeart(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = join(helper, "CurseHealTold", AT.offset(2, 0, 0));
        UUID uuid = player.getUUID();
        Runnable cleanUp = BodyTestKit.once(() -> {
            Curse.set(uuid, 0);
            leave(server, uuid);
        });
        boolean waiting = false;
        try {
            Curse.set(uuid, 1);
            helper.runAfterDelay(200 + SLACK, () -> {
                try {
                    assertLost(helper, uuid, 0, "after 200 ticks");
                    List<String> told = actionBars(player);
                    helper.assertValueEqual(told.stream().filter("Curse of the Undead: +1 max heart."::equals).count(), 1L,
                            "heal notices: " + told);
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

    // A player body's totem is its owner's return from death: the owner pays the heart, and the body shows it at once
    @GameTest(environment = CURSE)
    public void curse5APlayerBodysTotemCostsItsOwnerAHeart(GameTestHelper helper) {
        ServerPlayer player = join(helper, "CurseBodyTotem");
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = BodyTestKit.usePlayerBodies();
        try {
            Curse.set(owner, 1);
            player.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
            PlayerBody body = BodyTestKit.leavePlayerBody(helper, player);
            assertMaxHealth(helper, body, 18.0F, "on the player body of a player 1 heart down");
            body.setHealth(2.0F);
            body.hurtServer(helper.getLevel(), helper.getLevel().damageSources().generic(), 10.0F);
            helper.assertFalse(body.isDeadOrDying(), "the totem did not save the player body");
            assertLost(helper, owner, 2, "by the owner after the body's totem");
            assertMaxHealth(helper, body, 16.0F, "on the player body after its totem");
        } finally {
            Curse.set(owner, 0);
            BodyTestKit.cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    // A logout writes the state at once, so the file has it without waiting for the next flush. The player's state is
    // loaded first, as their first hardcore tick does: until then a change to it goes straight to the file.
    @GameTest(environment = LEGS)
    public void stateIsOnDiskAfterALogout(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = join(helper, "LegsOnDisk");
        UUID uuid = player.getUUID();
        try {
            HardcoreStates.of(player);
            Legs.set(uuid, true);
            helper.assertFalse(onDisk(server, uuid).legsBroken, "the broken legs were on disk before the logout");
            TestPlayers.leave(player);
            helper.assertTrue(onDisk(server, uuid).legsBroken, "the broken legs aren't on disk after the logout");
        } finally {
            Legs.set(uuid, false);
            leave(server, uuid);
        }
        helper.succeed();
    }

    // Its wording follows the cap: one heart, not "1 hearts"
    @GameTest(environment = CURSE)
    public void curseCommandWordsOneHeart(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = join(helper, "CurseOneHeart");
        UUID uuid = player.getUUID();
        String name = player.getGameProfile().name();
        HardcoreConfig.CurseSection cfg = Settings.get().hardcore.curse;
        int capBefore = cfg.maxLostHearts;
        try {
            cfg.maxLostHearts = 1;
            helper.assertValueEqual(CommandGameTests.run(server, "yolt curse " + name + " set 2"),
                    List.of("The curse takes at most 1 heart."), "the reply past a cap of 1");
            assertLost(helper, uuid, 0, "after the refused set");
            helper.assertValueEqual(CommandGameTests.run(server, "yolt curse " + name + " set 1"),
                    List.of(name + " has lost 1 max heart to the Curse of the Undead."), "the reply to set 1");
            assertLost(helper, uuid, 1, "after set 1");
            assertMaxHealth(helper, player, 18.0F, "after set 1");
            helper.assertValueEqual(CommandGameTests.run(server, "yolt curse " + name + " set 0"),
                    List.of("Cleared " + name + "'s Curse of the Undead."), "the reply to set 0");
            assertLost(helper, uuid, 0, "after set 0");
        } finally {
            cfg.maxLostHearts = capBefore;
            Curse.set(uuid, 0);
            leave(server, uuid);
        }
        helper.succeed();
    }

    // Ops of level 2 and command blocks may set it; a level 1 op is refused before it runs
    @GameTest(environment = CURSE)
    public void curseCommandFromOpPlayerAndCommandBlock(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer target = join(helper, "CurseTarget");
        ServerPlayer admin = join(helper, "CurseAdmin", AT.offset(1, 0, 0));
        ServerPlayer moderator = join(helper, "CurseModerator", AT.offset(2, 0, 0));
        UUID uuid = target.getUUID();
        String name = target.getGameProfile().name();
        try {
            TestPlayers.op(admin);
            TestPlayers.op(moderator, LevelBasedPermissionSet.MODERATOR);
            server.getCommands().performPrefixedCommand(admin.createCommandSourceStack(), "yolt curse " + name + " set 1");
            assertLost(helper, uuid, 1, "after a level 2 op's set");

            CommandSourceStack low = moderator.createCommandSourceStack();
            helper.assertTrue(Commands.getParseException(server.getCommands().getDispatcher().parse("yolt curse " + name + " set 3", low)) != null,
                    "a level 1 op's /yolt curse parsed");
            server.getCommands().performPrefixedCommand(low, "yolt curse " + name + " set 3");
            assertLost(helper, uuid, 1, "after a level 1 op's set");

            BlockPos blockAt = new BlockPos(4, 1, 4);
            helper.setBlock(blockAt, Blocks.COMMAND_BLOCK);
            CommandBlockEntity block = helper.getBlockEntity(blockAt, CommandBlockEntity.class);
            block.getCommandBlock().setCommand("yolt curse " + name + " set 2");
            block.getCommandBlock().performCommand(helper.getLevel());
            helper.assertValueEqual(block.getCommandBlock().getSuccessCount(), 1, "the command block's success count");
            assertLost(helper, uuid, 2, "after the command block's set");
        } finally {
            TestPlayers.deop(admin);
            TestPlayers.deop(moderator);
            Curse.set(uuid, 0);
            leave(server, uuid);
            leave(server, admin.getUUID());
            leave(server, moderator.getUUID());
        }
        helper.succeed();
    }

    // With countAfk off, idle time counts up to five minutes and no further: one player 4m50s idle still earns heal
    // time, one 5m10s idle doesn't
    @GameTest(environment = CURSE_AFK_LINE, maxTicks = 40)
    public void curse6AfkLineIsFiveMinutes(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer under = join(helper, "CurseNearlyIdle");
        ServerPlayer over = join(helper, "CurseJustIdle", AT.offset(1, 0, 0));
        List<UUID> players = List.of(under.getUUID(), over.getUUID());
        Runnable cleanUp = BodyTestKit.once(() -> {
            for (UUID uuid : players) {
                Curse.set(uuid, 0);
                leave(server, uuid);
            }
        });
        boolean waiting = false;
        try {
            helper.assertValueEqual(Settings.get().hardcore.curse.afkMinutes, 5, "afkMinutes as it ships");
            for (UUID uuid : players) {
                Curse.set(uuid, 1);
            }
            ((LastActionAccessor) under).yoltSetLastActionTime(Util.getMillis() - 290_000L);
            ((LastActionAccessor) over).yoltSetLastActionTime(Util.getMillis() - 310_000L);
            helper.runAfterDelay(5, () -> {
                try {
                    helper.assertTrue(HardcoreStates.read(under.getUUID()).curseOnlineTicks > 0, "no time counted for 4m50s idle");
                    helper.assertValueEqual(HardcoreStates.read(over.getUUID()).curseOnlineTicks, 0L, "ticks counted for 5m10s idle");
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

    // heartsPerDeath and heartsPerTotem are the server's: a death that costs 2 is refunded 2, and a totem that costs
    // nothing says nothing
    @GameTest(environment = CURSE_LOCKOUT)
    public void curse2TwoPerDeathNoneForATotem(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = join(helper, "CurseTwoPer");
        ServerPlayer saved = join(helper, "CurseFreeTotem", AT.offset(1, 0, 0));
        UUID uuid = player.getUUID();
        String name = player.getGameProfile().name();
        HardcoreConfig.CurseSection cfg = Settings.get().hardcore.curse;
        int perDeath = cfg.heartsPerDeath;
        int perTotem = cfg.heartsPerTotem;
        try (LockoutGameTests.OwnStore own = new LockoutGameTests.OwnStore()) {
            cfg.heartsPerDeath = 2;
            cfg.heartsPerTotem = 0;
            GraveDeathGameTests.respawnBesideTest(helper, player);
            player.kill(player.level());
            assertLost(helper, uuid, 2, "to a death worth 2");
            helper.assertValueEqual(CommandGameTests.run(server, "yolt unkill " + name),
                    List.of("Revived " + name + ". Gave back 2 max hearts."), "the unkill's reply");
            assertLost(helper, uuid, 0, "after the unkill");

            saved.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
            saved.setHealth(2.0F);
            saved.hurtServer(helper.getLevel(), helper.getLevel().damageSources().generic(), 10.0F);
            helper.assertFalse(saved.isDeadOrDying(), "the totem did not save the player");
            assertLost(helper, saved.getUUID(), 0, "to a totem worth 0");
            assertMaxHealth(helper, saved, 20.0F, "after a totem worth 0");
            helper.assertFalse(actionBars(saved).stream().anyMatch(line -> line.startsWith("Curse of the Undead")),
                    "a free totem was announced: " + actionBars(saved));
        } finally {
            cfg.heartsPerDeath = perDeath;
            cfg.heartsPerTotem = perTotem;
            Curse.set(uuid, 0);
            Curse.set(saved.getUUID(), 0);
            leave(server, uuid);
            leave(server, saved.getUUID());
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

    // What the hardcore store's file holds for the player, read through a connection of its own
    private static HardcoreState onDisk(MinecraftServer server, UUID uuid) {
        Path file = server.getWorldPath(LevelResource.ROOT).resolve("database/" + Yolt.MOD_ID + "-hardcore.db");
        try (HardcoreStore store = HardcoreStore.open(file)) {
            return store.player(uuid).map(HardcoreState::decode).orElseGet(HardcoreState::new);
        }
    }

    // Every max health modifier YOLT has on the player, by id
    private static List<Identifier> ourMaxHealthModifiers(ServerPlayer player) {
        return player.getAttribute(Attributes.MAX_HEALTH).getModifiers().stream()
                .map(AttributeModifier::id)
                .filter(id -> id.getNamespace().equals(Yolt.MOD_ID))
                .toList();
    }

    private static List<String> actionBars(ServerPlayer player) {
        return TestPlayers.sentPackets(player).stream()
                .filter(packet -> packet instanceof ClientboundSystemChatPacket chat && chat.overlay())
                .map(packet -> ((ClientboundSystemChatPacket) packet).content().getString())
                .toList();
    }
}
