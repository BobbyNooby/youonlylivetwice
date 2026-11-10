package bobbynooby.dev.gametest;

import bobbynooby.dev.bodies.Kills;
import bobbynooby.dev.chat.CommandRestriction;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.graves.Grave;
import bobbynooby.dev.graves.GraveItems;
import bobbynooby.dev.graves.GraveStore;
import bobbynooby.dev.lockout.DeathLockout;
import bobbynooby.dev.skins.CachedSkin;
import bobbynooby.dev.skins.SkinStore;
import bobbynooby.dev.skins.SkinTextures;
import bobbynooby.dev.skins.Skins;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

// The fake lookup stands in for Mojang (TestServerSetup puts it in before the server starts), and each test sets up
// names of its own. Logins run in LOGIN_CHECK, where no other test's player is online: the GameTest server holds one
// player, and vanilla refuses a login to a full server. Every test puts back what it changes in the same tick.
public class SkinGameTests {
    private static final FakeSkinLookup MOJANG = FakeSkinLookup.INSTANCE;
    private static final UUID NOTCH_ID = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    // The skin goes into the profile the player is made from, so the player has it, and so does the body they leave
    @GameTest(environment = LockoutGameTests.LOGIN_CHECK)
    public void offlineJoinGetsCachedSkin(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        String name = uniqueName("Skin");
        SkinTextures skin = FakeSkinLookup.texturesOf(name);
        MOJANG.answer(name);
        ServerPlayer player = null;
        try (TestLogins.Login login = TestLogins.start(server, TestLogins.offline(name))) {
            login.tick();
            Optional<GameProfile> told = login.finished();
            helper.assertTrue(told.isPresent(), "the login did not go through");
            assertEqual(helper, textures(told.get()), skin, "textures the client was told it has");
            assertEqual(helper, textures(login.profile()), skin, "textures in the profile the player is made from");
            CachedSkin cached = SkinStore.get(server).skin(name);
            helper.assertTrue(cached != null && skin.equals(cached.textures()), "the skin was not cached: " + cached);

            player = TestPlayers.join(helper, login.profile(), GameType.SURVIVAL, new BlockPos(1, 1, 1));
            assertEqual(helper, textures(player.getGameProfile()), skin, "the joined player's textures");
            Mannequin body = BodyTestKit.leaveBodyWithDefaults(helper, player);
            assertEqual(helper, textures(body.getProfile().partialProfile()), skin, "the body's textures");
        } finally {
            MOJANG.forget(name);
            if (player != null) {
                BodyTestKit.cleanUp(helper, player.getUUID());
            }
        }
        helper.succeed();
    }

    // Online mode, a proxy or another mod gave the skin: Mojang isn't asked, and the skin stays as it came
    @GameTest(environment = LockoutGameTests.LOGIN_CHECK)
    public void onlineProfileIsLeftAlone(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        String name = uniqueName("Online");
        SkinTextures own = new SkinTextures("own textures", "own signature");
        // What the lookup would give, if it were asked
        MOJANG.answer(name);
        GameProfile online = Skins.withTextures(new GameProfile(UUID.randomUUID(), name), own);
        try (TestLogins.Login login = TestLogins.start(server, online)) {
            login.tick();
            helper.assertTrue(login.finished().isPresent(), "the login did not go through");
            helper.assertValueEqual(MOJANG.timesAsked(name), 0, "times Mojang was asked");
            assertEqual(helper, textures(login.profile()), own, "textures in the profile the player is made from");
            // Kept for heads built later from the name alone
            assertEqual(helper, SkinStore.get(server).applied(online.id()), own, "skin kept as the one they joined with");
        } finally {
            MOJANG.forget(name);
        }
        helper.succeed();
    }

    // Mojang never answers: the login waits out skins.lookupTimeoutSeconds, a tick at a time, then goes on without a skin
    @GameTest(environment = LockoutGameTests.LOGIN_CHECK)
    public void lookupTimeoutStillLogsIn(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        String name = uniqueName("Slow");
        MOJANG.neverAnswer(name);
        int ticks = Settings.get().skins.lookupTimeoutSeconds * SharedConstants.TICKS_PER_SECOND;
        try (TestLogins.Login login = TestLogins.start(server, TestLogins.offline(name))) {
            for (int tick = 0; tick < ticks; tick++) {
                login.tick();
            }
            helper.assertTrue(login.finished().isEmpty(), "the login went on before the timeout");
            login.tick();
            Optional<GameProfile> told = login.finished();
            helper.assertTrue(told.isPresent(), "the login did not go through after the timeout");
            assertEqual(helper, textures(told.get()), null, "textures after the timeout");
            helper.assertValueEqual(MOJANG.timesAsked(name), 1, "times Mojang was asked");
        } finally {
            MOJANG.forget(name);
        }
        helper.succeed();
    }

    @GameTest(environment = LockoutGameTests.LOGIN_CHECK)
    public void modeOffNeverLooksUp(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        String name = uniqueName("Off");
        MOJANG.answer(name);
        YoltConfig.SkinsSection skins = Settings.get().skins;
        YoltConfig.SkinMode before = skins.mode;
        skins.mode = YoltConfig.SkinMode.OFF;
        try (TestLogins.Login login = TestLogins.start(server, TestLogins.offline(name))) {
            login.tick();
            Optional<GameProfile> told = login.finished();
            helper.assertTrue(told.isPresent(), "the login did not go through");
            helper.assertValueEqual(MOJANG.timesAsked(name), 0, "times Mojang was asked");
            assertEqual(helper, textures(told.get()), null, "textures with skins off");
            // Refused before the player is even looked up
            helper.assertValueEqual(console(server, "yolt skin set " + name + " " + name), List.of("Skins are turned off."),
                    "reply to a pick with skins off");
            helper.assertValueEqual(MOJANG.timesAsked(name), 0, "times Mojang was asked after the pick");
        } finally {
            skins.mode = before;
            MOJANG.forget(name);
        }
        helper.succeed();
    }

    // SkinsRestorer or SkinRestorer gives skins at login itself, so YOLT does nothing
    @GameTest(environment = LockoutGameTests.LOGIN_CHECK)
    public void skinModPresentStepsAside(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        String name = uniqueName("Mod");
        MOJANG.answer(name);
        BooleanSupplier before = Skins.useSkinModCheckForTesting(() -> true);
        try (TestLogins.Login login = TestLogins.start(server, TestLogins.offline(name))) {
            login.tick();
            Optional<GameProfile> told = login.finished();
            helper.assertTrue(told.isPresent(), "the login did not go through");
            helper.assertValueEqual(MOJANG.timesAsked(name), 0, "times Mojang was asked");
            assertEqual(helper, textures(told.get()), null, "textures with a skin mod loaded");
        } finally {
            Skins.useSkinModCheckForTesting(before);
            MOJANG.forget(name);
        }
        helper.succeed();
    }

    // /yolt kill of an offline player with no body builds the grave head from the name and id alone, so the skin
    // comes from what YOLT saved for that name
    @GameTest
    public void nameOnlyHeadUsesCache(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TestPlayers.join(helper, "Head", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        NameAndId who = player.nameAndId();
        // Something to carry, so the death leaves a grave
        player.getInventory().add(new ItemStack(Items.DIRT));
        // Leaving saves the player, so the kill is an offline one
        TestPlayers.leave(player);
        SkinTextures skin = FakeSkinLookup.texturesOf(who.name());
        SkinStore.get(server).putSkin(CachedSkin.found(who.name(), skin, System.currentTimeMillis()));
        try {
            helper.assertValueEqual(Kills.kill(server, who), Kills.Result.KILLED_OFFLINE, "kill result");
            List<Grave> graves = gravesOf(server, who.id());
            helper.assertFalse(graves.isEmpty(), "the kill left no grave");
            for (Grave grave : graves) {
                assertEqual(helper, textures(grave.profile().partialProfile()), skin, "the grave head's textures");
            }
            ItemStack head = graves.getFirst().items().stream().filter(GraveItems::isGraveHead).findFirst().orElse(ItemStack.EMPTY);
            ResolvableProfile headProfile = head.get(DataComponents.PROFILE);
            helper.assertTrue(headProfile != null, "the grave holds no grave head item");
            assertEqual(helper, textures(headProfile.partialProfile()), skin, "the grave head item's textures");
        } finally {
            for (Grave grave : gravesOf(server, who.id())) {
                GraveStore.get(server).remove(grave.id());
                helper.getLevel().setBlock(grave.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
            DeathLockout.revive(who.id(), who.name(), "console");
        }
        helper.succeed();
    }

    private static List<Grave> gravesOf(MinecraftServer server, UUID owner) {
        return GraveStore.get(server).all().stream().filter(grave -> grave.owner().equals(owner)).toList();
    }

    // The account a player picked wins over the one with their name, which Mojang isn't even asked about
    @GameTest(environment = LockoutGameTests.LOGIN_CHECK)
    public void skinChoiceOverridesUsernameLookup(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        String name = uniqueName("Pick");
        String account = uniqueName("Acct");
        MOJANG.answer(name);
        MOJANG.answer(account);
        GameProfile profile = TestLogins.offline(name);
        try {
            // The console names the player, which an offline server turns into the same id their login gets
            helper.assertValueEqual(console(server, "yolt skin set " + name + " " + account),
                    List.of(name + "'s skin is now " + account + "'s. It shows once they rejoin."), "command output");
            assertEqual(helper, SkinStore.get(server).choice(profile.id()), account, "the picked account");
            try (TestLogins.Login login = TestLogins.start(server, profile)) {
                login.tick();
                Optional<GameProfile> told = login.finished();
                helper.assertTrue(told.isPresent(), "the login did not go through");
                assertEqual(helper, textures(login.profile()), FakeSkinLookup.texturesOf(account), "textures after the pick");
            }
            helper.assertValueEqual(MOJANG.timesAsked(name), 0, "times Mojang was asked about the player's own name");
        } finally {
            SkinStore.get(server).clearChoice(profile.id());
            MOJANG.forget(name);
            MOJANG.forget(account);
        }
        helper.succeed();
    }

    // Even a skin the player came with, as on an online-mode server, gives way to the account they picked
    @GameTest(environment = LockoutGameTests.LOGIN_CHECK)
    public void skinChoiceOverridesOnlineSkin(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        String name = uniqueName("Online");
        String account = uniqueName("Acct");
        MOJANG.answer(account);
        GameProfile online = Skins.withTextures(new GameProfile(UUID.randomUUID(), name), new SkinTextures("own textures", "own signature"));
        // Straight into the store: on this offline test server a command would give the name its offline id
        SkinStore.get(server).choose(online.id(), account);
        try (TestLogins.Login login = TestLogins.start(server, online)) {
            login.tick();
            helper.assertTrue(login.finished().isPresent(), "the login did not go through");
            assertEqual(helper, textures(login.profile()), FakeSkinLookup.texturesOf(account), "textures after the pick");
            helper.assertValueEqual(MOJANG.timesAsked(account), 1, "times Mojang was asked about the picked account");
        } finally {
            SkinStore.get(server).clearChoice(online.id());
            MOJANG.forget(account);
        }
        helper.succeed();
    }

    // A picked account that no longer exists gives way to the player's own name at login, as it does for heads built
    // from the name alone, so the player and a later head agree
    @GameTest(environment = LockoutGameTests.LOGIN_CHECK)
    public void goneAccountPickFallsBackToUsername(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        String name = uniqueName("Gone");
        // No test sets this one up, so the fake says no account has it, as after the account was renamed
        String account = uniqueName("Acct");
        MOJANG.answer(name);
        GameProfile profile = TestLogins.offline(name);
        SkinStore store = SkinStore.get(server);
        // Straight into the store, as a pick made while the account still existed: a command now would refuse it
        store.choose(profile.id(), account);
        try (TestLogins.Login login = TestLogins.start(server, profile)) {
            login.tick();
            helper.assertTrue(login.finished().isPresent(), "the login did not go through");
            assertEqual(helper, textures(login.profile()), FakeSkinLookup.texturesOf(name), "textures with the picked account gone");
            helper.assertValueEqual(MOJANG.timesAsked(account), 1, "times Mojang was asked about the picked account");
            helper.assertValueEqual(MOJANG.timesAsked(name), 1, "times Mojang was asked about the player's own name");
            assertEqual(helper, textures(Skins.known(server, new NameAndId(profile))), FakeSkinLookup.texturesOf(name),
                    "textures of a head built from the name");
            // Kept, so the pick comes back if someone takes the name again
            assertEqual(helper, store.choice(profile.id()), account, "the pick after its account was found gone");
        } finally {
            store.clearChoice(profile.id());
            MOJANG.forget(name);
            MOJANG.forget(account);
        }
        helper.succeed();
    }

    @GameTest
    public void unknownMojangNameChangesNothing(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig config = Settings.get();
        boolean restrictBefore = config.restrictCommands;
        boolean choiceBefore = config.skins.allowPlayerChoice;
        String account = uniqueName("Acct");
        // The fake has no account for a name no test set up
        String unknown = uniqueName("None");
        MOJANG.answer(account);
        TestPlayers.Recording player = TestPlayers.joinRecording(helper, "Unknown", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        SkinStore store = SkinStore.get(server);
        try {
            config.restrictCommands = true;
            config.skins.allowPlayerChoice = true;
            type(player, "yolt skin set " + account);
            assertEqual(helper, store.choice(player.getUUID()), account, "the first pick");

            player.messages.clear();
            type(player, "yolt skin set " + unknown);
            helper.assertValueEqual(texts(player), List.of("No Minecraft account is called " + unknown + "."), "reply to an unknown name");
            assertEqual(helper, store.choice(player.getUUID()), account, "the pick after an unknown name");
            helper.assertValueEqual(MOJANG.timesAsked(unknown), 1, "times Mojang was asked about the unknown name");

            // No account can have a dot in its name, so Mojang isn't asked
            player.messages.clear();
            type(player, "yolt skin set no.such");
            helper.assertValueEqual(texts(player), List.of("No Minecraft account is called no.such."), "reply to an impossible name");
            helper.assertValueEqual(MOJANG.timesAsked("no.such"), 0, "times Mojang was asked about an impossible name");
            assertEqual(helper, store.choice(player.getUUID()), account, "the pick after an impossible name");
        } finally {
            config.restrictCommands = restrictBefore;
            config.skins.allowPlayerChoice = choiceBefore;
            store.clearChoice(player.getUUID());
            TestPlayers.leave(player);
            MOJANG.forget(account);
            MOJANG.forget(unknown);
        }
        helper.succeed();
    }

    // Typed by a non-op, read against the tree their client has: /yolt skin for their own skin, and nothing more
    @GameTest
    public void nonOpCanOnlySetOwnSkin(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig config = Settings.get();
        boolean restrictBefore = config.restrictCommands;
        boolean choiceBefore = config.skins.allowPlayerChoice;
        String account = uniqueName("Acct");
        MOJANG.answer(account);
        TestPlayers.Recording player = TestPlayers.joinRecording(helper, "Picker", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        ServerPlayer other = TestPlayers.join(helper, "Picked", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        String otherName = other.nameAndId().name();
        SkinStore store = SkinStore.get(server);
        try {
            config.restrictCommands = true;
            config.skins.allowPlayerChoice = true;
            helper.assertTrue(CommandRestriction.restricted(player), "the test player is not held to the non-op tree");

            type(player, "yolt skin set " + otherName + " " + account);
            assertEqual(helper, store.choice(other.getUUID()), null, "a non-op picked another player's skin");

            type(player, "yolt skin set " + account);
            assertEqual(helper, store.choice(player.getUUID()), account, "the non-op's own pick");
            helper.assertTrue(texts(player).contains("Your skin is now " + account + "'s. Rejoin to see it."),
                    "no reply to the pick: " + texts(player));

            store.choose(other.getUUID(), account);
            type(player, "yolt skin clear " + otherName);
            assertEqual(helper, store.choice(other.getUUID()), account, "a non-op cleared another player's pick");

            player.messages.clear();
            type(player, "yolt skin clear");
            assertEqual(helper, store.choice(player.getUUID()), null, "the non-op's own pick after clearing");
            helper.assertValueEqual(texts(player), List.of("Your skin is back to the default. Rejoin to see it."), "reply to clearing");

            // With the switch off the tree has no /yolt, so nothing runs
            config.skins.allowPlayerChoice = false;
            type(player, "yolt skin set " + account);
            assertEqual(helper, store.choice(player.getUUID()), null, "a pick with player choice off");
        } finally {
            config.restrictCommands = restrictBefore;
            config.skins.allowPlayerChoice = choiceBefore;
            store.clearChoice(player.getUUID());
            store.clearChoice(other.getUUID());
            TestPlayers.leave(player);
            TestPlayers.leave(other);
            MOJANG.forget(account);
        }
        helper.succeed();
    }

    // With the command filter off a non-op gets vanilla's tree, cut down to the nodes they may use: of /yolt, only
    // their own skin. Typed, their commands are read against the full tree, where the requirements stop the rest.
    @GameTest
    public void nonOpPicksOwnSkinWithFilterOff(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig config = Settings.get();
        boolean restrictBefore = config.restrictCommands;
        boolean choiceBefore = config.skins.allowPlayerChoice;
        int diameterBefore = config.worldBorder.diameter;
        String account = uniqueName("Acct");
        MOJANG.answer(account);
        TestPlayers.Recording player = TestPlayers.joinRecording(helper, "Unfiltered", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        ServerPlayer other = TestPlayers.join(helper, "Unpicked", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        String otherName = other.nameAndId().name();
        SkinStore store = SkinStore.get(server);
        try {
            config.restrictCommands = false;
            config.skins.allowPlayerChoice = true;
            helper.assertFalse(CommandRestriction.restricted(player), "the test player is held to the non-op tree");
            server.getCommands().sendCommands(player);
            helper.assertValueEqual(yoltPaths(CommandRestrictionGameTests.lastTree(helper, player)),
                    List.of("yolt", "yolt skin", "yolt skin set", "yolt skin set <mojangName>", "yolt skin clear"),
                    "the /yolt a non-op is sent");

            type(player, "yolt skin set " + account);
            assertEqual(helper, store.choice(player.getUUID()), account, "the non-op's own pick");
            helper.assertTrue(texts(player).contains("Your skin is now " + account + "'s. Rejoin to see it."),
                    "no reply to the pick: " + texts(player));

            type(player, "yolt skin set " + otherName + " " + account);
            assertEqual(helper, store.choice(other.getUUID()), null, "a non-op picked another player's skin");
            store.choose(other.getUUID(), account);
            type(player, "yolt skin clear " + otherName);
            assertEqual(helper, store.choice(other.getUUID()), account, "a non-op cleared another player's pick");
            type(player, "yolt border 5000");
            helper.assertValueEqual(config.worldBorder.diameter, diameterBefore, "the border diameter after a non-op's /yolt border");

            player.messages.clear();
            type(player, "yolt skin clear");
            assertEqual(helper, store.choice(player.getUUID()), null, "the non-op's own pick after clearing");
            helper.assertValueEqual(texts(player), List.of("Your skin is back to the default. Rejoin to see it."), "reply to clearing");

            // With player choice off a non-op is sent no /yolt, and can't run it
            config.skins.allowPlayerChoice = false;
            server.getCommands().sendCommands(player);
            helper.assertValueEqual(yoltPaths(CommandRestrictionGameTests.lastTree(helper, player)), List.of(),
                    "the /yolt a non-op is sent with player choice off");
            type(player, "yolt skin set " + account);
            assertEqual(helper, store.choice(player.getUUID()), null, "a pick with player choice off");
        } finally {
            config.restrictCommands = restrictBefore;
            config.skins.allowPlayerChoice = choiceBefore;
            store.clearChoice(player.getUUID());
            store.clearChoice(other.getUUID());
            TestPlayers.leave(player);
            TestPlayers.leave(other);
            MOJANG.forget(account);
        }
        helper.succeed();
    }

    // The skin of a player who has joined this server is refused, whatever the case it is typed in, though Mojang has the
    // account. A non-op's own name never is, even while another player has picked it. With the switch off the same
    // pick goes through. Every switch goes back in the same tick.
    @GameTest
    public void skinOfServerMemberRefused(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig config = Settings.get();
        boolean restrictBefore = config.restrictCommands;
        boolean choiceBefore = config.skins.allowPlayerChoice;
        boolean refuseBefore = config.skins.refuseKnownPlayers;
        // Joined once, and away now
        ServerPlayer member = TestPlayers.join(helper, "Member", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        String memberName = member.nameAndId().name();
        TestPlayers.leave(member);
        TestPlayers.Recording player = TestPlayers.joinRecording(helper, "Copier", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        String ownName = player.nameAndId().name();
        MOJANG.answer(memberName);
        MOJANG.answer(ownName);
        // Away, with the copier's own name picked
        UUID borrower = UUID.randomUUID();
        SkinStore store = SkinStore.get(server);
        try {
            helper.assertTrue(refuseBefore, "known players' skins are not refused by default");
            config.restrictCommands = true;
            config.skins.allowPlayerChoice = true;
            player.messages.clear();
            type(player, "yolt skin set " + memberName.toLowerCase(Locale.ROOT));
            helper.assertValueEqual(texts(player), List.of("You can't use " + memberName + "'s skin. Pick another."),
                    "reply to a server member's skin");
            assertEqual(helper, store.choice(player.getUUID()), null, "the pick after the refusal");

            store.choose(borrower, ownName);
            type(player, "yolt skin set " + ownName);
            assertEqual(helper, store.choice(player.getUUID()), ownName, "the pick of their own name while it is borrowed");

            config.skins.refuseKnownPlayers = false;
            type(player, "yolt skin set " + memberName);
            assertEqual(helper, store.choice(player.getUUID()), memberName, "the pick with the switch off");
        } finally {
            config.restrictCommands = restrictBefore;
            config.skins.allowPlayerChoice = choiceBefore;
            config.skins.refuseKnownPlayers = refuseBefore;
            store.clearChoice(player.getUUID());
            store.clearChoice(borrower);
            TestPlayers.leave(player);
            MOJANG.forget(memberName);
            MOJANG.forget(ownName);
        }
        helper.succeed();
    }

    // An account another player picked is taken while they wear it, and free once they clear it
    @GameTest
    public void skinWornByAnotherRefused(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig config = Settings.get();
        boolean restrictBefore = config.restrictCommands;
        boolean choiceBefore = config.skins.allowPlayerChoice;
        String account = uniqueName("Acct");
        MOJANG.answer(account);
        // A player who is away, so only the pick can make the account taken
        UUID wearer = UUID.randomUUID();
        TestPlayers.Recording player = TestPlayers.joinRecording(helper, "Second", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        SkinStore store = SkinStore.get(server);
        try {
            config.restrictCommands = true;
            config.skins.allowPlayerChoice = true;
            store.choose(wearer, account);
            player.messages.clear();
            type(player, "yolt skin set " + account);
            helper.assertValueEqual(texts(player), List.of("You can't use " + account + "'s skin. Pick another."),
                    "reply to a skin another player wears");
            assertEqual(helper, store.choice(player.getUUID()), null, "the pick after the refusal");

            store.clearChoice(wearer);
            type(player, "yolt skin set " + account);
            assertEqual(helper, store.choice(player.getUUID()), account, "the pick once nobody wears it");
        } finally {
            config.restrictCommands = restrictBefore;
            config.skins.allowPlayerChoice = choiceBefore;
            store.clearChoice(wearer);
            store.clearChoice(player.getUUID());
            TestPlayers.leave(player);
            MOJANG.forget(account);
        }
        helper.succeed();
    }

    // The owner of a skin joins: every player's pick of it goes, but not one an op made, nor a pick of another account.
    // Nobody is told then, since that would tell a borrower online the owner just came online. Each borrower hears it
    // once, at their next join, whether they were online at the time or away.
    @GameTest
    public void ownerJoinResetsBorrowedSkin(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        String reset = "Your skin was reset because its owner joined this server.";
        GameProfile owner = new GameProfile(UUID.randomUUID(), uniqueName("Owner"));
        GameProfile online = new GameProfile(UUID.randomUUID(), uniqueName("Online"));
        GameProfile away = new GameProfile(UUID.randomUUID(), uniqueName("Away"));
        UUID other = UUID.randomUUID();
        String otherAccount = uniqueName("Acct");
        MOJANG.answer(owner.name());
        SkinStore store = SkinStore.get(server);
        List<UUID> joined = new ArrayList<>();
        UUID given = null;
        try {
            joined.add(online.id());
            ServerPlayer borrower = TestPlayers.join(helper, online, GameType.SURVIVAL, new BlockPos(1, 1, 1));
            ServerPlayer opGiven = TestPlayers.join(helper, "OpGiven", GameType.SURVIVAL, new BlockPos(1, 1, 1));
            given = opGiven.getUUID();
            joined.add(given);
            String givenName = opGiven.nameAndId().name();
            store.choose(online.id(), owner.name());
            store.choose(away.id(), owner.name());
            store.choose(other, otherAccount);
            helper.assertValueEqual(console(server, "yolt skin set " + givenName + " " + owner.name()),
                    List.of(givenName + "'s skin is now " + owner.name() + "'s. It shows once they rejoin."), "the op's pick");

            joined.add(owner.id());
            TestPlayers.join(helper, owner, GameType.SURVIVAL, new BlockPos(1, 1, 1));
            assertEqual(helper, store.choice(online.id()), null, "the online borrower's pick");
            assertEqual(helper, store.choice(away.id()), null, "the away borrower's pick");
            assertEqual(helper, store.choice(other), otherAccount, "a pick of another account");
            assertEqual(helper, store.choice(given), owner.name(), "the pick an op made");
            helper.assertValueEqual(chatLines(borrower, reset), 0, "times the online borrower was told at the owner's join");

            TestPlayers.leave(borrower);
            ServerPlayer onlineBack = TestPlayers.join(helper, online, GameType.SURVIVAL, new BlockPos(1, 1, 1));
            helper.assertValueEqual(chatLines(onlineBack, reset), 1, "times the online borrower was told at their next join");
            joined.add(away.id());
            ServerPlayer awayBack = TestPlayers.join(helper, away, GameType.SURVIVAL, new BlockPos(1, 1, 1));
            helper.assertValueEqual(chatLines(awayBack, reset), 1, "times the away borrower was told at their join");
            TestPlayers.leave(awayBack);
            awayBack = TestPlayers.join(helper, away, GameType.SURVIVAL, new BlockPos(1, 1, 1));
            helper.assertValueEqual(chatLines(awayBack, reset), 0, "times the away borrower was told at the join after");
        } finally {
            for (UUID id : joined) {
                ServerPlayer still = server.getPlayerList().getPlayer(id);
                if (still != null) {
                    TestPlayers.leave(still);
                }
            }
            store.clearChoice(online.id());
            store.clearChoice(away.id());
            store.clearChoice(other);
            if (given != null) {
                store.clearChoice(given);
            }
            MOJANG.forget(owner.name());
        }
        helper.succeed();
    }

    // A member from before YOLT kept its record of joins: only their save in players/data says they played here. It
    // counts under the account's own id, as on an online-mode server, and under the id an offline-mode server gives the
    // name. Neither id joined in this run. Without the saves the same picks go through. One tick, and the saves go again.
    @GameTest
    public void skinOfPastMemberRefused(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig config = Settings.get();
        boolean restrictBefore = config.restrictCommands;
        boolean choiceBefore = config.skins.allowPlayerChoice;
        String onlineName = uniqueName("Past");
        UUID onlineId = UUID.randomUUID();
        String offlineName = uniqueName("Past");
        MOJANG.answer(onlineName, onlineId);
        MOJANG.answer(offlineName);
        Path saves = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
        Path onlineSave = saves.resolve(onlineId + ".dat");
        Path offlineSave = saves.resolve(UUIDUtil.createOfflinePlayerUUID(offlineName) + ".dat");
        TestPlayers.Recording player = TestPlayers.joinRecording(helper, "Newcomer", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        SkinStore store = SkinStore.get(server);
        try {
            config.restrictCommands = true;
            config.skins.allowPlayerChoice = true;
            Files.createDirectories(saves);
            NbtIo.writeCompressed(new CompoundTag(), onlineSave);
            NbtIo.writeCompressed(new CompoundTag(), offlineSave);
            for (String name : List.of(onlineName, offlineName)) {
                player.messages.clear();
                type(player, "yolt skin set " + name);
                helper.assertValueEqual(texts(player), List.of("You can't use " + name + "'s skin. Pick another."),
                        "reply to the skin of " + name);
                assertEqual(helper, store.choice(player.getUUID()), null, "the pick after the refusal of " + name);
            }

            Files.delete(onlineSave);
            Files.delete(offlineSave);
            type(player, "yolt skin set " + onlineName);
            assertEqual(helper, store.choice(player.getUUID()), onlineName, "the pick with the account's save gone");
            type(player, "yolt skin set " + offlineName);
            assertEqual(helper, store.choice(player.getUUID()), offlineName, "the pick with the name's offline save gone");
        } finally {
            Files.deleteIfExists(onlineSave);
            Files.deleteIfExists(offlineSave);
            config.restrictCommands = restrictBefore;
            config.skins.allowPlayerChoice = choiceBefore;
            store.clearChoice(player.getUUID());
            TestPlayers.leave(player);
            MOJANG.forget(onlineName);
            MOJANG.forget(offlineName);
        }
        helper.succeed();
    }

    // With the lock on, a non-op picks once: a second pick and a clear are both refused, and the skin stays. An op still
    // changes it for them. With the lock off they change it again themselves. Every switch goes back in the same tick.
    @GameTest
    public void lockedSkinNeedsOp(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig config = Settings.get();
        boolean restrictBefore = config.restrictCommands;
        boolean choiceBefore = config.skins.allowPlayerChoice;
        boolean lockBefore = config.skins.lockAfterFirstChoice;
        String first = uniqueName("Acct");
        String second = uniqueName("Acct");
        MOJANG.answer(first);
        MOJANG.answer(second);
        TestPlayers.Recording player = TestPlayers.joinRecording(helper, "Locked", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        String name = player.nameAndId().name();
        SkinStore store = SkinStore.get(server);
        try {
            helper.assertFalse(lockBefore, "skins lock after the first pick by default");
            config.restrictCommands = true;
            config.skins.allowPlayerChoice = true;
            config.skins.lockAfterFirstChoice = true;
            type(player, "yolt skin set " + first);
            assertEqual(helper, store.choice(player.getUUID()), first, "the first pick");

            player.messages.clear();
            type(player, "yolt skin set " + second);
            helper.assertValueEqual(texts(player), List.of("Your skin is locked. Ask an op to change it."), "reply to a second pick");
            assertEqual(helper, store.choice(player.getUUID()), first, "the pick after a second pick");
            player.messages.clear();
            type(player, "yolt skin clear");
            helper.assertValueEqual(texts(player), List.of("Your skin is locked. Ask an op to change it."), "reply to a clear");
            assertEqual(helper, store.choice(player.getUUID()), first, "the pick after a clear");

            helper.assertValueEqual(console(server, "yolt skin set " + name + " " + second),
                    List.of(name + "'s skin is now " + second + "'s. It shows once they rejoin."), "an op's change");
            assertEqual(helper, store.choice(player.getUUID()), second, "the pick an op changed");

            config.skins.lockAfterFirstChoice = false;
            type(player, "yolt skin set " + first);
            assertEqual(helper, store.choice(player.getUUID()), first, "the pick with the lock off");
        } finally {
            config.restrictCommands = restrictBefore;
            config.skins.allowPlayerChoice = choiceBefore;
            config.skins.lockAfterFirstChoice = lockBefore;
            store.clearChoice(player.getUUID());
            TestPlayers.leave(player);
            MOJANG.forget(first);
            MOJANG.forget(second);
        }
        helper.succeed();
    }

    // Neither rule holds an op or the console back: with both on, an op picks a server member's skin, then one another
    // player wears, then clears it, all past the lock, and the console gives a player a member's skin. Every switch
    // goes back in the same tick.
    @GameTest
    public void opCanSetAnySkin(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig config = Settings.get();
        boolean refuseBefore = config.skins.refuseKnownPlayers;
        boolean lockBefore = config.skins.lockAfterFirstChoice;
        ServerPlayer member = TestPlayers.join(helper, "OpMember", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        String memberName = member.nameAndId().name();
        TestPlayers.leave(member);
        String worn = uniqueName("Acct");
        UUID wearer = UUID.randomUUID();
        MOJANG.answer(memberName);
        MOJANG.answer(worn);
        TestPlayers.Recording op = TestPlayers.joinRecording(helper, "SkinOp", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        ServerPlayer target = TestPlayers.join(helper, "SkinTarget", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        String targetName = target.nameAndId().name();
        SkinStore store = SkinStore.get(server);
        try {
            TestPlayers.op(op);
            config.skins.refuseKnownPlayers = true;
            config.skins.lockAfterFirstChoice = true;
            store.choose(wearer, worn);
            type(op, "yolt skin set " + memberName);
            assertEqual(helper, store.choice(op.getUUID()), memberName, "an op's pick of a member's skin");
            type(op, "yolt skin set " + worn);
            assertEqual(helper, store.choice(op.getUUID()), worn, "an op's locked pick of a worn skin");
            type(op, "yolt skin clear");
            assertEqual(helper, store.choice(op.getUUID()), null, "an op's locked pick after a clear");

            helper.assertValueEqual(console(server, "yolt skin set " + targetName + " " + memberName),
                    List.of(targetName + "'s skin is now " + memberName + "'s. It shows once they rejoin."), "the console's pick");
            assertEqual(helper, store.choice(target.getUUID()), memberName, "the console's pick of a member's skin");
        } finally {
            config.skins.refuseKnownPlayers = refuseBefore;
            config.skins.lockAfterFirstChoice = lockBefore;
            store.clearChoice(wearer);
            store.clearChoice(op.getUUID());
            store.clearChoice(target.getUUID());
            TestPlayers.deop(op);
            TestPlayers.leave(op);
            TestPlayers.leave(target);
            MOJANG.forget(memberName);
            MOJANG.forget(worn);
        }
        helper.succeed();
    }

    // How many times this line came to the player in chat
    private static int chatLines(ServerPlayer player, String line) {
        return (int) TestPlayers.sentPackets(player).stream()
                .filter(packet -> packet instanceof ClientboundSystemChatPacket chat && chat.content().getString().equals(line))
                .count();
    }

    // Every path under /yolt in a tree, as the dump writes them
    private static List<String> yoltPaths(RootCommandNode<CommandSourceStack> root) {
        List<String> paths = new ArrayList<>();
        CommandNode<CommandSourceStack> yolt = root.getChild("yolt");
        if (yolt != null) {
            addPaths(yolt, "", paths);
        }
        return paths;
    }

    private static void addPaths(CommandNode<CommandSourceStack> node, String parent, List<String> paths) {
        String path = parent + (node instanceof ArgumentCommandNode<?, ?> ? "<" + node.getName() + ">" : node.getName());
        paths.add(path);
        for (CommandNode<CommandSourceStack> child : node.getChildren()) {
            addPaths(child, path + " ", paths);
        }
    }

    // Clearing goes back to the account with the player's own name
    @GameTest(environment = LockoutGameTests.LOGIN_CHECK)
    public void skinClearRestoresDefault(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        String name = uniqueName("Clear");
        String account = uniqueName("Acct");
        MOJANG.answer(name);
        MOJANG.answer(account);
        GameProfile profile = TestLogins.offline(name);
        try {
            console(server, "yolt skin set " + name + " " + account);
            assertEqual(helper, SkinStore.get(server).choice(profile.id()), account, "the pick");
            // On disk at once, so a crash can't lose what the player was told
            assertEqual(helper, onDisk(server).choice(profile.id()), account, "the pick in the skin file");
            helper.assertValueEqual(console(server, "yolt skin clear " + name),
                    List.of(name + "'s skin is back to the default. It shows once they rejoin."), "command output");
            assertEqual(helper, SkinStore.get(server).choice(profile.id()), null, "the pick after clearing");
            assertEqual(helper, onDisk(server).choice(profile.id()), null, "the pick in the skin file after clearing");
            helper.assertValueEqual(console(server, "yolt skin clear " + name), List.of(name + " hasn't picked a skin."),
                    "output of a second clear");
            try (TestLogins.Login login = TestLogins.start(server, profile)) {
                login.tick();
                helper.assertTrue(login.finished().isPresent(), "the login did not go through");
                assertEqual(helper, textures(login.profile()), FakeSkinLookup.texturesOf(name), "textures after clearing");
            }
        } finally {
            SkinStore.get(server).clearChoice(profile.id());
            MOJANG.forget(name);
            MOJANG.forget(account);
        }
        helper.succeed();
    }

    // What the skin file on disk holds right now
    private static SkinStore onDisk(MinecraftServer server) throws IOException {
        Path file = SkinStore.TYPE.id().withSuffix(".dat").resolveAgainst(server.getWorldPath(LevelResource.DATA));
        CompoundTag saved = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        return SkinStore.TYPE.codec().parse(NbtOps.INSTANCE, saved.get("data")).getOrThrow();
    }

    // As the player's client sends a typed command, so it is read against the tree that player has
    static void type(ServerPlayer player, String command) {
        player.connection.handleChatCommand(new ServerboundChatCommandPacket(command));
    }

    static List<String> texts(TestPlayers.Recording player) {
        return player.messages.stream().map(Component::getString).toList();
    }

    // Runs the command as the console and returns every line it sent back
    static List<String> console(MinecraftServer server, String command) {
        return console(server.createCommandSourceStack(), command);
    }

    // The same from a console source moved somewhere, as for a selector that measures distance
    static List<String> console(CommandSourceStack source, String command) {
        List<String> output = new ArrayList<>();
        CommandSource capture = new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                output.add(message.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        source.getServer().getCommands().performPrefixedCommand(source.withSource(capture), command);
        return output;
    }

    // A current file loads as it was saved, and one from a newer YOLT still gives what this one can read
    @GameTest
    public void skinStoreReadsWhatItSaves(GameTestHelper helper) {
        UUID player = UUID.randomUUID();
        CompoundTag data = new CompoundTag();
        data.putInt("format", SkinStore.FORMAT);
        ListTag skins = new ListTag();
        CompoundTag notch = skinTag("Notch", new SkinTextures("notch value", "notch signature"), 123L);
        notch.put("id", UUIDUtil.CODEC.encodeStart(NbtOps.INSTANCE, NOTCH_ID).getOrThrow());
        skins.add(notch);
        skins.add(skinTag("Nobody", null, 456L));
        data.put("skins", skins);
        ListTag applied = new ListTag();
        CompoundTag entry = new CompoundTag();
        entry.put("player", UUIDUtil.CODEC.encodeStart(NbtOps.INSTANCE, player).getOrThrow());
        entry.put("textures", texturesTag(new SkinTextures("applied value", null)));
        applied.add(entry);
        data.put("applied", applied);
        ListTag choices = new ListTag();
        CompoundTag choice = new CompoundTag();
        choice.put("player", UUIDUtil.CODEC.encodeStart(NbtOps.INSTANCE, player).getOrThrow());
        choice.putString("name", "Notch");
        choice.putBoolean("by_op", true);
        choices.add(choice);
        data.put("choices", choices);
        ListTag joined = new ListTag();
        CompoundTag join = new CompoundTag();
        join.put("player", UUIDUtil.CODEC.encodeStart(NbtOps.INSTANCE, player).getOrThrow());
        join.putString("name", "Joiner");
        joined.add(join);
        data.put("joined", joined);

        SkinStore loaded = SkinStore.TYPE.codec().parse(NbtOps.INSTANCE, data).getOrThrow();
        assertStore(helper, loaded, player, "loaded");
        helper.assertFalse(loaded.fromNewer(), "a current file read as newer");

        Tag saved = SkinStore.TYPE.codec().encodeStart(NbtOps.INSTANCE, loaded).getOrThrow();
        helper.assertTrue(saved instanceof CompoundTag tag && tag.getIntOr("format", 0) == SkinStore.FORMAT,
                "the saved file has no format: " + saved);
        assertStore(helper, SkinStore.TYPE.codec().parse(NbtOps.INSTANCE, saved).getOrThrow(), player, "saved and loaded again");

        data.putInt("format", SkinStore.FORMAT + 1);
        SkinStore newer = SkinStore.TYPE.codec().parse(NbtOps.INSTANCE, data).getOrThrow();
        helper.assertTrue(newer.fromNewer(), "a newer file did not read as newer");
        assertStore(helper, newer, player, "from a newer file");
        helper.succeed();
    }

    private static void assertStore(GameTestHelper helper, SkinStore store, UUID player, String what) {
        assertEqual(helper, store.skin("NOTCH"), CachedSkin.found("Notch", NOTCH_ID, new SkinTextures("notch value", "notch signature"), 123L),
                "Notch's skin, " + what);
        assertEqual(helper, store.skin("nobody"), CachedSkin.noAccount("Nobody", 456L), "the no-account answer, " + what);
        assertEqual(helper, store.applied(player), new SkinTextures("applied value", null), "the applied skin, " + what);
        assertEqual(helper, store.choice(player), "Notch", "the picked account, " + what);
        helper.assertTrue(store.pickedByOp(player), "the pick is not an op's, " + what);
        helper.assertTrue(store.joined(player) && store.joinedAs("joiner", UUID.randomUUID()), "the join is not recorded, " + what);
    }

    private static CompoundTag skinTag(String name, @Nullable SkinTextures textures, long fetchedAt) {
        CompoundTag tag = new CompoundTag();
        tag.putString("name", name);
        if (textures != null) {
            tag.put("textures", texturesTag(textures));
        }
        tag.putLong("fetched_at", fetchedAt);
        return tag;
    }

    private static CompoundTag texturesTag(SkinTextures textures) {
        CompoundTag tag = new CompoundTag();
        tag.putString("value", textures.value());
        if (textures.signature() != null) {
            tag.putString("signature", textures.signature());
        }
        return tag;
    }

    // Like assertValueEqual, which can't take a null value
    static void assertEqual(GameTestHelper helper, @Nullable Object value, @Nullable Object expected, String what) {
        helper.assertTrue(Objects.equals(value, expected), what + ": expected " + expected + ", got " + value);
    }

    static @Nullable SkinTextures textures(GameProfile profile) {
        return profile.properties().get("textures").stream().findFirst()
                .map(property -> new SkinTextures(property.value(), property.signature()))
                .orElse(null);
    }

    // Short enough for a player name, and never one another test uses
    static String uniqueName(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 6);
    }
}
